package com.core.data

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * SAF（DocumentFile）存档复制（重构计划 3.5 阶段 92 拆分自 GameSaveFileManager）。
 * 负责本地目录树与系统目录选择器（SAF 树）之间的递归复制，
 * 行为与原 GameSaveFileManager 私有实现逐字等价。
 */
internal class SaveDocumentTransfer(private val context: Context) {

    /** 将本地目录内容递归复制到 SAF 目标树；同名文件已存在时抛 IOException。 */
    @Throws(IOException::class)
    fun copyDirectoryContentsToDocument(
        source: File,
        destination: DocumentFile,
        exclude: (String) -> Boolean = { false },
    ): Int {
        val children = source.listFiles() ?: return 0
        var copied = 0
        for (child in children) {
            val name = child.name ?: continue
            if (name.isEmpty() || exclude(name)) continue
            var target = destination.findFile(name)
            if (child.isDirectory) {
                if (target != null && !target.isDirectory) throw IOException("导出目录已存在同名文件：" + name)
                if (target == null) target = destination.createDirectory(name)
                if (target == null) throw IOException("无法创建导出目录：" + name)
                copied += copyDirectoryContentsToDocument(child, target, exclude)
            } else if (child.isFile) {
                if (target != null) throw IOException("导出目录已存在同名文件：" + name)
                target = destination.createFile("application/octet-stream", name)
                if (target == null) throw IOException("无法创建导出文件：" + name)
                copyFileToDocument(child, target)
                copied++
            }
        }
        return copied
    }

    /** 将 SAF 源树内容递归复制到本地目录；同名文件已存在时抛 IOException。 */
    @Throws(IOException::class)
    fun copyDocumentContentsToDirectory(
        source: DocumentFile,
        destination: File,
        exclude: (String) -> Boolean = { false },
    ): Int {
        val children = source.listFiles() ?: return 0
        var copied = 0
        for (child in children) {
            val name = child.name ?: continue
            if (name.trim().isEmpty() || exclude(name)) continue
            val target = File(destination, name)
            if (child.isDirectory) {
                if (target.exists() && !target.isDirectory) throw IOException("游戏存档目录已存在同名文件：$name")
                if (!target.exists() && !target.mkdirs()) throw IOException("无法创建存档目录：$name")
                copied += copyDocumentContentsToDirectory(child, target, exclude)
            } else if (child.isFile) {
                if (target.exists()) throw IOException("游戏存档目录已存在同名文件：$name")
                copyDocumentToFile(child, target)
                copied++
            }
        }
        return copied
    }

    @Throws(IOException::class)
    private fun copyFileToDocument(source: File, target: DocumentFile) {
        val buffer = ByteArray(SaveFileUtils.BUFFER_SIZE)
        FileInputStream(source).use { input ->
            val out = context.contentResolver.openOutputStream(target.uri, "w")
                ?: throw IOException("无法写入导出文件：" + source.name)
            out.use {
                var read = input.read(buffer)
                while (read != -1) {
                    it.write(buffer, 0, read)
                    read = input.read(buffer)
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun copyDocumentToFile(source: DocumentFile, target: File) {
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw IOException("无法创建存档目录：$parent")
        val buffer = ByteArray(SaveFileUtils.BUFFER_SIZE)
        val input = context.contentResolver.openInputStream(source.uri)
            ?: throw IOException("无法读取导入文件：" + source.name)
        input.use { inStream ->
            FileOutputStream(target, false).use { out ->
                var read = inStream.read(buffer)
                while (read != -1) {
                    out.write(buffer, 0, read)
                    read = inStream.read(buffer)
                }
            }
        }
    }
}
