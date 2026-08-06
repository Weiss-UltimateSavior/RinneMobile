package com.core.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * ZIP 存档传输（重构计划 3.5 阶段 92 拆分自 GameSaveFileManager）。
 * 负责将多个存档目录打包导出到 URI，以及将外部 ZIP 解压到临时目录。
 * 临时目录由调用方负责在 finally 中清理；行为与原 GameSaveFileManager
 * 私有实现逐字等价。
 */
internal class SaveZipTransfer(private val context: Context) {

    /**
     * 将多个源目录打包写入目标 URI；无文件时抛 IOException（语义与原实现一致）。
     * @param exclude 非空时按条目名过滤（Artemis 游戏目录排除 root.pfs/system/movie 等资源），
     *                且跳过整体 payload 校验（游戏目录本身含资源，整体校验会误拒）。
     */
    @Throws(IOException::class)
    fun exportToZip(
        sources: List<File>,
        destinationUri: Uri,
        exclude: ((String) -> Boolean)? = null,
    ): Int {
        val raw = context.contentResolver.openOutputStream(destinationUri, "w")
            ?: throw IOException("无法创建导出压缩包")
        return ZipOutputStream(raw).use { zip ->
            var written = 0
            val entries = mutableSetOf<String>()
            for (source in sources) {
                if (!source.isDirectory) continue
                if (exclude == null) SaveFileUtils.rejectGamePayload(source)
                written += writeZipContents(source, source, zip, entries, exclude)
            }
            if (written == 0) throw IOException("暂未发现可导出的存档文件")
            written
        }
    }

    /** 创建解压用的临时目录，调用方须在 finally 中清理。 */
    @Throws(IOException::class)
    fun createTemporaryImportDirectory(): File {
        val cache = context.cacheDir ?: throw IOException("应用缓存目录不可用")
        val directory = File.createTempFile("save_zip_", "", cache)
        if (!directory.delete() || !directory.mkdirs()) {
            throw IOException("无法创建临时解压目录")
        }
        return directory.canonicalFile
    }

    /** 将 ZIP 解压到目标目录（含路径穿越/重复项/payload/大小校验），返回解压的文件数。 */
    @Throws(IOException::class)
    fun extractZipToDirectory(sourceUri: Uri, destination: File): Int {
        val rootPath = destination.canonicalPath
        val entries = mutableSetOf<String>()
        var extracted = 0
        var totalBytes = 0L
        val raw = context.contentResolver.openInputStream(sourceUri)
            ?: throw IOException("无法读取导入压缩包")
        ZipInputStream(raw).use { zip ->
            val buffer = ByteArray(SaveFileUtils.BUFFER_SIZE)
            var entry = zip.nextEntry
            while (entry != null) {
                val name = SaveFileUtils.safeZipEntryName(entry.name)
                SaveFileUtils.rejectGamePayloadEntry(name)
                if (!entries.add(name)) throw IOException("压缩包包含重复文件：$name")
                if (entries.size > SaveFileUtils.MAX_SAVE_ZIP_FILES) {
                    throw IOException("压缩包文件数量过多，不是有效的存档备份")
                }
                if (entry.size > SaveFileUtils.MAX_SAVE_ZIP_BYTES) {
                    throw IOException("压缩包包含过大的文件，不是有效的存档备份：$name")
                }
                val output = File(destination, name).canonicalFile
                if (!output.path.startsWith(rootPath + File.separator)) {
                    throw IOException("压缩包包含非法路径：" + entry.name)
                }
                if (entry.isDirectory) {
                    if (!output.exists() && !output.mkdirs()) throw IOException("无法创建存档目录：$name")
                } else {
                    val parent = output.parentFile
                    if (parent == null || (!parent.exists() && !parent.mkdirs())) {
                        throw IOException("无法创建存档目录：$name")
                    }
                    FileOutputStream(output, false).use { out ->
                        var read = zip.read(buffer)
                        while (read != -1) {
                            totalBytes += read.toLong()
                            if (totalBytes > SaveFileUtils.MAX_SAVE_ZIP_BYTES) {
                                throw IOException("压缩包解压后过大，不是有效的存档备份")
                            }
                            out.write(buffer, 0, read)
                            read = zip.read(buffer)
                        }
                    }
                    if (entry.time > 0L) output.setLastModified(entry.time)
                    extracted++
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return extracted
    }

    @Throws(IOException::class)
    private fun writeZipContents(
        root: File,
        directory: File,
        zip: ZipOutputStream,
        entries: MutableSet<String>,
        exclude: ((String) -> Boolean)?,
    ): Int {
        val children = directory.listFiles() ?: return 0
        var written = 0
        for (child in children) {
            val name = child.name ?: continue
            if (name.isEmpty() || exclude?.invoke(name) == true) continue
            if (child.isDirectory) {
                written += writeZipContents(root, child, zip, entries, exclude)
            } else if (child.isFile) {
                val relative = root.toPath().relativize(child.toPath()).toString()
                    .replace(File.separatorChar, '/')
                // App-private callback saves take priority if both KRKR paths
                // contain a file with the same relative name.
                if (!entries.add(relative)) continue
                val entry = ZipEntry(relative)
                entry.time = child.lastModified()
                zip.putNextEntry(entry)
                try {
                    FileInputStream(child).use { input ->
                        val buffer = ByteArray(SaveFileUtils.BUFFER_SIZE)
                        var read = input.read(buffer)
                        while (read != -1) {
                            zip.write(buffer, 0, read)
                            read = input.read(buffer)
                        }
                    }
                } finally {
                    zip.closeEntry()
                }
                written++
            }
        }
        return written
    }
}
