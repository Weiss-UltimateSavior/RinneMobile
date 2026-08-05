package com.core.data

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

/**
 * 存档复制/校验的纯函数工具（重构计划 3.5 阶段 92 拆分自 GameSaveFileManager）。
 * 全部为无实例状态的静态函数，供存档管理器与 ZIP/SAF 传输类共用，行为与原
 * GameSaveFileManager companion 私有函数逐字等价。
 */
internal object SaveFileUtils {

    const val BUFFER_SIZE = 64 * 1024

    // A save backup should never contain an engine payload. This is also a
    // practical upper bound for screenshot-heavy saves while rejecting a
    // mistakenly selected multi-gigabyte game archive.
    const val MAX_SAVE_ZIP_BYTES = 512L * 1024L * 1024L
    const val MAX_SAVE_ZIP_FILES = 4_000

    @Throws(IOException::class)
    fun requireDirectory(directory: File?, label: String): File {
        if (directory == null) throw IOException("$label 不能为空")
        if (!directory.exists() && !directory.mkdirs()) throw IOException("无法创建 $label：$directory")
        if (!directory.isDirectory) throw IOException("$label 不是目录：$directory")
        return directory.canonicalFile
    }

    @Throws(IOException::class)
    fun requireExistingDirectory(directory: File?, label: String): File {
        if (directory == null || !directory.isDirectory) {
            throw IOException("$label 不存在或不是目录：$directory")
        }
        return directory.canonicalFile
    }

    fun collectFiles(directory: File, output: MutableList<File>) {
        val children = directory.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) collectFiles(child, output)
            else if (child.isFile) output.add(child)
        }
    }

    @Throws(IOException::class)
    fun safeZipEntryName(name: String?): String {
        // 保留 Java 行为：null 入参抛 IOException（非 NPE），保持调用方 catch 类型一致。
        if (name == null) throw IOException("压缩包包含无效文件名")
        var normalized = name.replace('\\', '/')
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length - 1)
        if (normalized.isEmpty() || normalized.startsWith("/") || normalized.contains("//")) {
            throw IOException("压缩包包含非法路径：$name")
        }
        for (part in normalized.split("/")) {
            if (part.isEmpty() || "." == part || ".." == part) {
                throw IOException("压缩包包含非法路径：$name")
            }
        }
        return normalized
    }

    /** Reject engine archives and native plug-ins; these belong to a game root, never a save backup. */
    @Throws(IOException::class)
    fun rejectGamePayloadEntry(name: String?) {
        // 保留 Java 行为：null 入参当作空串处理（命中 isEmpty 不会触发 payload 规则）。
        val safeName = name ?: ""
        val normalized = safeName.replace('\\', '/').lowercase(Locale.ROOT)
        val leaf = normalized.substring(normalized.lastIndexOf('/') + 1)
        if (normalized.startsWith("plugin/") || normalized.contains("/plugin/")
            || leaf.endsWith(".xp3") || leaf.endsWith(".pfs")
            || leaf.endsWith(".dll") || leaf.endsWith(".exe")
            || leaf.endsWith(".so") || leaf.endsWith(".apk") || leaf.endsWith(".obb")
        ) {
            throw IOException("压缩包包含游戏资源，不能作为存档导入：$safeName")
        }
    }

    @Throws(IOException::class)
    fun rejectGamePayload(directory: File) {
        val files = mutableListOf<File>()
        collectFiles(directory, files)
        var totalBytes = 0L
        for (file in files) {
            val relative = directory.toPath().relativize(file.toPath()).toString()
                .replace(File.separatorChar, '/')
            rejectGamePayloadEntry(relative)
            totalBytes += maxOf(0L, file.length())
            if (totalBytes > MAX_SAVE_ZIP_BYTES) {
                throw IOException("真实存档目录异常过大，疑似混入游戏资源；请先清理后再导出")
            }
        }
    }

    @Throws(IOException::class)
    fun copyDirectoryContents(source: File, destination: File, replaceExisting: Boolean): Int {
        val children = source.listFiles() ?: return 0
        var copied = 0
        for (child in children) {
            val target = File(destination, child.name)
            if (child.isDirectory) {
                if (target.exists() && !target.isDirectory) {
                    if (!replaceExisting) throw IOException("目标文件已存在：$target")
                    deleteRecursively(target)
                }
                if (!target.exists() && !target.mkdirs()) throw IOException("无法创建目录：$target")
                copied += copyDirectoryContents(child, target, replaceExisting)
            } else if (child.isFile) {
                if (target.exists() && !replaceExisting) throw IOException("目标文件已存在：$target")
                copyFile(child, target)
                copied++
            }
        }
        return copied
    }

    @Throws(IOException::class)
    fun copyFile(source: File, target: File) {
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("无法创建目录：$parent")
        }
        val buffer = ByteArray(BUFFER_SIZE)
        FileInputStream(source).use { input ->
            FileOutputStream(target, false).use { out ->
                var read = input.read(buffer)
                while (read != -1) {
                    out.write(buffer, 0, read)
                    read = input.read(buffer)
                }
            }
        }
        target.setLastModified(source.lastModified())
    }

    @Throws(IOException::class)
    fun clearDirectory(directory: File) {
        val children = directory.listFiles() ?: return
        for (child in children) deleteRecursively(child)
    }

    @Throws(IOException::class)
    fun deleteRecursively(file: File) {
        if (file.isDirectory) {
            val children = file.listFiles()
            if (children != null) for (child in children) deleteRecursively(child)
        }
        if (!file.delete()) throw IOException("无法删除：$file")
    }

    @Throws(IOException::class)
    fun rejectNestedDirectories(source: File, destination: File) {
        val sourcePath = source.canonicalPath
        val destinationPath = destination.canonicalPath
        if (destinationPath.startsWith(sourcePath + File.separator)
            || sourcePath.startsWith(destinationPath + File.separator)
        ) {
            throw IOException("源目录与目标目录不能互为父子目录")
        }
    }

    @Throws(IOException::class)
    fun samePath(first: File, second: File): Boolean {
        return first.canonicalPath == second.canonicalPath
    }
}
