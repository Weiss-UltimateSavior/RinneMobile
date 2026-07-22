package com.yuki.yukihub.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.yuki.yukihub.data.GameRepository
import com.yuki.yukihub.data.MetadataRepository
import com.yuki.yukihub.data.YukiDatabaseHelper
import com.yuki.yukihub.util.AppExecutors
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class SyncManager(context: Context) {
    private val context: Context = context.applicationContext
    private val syncPrefs: SharedPreferences = this.context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
    private val appPrefs: SharedPreferences = this.context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)

    private var client: WebDavClient? = null

    val isConfigured: Boolean
        get() {
            val c = config
            return c.serverUrl.trim().isNotEmpty() && c.username.trim().isNotEmpty() && c.password.trim().isNotEmpty()
        }

    val config: SyncConfig
        get() = SyncConfig(
            syncPrefs.getString(KEY_SERVER_URL, "") ?: "",
            syncPrefs.getString(KEY_USERNAME, "") ?: "",
            syncPrefs.getString(KEY_PASSWORD, "") ?: "",
            syncPrefs.getBoolean(KEY_AUTO_SYNC, false)
        )

    @Synchronized
    fun saveConfig(serverUrl: String?, username: String?, password: String?, autoSync: Boolean) {
        syncPrefs.edit()
            .putString(KEY_SERVER_URL, serverUrl?.trim() ?: "")
            .putString(KEY_USERNAME, username?.trim() ?: "")
            .putString(KEY_PASSWORD, password ?: "")
            .putBoolean(KEY_AUTO_SYNC, autoSync)
            .apply()
        client = null
    }

    @Synchronized
    fun getClient(): WebDavClient? {
        if (client == null && isConfigured) {
            val c = config
            client = WebDavClient(c.serverUrl, c.username, c.password)
        }
        return client
    }

    fun testConnection(): Boolean {
        return try {
            val c = getClient()
            c != null && c.testConnection()
        } catch (t: Throwable) {
            Log.w(TAG, "testConnection failed", t)
            false
        }
    }

    val isAutoSyncEnabled: Boolean
        get() = syncPrefs.getBoolean(KEY_AUTO_SYNC, false)

    val lastSyncTime: Long
        get() = syncPrefs.getLong(KEY_LAST_SYNC, 0)

    fun sync(listener: SyncListener?) {
        if (!isConfigured) {
            listener?.onError("WebDAV 未配置")
            return
        }
        AppExecutors.runOnSingle {
            try {
                listener?.onSyncStart()
                val c = getClient() ?: throw Exception("WebDAV 客户端初始化失败")
                // 坚果云根目录通常不可直接创建同步文件夹；要求用户先在坚果云创建 YukiHub 文件夹。

                val local = buildLocalSnapshot()
                val localText = snapshotToText(local, MAX_REMOTE_SNAPSHOT_BYTES, "本地同步快照")
                val localHash = sha256(localText)
                val lastHash = syncPrefs.getString(KEY_LAST_SYNC_HASH, "")

                var remote: JSONObject? = null
                var remoteText: String? = null
                var remoteHash = ""
                val remoteExists = c.exists(REMOTE_FILE)
                if (remoteExists) {
                    val remoteBytes = c.readFileLimited(REMOTE_FILE, MAX_REMOTE_SNAPSHOT_BYTES.toLong())
                    remoteText = decompressIfGzip(remoteBytes, MAX_REMOTE_SNAPSHOT_BYTES)
                    remote = JSONObject(remoteText)
                    if ("YukiHub" != remote.optString("app", "")) throw Exception("云端文件不是有效的 YukiHub 同步文件")
                    remoteHash = sha256(remoteText)
                }

                val result = SyncResult()
                result.localBytes = localText.toByteArray(Charsets.UTF_8).size
                result.remoteBytes = if (remoteText == null) 0 else remoteText.toByteArray(Charsets.UTF_8).size

                if (!remoteExists) {
                    c.writeFile(REMOTE_FILE, compressGzip(localText))
                    markSynced(localHash)
                    result.uploaded = true
                    listener?.onProgress("首次上传", true)
                    listener?.onSyncComplete(result)
                    return@runOnSingle
                }

                val localChanged = localHash != lastHash
                val remoteChanged = remoteHash != lastHash

                // 新设备首次同步：本地没有游戏库而云端已有数据时，直接下载云端，避免默认本地资料参与“智能合并”覆盖云端资料。
                if (lastHash.isNullOrEmpty() && remoteExists && isSnapshotEmpty(local)) {
                    importSnapshot(remote!!)
                    markSynced(remoteHash)
                    result.downloaded = true
                    listener?.onProgress("首次下载云端数据", true)
                    listener?.onSyncComplete(result)
                    return@runOnSingle
                }

                if (!localChanged && !remoteChanged) {
                    result.noChanges = true
                    markSynced(localHash)
                    listener?.onProgress("数据检查", false)
                    listener?.onSyncComplete(result)
                    return@runOnSingle
                }
                if (localChanged && !remoteChanged) {
                    c.writeFile(REMOTE_FILE, compressGzip(localText))
                    markSynced(localHash)
                    result.uploaded = true
                    listener?.onProgress("上传本地修改", true)
                    listener?.onSyncComplete(result)
                    return@runOnSingle
                }
                if (!localChanged && remoteChanged) {
                    importSnapshot(remote!!)
                    markSynced(remoteHash)
                    result.downloaded = true
                    listener?.onProgress("下载云端修改", true)
                    listener?.onSyncComplete(result)
                    return@runOnSingle
                }

                val conflict = Conflict(local, remote!!, localHash, remoteHash, result.localBytes, result.remoteBytes)
                val decision = listener?.onConflict(conflict) ?: RESOLVE_MERGE
                if (decision == RESOLVE_CANCEL) {
                    result.cancelled = true
                    listener?.onSyncComplete(result)
                    return@runOnSingle
                }
                if (decision == RESOLVE_USE_REMOTE) {
                    importSnapshot(remote!!)
                    markSynced(remoteHash)
                    result.downloaded = true
                } else if (decision == RESOLVE_USE_LOCAL) {
                    c.writeFile(REMOTE_FILE, compressGzip(localText))
                    markSynced(localHash)
                    result.uploaded = true
                } else {
                    val merged = mergeSnapshots(local, remote!!)
                    val mergedText = snapshotToText(merged, MAX_REMOTE_SNAPSHOT_BYTES, "合并后的同步快照")
                    c.writeFile(REMOTE_FILE, compressGzip(mergedText))
                    markSynced(sha256(mergedText))
                    result.merged = true
                }
                listener?.onSyncComplete(result)
            } catch (t: Throwable) {
                Log.e(TAG, "sync failed", t)
                listener?.onError(t.message ?: "未知错误")
            }
        }
    }

    @Throws(Exception::class)
    fun exportSnapshotForLocalBackup(): JSONObject {
        // 本地备份同样限制游玩记录数量，避免备份文件过大
        val snapshot = buildLocalSnapshot(MAX_PLAY_SESSIONS)
        snapshotToText(snapshot, MAX_LOCAL_BACKUP_BYTES, "本地完整备份")
        return snapshot
    }

    @Throws(Exception::class)
    fun importSnapshotFromLocalBackup(root: JSONObject) {
        snapshotToText(root, MAX_LOCAL_BACKUP_BYTES, "本地备份")
        importSnapshot(root)
    }

    @Throws(Exception::class)
    private fun buildLocalSnapshot(): JSONObject {
        return buildLocalSnapshot(MAX_PLAY_SESSIONS)
    }

    @Throws(Exception::class)
    private fun buildLocalSnapshot(playSessionLimit: Int): JSONObject {
        val gameRepo = GameRepository(context)
        val metaRepo = MetadataRepository(context)
        val root = JSONObject()
        root.put("app", "YukiHub")
        root.put("schema", 5)
        root.put("lightweight", true)
        root.put("created_at", 0)
        root.put("note", "Only text metadata is synced. No game files, save files, or binary cover images are embedded.")

        val profile = JSONObject()
        profile.put("name", appPrefs.getString(KEY_PROFILE_NAME, "Yuki"))
        profile.put("signature", appPrefs.getString(KEY_PROFILE_SIGNATURE, ""))
        val avatarUri = appPrefs.getString(KEY_PROFILE_AVATAR, "")
        // 只同步网络头像地址；本地 file/content 路径跨设备无效，也可能暴露本机目录。
        if (avatarUri != null && (avatarUri.startsWith("http://") || avatarUri.startsWith("https://"))) {
            profile.put("avatar_uri", avatarUri)
        } else {
            profile.put("avatar_uri", "")
        }
        root.put("profile", profile)

        val settings = JSONObject()
        settings.put("metadata_source", appPrefs.getString(KEY_METADATA_SOURCE, "vndb"))
        // 不同步扫描目录（last_scan_root_uri/scan_root_uris）：它们通常包含用户本机目录/存储路径，跨设备无效且可能泄露隐私。
        settings.put("auto_scan_on_startup", appPrefs.getBoolean(KEY_AUTO_SCAN_ON_STARTUP, false))
        settings.put("startup_scan_depth", appPrefs.getInt(KEY_STARTUP_SCAN_DEPTH, 2))
        settings.put("engine_label_position", appPrefs.getString(KEY_ENGINE_LABEL_POSITION, "title"))
        settings.put("sort_mode", appPrefs.getString(KEY_SORT_MODE, "recent"))
        settings.put("background_video_sound", appPrefs.getBoolean(KEY_BACKGROUND_VIDEO_SOUND, false))
        settings.put("kr_engine_version", appPrefs.getString(KEY_KR_ENGINE_VERSION, "auto"))
        settings.put("kr_scoped_save_dir", appPrefs.getBoolean(KEY_KR_SCOPED_SAVE_DIR, false))
        settings.put("artemis_scoped_save_dir", appPrefs.getBoolean(KEY_ARTEMIS_SCOPED_SAVE_DIR, false))
        settings.put("tyrano_scoped_save_dir", appPrefs.getBoolean(KEY_TYRANO_SCOPED_SAVE_DIR, true))
        settings.put("ui_font_scale", appPrefs.getFloat(KEY_UI_FONT_SCALE, 1.0f))
        // 不同步自定义背景文件引用：本地图片/视频路径跨设备通常无效，且视频背景不应进入同步逻辑。
        settings.put("background_dim_enabled", appPrefs.getBoolean(KEY_BACKGROUND_DIM_ENABLED, true))
        settings.put("background_dim_alpha", appPrefs.getInt(KEY_BACKGROUND_DIM_ALPHA, 120))
        settings.put("game_columns", appPrefs.getInt(KEY_GAME_COLUMNS, 5))
        settings.put("ui_scale", appPrefs.getFloat(KEY_UI_SCALE, 1.0f))
        root.put("settings", settings)

        root.put("games", gameRepo.exportGamesJson())
        val sessions = gameRepo.exportPlaySessionsJson()
        root.put("play_sessions", if (playSessionLimit > 0) tail(sessions, playSessionLimit) else sessions)
        root.put("metadata_cache", metaRepo.exportMetadataJson())
        return root
    }

    private fun isSnapshotEmpty(root: JSONObject?): Boolean {
        if (root == null) return true
        val games = root.optJSONArray("games")
        return games == null || games.length() == 0
    }

    @Throws(Exception::class)
    private fun importSnapshot(root: JSONObject) {
        importSnapshotsAtomically(root)
    }

    @Throws(Exception::class)
    private fun importSnapshotsAtomically(vararg roots: JSONObject?) {
        if (roots.isEmpty()) return
        for (root in roots) {
            if (root == null || "YukiHub" != root.optString("app", "")) {
                throw Exception("不是有效的 YukiHub 同步文件")
            }
        }
        val prefsEditor = appPrefs.edit()
        val gameRepo = GameRepository(context)
        val metaRepo = MetadataRepository(context)
        val helper = YukiDatabaseHelper(context)
        val db = helper.writableDatabase
        var transactionStarted = false
        try {
            db.beginTransaction()
            transactionStarted = true
            for (root in roots) {
                applySnapshotPreferences(root!!, prefsEditor)
                gameRepo.importGamesJson(db, root.optJSONArray("games"))
                gameRepo.importPlaySessionsJson(db, root.optJSONArray("play_sessions"))
                if (root.has("metadata_cache")) metaRepo.importMetadataJson(db, root.optJSONArray("metadata_cache"))
            }
            db.setTransactionSuccessful()
        } finally {
            try {
                if (transactionStarted) db.endTransaction()
            } finally {
                helper.close()
            }
        }
        // 偏好设置在数据库完整提交后一次性落盘，失败的数据库导入不会污染配置。
        if (!prefsEditor.commit()) throw Exception("同步设置保存失败")
    }

    private fun applySnapshotPreferences(root: JSONObject, prefsEditor: SharedPreferences.Editor) {
        val profile = root.optJSONObject("profile")
        if (profile != null) {
            var incomingAvatar = profile.optString("avatar_uri", "") ?: ""
            if (!(incomingAvatar.startsWith("http://") || incomingAvatar.startsWith("https://"))) incomingAvatar = ""
            prefsEditor
                .putString(KEY_PROFILE_NAME, profile.optString("name", appPrefs.getString(KEY_PROFILE_NAME, "Yuki")))
                .putString(KEY_PROFILE_SIGNATURE, profile.optString("signature", appPrefs.getString(KEY_PROFILE_SIGNATURE, "")))
                .putString(KEY_PROFILE_AVATAR, incomingAvatar)
        }
        val settings = root.optJSONObject("settings")
        if (settings != null) {
            val source = settings.optString("metadata_source", "")
            if (SOURCE_VNDB == source || SOURCE_BANGUMI == source || SOURCE_BANGUMI_MIRROR == source || SOURCE_YMGAL == source) prefsEditor.putString(KEY_METADATA_SOURCE, source)
            // 兼容旧备份：忽略扫描目录（last_scan_root_uri/scan_root_uris），避免导入跨设备无效路径或泄露本机目录。
            if (settings.has("auto_scan_on_startup")) prefsEditor.putBoolean(KEY_AUTO_SCAN_ON_STARTUP, settings.optBoolean("auto_scan_on_startup", false))
            if (settings.has("startup_scan_depth")) prefsEditor.putInt(KEY_STARTUP_SCAN_DEPTH, Math.max(1, Math.min(4, settings.optInt("startup_scan_depth", 2))))
            if (settings.has("engine_label_position")) prefsEditor.putString(KEY_ENGINE_LABEL_POSITION, if ("cover" == settings.optString("engine_label_position", "title")) "cover" else "title")
            if (settings.has("sort_mode")) {
                val sort = settings.optString("sort_mode", "recent")
                if ("name" == sort || "newest" == sort || "recent" == sort) prefsEditor.putString(KEY_SORT_MODE, sort)
            }
            if (settings.has("background_video_sound")) prefsEditor.putBoolean(KEY_BACKGROUND_VIDEO_SOUND, settings.optBoolean("background_video_sound", false))
            if (settings.has("kr_engine_version")) {
                val krVersion = settings.optString("kr_engine_version", "auto")
                if ("auto" == krVersion || "1.3.9" == krVersion || "1.3.4" == krVersion) prefsEditor.putString(KEY_KR_ENGINE_VERSION, krVersion)
            }
            if (settings.has("kr_scoped_save_dir")) prefsEditor.putBoolean(KEY_KR_SCOPED_SAVE_DIR, settings.optBoolean("kr_scoped_save_dir", false))
            if (settings.has("artemis_scoped_save_dir")) prefsEditor.putBoolean(KEY_ARTEMIS_SCOPED_SAVE_DIR, settings.optBoolean("artemis_scoped_save_dir", false))
            if (settings.has("tyrano_scoped_save_dir")) prefsEditor.putBoolean(KEY_TYRANO_SCOPED_SAVE_DIR, settings.optBoolean("tyrano_scoped_save_dir", true))
            if (settings.has("ui_font_scale")) prefsEditor.putFloat(KEY_UI_FONT_SCALE, Math.max(0.85, Math.min(1.30, settings.optDouble("ui_font_scale", 1.0))).toFloat())
            // 不导入 custom_background/custom_background_type，避免旧备份里的本地图片/视频路径污染新设备。
            if (settings.has("background_dim_enabled")) prefsEditor.putBoolean(KEY_BACKGROUND_DIM_ENABLED, settings.optBoolean("background_dim_enabled", true))
            if (settings.has("background_dim_alpha")) prefsEditor.putInt(KEY_BACKGROUND_DIM_ALPHA, settings.optInt("background_dim_alpha", 120))
            if (settings.has("game_columns")) prefsEditor.putInt(KEY_GAME_COLUMNS, Math.max(2, Math.min(10, settings.optInt("game_columns", 5))))
            if (settings.has("ui_scale")) prefsEditor.putFloat(KEY_UI_SCALE, Math.max(0.70, Math.min(1.50, settings.optDouble("ui_scale", 1.0))).toFloat())
        }
    }

    @Throws(Exception::class)
    private fun mergeSnapshots(local: JSONObject, remote: JSONObject): JSONObject {
        // 顺序：先导入 remote，再导入 local（local 覆盖 remote），最后重新导出本地快照。
        importSnapshotsAtomically(remote, local)
        return buildLocalSnapshot()
    }

    @Throws(Exception::class)
    private fun tail(arr: JSONArray?, max: Int): JSONArray {
        if (arr == null) return JSONArray()
        if (arr.length() <= max) return arr
        val out = JSONArray()
        val start = Math.max(0, arr.length() - max)
        for (i in start until arr.length()) out.put(arr.get(i))
        return out
    }

    private fun markSynced(hash: String?) {
        syncPrefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).putString(KEY_LAST_SYNC_HASH, hash ?: "").apply()
    }

    @Throws(Exception::class)
    private fun sha256(text: String?): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest((text ?: "").toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (b in bytes) sb.append(String.format(Locale.ROOT, "%02x", b))
        return sb.toString()
    }

    companion object {
        const val MAX_REMOTE_SNAPSHOT_BYTES = 16 * 1024 * 1024
        const val MAX_LOCAL_BACKUP_BYTES = 32 * 1024 * 1024
        const val RESOLVE_CANCEL = 0
        const val RESOLVE_USE_LOCAL = 1
        const val RESOLVE_USE_REMOTE = 2
        const val RESOLVE_MERGE = 3

        private const val TAG = "SyncManager"
        private const val SYNC_PREFS = "yukihub_sync"
        private const val APP_PREFS = "yukihub_prefs"
        // 坚果云 WebDAV 根目录通常不可直接写文件，需要写入一个已存在的同步文件夹。
        // 请用户先在坚果云中创建 YukiHub 文件夹。
        private const val REMOTE_DIR = "YukiHub"
        private const val REMOTE_FILE = REMOTE_DIR + "/YukiHub_sync.json"

        private const val KEY_SERVER_URL = "webdav_server"
        private const val KEY_USERNAME = "webdav_username"
        private const val KEY_PASSWORD = "webdav_password"
        private const val KEY_AUTO_SYNC = "auto_sync"
        private const val KEY_LAST_SYNC = "last_sync_time"
        private const val KEY_LAST_SYNC_HASH = "last_sync_hash"

        private const val KEY_PROFILE_NAME = "profile_name"
        private const val KEY_PROFILE_SIGNATURE = "profile_signature"
        private const val KEY_PROFILE_AVATAR = "profile_avatar"
        private const val KEY_METADATA_SOURCE = "metadata_source"
        private const val SOURCE_VNDB = "vndb"
        private const val SOURCE_BANGUMI = "bangumi"
        private const val SOURCE_BANGUMI_MIRROR = "bangumi_mirror"
        private const val SOURCE_YMGAL = "ymgal"
        private const val KEY_LAST_SCAN_ROOT_URI = "last_scan_root_uri"
        private const val KEY_BACKGROUND_DIM_ENABLED = "background_dim_enabled"
        private const val KEY_BACKGROUND_DIM_ALPHA = "background_dim_alpha"
        private const val KEY_AUTO_SCAN_ON_STARTUP = "auto_scan_on_startup"
        private const val KEY_STARTUP_SCAN_DEPTH = "startup_scan_depth"
        private const val KEY_ENGINE_LABEL_POSITION = "engine_label_position"
        private const val KEY_SORT_MODE = "sort_mode"
        private const val KEY_BACKGROUND_VIDEO_SOUND = "background_video_sound"
        private const val KEY_KR_ENGINE_VERSION = "kr_engine_version"
        private const val KEY_KR_SCOPED_SAVE_DIR = "kr_scoped_save_dir"
        private const val KEY_ARTEMIS_SCOPED_SAVE_DIR = "artemis_scoped_save_dir"
        private const val KEY_TYRANO_SCOPED_SAVE_DIR = "tyrano_scoped_save_dir"
        private const val KEY_UI_FONT_SCALE = "ui_font_scale"
        private const val KEY_GAME_COLUMNS = "game_columns"
        private const val KEY_UI_SCALE = "ui_scale"

        // 游戏库/游戏卡片信息必须完整同步；只限制动态类数据（游玩记录）数量。
        // WebDAV 同步和本地备份统一限制，保持一致。
        //
        // 迁移风险：从旧版本（MAX_PLAY_SESSIONS=200）升级后，首次同步会用本地 30 条覆写云端
        // 最多 200 条历史记录，导致最多 170 条旧记录丢失。这是有意调整：控制文件大小并聚焦近期记录。
        // 如需保留完整历史，用户应在升级前手动导出本地备份。
        private const val MAX_PLAY_SESSIONS = 30

        @Throws(Exception::class)
        private fun snapshotToText(root: JSONObject?, maxBytes: Int, label: String): String {
            val text = root?.toString() ?: ""
            val bytes = text.toByteArray(Charsets.UTF_8).size
            if (bytes > maxBytes) {
                throw Exception("${label}过大（${bytes} 字节，最大允许 ${maxBytes} 字节）")
            }
            return text
        }

        /**
         * 将 JSON 文本 gzip 压缩为 byte[]，用于 WebDAV 上传和本地备份写入。
         */
        @Throws(Exception::class)
        internal fun compressGzip(text: String?): ByteArray {
            val raw = (text ?: "").toByteArray(Charsets.UTF_8)
            val bos = ByteArrayOutputStream(maxOf(256, raw.size / 4))
            GZIPOutputStream(bos).use { gzip ->
                gzip.write(raw)
                gzip.finish()
            }
            return bos.toByteArray()
        }

        /**
         * 读取 WebDAV / 本地备份的 byte[] 数据，自动检测 gzip 格式并解压。
         * 兼容老的纯 JSON 云端文件：如果不是 gzip 格式（没有 0x1f 0x8b 魔数），直接当 UTF-8 文本返回。
         *
         * @param data 原始字节数据
         * @param maxBytes 解压输出最大字节数，防止压缩放大攻击。
         *                 WebDAV 远程快照用 [MAX_REMOTE_SNAPSHOT_BYTES]（16MB），
         *                 本地备份用 [MAX_LOCAL_BACKUP_BYTES]（32MB），须与导出端限制一致。
         */
        @Throws(Exception::class)
        internal fun decompressIfGzip(data: ByteArray?, maxBytes: Int): String {
            if (data == null || data.isEmpty()) return ""
            // gzip 文件头: 0x1f 0x8b
            if (data.size >= 2 && (data[0].toInt() and 0xff) == 0x1f && (data[1].toInt() and 0xff) == 0x8b) {
                GZIPInputStream(ByteArrayInputStream(data)).use { gzip ->
                    val bos = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var len: Int
                    while (gzip.read(buf).also { len = it } != -1) {
                        // 写入前校验，避免越过限制最多 buf.length-1 字节才抛出
                        if (bos.size() + len > maxBytes) {
                            throw Exception("解压数据超过大小限制（${maxBytes} 字节）")
                        }
                        bos.write(buf, 0, len)
                    }
                    return bos.toString("UTF-8")
                }
            }
            // 不是 gzip，按纯 JSON 文本处理（兼容老格式）
            return String(data, Charsets.UTF_8)
        }
    }

    data class SyncConfig(val serverUrl: String, val username: String, val password: String, val autoSync: Boolean)

    data class Conflict(
        val local: JSONObject,
        val remote: JSONObject,
        val localHash: String,
        val remoteHash: String,
        val localBytes: Int,
        val remoteBytes: Int
    )

    class SyncResult {
        var uploaded = false
        var downloaded = false
        var merged = false
        var noChanges = false
        var cancelled = false
        var localBytes = 0
        var remoteBytes = 0
        fun hasChanges() = uploaded || downloaded || merged
    }

    interface SyncListener {
        fun onSyncStart()
        fun onProgress(item: String, changed: Boolean)
        fun onConflict(conflict: Conflict): Int
        fun onSyncComplete(result: SyncResult)
        fun onError(error: String)
    }
}
