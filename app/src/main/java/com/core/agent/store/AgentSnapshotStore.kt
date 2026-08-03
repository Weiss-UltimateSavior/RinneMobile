package com.core.agent.store

import android.content.Context
import com.core.util.TimeFormatUtil
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Recoverable mutation snapshots stored outside Android backup and FileProvider roots. */
object AgentSnapshotStore {
    private const val MAX_SNAPSHOTS = 50
    private const val INCOMPLETE_GRACE_MS = 5 * 60 * 1000L

    data class Snapshot(
        @JvmField val id: String?,
        @JvmField val gameId: Long,
        @JvmField val gameTitle: String?,
        @JvmField val rootIdentity: String?,
        @JvmField val relativePath: String?,
        @JvmField val contentSha256: String?,
        @JvmField val expectedCurrentSha256: String?,
        @JvmField val encoding: String?,
        @JvmField val status: String?,
        @JvmField val createdAt: Long,
        @JvmField val content: ByteArray
    )

    @JvmStatic
    @Throws(Exception::class)
    fun create(
        context: Context, gameId: Long, gameTitle: String?, rootIdentity: String?,
        relativePath: String?, contentSha256: String?, expectedCurrentSha256: String?,
        encoding: String?, content: ByteArray
    ): String {
        val directory = directory(context)
        val id = UUID.randomUUID().toString()
        val data = File(directory, "$id.bin")
        val metadata = File(directory, "$id.json")
        val metadataTemp = File(directory, "$id.json.tmp")
        try {
            syncWrite(data, content)
            val value = JSONObject()
                .put("id", id)
                .put("game_id", gameId)
                .put("game_title", safe(gameTitle, 200))
                .put("root_identity", rootIdentity)
                .put("relative_path", relativePath)
                .put("content_sha256", contentSha256)
                .put("expected_current_sha256", expectedCurrentSha256)
                .put("encoding", encoding)
                .put("status", "pending")
                .put("created_at", System.currentTimeMillis())
            syncWrite(metadataTemp, value.toString().toByteArray(StandardCharsets.UTF_8))
            moveAtomically(metadataTemp, metadata)
            cleanup(directory)
            return id
        } catch (error: Throwable) {
            // 回滚清理：写入失败时删除半成品文件再重抛；Error 也须先清理，故捕获 Throwable
            data.delete(); metadata.delete(); metadataTemp.delete()
            throw error
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun load(context: Context, id: String?): Snapshot {
        if (id == null || !id.matches(Regex("[0-9a-fA-F-]{36}"))) throw IllegalArgumentException("snapshot_id 格式错误")
        val directory = directory(context)
        val value = JSONObject(String(read(File(directory, "$id.json"), 16 * 1024), StandardCharsets.UTF_8))
        val content = read(File(directory, "$id.bin"), 64 * 1024)
        return Snapshot(
            id, value.getLong("game_id"), value.optString("game_title"),
            value.getString("root_identity"), value.getString("relative_path"),
            value.getString("content_sha256"), value.getString("expected_current_sha256"),
            value.optString("encoding", "utf-8"), value.optString("status", "pending"),
            value.getLong("created_at"), content
        )
    }

    @JvmStatic
    @Throws(Exception::class)
    fun markStatus(context: Context, id: String?, status: String?, observedHash: String?) {
        val directory = directory(context)
        val metadata = File(directory, "$id.json")
        val value = JSONObject(String(read(metadata, 16 * 1024), StandardCharsets.UTF_8))
        value.put("status", status).put("status_updated_at", System.currentTimeMillis())
        if (!observedHash.isNullOrEmpty()) value.put("observed_sha256", observedHash)
        val temp = File(directory, "$id.json.status.tmp")
        syncWrite(temp, value.toString().toByteArray(StandardCharsets.UTF_8))
        try {
            moveAtomically(temp, metadata)
        } finally {
            temp.delete()
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun list(context: Context, gameId: Long, limit: Int): String {
        val files = directory(context).listFiles { _, name -> name.endsWith(".json") } ?: emptyArray()
        files.sortByDescending { it.lastModified() }
        val items = JSONArray()
        for (file in files) {
            if (items.length() >= limit) break
            try {
                val value = JSONObject(String(read(file, 16 * 1024), StandardCharsets.UTF_8))
                if (value.optLong("game_id") != gameId) continue
                items.put(JSONObject()
                    .put("snapshot_id", value.getString("id"))
                    .put("game_id", gameId)
                    .put("relative_path", value.getString("relative_path"))
                    .put("content_sha256", value.getString("content_sha256"))
                    .put("expected_current_sha256", value.getString("expected_current_sha256"))
                    .put("status", value.optString("status", "pending"))
                    .put("created_at", value.getLong("created_at")))
            } catch (e: Exception) {
                // 跳过损坏快照（解析/IO 失败均忽略单条）
            }
        }
        return JSONObject().put("items", items).toString()
    }

    @JvmStatic
    @Throws(Exception::class)
    fun recentDisplay(context: Context, limit: Int): String {
        val files = directory(context).listFiles { _, name -> name.endsWith(".json") }
        if (files == null || files.isEmpty()) return "暂无智能体文件修改快照。"
        files.sortByDescending { it.lastModified() }
        val text = StringBuilder("这些记录独立于对话，清空会话不会删除。需要恢复时，可把快照 ID 发给智能体。\n")
        var count = 0
        for (file in files) {
            if (count >= limit) break
            try {
                val value = JSONObject(String(read(file, 16 * 1024), StandardCharsets.UTF_8))
                text.append("\n游戏：").append(value.optString("game_title"))
                    .append("\n文件：").append(value.optString("relative_path"))
                    .append("\n状态：").append(value.optString("status", "pending"))
                    .append("\n快照 ID：").append(value.optString("id"))
                    .append("\n时间：").append(TimeFormatUtil.date(value.optLong("created_at")))
                    .append('\n')
                count++
            } catch (e: Exception) {
                // 跳过损坏快照（解析/IO 失败均忽略单条）
            }
        }
        return text.toString()
    }

    @Throws(IOException::class)
    private fun directory(context: Context): File {
        val value = File(context.getNoBackupFilesDir(), "agent_snapshots")
        if (!value.exists() && !value.mkdirs()) throw IOException("无法创建本地快照目录")
        recoverIncompleteSnapshots(value, System.currentTimeMillis())
        return value
    }

    @Throws(IOException::class)
    private fun syncWrite(file: File, content: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(content); output.flush(); output.getFD().sync()
        }
    }

    @Throws(IOException::class)
    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE)
        } catch (ignored: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Throws(IOException::class)
    private fun read(file: File, max: Int): ByteArray {
        if (!file.isFile || file.length() > max.toLong()) throw IOException("快照不存在或已损坏")
        val result = ByteArray(file.length().toInt())
        FileInputStream(file).use { input ->
            var offset = 0
            while (offset < result.size) {
                val count = input.read(result, offset, result.size - offset)
                if (count < 0) throw IOException("快照读取不完整")
                offset += count
            }
        }
        return result
    }

    @JvmStatic
    @JvmName("recoverIncompleteSnapshots")
    @Throws(IOException::class)
    internal fun recoverIncompleteSnapshots(directory: File, now: Long) {
        val temporary = directory.listFiles { _, name -> name.endsWith(".json.tmp") }
        if (temporary != null) for (temp in temporary) {
            if (now - temp.lastModified() < INCOMPLETE_GRACE_MS) continue
            val name = temp.name
            val id = name.substring(0, name.length - ".json.tmp".length)
            val data = File(directory, "$id.bin")
            val metadata = File(directory, "$id.json")
            if (!metadata.exists() && data.isFile && validTemporaryMetadata(temp, id)) {
                moveAtomically(temp, metadata)
            } else {
                temp.delete()
            }
        }

        val statusTemporary = directory.listFiles { _, name -> name.endsWith(".json.status.tmp") }
        if (statusTemporary != null) for (temp in statusTemporary) {
            if (now - temp.lastModified() < INCOMPLETE_GRACE_MS) continue
            val name = temp.name
            val id = name.substring(0, name.length - ".json.status.tmp".length)
            val data = File(directory, "$id.bin")
            val metadata = File(directory, "$id.json")
            if (data.isFile && validTemporaryMetadata(temp, id)) {
                moveAtomically(temp, metadata)
            } else {
                temp.delete()
            }
        }

        val dataFiles = directory.listFiles { _, name -> name.endsWith(".bin") }
        if (dataFiles != null) for (data in dataFiles) {
            if (now - data.lastModified() < INCOMPLETE_GRACE_MS) continue
            val name = data.name
            val id = name.substring(0, name.length - ".bin".length)
            if (!File(directory, "$id.json").isFile
                && !File(directory, "$id.json.tmp").isFile
            ) data.delete()
        }
    }

    private fun validTemporaryMetadata(file: File, expectedId: String): Boolean {
        return try {
            val value = JSONObject(String(read(file, 16 * 1024), StandardCharsets.UTF_8))
            expectedId == value.optString("id")
        } catch (e: Exception) {
            // 临时元数据解析失败视为无效
            false
        }
    }

    private fun cleanup(directory: File) {
        val metadata = directory.listFiles { _, name -> name.endsWith(".json") }
        if (metadata == null || metadata.size <= MAX_SNAPSHOTS) return
        metadata.sortByDescending { it.lastModified() }
        var retained = metadata.size
        var i = metadata.size - 1
        while (i >= 0 && retained > MAX_SNAPSHOTS) {
            try {
                val value = JSONObject(String(read(metadata[i], 16 * 1024), StandardCharsets.UTF_8))
                val status = value.optString("status", "pending")
                if (status != "pending" && status != "recovery_required") {
                    val name = metadata[i].name
                    val id = name.substring(0, name.length - 5)
                    if (metadata[i].delete()) {
                        File(directory, "$id.bin").delete()
                        retained--
                    }
                }
            } catch (e: Exception) {
                // 跳过损坏快照（解析/IO 失败均忽略单条）
            }
            i--
        }
    }

    private fun safe(value: String?, max: Int): String {
        val text = (value ?: "")
            .replace("[\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069]".toRegex(), " ")
            .trim { it <= ' ' }
        return if (text.length <= max) text else text.substring(0, max)
    }
}
