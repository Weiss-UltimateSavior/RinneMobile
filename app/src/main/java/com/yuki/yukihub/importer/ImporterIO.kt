package com.yuki.yukihub.importer

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap

/**
 * 导入器共享 IO 工具：统一处理 Zip Bomb、Zip Slip、临时目录清理。
 *
 * - [readBytes] 限制单个 entry 读取字节数 + 通过 [ReadAccumulator] 跟踪累计字节，
 *   避免恶意 ZIP 用多个 49MB entry 绕过单条限制。
 * - [ensurePathInside] 通过 canonical path 校验防止 zip slip 路径穿越。
 * - [skipFully] 拒绝 entry 时不分配完整 byte[]，分块跳过。
 * - [MAX_ENTRY_COUNT] 限制 ZIP entry 数量，避免恶意 ZIP 用数百万空 entry 耗尽资源。
 * - [registerTempDir] + [cleanupAllTempDirs] 替代 deleteOnExit()
 *   （Android 进程几乎不退出，shutdown hook 不会执行，deleteOnExit 形同虚设）。
 *
 * 注：原 Java 为 package-private；Kotlin 无包级可见性，改为 public object。
 * 逻辑上仍仅供 `com.yuki.yukihub.importer` 包内使用。
 */
object ImporterIO {

    /** 单个 entry 最大字节数：50MB（足够容纳正常封面图与 JSON） */
    const val MAX_ENTRY_BYTES: Long = 50L * 1024 * 1024

    /** 累计最大字节数：200MB（防止恶意 ZIP 用多个 entry 累积内存） */
    const val MAX_TOTAL_BYTES: Long = 200L * 1024 * 1024

    /** ZIP entry 最大数量：10000（防止恶意 ZIP 用海量空 entry 耗尽资源） */
    const val MAX_ENTRY_COUNT: Int = 10000

    /** 已注册的临时目录，由 ImporterService 写库完成后统一清理 */
    private val tempDirs = ConcurrentHashMap<File, Boolean>()

    /**
     * 累积字节计数器，用于在解压循环中跟踪总字节数。
     * 单条 entry 检查 [MAX_ENTRY_BYTES]，累计检查 [MAX_TOTAL_BYTES]。
     * 每次解压在 parse() 入口 new 一个，循环中复用。
     */
    class ReadAccumulator(@JvmField val maxTotal: Long) {
        @JvmField var total: Long = 0
    }

    /**
     * 读取流到 byte[]，超过 maxBytes 或累计超过 acc.maxTotal 抛 IOException。
     * 防止恶意大文件触发 OOM。
     *
     * @param acc 累积计数器，null 表示不跟踪累计（用于单文件场景如 Playnite）
     */
    @JvmStatic
    @Throws(IOException::class)
    fun readBytes(input: InputStream, maxBytes: Long, acc: ReadAccumulator?): ByteArray {
        val baos = ByteArrayOutputStream()
        val tmp = ByteArray(8192)
        var entryTotal = 0L
        while (true) {
            val len = input.read(tmp)
            if (len <= 0) break
            entryTotal += len
            if (entryTotal > maxBytes) {
                throw IOException("条目大小超过上限 ${maxBytes / 1024 / 1024}MB")
            }
            if (acc != null) {
                acc.total += len
                if (acc.total > acc.maxTotal) {
                    throw IOException("累计大小超过上限 ${acc.maxTotal / 1024 / 1024}MB")
                }
            }
            baos.write(tmp, 0, len)
        }
        return baos.toByteArray()
    }

    /** 读取流到 String，超过 maxBytes 抛 IOException。无累计跟踪（单文件场景）。 */
    @JvmStatic
    @Throws(IOException::class)
    fun readString(input: InputStream, maxBytes: Long, charset: Charset): String =
        String(readBytes(input, maxBytes, null), charset)

    /** 读取流到 String，超过 maxBytes 或累计超过 acc.maxTotal 抛 IOException。 */
    @JvmStatic
    @Throws(IOException::class)
    fun readString(input: InputStream, maxBytes: Long, charset: Charset, acc: ReadAccumulator?): String =
        String(readBytes(input, maxBytes, acc), charset)

    /**
     * 分块跳过指定字节数，不分配完整 byte[] 缓冲区。
     * 用于 zip slip 拒绝 entry 时维持流进度，避免无谓的 50MB 堆分配。
     */
    @JvmStatic
    @Throws(IOException::class)
    fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        val tmp = ByteArray(8192)
        while (remaining > 0) {
            val toRead = minOf(remaining, tmp.size.toLong()).toInt()
            val len = input.read(tmp, 0, toRead)
            if (len <= 0) break
            remaining -= len
        }
    }

    /**
     * 校验 outFile 的 canonical path 必须位于 baseDir 之内，
     * 防止 zip slip 通过 "../" 或绝对路径逃逸。
     */
    @JvmStatic
    @Throws(IOException::class)
    fun ensurePathInside(outFile: File, baseDir: File) {
        val basePath = baseDir.canonicalPath
        val outPath = outFile.canonicalPath
        if (!outPath.startsWith(basePath + File.separator) && outPath != basePath) {
            throw IOException("路径穿越检测： $outPath 不在 $basePath 之内")
        }
    }

    /** 注册临时目录，待 ImporterService 写库完成后统一清理。 */
    @JvmStatic
    fun registerTempDir(dir: File?) {
        if (dir != null) tempDirs[dir] = true
    }

    /** 递归删除所有已注册的临时目录。在 ImporterService.importSelected 完成后调用。 */
    @JvmStatic
    fun cleanupAllTempDirs() {
        for (dir in tempDirs.keys) {
            deleteRecursively(dir)
            tempDirs.remove(dir)
        }
    }

    /** 递归删除文件或目录。 */
    @JvmStatic
    fun deleteRecursively(file: File?) {
        if (file == null || !file.exists()) return
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        @Suppress("ResultOfMethodCallIgnored")
        file.delete()
    }
}
