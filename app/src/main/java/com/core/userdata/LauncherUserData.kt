package com.core.userdata

import android.app.Activity
import android.content.Context
import android.os.Process
import com.core.CorePreferences
import com.core.launcher.EngineSaveKeys
import com.core.launcher.LauncherUiBridge
import com.core.launcherbridge.LauncherRepositoryBridge
import com.core.launcherbridge.LauncherSyncBridge
import com.core.prefs.LauncherMainKeys
import com.core.prefs.ScanRootKeys
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * 统一管理 Launcher 所有用户设置的导出/导入。
 * 数据来源：
 *   1. SharedPreferences（yukihub_prefs / launcher_profile_prefs / launcher_account_settings）→ JSON
 *   2. SQLite 数据库（games + play_sessions）→ SQL
 * 存储在应用私有的 UserData 目录下：
 *   - launcher_user_data.json  （设置）
 *   - launcher_play_data.sql   （游玩记录）
 */
object LauncherUserData {

    private const val SETTINGS_FILE = "launcher_user_data.json"
    private const val PLAY_SQL_FILE = "launcher_play_data.sql"
    private const val VERSION = 1
    private const val MAX_SETTINGS_BYTES = 1024 * 1024
    private const val MAX_PLAY_SQL_BYTES = 32 * 1024 * 1024
    private const val MAX_CLOUD_PLAY_DATA_BYTES = 16 * 1024 * 1024

    // ── SharedPreferences 文件名 ──
    private const val PREFS_MAIN = CorePreferences.APP_PREFS
    private const val PREFS_PROFILE = "launcher_profile_prefs"
    private const val PREFS_ACCOUNT_SETTINGS = "launcher_account_settings"
    private const val KEY_REALTIME_DEVICE_ID = "realtime_playtime_device_id"

    // ── yukihub_prefs 键 ──
    private val MAIN_PREF_KEYS = arrayOf(
            LauncherMainKeys.KEY_LAUNCHER_DARK_MODE,
            LauncherMainKeys.KEY_LAUNCHER_THEME_STYLE,
            LauncherMainKeys.KEY_LAUNCHER_PARTICLES_ENABLED,
            LauncherMainKeys.KEY_LAUNCHER_PARTICLE_STYLE,
            LauncherMainKeys.KEY_STORAGE_PERMISSION_ASKED,
            ScanRootKeys.KEY_SCAN_ROOT_URIS,
            ScanRootKeys.KEY_SCAN_ROOT_ENABLED,
            ScanRootKeys.KEY_LAST_SCAN_ROOT_URI,
            ScanRootKeys.KEY_STARTUP_SCAN_DEPTH,
            CorePreferences.KEY_PROFILE_AVATAR,
            LauncherMainKeys.KEY_AUTH_SAVED_EMAIL,
            CorePreferences.KEY_KR_ENGINE_VERSION,
            CorePreferences.KEY_ARTEMIS_ENGINE_VERSION,
            CorePreferences.KEY_ARTEMIS_ROTATE_SCREEN,
            EngineSaveKeys.KEY_KR_SCOPED_SAVE_DIR,
            EngineSaveKeys.KEY_TYRANO_SCOPED_SAVE_DIR,
            EngineSaveKeys.KEY_TYRANO_EXTERNAL_NETWORK
    )

    // ── launcher_profile_prefs 键 ──
    private val PROFILE_PREF_KEYS = arrayOf(
            "custom_cover_uri",
            "custom_avatar_uri"
    )

    // ── launcher_account_settings 键 ──
    private val ACCOUNT_SETTINGS_KEYS = arrayOf(
            "sync_config",
            "realtime_playtime",
            "profile_display",
            "model_feature",
            "email_subscribe"
    )

    // ══════════════════════════════════════════════════
    //  导出
    // ══════════════════════════════════════════════════
    /**
     * 导出所有数据：设置→JSON，游玩记录→SQL。
     *
     * @return 导出目录路径，失败返回 null
     */
    @JvmStatic
    fun exportAll(context: Context): String? {
        try {
            val dir = getUserDataDir(context)

            // 设置 → JSON
            val root = JSONObject()
            root.put("version", VERSION)
            root.put("export_time", System.currentTimeMillis())
            root.put("main_prefs", exportSharedPreferences(context, PREFS_MAIN, MAIN_PREF_KEYS))
            root.put("profile_prefs", exportSharedPreferences(context, PREFS_PROFILE, PROFILE_PREF_KEYS))
            root.put("account_settings", exportSharedPreferences(context, PREFS_ACCOUNT_SETTINGS, ACCOUNT_SETTINGS_KEYS))
            writeText(File(dir, SETTINGS_FILE), root.toString(2))

            // 游玩记录 → SQL
            val sql = LauncherRepositoryBridge.exportPlaySql(context)
            writeText(File(dir, PLAY_SQL_FILE), sql)

            return dir.absolutePath
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 仅导出设置为 JSON 字符串（不含游玩记录）。
     */
    @JvmStatic
    fun exportSettingsJson(context: Context): String? {
        try {
            val root = JSONObject()
            root.put("version", VERSION)
            root.put("export_time", System.currentTimeMillis())
            root.put("main_prefs", exportSharedPreferences(context, PREFS_MAIN, MAIN_PREF_KEYS))
            root.put("profile_prefs", exportSharedPreferences(context, PREFS_PROFILE, PROFILE_PREF_KEYS))
            root.put("account_settings", exportSharedPreferences(context, PREFS_ACCOUNT_SETTINGS, ACCOUNT_SETTINGS_KEYS))
            return root.toString(2)
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 导出用于账户云备份的结构化快照。
     * 游戏按稳定身份恢复，游玩会话按 session_uuid 去重，不携带跨设备无效的本地主键关系。
     */
    @JvmStatic
    fun exportCloudPlayData(context: Context?): String? {
        if (context == null) return null
        val root = LauncherSyncBridge.exportCloudSnapshot(context) ?: return null
        return root.toString()
    }

    /**
     * 导入账户云备份。新版 JSON 走 LauncherSyncBridge 的稳定身份合并；旧版 SQL 继续兼容。
     */
    @JvmStatic
    fun importCloudPlayData(context: Context?, playData: String?): Boolean {
        if (context == null || playData == null || playData.trim { it <= ' ' }.isEmpty()) return false
        if (utf8Length(playData) > MAX_CLOUD_PLAY_DATA_BYTES) return false
        val trimmed = playData.trim { it <= ' ' }
        if (trimmed.startsWith("{")) {
            try {
                val root = JSONObject(trimmed)
                if (LauncherSyncBridge.importCloudSnapshot(context, root)) {
                    return true
                }
                return false
            } catch (e: Exception) {
                e.printStackTrace()
                return false
            }
        }
        return LauncherRepositoryBridge.importPlaySql(context, playData)
    }

    // ══════════════════════════════════════════════════
    //  导入
    // ══════════════════════════════════════════════════

    /**
     * 从 UserData 目录导入所有数据：设置从 JSON，游玩记录从 SQL。
     *
     * @return 成功返回 true
     */
    @JvmStatic
    fun importAll(context: Context): Boolean {
        val dir = getUserDataDir(context)
        var ok = true

        val settingsFile = File(dir, SETTINGS_FILE)
        if (settingsFile.exists()) {
            try {
                val json = readText(settingsFile, MAX_SETTINGS_BYTES, "设置备份")
                ok = importSettingsFromJson(context, json)
            } catch (e: Exception) {
                ok = false
            }
        }

        val sqlFile = File(dir, PLAY_SQL_FILE)
        if (sqlFile.exists()) {
            try {
                val sql = readText(sqlFile, MAX_PLAY_SQL_BYTES, "游玩记录备份")
                ok = LauncherRepositoryBridge.importPlaySql(context, sql) && ok
            } catch (e: Exception) {
                ok = false
            }
        }

        return ok
    }

    /**
     * 导入所有数据并重启 Launcher 使设置生效。
     * 用于在线同步配置后应用变更。
     *
     * @param activity 当前 Activity（用于触发重启）
     * @return 导入是否成功（无论成功与否都会尝试重启）
     */
    @JvmStatic
    fun importAndRestart(activity: Activity): Boolean {
        val ok = importAll(activity)
        // 重启 LauncherActivity 使所有设置（主题、暗色模式、扫描目录等）生效
        restartLauncher(activity)
        return ok
    }

    /**
     * 从 JSON 字符串仅导入设置。
     */
    @JvmStatic
    fun importSettingsFromJson(context: Context, json: String?): Boolean {
        try {
            if (json == null || json.trim { it <= ' ' }.isEmpty()) return false

            val root = JSONObject(json)

            var ok = true

            if (root.has("main_prefs")) {
                ok = importSharedPreferences(context, PREFS_MAIN, root.getJSONObject("main_prefs")) && ok
            }

            if (root.has("profile_prefs")) {
                ok = importSharedPreferences(context, PREFS_PROFILE, root.getJSONObject("profile_prefs")) && ok
            }

            if (root.has("account_settings")) {
                ok = importSharedPreferences(context, PREFS_ACCOUNT_SETTINGS, root.getJSONObject("account_settings")) && ok
            }

            return ok
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    // ══════════════════════════════════════════════════
    //  查询
    // ══════════════════════════════════════════════════

    @JvmStatic
    fun getSettingsFile(context: Context): File {
        return File(getUserDataDir(context), SETTINGS_FILE)
    }

    @JvmStatic
    fun getPlaySqlFile(context: Context): File {
        return File(getUserDataDir(context), PLAY_SQL_FILE)
    }

    @JvmStatic
    fun getUserDataDir(context: Context): File {
        val dir = File(context.applicationContext.filesDir, "UserData")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    @JvmStatic
    fun readExportedJson(context: Context): String? {
        val file = getSettingsFile(context)
        if (!file.exists()) return null
        try {
            return readText(file, MAX_SETTINGS_BYTES, "设置备份")
        } catch (e: Exception) {
            return null
        }
    }

    @JvmStatic
    fun readExportedSql(context: Context): String? {
        val file = getPlaySqlFile(context)
        if (!file.exists()) return null
        try {
            return readText(file, MAX_PLAY_SQL_BYTES, "游玩记录备份")
        } catch (e: Exception) {
            return null
        }
    }

    @JvmStatic
    fun deleteExported(context: Context): Boolean {
        val a = getSettingsFile(context).delete()
        val b = getPlaySqlFile(context).delete()
        return a || b
    }

    // ══════════════════════════════════════════════════
    //  SharedPreferences 导出/导入
    // ══════════════════════════════════════════════════

    private fun exportSharedPreferences(context: Context, prefsName: String?, keys: Array<String>): JSONObject {
        val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val obj = JSONObject()
        for (key in keys) {
            if (!prefs.contains(key)) continue
            val value = prefs.all[key]
            obj.put(key, value)
        }
        return obj
    }

    /** 从 JSONObject 导入 SharedPreferences 数据。 */
    @JvmStatic
    @Throws(JSONException::class)
    fun importSharedPreferences(context: Context, prefsName: String?, obj: JSONObject): Boolean {
        val prefs = context.applicationContext
                .getSharedPreferences(prefsName, Context.MODE_PRIVATE)

        val editor = prefs.edit()
        val it = obj.keys()

        while (it.hasNext()) {
            val key = it.next()
            val value = obj.get(key)

            if (value === JSONObject.NULL) {
                editor.remove(key)
            } else if (value is Boolean) {
                editor.putBoolean(key, value)
            } else if (value is Int) {
                editor.putInt(key, value)
            } else if (value is Long) {
                editor.putLong(key, value)
            } else if (value is Float) {
                editor.putFloat(key, value)
            } else if (value is Double) {
                val d = value
                if (d == Math.rint(d)) {
                    editor.putLong(key, d.toLong())
                } else {
                    editor.putFloat(key, d.toFloat())
                }
            } else {
                editor.putString(key, value.toString())
            }
        }

        return editor.commit()
    }

    // ══════════════════════════════════════════════════
    //  重启
    // ══════════════════════════════════════════════════

    /** 直接重启 LauncherActivity，不重新导入数据。用于数据已导入后仅需重启的场景。 */
    @JvmStatic
    fun restartLauncher(activity: Activity) {
        try {
            if (LauncherUiBridge.restartLauncher(activity)) return
        } catch (ignored: Exception) {
            // UI 桥重启失败时忽略，降级为下方普通重启（尽力而为）
        }
        // 非 Launcher 上下文或 UI 桥未注册时降级为普通重启。
        try {
            Process.killProcess(Process.myPid())
        } catch (ignored: Exception) {
            // killProcess 失败时忽略（进程即将终止，无需处理）
        }
    }

    //清理 SQL 注释
    private fun removeSqlLineComments(sql: String?): String {
        val sb = StringBuilder()
        val lines = sql!!.split("\\r?\\n".toRegex())

        for (line in lines) {
            val trimmed = line.trim { it <= ' ' }
            if (trimmed.startsWith("--")) {
                continue
            }
            sb.append(line).append('\n')
        }

        return sb.toString()
    }

    // ══════════════════════════════════════════════════
    //  实际游玩记录临时存储（供后续上传）
    // ══════════════════════════════════════════════════
    //
    // 设计说明：
    //   主项目通过 GameRepository.startPlaySession/finishPlaySession 将会话写入
    //   play_sessions 表（由 LauncherGameLaunchBridge 透出）。LauncherUserData 在此
    //   之外并行维护一份「实际游玩记录」缓冲，用于后续上传到服务端。
    //   该缓冲为追加式 JSON 文件 launcher_play_records.json，上传成功后可调用
    //   clearPlayRecords 清空。
    //   实现已拆分至 LauncherPlayRecords（重构计划 3.5），此处保留同签名委托。

    /**
     * 追加一条实际游玩记录到临时缓冲。
     * 调用时机：游戏会话结束时（finishDirectPlaySessionIfNeeded）。
     *
     * @param gameId     游戏 id
     * @param gameTitle  游戏标题（用于上传时展示）
     * @param startTime  会话开始时间戳（ms）
     * @param endTime    会话结束时间戳（ms）
     * @param duration   实际游玩时长（ms），<=0 时按 endTime-startTime 推算
     * @param launchType 启动类型（internal.krkr / external 等）
     * @return 生成的记录的 sessionUuid，失败返回 null
     */
    @JvmStatic
    fun appendPlayRecord(context: Context?, gameId: Long, gameTitle: String?,
                         startTime: Long, endTime: Long, duration: Long, launchType: String?): String? =
        LauncherPlayRecords.appendPlayRecord(context, gameId, gameTitle, startTime, endTime, duration, launchType)

    /**
     * 读取所有暂存的游玩记录（按记录追加顺序）。
     */
    @JvmStatic
    fun readPlayRecords(context: Context?): List<JSONObject> = LauncherPlayRecords.readPlayRecords(context)

    /**
     * 读取暂存的游玩记录条数。
     */
    @JvmStatic
    fun getPlayRecordCount(context: Context): Int = LauncherPlayRecords.getPlayRecordCount(context)

    /**
     * 清空所有暂存的游玩记录。建议在上传成功后调用。
     */
    @JvmStatic
    fun clearPlayRecords(context: Context?): Boolean = LauncherPlayRecords.clearPlayRecords(context)

    /**
     * 删除已上传的若干条记录（按 sessionUuid 匹配），用于增量上传场景。
     */
    @JvmStatic
    fun removePlayRecords(context: Context?, sessionUuids: Collection<String>?): Boolean =
        LauncherPlayRecords.removePlayRecords(context, sessionUuids)

    @JvmStatic
    fun getPlayRecordsFile(context: Context): File = LauncherPlayRecords.getPlayRecordsFile(context)

    /**
     * 取得服务端实际游玩计时使用的设备 ID。安装后生成并持久化，不能每次启动都变化。
     */
    @JvmStatic
    fun getRealtimePlaytimeDeviceId(context: Context?): String {
        if (context == null) return ""
        val prefs = context.applicationContext.getSharedPreferences(PREFS_MAIN, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_REALTIME_DEVICE_ID, "")
        if (existing != null && existing.trim { it <= ' ' }.isNotEmpty()) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_REALTIME_DEVICE_ID, created).apply()
        return created
    }

    /**
     * 保存本地 play_session 与服务端 session_id 的映射。应用异常退出后可据此恢复 finish。
     */
    @JvmStatic
    fun rememberServerPlaySession(context: Context?, localSessionId: Long, gameId: Long,
                                  gameTitle: String?, serverSessionId: String?): Boolean =
        LauncherPlayRecords.rememberServerPlaySession(context, localSessionId, gameId, gameTitle, serverSessionId)

    @JvmStatic
    fun findServerPlaySessionId(context: Context?, localSessionId: Long): String =
        LauncherPlayRecords.findServerPlaySessionId(context, localSessionId)

    @JvmStatic
    fun removeServerPlaySession(context: Context?, localSessionId: Long): Boolean =
        LauncherPlayRecords.removeServerPlaySession(context, localSessionId)

    @JvmStatic
    fun getServerSessionsFile(context: Context): File = LauncherPlayRecords.getServerSessionsFile(context)

    // ══════════════════════════════════════════════════
    //  文件 I/O 工具
    // ══════════════════════════════════════════════════

    @JvmStatic
    @Throws(IOException::class)
    fun writeText(file: File, text: String?) {
        val fos = FileOutputStream(file)
        try {
            fos.write(text!!.toByteArray(StandardCharsets.UTF_8))
        } finally {
            fos.close()
        }
    }

    @Throws(IOException::class)
    internal fun readText(file: File?, maxBytes: Int, label: String?): String {
        if (file == null || !file.isFile) throw IOException("$label 不存在或不是普通文件")
        val declaredLength = file.length()
        if (declaredLength > maxBytes.toLong()) {
            throw IOException("$label 过大（文件声明 $declaredLength 字节，最大允许 $maxBytes 字节）")
        }
        FileInputStream(file).use { fis ->
            ByteArrayOutputStream(Math.max(0L, declaredLength).toInt()).use { out ->
                val buffer = ByteArray(8192)
                var total = 0
                var read = fis.read(buffer)
                while (read != -1) {
                    total += read
                    if (total > maxBytes) throw IOException("$label 过大（读取超过最大允许 $maxBytes 字节）")
                    out.write(buffer, 0, read)
                    read = fis.read(buffer)
                }
                return out.toString(StandardCharsets.UTF_8.name())
            }
        }
    }

    private fun utf8Length(text: String?): Int {
        return if (text == null) 0 else text.toByteArray(StandardCharsets.UTF_8).size
    }
}
