package com.yuki.yukihub.launcherbridge

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import com.yuki.yukihub.sync.SyncManager
import com.yuki.yukihub.util.RxMainScheduler
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 同步桥接层：WebDAV 同步配置、本地备份导入导出、云备份快照。
 */
object LauncherSyncBridge {

    private val MAX_LOCAL_BACKUP_BYTES: Int = SyncManager.MAX_LOCAL_BACKUP_BYTES

    @JvmStatic
    fun isConfigured(context: Context?): Boolean =
        context != null && SyncManager(context.applicationContext).isConfigured

    @JvmStatic
    fun lastSyncTime(context: Context?): Long {
        if (context == null) return 0L
        return SyncManager(context.applicationContext).lastSyncTime
    }

    @JvmStatic
    fun getConfig(context: Context?): SyncConfigSnapshot {
        if (context == null) return SyncConfigSnapshot("", "", "", false)
        val source = SyncManager(context.applicationContext).config
            ?: return SyncConfigSnapshot("", "", "", false)
        return SyncConfigSnapshot(source.serverUrl, source.username, source.password, source.autoSync)
    }

    /** 不可变的 WebDAV 同步配置快照。对应 SyncManager.SyncConfig。 */
    class SyncConfigSnapshot(
        @JvmField val serverUrl: String,
        @JvmField val username: String,
        @JvmField val password: String,
        @JvmField val autoSync: Boolean
    )

    @JvmStatic
    fun saveConfig(context: Context?, serverUrl: String, username: String, password: String, autoSync: Boolean) {
        if (context == null) return
        SyncManager(context.applicationContext).saveConfig(serverUrl, username, password, autoSync)
    }

    @JvmStatic
    @Throws(Exception::class)
    fun exportLocalBackup(context: Context?): JSONObject {
        if (context == null) throw Exception("上下文不可用")
        val root = SyncManager(context.applicationContext).exportSnapshotForLocalBackup()
        root.put("created_at", System.currentTimeMillis())
        root.put("backup_type", "local_full")
        root.put("note", "Local backup uses the same schema as WebDAV sync, but keeps full play session history.")
        return root
    }

    @JvmStatic
    @Throws(Exception::class)
    fun importLocalBackup(context: Context?, snapshot: JSONObject?) {
        if (context == null) throw Exception("上下文不可用")
        if (snapshot == null) throw Exception("备份内容为空")
        if ("YukiHub" != snapshot.optString("app", "")) {
            throw Exception("不是有效的 YukiHub 备份")
        }
        SyncManager(context.applicationContext).importSnapshotFromLocalBackup(snapshot)
    }

    @JvmStatic
    @Throws(Exception::class)
    fun importLocalBackupFromUri(context: Context?, uri: Uri?) {
        if (context == null) throw Exception("上下文不可用")
        if (uri == null) throw Exception("备份文件不可用")
        val text = readTextFromUri(context, uri)
        val root = JSONObject(text)
        importLocalBackup(context, root)
    }

    /**
     * 导出用于账户云备份的结构化快照。与 [exportLocalBackup] 不同：
     * 不附加 created_at/note 字段，backup_type 标记为 launcher_cloud_play_v2，
     * 供 Launcher 账户系统作为可恢复的云备份 payload 使用。
     */
    @JvmStatic
    fun exportCloudSnapshot(context: Context?): JSONObject? {
        if (context == null) return null
        return try {
            val root = SyncManager(context.applicationContext).exportSnapshotForLocalBackup()
            root.put("backup_type", "launcher_cloud_play_v2")
            root
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 导入账户云备份 JSON。仅接受 YukiHub schema 且包含 games/play_sessions 数组的快照。
     *
     * @return 成功导入返回 true；上下文无效、格式不符或导入异常返回 false
     */
    @JvmStatic
    fun importCloudSnapshot(context: Context?, root: JSONObject?): Boolean {
        if (context == null || root == null) return false
        return try {
            if ("YukiHub" != root.optString("app", "")) return false
            if (root.optJSONArray("games") == null || root.optJSONArray("play_sessions") == null) return false
            SyncManager(context.applicationContext).importSnapshotFromLocalBackup(root)
            true
        } catch (_: Exception) {
            false
        }
    }

    @Throws(Exception::class)
    private fun readTextFromUri(context: Context, uri: Uri): String {
        var declaredLength = -1L
        try {
            val descriptor: AssetFileDescriptor? =
                context.contentResolver.openAssetFileDescriptor(uri, "r")
            descriptor?.use { declaredLength = it.length }
        } catch (_: Exception) {
            // Some document providers cannot report a length; the stream limit below remains authoritative.
        }
        if (declaredLength > MAX_LOCAL_BACKUP_BYTES) {
            throw Exception("本地备份文件过大（文件声明 $declaredLength 字节，最大允许 $MAX_LOCAL_BACKUP_BYTES 字节）")
        }
        val input: InputStream = context.contentResolver.openInputStream(uri)
            ?: throw Exception("openInputStream failed")
        input.use { stream ->
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0L
            var len: Int
            while (stream.read(buf).also { len = it } != -1) {
                total += len
                if (total > MAX_LOCAL_BACKUP_BYTES) {
                    throw Exception("本地备份文件过大（最大允许 $MAX_LOCAL_BACKUP_BYTES 字节）")
                }
                bos.write(buf, 0, len)
            }
            return bos.toString("UTF-8")
        }
    }

    @JvmStatic
    fun testConnection(context: Context?): Boolean {
        if (context == null) return false
        return SyncManager(context.applicationContext).testConnection()
    }

    @JvmStatic
    fun isAutoSyncEnabled(context: Context?): Boolean {
        if (context == null) return false
        return SyncManager(context.applicationContext).isAutoSyncEnabled
    }

    @JvmStatic
    fun syncNow(context: Context?, callback: Callback?) {
        if (context == null) {
            callback?.onError("上下文不可用")
            return
        }
        val syncManager = SyncManager(context.applicationContext)
        syncManager.sync(object : SyncManager.SyncListener {
            override fun onSyncStart() {
                post { callback?.onStart() }
            }

            override fun onProgress(item: String, changed: Boolean) {
                post { callback?.onProgress(item, changed) }
            }

            override fun onConflict(conflict: SyncManager.Conflict): Int {
                post { callback?.onError("检测到同步冲突，请打开同步中心处理") }
                return SyncManager.RESOLVE_CANCEL
            }

            override fun onSyncComplete(result: SyncManager.SyncResult) {
                post { callback?.onComplete(summary(result)) }
            }

            override fun onError(error: String) {
                post { callback?.onError(if (error.isBlank()) "同步失败" else error) }
            }
        })
    }

    private fun post(runnable: Runnable?) {
        if (runnable != null) RxMainScheduler.post(runnable)
    }

    private fun summary(result: SyncManager.SyncResult?): String {
        if (result == null) return "同步完成"
        if (result.cancelled) return "同步已取消"
        if (result.uploaded) return "已上传本地修改"
        if (result.downloaded) return "已下载云端修改"
        if (result.merged) return "已合并同步数据"
        if (result.noChanges) return "云端与本地已是最新"
        return "同步完成"
    }

    interface Callback {
        fun onStart()
        fun onProgress(item: String, changed: Boolean)
        fun onComplete(message: String)
        fun onError(error: String)
    }
}
