package com.yuki.yukihub.diagnostics

import android.content.Context
import android.os.Build
import com.yuki.yukihub.data.GameRepository
import com.yuki.yukihub.model.Game
import com.yuki.yukihub.util.DevLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Always-on, bounded diagnostic timeline for game launch and storage incidents. */
object GameDiagnostics {
    private const val DIRECTORY = "diagnostics"
    private const val EVENTS_FILE = "game_events.jsonl"
    private const val MAX_EVENTS_BYTES = 512L * 1024L

    @JvmStatic
    fun recordLaunch(context: Context?, game: Game?, success: Boolean, message: String) {
        record(context, if (success) "launch_success" else "launch_failure", game, message)
    }

    @JvmStatic
    fun recordSafPermissionInvalid(context: Context?, game: Game?, message: String) {
        record(context, "saf_permission_invalid", game, message)
    }

    @JvmStatic
    fun recordDirectoryRebound(context: Context?, game: Game?) {
        record(context, "directory_rebound", game, "用户重新绑定游戏目录")
    }

    @JvmStatic
    @Synchronized
    fun record(context: Context?, type: String, game: Game?, message: String) {
        if (context == null) return
        try {
            val file = eventsFile(context)
            if (file.exists() && file.length() > MAX_EVENTS_BYTES) {
                File(file.parentFile, "$EVENTS_FILE.old").delete()
                file.renameTo(File(file.parentFile, "$EVENTS_FILE.old"))
            }
            val event = JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("type", type)
                put("message", message.take(1000))
                put("game", gameJson(game))
            }
            FileWriter(file, true).use { it.append(event.toString()).append('\n') }
        } catch (_: Throwable) {
            // Diagnostics must never interrupt launching a game.
        }
    }

    @JvmStatic
    fun exportPackage(context: Context?): File? {
        if (context == null) return null
        return try {
            val output = File(context.cacheDir, "Rinne_diagnostics_${System.currentTimeMillis()}.zip")
            ZipOutputStream(FileOutputStream(output)).use { zip ->
                putText(zip, "diagnostic.json", buildSnapshot(context).toString(2))
                putFile(zip, "events/$EVENTS_FILE", eventsFile(context))
                DevLogger.getLogFile()?.let { putFile(zip, "logs/logcat.txt", it) }
            }
            output
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildSnapshot(context: Context): JSONObject = JSONObject().apply {
        put("generated_at", System.currentTimeMillis())
        put("app", JSONObject().apply {
            put("package_name", context.packageName)
            put("version_name", versionName(context))
            put("version_code", versionCode(context))
        })
        put("device", JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("brand", Build.BRAND)
            put("model", Build.MODEL)
            put("android_version", Build.VERSION.RELEASE)
            put("sdk_int", Build.VERSION.SDK_INT)
        })
        put("games", JSONArray().apply {
            GameRepository(context.applicationContext).getAll().forEach { put(gameJson(it)) }
        })
    }

    private fun gameJson(game: Game?): JSONObject = JSONObject().apply {
        if (game == null) return@apply
        put("id", game.id)
        put("title", game.title ?: "")
        put("engine_type", game.engine?.name ?: "UNKNOWN")
        put("launch_target", game.launchTarget ?: "")
        put("emulator_package", game.emulatorPackage ?: "")
        put("root_uri", game.rootUri ?: "")
        put("gamehub_local_game_id", game.gamehubLocalGameId ?: "")
    }

    private fun eventsFile(context: Context): File {
        val directory = File(context.filesDir, DIRECTORY)
        if (!directory.exists()) directory.mkdirs()
        return File(directory, EVENTS_FILE)
    }

    private fun putText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun putFile(zip: ZipOutputStream, name: String, file: File) {
        if (!file.exists() || !file.isFile) return
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { input -> input.copyTo(zip) }
        zip.closeEntry()
    }

    private fun versionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (_: Throwable) { "" }

    private fun versionCode(context: Context): Long = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (_: Throwable) { 0L }
}
