package com.core.launcher

import android.util.Log
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale
import kotlin.text.Charsets.UTF_8

/**
 * Artemis「基础补丁」解包器（参考 tyranor 启动器 f5.1/F.J() 语义，见 com_apps_refactor_plan.md 注意事项）。
 *
 * 部分 Artemis 游戏数据整包在 `.pfs` 封包中且目录缺少 `system.ini`，引擎无法直接启动；
 * 启动前需从封包解出引擎必需文件（system.ini / list_windows→list_android / 媒体资源），
 * 其余数据由引擎流式读取封包。
 *
 * 安全约束（对照 ImporterIO 防 ZIP 攻击模式）：
 * - 单条目数据上限 [MAX_ENTRY_BYTES]，累计上限 [MAX_TOTAL_BYTES]，条目数上限 [MAX_ENTRY_COUNT]
 * - 解包目标路径必须位于游戏目录内（canonical 校验，防 Zip Slip）
 * - 大小校验先于读取，防止压缩放大/越界读
 */
internal object ArtemisPfsUnpacker {
    private const val TAG = "ArtemisPfsUnpacker"

    private const val MAX_ENTRY_BYTES = 50L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 200L * 1024 * 1024
    private const val MAX_ENTRY_COUNT = 10_000
    private const val MAX_NAME_BYTES = 4096
    private const val MIN_ENCRYPTED_LEN = 8

    /** 目录是否需要基础补丁：存在 .pfs 封包且缺少 system.ini。 */
    @JvmStatic
    fun needsBasePatch(rootPath: String?): Boolean {
        if (rootPath.isNullOrBlank() || rootPath.startsWith("content://")) return false
        val dir = File(rootPath)
        if (!dir.isDirectory) return false
        if (File(dir, "system.ini").exists()) return false
        return listPfsFiles(dir).isNotEmpty()
    }

    /**
     * 应用基础补丁：解包目录内全部 .pfs 封包并补齐 system.ini。
     * @return true 表示成功完成；任何解析失败返回 false 但不抛异常（不阻塞后续启动）。
     */
    @JvmStatic
    fun applyBasePatch(rootPath: String?): Boolean {
        if (!needsBasePatch(rootPath)) return true
        val dir = File(rootPath)
        var totalBytes = 0L
        try {
            for (pfs in listPfsFiles(dir)) {
                totalBytes += unpackPfs(dir, pfs)
                if (totalBytes > MAX_TOTAL_BYTES) {
                    logWarn("base patch total bytes exceed limit, abort")
                    return false
                }
            }
            ensureSystemIni(dir)
            return true
        } catch (error: Exception) {
            logWarn("apply base patch failed root=$rootPath", error)
            return false
        }
    }

    private fun listPfsFiles(dir: File): List<File> {
        // listFiles() 可能抛 SecurityException（目录无权限），视为无 .pfs，安全忽略。
        val files = runCatching { dir.listFiles()?.filter { it.isFile && isPfsName(it.name) } }
            .getOrNull() ?: return emptyList()
        // 分卷（root.pfs.001）与主文件去重：优先主文件，且按名称排序保证确定性。
        return files.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    private fun isPfsName(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".pfs") || Regex("^[^.]+\\.pfs\\.\\d{3}$").matches(lower)
    }

    /** 解包单个 .pfs，返回本次写入字节数。 */
    private fun unpackPfs(gameDir: File, pfs: File): Long {
        var written = 0L
        var entries = 0
        RandomAccessFile(pfs, "r").use { raf ->
            if (raf.read() != 0x70 || raf.read() != 0x66) {
                // magic 不是 "pf"，跳过该文件（不视为失败）。
                return written
            }
            raf.read() // 版本字符（丢弃）
            val tableLen = readM(raf)
            if (tableLen <= 0 || tableLen > MAX_ENTRY_BYTES) {
                logWarn("invalid pfs table length $tableLen")
                return written
            }
            val tableStart = raf.filePointer
            val table = ByteArray(tableLen)
            raf.readFully(table)
            val key = MessageDigest.getInstance("SHA-1").digest(table)
            raf.seek(tableStart)
            val entryCount = readM(raf)
            if (entryCount < 0 || entryCount > MAX_ENTRY_COUNT) {
                logWarn("invalid pfs entry count $entryCount")
                return written
            }
            val fileLength = raf.length()
            for (i in 0 until entryCount) {
                val nameLen = readM(raf)
                if (nameLen < 0 || nameLen > MAX_NAME_BYTES) {
                    logWarn("invalid pfs entry name length $nameLen")
                    return written
                }
                val nameBytes = ByteArray(nameLen)
                raf.readFully(nameBytes)
                val rawName = String(nameBytes, UTF_8)
                readM(raf) // 未知字段（对齐 tyranor 流位置）
                val offset = readM(raf).toLong()
                val dataLen = readM(raf).toLong()
                if (offset < 0 || dataLen < 0 || offset + dataLen > fileLength || dataLen > MAX_ENTRY_BYTES) {
                    logWarn("invalid pfs entry bounds name=$rawName offset=$offset len=$dataLen")
                    return written
                }
                val relPath = rawName.replace('\\', '/')
                if (!shouldExtract(relPath)) continue
                val target = safeTarget(gameDir, relPath) ?: continue
                val data = readEntryData(raf, offset, dataLen.toInt(), key)
                if (data == null) return written
                writeEntry(target, data)
                written += data.size
                entries++
                postProcessEntry(target, relPath)
            }
        }
        logInfo("unpacked ${pfs.name}: entries=$entries bytes=$written")
        return written
    }

    /** 按 tyranor 语义决定是否解出该条目：system.ini/list_windows 必解，其余仅解 movie 媒体。 */
    private fun shouldExtract(relPath: String): Boolean {
        val lower = relPath.lowercase(Locale.ROOT)
        if (lower.contains("system.ini") || lower.contains("list_windows")) return true
        if (!lower.contains("movie")) return false
        return lower.endsWith(".dat") || lower.endsWith(".mp4") || lower.endsWith(".ogv") ||
            lower.endsWith(".wmv") || lower.endsWith(".mpg") || lower.endsWith(".webm")
    }

    /** 读取并解密条目数据：数据位于 offset，读取后须恢复文件指针以继续解析条目表。 */
    private fun readEntryData(raf: RandomAccessFile, offset: Long, dataLen: Int, key: ByteArray): ByteArray? {
        val saved = raf.filePointer
        try {
            raf.seek(offset)
            val data = ByteArray(dataLen)
            raf.readFully(data)
            // 数据长度 >= 8 时按 tyranor 语义做 XOR 解密（密钥 = SHA-1(条目表)）。
            if (dataLen >= MIN_ENCRYPTED_LEN) {
                for (j in data.indices) {
                    data[j] = (data[j].toInt() xor key[j % key.size].toInt()).toByte()
                }
            }
            return data
        } finally {
            raf.seek(saved)
        }
    }

    /** 写入解包文件，确保父目录存在（不跟随符号链接）。 */
    private fun writeEntry(target: File, data: ByteArray) {
        val parent = target.parentFile ?: return
        if (!parent.exists() && !parent.mkdirs()) {
            logWarn("cannot create directory ${parent.path}")
            return
        }
        target.writeBytes(data)
    }

    /** 目标路径必须位于游戏目录内（canonical 校验，防路径穿越）。 */
    private fun safeTarget(gameDir: File, relPath: String): File? {
        if (relPath.isBlank()) return null
        val root = runCatching { gameDir.canonicalFile }.getOrElse { gameDir.absoluteFile }
        val target = File(root, relPath)
        val canonical = runCatching { target.canonicalFile }.getOrElse { return null }
        if (canonical != root && !canonical.path.startsWith(root.path + File.separator)) {
            logWarn("blocked path traversal entry=$relPath")
            return null
        }
        return canonical
    }

    /**
     * 条目后处理：
     * - system.ini：解析保留 WIDTH/HEIGHT/CHARSET，补齐 [ANDROID] 段（参考 tyranor 生成内容）
     * - list_windows：改名为 list_android（Android 引擎文件列表表）
     */
    private fun postProcessEntry(target: File, relPath: String) {
        val lower = relPath.lowercase(Locale.ROOT)
        when {
            lower.contains("system.ini") -> patchSystemIni(target)
            lower.contains("list_windows") -> renameListWindows(target)
        }
    }

    /** 追加/补齐 system.ini 的 [ANDROID] 段（保留原有 WIDTH/HEIGHT/CHARSET 行）。 */
    private fun patchSystemIni(file: File) {
        try {
            val lines = file.readLines(UTF_8).toMutableList()
            if (lines.any { it.trim() == "[ANDROID]" }) return
            lines += "\n[ANDROID]"
            lines += "SIDECUT = 0"
            lines += "BOOT = system/first.iet"
            lines += "FONT_CACHE_SIZE = 8388608"
            file.writeText(lines.joinToString("\n"), UTF_8)
        } catch (error: Exception) {
            // 单个 system.ini 补丁失败不阻塞整个解包，仅记录日志。
            logWarn("patch system.ini failed ${file.path}", error)
        }
    }

    private fun renameListWindows(file: File) {
        try {
            val target = File(file.parentFile, file.name.replace("list_windows", "list_android"))
            if (!target.exists() && file.exists() && file.renameTo(target)) {
                logInfo("renamed ${file.name} -> ${target.name}")
                // list_windows.tbl 改名 list_android.tbl 后需做 config_tablet 翻转（对齐 tyranor f5.1/F.J()）。
                if (target.name.equals("list_android.tbl", ignoreCase = true)) {
                    flipListAndroidTblConfig(target)
                }
            }
        } catch (error: Exception) {
            // 单个 list_windows 改名失败不影响整体解包，仅记录日志。
            logWarn("rename list_windows failed ${file.path}", error)
        }
    }

    /**
     * 对齐 tyranor f5.1/F.J()：list_windows.tbl 改名 list_android.tbl 后，把
     * `config_tablet=0` / `config_tabletui=0` 翻转为 `=1`（强制平板模式配置），其余行原样保留。
     * 幂等：逐行纯函数重写结果稳定；失败不阻塞。
     */
    private fun flipListAndroidTblConfig(file: File) {
        try {
            val out = buildString {
                file.readLines(UTF_8).forEach { raw ->
                    val key = raw.trim().lowercase(Locale.ROOT)
                    when {
                        key.contains("config_tablet=0") -> append("config_tablet=1,\n")
                        key.contains("config_tabletui=0") -> append("config_tabletui=1,\n")
                        else -> {
                            append(raw)
                            append("\n")
                        }
                    }
                }
            }
            file.writeText(out, UTF_8)
        } catch (error: Exception) {
            // 单个 list_android.tbl 翻转失败不影响整体解包，仅记录日志。
            logWarn("flip list_android.tbl config failed ${file.path}", error)
        }
    }

    /** 解包后游戏目录仍缺 system.ini 时生成最小配置，防止引擎无法启动。 */
    private fun ensureSystemIni(dir: File) {
        val file = File(dir, "system.ini")
        if (file.exists()) return
        try {
            file.writeText(
                buildString {
                    appendLine("[SYSTEM]")
                    appendLine("WIDTH = 1280")
                    appendLine("HEIGHT = 720")
                    appendLine("CHARSET = UTF-8")
                    appendLine()
                    appendLine("[ANDROID]")
                    appendLine("SIDECUT = 0")
                    appendLine("BOOT = system/first.iet")
                    appendLine("FONT_CACHE_SIZE = 8388608")
                },
                UTF_8,
            )
            logInfo("generated fallback system.ini at ${file.path}")
        } catch (error: Exception) {
            // 生成兜底 system.ini 失败不阻塞整体解包，仅记录日志（引擎可能仍由封包内 system.ini 启动）。
            logWarn("generate system.ini failed ${file.path}", error)
        }
    }

    /** 读取 4 字节小端序 int（与 tyranor f5.1/F.M() 一致：r1<<0 | r2<<8 | r3<<16 | r4<<24，且 & 0x7fffffff）。 */
    private fun readM(raf: RandomAccessFile): Int {
        val b0 = raf.read()
        val b1 = raf.read()
        val b2 = raf.read()
        val b3 = raf.read()
        if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) throw EOFException("pfs truncated")
        return ((b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0) and 0x7fffffff
    }

    private fun logInfo(message: String) {
        // runCatching：单元测试环境无 Android Log 实现，日志失败不影响解包结果。
        runCatching { Log.i(TAG, message) }
    }

    private fun logWarn(message: String, error: Throwable? = null) {
        runCatching { if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error) }
    }
}
