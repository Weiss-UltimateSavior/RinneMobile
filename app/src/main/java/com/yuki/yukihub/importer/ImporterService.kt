package com.yuki.yukihub.importer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.yuki.yukihub.data.GameRepository
import com.yuki.yukihub.data.YukiDatabaseHelper
import com.yuki.yukihub.model.EngineType
import com.yuki.yukihub.model.Game
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * 把三方平台解析出的 [ImportGameData] 同步写入数据库。
 *
 * 本类不管理线程或 Android Service 生命周期；调用方负责把耗时工作调度到后台线程。
 * 整批写入共享一个 [SQLiteDatabase] 事务，并在所有退出路径清理导入临时目录。
 */
class ImporterService(context: Context) {
    private val context = context.applicationContext
    private val dbHelper = YukiDatabaseHelper(this.context)
    private val gameRepository = GameRepository(this.context)

    /** 预览阶段按标题标记冲突，去重范围包含隐藏游戏。 */
    fun markExisting(games: List<ImportGameData>) {
        val existingNames = loadExistingTitleKeys()
        games.forEach { game ->
            game.exists = titleKey(game.name) in existingNames
            game.selected = !game.exists
            game.conflictReason = if (game.exists) "已存在" else null
        }
    }

    /** 仅导入已选择且预览时未标记为冲突的条目。 */
    fun importSelected(games: List<ImportGameData>): ImportResult {
        val result = ImportResult()
        val existingByName = loadExistingByName()
        val db = dbHelper.writableDatabase
        try {
            db.beginTransaction()
            try {
                games.asSequence()
                    .filter { it.selected && !it.exists }
                    .forEach { insertOne(db, it, existingByName, result) }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            try {
                gameRepository.recalculatePlayStats()
            } catch (error: Exception) {
                Log.w(TAG, "recalculatePlayStats 失败", error)
            }
            ImporterIO.cleanupAllTempDirs()
        }
        return result
    }

    private fun insertOne(
        db: SQLiteDatabase,
        imported: ImportGameData,
        existingByName: MutableMap<String, Game>,
        result: ImportResult,
    ) {
        // @JvmField 可被 Java 调用方绕过 Kotlin 非空约束写入 null，保持原 Java 防御语义。
        val title = (imported.name as String?)?.trim().orEmpty()
        if (title.isEmpty()) {
            result.failed++
            result.failedNames.add("(空名称)")
            return
        }

        val nameKey = titleKey(title)
        if (nameKey in existingByName) {
            result.skipped++
            result.skippedNames.add("${imported.name} (已存在)")
            return
        }

        val now = System.currentTimeMillis()
        val game = Game().apply {
            this.title = title
            originalTitle = imported.originalName?.takeIf(String::isNotEmpty) ?: title
            engine = EngineType.UNKNOWN
            rootUri = ""
            description = imported.description.orEmpty()
            tags = imported.tags?.joinToString(", ")
            imported.playStatus?.takeIf(String::isNotEmpty)?.let { playStatus = it }
            createdAt = imported.createdAt.takeIf { it > 0 } ?: now
            updatedAt = now
        }
        applyCover(game, imported)

        val id = gameRepository.insert(game, db)
        game.id = id
        existingByName[nameKey] = game

        val sessions = importSessions(db, id, imported)
        result.sessionsImported += sessions
        if (imported.totalPlayTime > 0) {
            val sessionTotal = if (sessions > 0) sumSessionsDuration(db, id) else 0L
            val importedTotal = imported.totalPlayTime * 1000L
            if (importedTotal > sessionTotal) {
                db.execSQL(
                    "UPDATE games SET total_play_time = ? WHERE id = ?",
                    arrayOf(importedTotal, id),
                )
            }
        }
        result.success++
    }

    private fun sumSessionsDuration(db: SQLiteDatabase, gameId: Long): Long =
        db.rawQuery(
            "SELECT IFNULL(SUM(duration), 0) FROM play_sessions WHERE game_id=?",
            arrayOf(gameId.toString()),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

    private fun applyCover(game: Game, imported: ImportGameData) {
        val coverUrl = imported.coverUrl
        if (!coverUrl.isNullOrEmpty() &&
            (coverUrl.startsWith("http://") || coverUrl.startsWith("https://"))
        ) {
            game.coverUri = coverUrl
            game.coverSourceType = 1
            return
        }
        val localPath = imported.coverLocalPath ?: return
        if (localPath.isEmpty()) return
        copyCoverToInternal(localPath, game.title.orEmpty())?.let { saved ->
            game.coverPersistUri = saved
            game.coverSourceType = 2
        }
    }

    private fun importSessions(db: SQLiteDatabase, gameId: Long, imported: ImportGameData): Int {
        imported.playedTimeMap?.takeIf { it.isNotEmpty() }?.let {
            return importPotatoVnPlaySessions(db, gameId, it)
        }
        imported.vniteTimers?.takeIf { it.isNotEmpty() }?.let {
            return importVniteTimers(db, gameId, it)
        }
        imported.lunaBoxSessions?.takeIf { it.isNotEmpty() }?.let {
            return importLunaBoxSessions(db, gameId, it)
        }
        return 0
    }

    private fun importPotatoVnPlaySessions(
        db: SQLiteDatabase,
        gameId: Long,
        playedTime: Map<String, Int>,
    ): Int = playedTime.count { (date, minutes) ->
        if (minutes <= 0) return@count false
        val startTime = parsePotatoVnDate(date)
        val duration = minutes * 60L * 1000L
        insertSession(
            db, gameId, startTime, startTime + duration, duration,
            "imported_potatovn", "potatovn_import",
        )
    }

    private fun importVniteTimers(
        db: SQLiteDatabase,
        gameId: Long,
        timers: List<ImportGameData.VniteTimer>,
    ): Int = timers.count { timer ->
        val startTime = parseIsoTime(timer.start)
        val endTime = parseIsoTime(timer.end)
        val duration = (endTime - startTime).coerceAtLeast(0L)
        duration > 0 && insertSession(
            db, gameId, startTime, endTime, duration,
            "imported_vnite", "vnite_import",
        )
    }

    private fun importLunaBoxSessions(
        db: SQLiteDatabase,
        gameId: Long,
        sessions: List<ImportGameData.LunaBoxSession>,
    ): Int = sessions.count { session ->
        val startTime = LunaBoxImporter.parseLunaBoxTimestamp(session.start)
        val endTime = LunaBoxImporter.parseLunaBoxTimestamp(session.end)
        val duration = if (session.durationSeconds > 0) {
            session.durationSeconds * 1000L
        } else {
            (endTime - startTime).coerceAtLeast(0L)
        }
        duration > 0 && insertSession(
            db, gameId, startTime, endTime, duration,
            "imported_lunabox", "lunabox_import",
        )
    }

    private fun insertSession(
        db: SQLiteDatabase,
        gameId: Long,
        startTime: Long,
        endTime: Long,
        duration: Long,
        launchType: String,
        deviceId: String,
    ): Boolean = try {
        val values = ContentValues().apply {
            put("game_id", gameId)
            put("start_time", startTime)
            put("end_time", endTime)
            put("duration", duration)
            put("launch_type", launchType)
            put("session_uuid", UUID.randomUUID().toString())
            put("device_id", deviceId)
            put("created_at", startTime)
            put("updated_at", endTime)
            put("dirty", 1)
            put("deleted", 0)
        }
        db.insert("play_sessions", null, values) > 0
    } catch (error: Exception) {
        Log.w(
            TAG,
            "写入 play_session 失败: gameId=$gameId, start=$startTime, duration=$duration",
            error,
        )
        false
    }

    private fun copyCoverToInternal(sourcePath: String, gameName: String): String? {
        val source = File(sourcePath)
        if (!source.exists()) return null
        val coverDirectory = File(context.filesDir, COVER_DIR)
        if (!coverDirectory.exists() && !coverDirectory.mkdirs()) return null

        val safeName = gameName
            .replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fff_-]"), "_")
            .take(MAX_COVER_NAME_LENGTH)
        val extension = when {
            sourcePath.endsWith(".png", ignoreCase = true) -> ".png"
            sourcePath.endsWith(".webp", ignoreCase = true) -> ".webp"
            else -> ".jpg"
        }
        val destination = File(coverDirectory, "${safeName}_${System.currentTimeMillis()}$extension")
        return try {
            FileInputStream(source).use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output) }
            }
            destination.absolutePath
        } catch (error: Exception) {
            Log.w(TAG, "复制封面失败: $sourcePath", error)
            null
        }
    }

    private fun loadExistingTitleKeys(): Set<String> = buildSet {
        dbHelper.readableDatabase
            .rawQuery("SELECT title FROM games WHERE title IS NOT NULL", null)
            .use { cursor ->
                while (cursor.moveToNext()) cursor.getString(0)?.let { add(titleKey(it)) }
            }
    }

    private fun loadExistingByName(): MutableMap<String, Game> {
        val games = mutableMapOf<String, Game>()
        gameRepository.getAll().forEach { game ->
            game.title?.let { games[titleKey(it)] = game }
        }
        dbHelper.readableDatabase.rawQuery(
            "SELECT id, title FROM games WHERE hidden=1 AND title IS NOT NULL",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val title = cursor.getString(1)
                games.putIfAbsent(titleKey(title), Game(id = id, title = title))
            }
        }
        return games
    }

    companion object {
        private const val TAG = "ImporterService"
        private const val COVER_DIR = "imported_covers"
        private const val MAX_COVER_NAME_LENGTH = 80

        /** 清理预览解析产生的临时目录；保留 Java 静态调用形式。 */
        @JvmStatic
        fun cancelImport() = ImporterIO.cleanupAllTempDirs()

        private fun titleKey(name: String?): String =
            name?.trim()?.lowercase(Locale.getDefault()) ?: ""

        private fun parsePotatoVnDate(date: String): Long {
            for (format in arrayOf("yyyy/M/d", "yyyy/MM/dd")) {
                try {
                    val parsed = SimpleDateFormat(format, Locale.US).parse(date) ?: continue
                    return Calendar.getInstance().apply {
                        time = parsed
                        set(Calendar.HOUR_OF_DAY, 12)
                    }.timeInMillis
                } catch (_: Exception) {
                    // Try the next supported representation.
                }
            }
            Log.w(TAG, "无法解析 PotatoVN 日期，fallback 当前时间: $date")
            return System.currentTimeMillis()
        }

        /** 通用 ISO 时间解析；公开静态入口便于同包测试和 Java 兼容。 */
        @JvmStatic
        fun parseIsoTime(raw: String?): Long {
            if (raw.isNullOrEmpty()) return System.currentTimeMillis()
            val formats = arrayOf(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss",
            )
            for (format in formats) {
                try {
                    val parser = SimpleDateFormat(format, Locale.US)
                    if ("'Z'" in format) parser.timeZone = TimeZone.getTimeZone("UTC")
                    val parsed: Date = parser.parse(raw) ?: continue
                    return parsed.time
                } catch (_: Exception) {
                    // Try the next supported representation.
                }
            }
            Log.w(TAG, "无法解析 ISO 时间，fallback 当前时间: $raw")
            return System.currentTimeMillis()
        }
    }
}
