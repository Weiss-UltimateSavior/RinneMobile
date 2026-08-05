package com.core.userdata

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 游玩记录与服务端会话映射的临时存储。
 * 从 LauncherUserData 按职责拆分（重构计划 3.5，§8:323 按职责切片）：
 * 主项目经 GameRepository 写入 play_sessions 表，本对象并行维护上传用缓冲
 * （launcher_play_records.json）与本地 play_session ↔ 服务端 session 映射
 * （launcher_play_server_sessions.json）。文件 I/O 复用 LauncherUserData 的
 * readText/writeText/getUserDataDir。
 */
object LauncherPlayRecords {

    private const val PLAY_RECORDS_FILE = "launcher_play_records.json"
    private const val PLAY_SERVER_SESSIONS_FILE = "launcher_play_server_sessions.json"
    private const val PLAY_RECORDS_VERSION = 1
    // 单条游玩记录最长 12 小时，与主项目 MAX_PLAY_SESSION_MS 保持一致
    private const val MAX_PLAY_RECORD_MS = 12L * 60L * 60L * 1000L
    private const val MAX_RUNTIME_RECORDS_BYTES = 2 * 1024 * 1024

    private val PLAY_RECORDS_LOCK = Any()
    private val SERVER_SESSIONS_LOCK = Any()

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
                         startTime: Long, endTime: Long, duration: Long, launchType: String?): String? {
        if (context == null || gameId <= 0L || startTime <= 0L) return null
        val safeEnd = if (endTime > 0L) endTime else System.currentTimeMillis()
        val rawDuration = if (duration > 0L) duration else Math.max(0L, safeEnd - startTime)
        if (rawDuration <= 0L) return null
        val safeDuration = Math.min(rawDuration, MAX_PLAY_RECORD_MS)

        val record = JSONObject()
        val sessionUuid = UUID.randomUUID().toString()
        try {
            record.put("sessionUuid", sessionUuid)
            record.put("gameId", gameId)
            record.put("gameTitle", if (gameTitle == null) "" else gameTitle)
            record.put("startTime", startTime)
            record.put("endTime", safeEnd)
            record.put("duration", safeDuration)
            record.put("launchType", if (launchType == null) "external" else launchType)
            record.put("recordedAt", System.currentTimeMillis())
        } catch (e: JSONException) {
            return null
        }

        synchronized(PLAY_RECORDS_LOCK) {
            val file = getPlayRecordsFile(context)
            val arr = readPlayRecordsArray(context)
            arr.put(record)
            if (writePlayRecordsFile(file, arr)) return sessionUuid
        }
        return null
    }

    /**
     * 读取所有暂存的游玩记录（按记录追加顺序）。
     */
    @JvmStatic
    fun readPlayRecords(context: Context?): List<JSONObject> {
        val list = mutableListOf<JSONObject>()
        if (context == null) return list
        val arr = readPlayRecordsArray(context)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o != null) list.add(o)
        }
        return list
    }

    /**
     * 读取暂存的游玩记录条数。
     */
    @JvmStatic
    fun getPlayRecordCount(context: Context): Int {
        return readPlayRecordsArray(context).length()
    }

    /**
     * 清空所有暂存的游玩记录。建议在上传成功后调用。
     */
    @JvmStatic
    fun clearPlayRecords(context: Context?): Boolean {
        if (context == null) return false
        synchronized(PLAY_RECORDS_LOCK) {
            val file = getPlayRecordsFile(context)
            if (!file.exists()) return true
            try {
                val root = JSONObject()
                root.put("version", PLAY_RECORDS_VERSION)
                root.put("records", JSONArray())
                LauncherUserData.writeText(file, root.toString(2))
                return true
            } catch (e: Exception) {
                // 清空失败时降级为直接删除整个文件，下次追加时自动重建空缓冲
                return file.delete()
            }
        }
    }

    /**
     * 删除已上传的若干条记录（按 sessionUuid 匹配），用于增量上传场景。
     */
    @JvmStatic
    fun removePlayRecords(context: Context?, sessionUuids: Collection<String>?): Boolean {
        if (context == null) return false
        if (sessionUuids == null || sessionUuids.isEmpty()) return true
        synchronized(PLAY_RECORDS_LOCK) {
            val arr = readPlayRecordsArray(context)
            val remaining = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o == null) continue
                val uuid = o.optString("sessionUuid", "")
                if (!sessionUuids.contains(uuid)) remaining.put(o)
            }
            return writePlayRecordsFile(getPlayRecordsFile(context), remaining)
        }
    }

    @JvmStatic
    fun getPlayRecordsFile(context: Context): File {
        return File(LauncherUserData.getUserDataDir(context), PLAY_RECORDS_FILE)
    }

    /**
     * 保存本地 play_session 与服务端 session_id 的映射。应用异常退出后可据此恢复 finish。
     */
    @JvmStatic
    fun rememberServerPlaySession(context: Context?, localSessionId: Long, gameId: Long,
                                  gameTitle: String?, serverSessionId: String?): Boolean {
        if (context == null || localSessionId <= 0L || gameId <= 0L || serverSessionId == null || serverSessionId.trim { it <= ' ' }.isEmpty()) {
            return false
        }
        synchronized(SERVER_SESSIONS_LOCK) {
            val arr = readServerSessionsArray(context)
            val kept = JSONArray()
            for (i in 0 until arr.length()) {
                val old = arr.optJSONObject(i)
                if (old == null || old.optLong("localSessionId", -1L) == localSessionId) continue
                kept.put(old)
            }
            val item = JSONObject()
            try {
                item.put("localSessionId", localSessionId)
                item.put("gameId", gameId)
                item.put("gameTitle", if (gameTitle == null) "" else gameTitle)
                item.put("serverSessionId", serverSessionId)
                item.put("createdAt", System.currentTimeMillis())
            } catch (e: JSONException) {
                return false
            }
            kept.put(item)
            return writeServerSessionsFile(getServerSessionsFile(context), kept)
        }
    }

    @JvmStatic
    fun findServerPlaySessionId(context: Context?, localSessionId: Long): String {
        if (context == null || localSessionId <= 0L) return ""
        val arr = readServerSessionsArray(context)
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i)
            if (item != null && item.optLong("localSessionId", -1L) == localSessionId) {
                return item.optString("serverSessionId", "")
            }
        }
        return ""
    }

    @JvmStatic
    fun removeServerPlaySession(context: Context?, localSessionId: Long): Boolean {
        if (context == null || localSessionId <= 0L) return false
        synchronized(SERVER_SESSIONS_LOCK) {
            val arr = readServerSessionsArray(context)
            val kept = JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i)
                if (item == null || item.optLong("localSessionId", -1L) == localSessionId) continue
                kept.put(item)
            }
            return writeServerSessionsFile(getServerSessionsFile(context), kept)
        }
    }

    @JvmStatic
    fun getServerSessionsFile(context: Context): File {
        return File(LauncherUserData.getUserDataDir(context), PLAY_SERVER_SESSIONS_FILE)
    }

    private fun readPlayRecordsArray(context: Context): JSONArray {
        val file = getPlayRecordsFile(context)
        if (!file.exists()) return JSONArray()
        try {
            val json = LauncherUserData.readText(file, MAX_RUNTIME_RECORDS_BYTES, "游玩记录缓存")
            val root = JSONObject(json)
            val arr = root.optJSONArray("records")
            return arr ?: JSONArray()
        } catch (e: Exception) {
            // 游玩记录为追加式临时缓冲，损坏/超限时按空处理，不阻断主流程（后续写入会重建）
            return JSONArray()
        }
    }

    private fun writePlayRecordsFile(file: File, arr: JSONArray): Boolean {
        try {
            val root = JSONObject()
            root.put("version", PLAY_RECORDS_VERSION)
            root.put("records", arr)
            LauncherUserData.writeText(file, root.toString(2))
            return true
        } catch (e: Exception) {
            // 写入失败返回 false，由调用方决定重试/忽略（上传缓冲非关键路径）
            return false
        }
    }

    private fun readServerSessionsArray(context: Context): JSONArray {
        val file = getServerSessionsFile(context)
        if (!file.exists()) return JSONArray()
        try {
            val json = LauncherUserData.readText(file, MAX_RUNTIME_RECORDS_BYTES, "游玩会话缓存")
            val root = JSONObject(json)
            val arr = root.optJSONArray("sessions")
            return arr ?: JSONArray()
        } catch (e: Exception) {
            // 服务端会话映射为可重建缓存，损坏/超限时按空处理，不阻断主流程
            return JSONArray()
        }
    }

    private fun writeServerSessionsFile(file: File, arr: JSONArray): Boolean {
        try {
            val root = JSONObject()
            root.put("version", PLAY_RECORDS_VERSION)
            root.put("sessions", arr)
            LauncherUserData.writeText(file, root.toString(2))
            return true
        } catch (e: Exception) {
            // 写入失败返回 false，由调用方决定重试/忽略（映射可重建，非关键路径）
            return false
        }
    }
}
