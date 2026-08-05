package com.core.sync

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 同步/备份快照的序列化编解码（重构计划 3.5 阶段 97 拆分自 SyncManager companion）。
 *
 * 纯静态函数，无实例状态：JSON 文本大小校验 + gzip 压缩/解压（带解压放大防护）。
 * 供 [SyncManager]（WebDAV 同步）与 com.core.launcherbridge.LauncherSyncBridge
 * （本地 .ykbak 备份）共用，行为与原实现逐字等价。
 */
object SyncSnapshotCodec {

    const val MAX_REMOTE_SNAPSHOT_BYTES = 16 * 1024 * 1024
    const val MAX_LOCAL_BACKUP_BYTES = 32 * 1024 * 1024

    /**
     * 将 JSON 对象序列化为文本并校验大小上限。
     *
     * @throws IllegalArgumentException 文本超过 maxBytes 上限
     */
    @Throws(IllegalArgumentException::class)
    fun snapshotToText(root: JSONObject?, maxBytes: Int, label: String): String {
        val text = root?.toString() ?: ""
        val bytes = text.toByteArray(Charsets.UTF_8).size
        if (bytes > maxBytes) {
            throw IllegalArgumentException("${label}过大（${bytes} 字节，最大允许 ${maxBytes} 字节）")
        }
        return text
    }

    /**
     * 将 JSON 文本 gzip 压缩为 byte[]，用于 WebDAV 上传和本地备份写入。
     *
     * @throws IOException gzip 写入失败
     */
    @Throws(IOException::class)
    fun compressGzip(text: String?): ByteArray {
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
     * @throws IOException 解压输出超过 maxBytes 上限（压缩放大防护）
     */
    @Throws(IOException::class)
    fun decompressIfGzip(data: ByteArray?, maxBytes: Int): String {
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
                        throw IOException("解压数据超过大小限制（${maxBytes} 字节）")
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
