package com.yuki.yukihub.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import com.yuki.yukihub.launcher.EmulatorLauncher
import com.yuki.yukihub.model.EngineType
import com.yuki.yukihub.model.Game
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Minimal manager for a game's real save directory.
 *
 * Game saves are local directories. Export additionally supports a SAF
 * destination tree so callers can use the system folder picker.
 */
class GameSaveFileManager(context: Context) {
    private val prefs: SharedPreferences
    private val context: Context

    init {
        this.context = context.applicationContext
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Resolves the real save location used by a supported built-in engine. */
    fun resolveInternalSaveLocation(game: Game): SaveLocation {
        val engine = game.engine
        if (engine == null) return unavailableSaveLocation("游戏或引擎信息不可用")
        if (!isBuiltInPackage(game)) return unavailableSaveLocation("该游戏使用外置模拟器，不纳入存档管理")

        val location = EmulatorLauncher.resolveActualSaveLocation(
            context, engine, game.rootUri, game.launchTarget, game.id
        )
        return if (location.available && location.directory != null)
            availableSaveLocation(location.directory, location.description)
        else
            unavailableSaveLocation(location.description)
    }

    /** Lists files from the automatically resolved built-in save location. */
    fun listInternalSaveFiles(game: Game): List<File> {
        val location = resolveInternalSaveLocation(game)
        if (!location.available || location.directory == null) return emptyList()
        val files = mutableListOf<File>()
        for (directory in resolveInternalSaveDirectories(game, location)) {
            if (directory.isDirectory) collectFiles(directory, files)
        }
        return files
    }

    /** Exports the automatically resolved built-in save directory. */
    @Throws(IOException::class)
    fun exportInternalSave(game: Game, destinationDirectory: File?): Int {
        val location = resolveInternalSaveLocation(game)
        if (!location.available || location.directory == null) throw IOException(location.reason)
        val source = requireExistingDirectory(location.directory, "游戏存档目录")
        rejectGamePayload(source)
        val destination = requireDirectory(destinationDirectory, "导出目录")
        rejectNestedDirectories(source, destination)
        return copyDirectoryContents(source, destination, false)
    }

    /** Exports the resolved save files into a directory selected through the system picker. */
    @Throws(IOException::class)
    fun exportInternalSaveToTree(game: Game, destinationTreeUri: Uri?): Int {
        val location = resolveInternalSaveLocation(game)
        if (!location.available || location.directory == null) throw IOException(location.reason)
        val source = requireExistingDirectory(location.directory, "游戏存档目录")
        rejectGamePayload(source)
        if (destinationTreeUri == null) throw IOException("导出目录不可用")
        val destination = DocumentFile.fromTreeUri(context, destinationTreeUri)
            ?: throw IOException("无法打开导出目录")
        if (!destination.isDirectory) throw IOException("无法打开导出目录")
        return copyDirectoryContentsToDocument(source, destination)
    }

    /** Exports all real save files as one ZIP archive selected through the system file picker. */
    @Throws(IOException::class)
    fun exportInternalSaveToZip(game: Game, destinationUri: Uri?): Int {
        val location = resolveInternalSaveLocation(game)
        if (!location.available || location.directory == null) throw IOException(location.reason)
        if (destinationUri == null) throw IOException("导出文件不可用")
        val sources = resolveInternalSaveDirectories(game, location)
        val raw = context.contentResolver.openOutputStream(destinationUri, "w")
            ?: throw IOException("无法创建导出压缩包")
        return ZipOutputStream(raw).use { zip ->
            var written = 0
            val entries = mutableSetOf<String>()
            for (source in sources) {
                if (!source.isDirectory) continue
                rejectGamePayload(source)
                written += writeZipContents(source, source, zip, entries)
            }
            if (written == 0) throw IOException("暂未发现可导出的存档文件")
            written
        }
    }

    /** Imports into the automatically resolved built-in save directory. */
    @Throws(IOException::class)
    fun importInternalSave(game: Game, sourceDirectory: File?, overwrite: Boolean): Int {
        val location = resolveInternalSaveLocation(game)
        if (!location.available || location.directory == null) throw IOException(location.reason)
        val source = requireExistingDirectory(sourceDirectory, "导入目录")
        val destination = requireDirectory(location.directory, "游戏存档目录")
        rejectNestedDirectories(source, destination)
        if (samePath(source, destination)) throw IOException("导入目录与游戏存档目录相同")
        if (overwrite) clearDirectory(destination)
        return copyDirectoryContents(source, destination, false)
    }

    /** Imports from a directory selected through the system picker. */
    @Throws(IOException::class)
    fun importInternalSaveFromTree(game: Game, sourceTreeUri: Uri?, overwrite: Boolean): Int {
        val location = resolveInternalSaveLocation(game)
        if (!location.available || location.directory == null) throw IOException(location.reason)
        if (sourceTreeUri == null) throw IOException("导入目录不可用")
        val source = DocumentFile.fromTreeUri(context, sourceTreeUri)
            ?: throw IOException("无法打开导入目录")
        if (!source.isDirectory) throw IOException("无法打开导入目录")
        val destination = requireDirectory(location.directory, "游戏存档目录")
        if (overwrite) clearDirectory(destination)
        return copyDocumentContentsToDirectory(source, destination)
    }

    /** Imports a ZIP archive selected through the system file picker. */
    @Throws(IOException::class)
    fun importInternalSaveFromZip(game: Game, sourceUri: Uri?, overwrite: Boolean): Int {
        if (sourceUri == null) throw IOException("导入压缩包不可用")
        val temporaryDirectory = createTemporaryImportDirectory()
        try {
            val extracted = extractZipToDirectory(sourceUri, temporaryDirectory)
            if (extracted == 0) throw IOException("压缩包中未找到存档文件")
            val location = resolveInternalSaveLocation(game)
            if (!location.available || location.directory == null) throw IOException(location.reason)
            val destinations = resolveInternalSaveDirectories(game, location)
            if (destinations.isEmpty()) throw IOException("无法解析实际存档目录")
            for (destination in destinations) {
                requireDirectory(destination, "游戏存档目录")
                if (overwrite) clearDirectory(destination)
            }
            var copied = 0
            for (destination in destinations) {
                copied = maxOf(
                    copied,
                    copyDirectoryContents(temporaryDirectory, requireDirectory(destination, "游戏存档目录"), false)
                )
            }
            return copied
        } finally {
            try {
                deleteRecursively(temporaryDirectory)
            } catch (ignored: Exception) {
            }
        }
    }

    data class SaveLocation internal constructor(
        @JvmField val directory: File?,
        @JvmField val reason: String,
        @JvmField val available: Boolean
    )

    // 工厂方法为 private，仅 GameSaveFileManager 内部可调用，外部无法绕过
    // resolveInternalSaveLocation 的引擎校验直接构造 SaveLocation。
    // Kotlin 中嵌套类的 private 构造器对 outer class 不可见（与 Java 不同），
    // 故构造器降为 internal，工厂方法作为 private 实例方法落在外层类，
    // 既保证工厂的私有性，又允许外层类构造 SaveLocation。
    private fun availableSaveLocation(directory: File?, description: String?): SaveLocation =
        SaveLocation(directory, description ?: "", true)

    private fun unavailableSaveLocation(reason: String?): SaveLocation =
        SaveLocation(null, reason ?: "", false)

    /** Records a writable local directory for the supplied game. */
    @Throws(IOException::class)
    fun recordSaveDirectory(game: Game, saveDirectory: File?): Boolean {
        val key = gameKey(game)
        val directory = requireDirectory(saveDirectory, "存档目录")
        return prefs.edit().putString(KEY_PREFIX + key, directory.canonicalPath).commit()
    }

    /** Returns the recorded directory, or `null` if no valid directory is recorded. */
    fun getSaveDirectory(game: Game): File? {
        val path = prefs.getString(KEY_PREFIX + gameKey(game), null) ?: return null
        if (path.trim().isEmpty()) return null
        val directory = File(path)
        return if (directory.isDirectory) directory else null
    }

    /** Removes only the path record; it never deletes the actual save files. */
    fun forgetSaveDirectory(game: Game): Boolean {
        return prefs.edit().remove(KEY_PREFIX + gameKey(game)).commit()
    }

    /** Lists every regular save file below the recorded directory. */
    fun listSaveFiles(game: Game): List<File> {
        val directory = getSaveDirectory(game) ?: return emptyList()
        val files = mutableListOf<File>()
        collectFiles(directory, files)
        return files
    }

    /**
     * Exports the recorded save directory's contents into `destinationDirectory`.
     * Existing destination files are not replaced.
     */
    @Throws(IOException::class)
    fun exportSave(game: Game, destinationDirectory: File?): Int {
        val source = requireRecordedDirectory(game)
        val destination = requireDirectory(destinationDirectory, "导出目录")
        rejectNestedDirectories(source, destination)
        return copyDirectoryContents(source, destination, false)
    }

    /**
     * Imports a directory into the recorded save directory.
     *
     * @param overwrite when true, clears the recorded directory first; when false,
     *                  importing a file that already exists fails.
     */
    @Throws(IOException::class)
    fun importSave(game: Game, sourceDirectory: File?, overwrite: Boolean): Int {
        val source = requireExistingDirectory(sourceDirectory, "导入目录")
        val destination = requireRecordedDirectory(game)
        rejectNestedDirectories(source, destination)
        if (samePath(source, destination)) throw IOException("导入目录与存档目录相同")
        if (overwrite) clearDirectory(destination)
        return copyDirectoryContents(source, destination, false)
    }

    /** Equivalent to [importSave]`game, source, true`. */
    @Throws(IOException::class)
    fun overwriteSave(game: Game, sourceDirectory: File?): Int {
        return importSave(game, sourceDirectory, true)
    }

    @Throws(IOException::class)
    private fun requireRecordedDirectory(game: Game): File {
        return getSaveDirectory(game) ?: throw IOException("未记录有效的游戏存档目录")
    }

    private fun resolveInternalSaveDirectories(game: Game, primary: SaveLocation?): List<File> {
        val directories = EmulatorLauncher.resolveActualSaveDirectories(
            context, game.engine, game.rootUri, game.launchTarget, game.id
        )
        if (directories == null || directories.isEmpty()) {
            return if (primary == null || primary.directory == null)
                emptyList() else listOf(primary.directory)
        }
        return directories
    }

    @Throws(IOException::class)
    private fun writeZipContents(
        root: File, directory: File, zip: ZipOutputStream, entries: MutableSet<String>
    ): Int {
        val children = directory.listFiles() ?: return 0
        var written = 0
        for (child in children) {
            if (child.isDirectory) {
                written += writeZipContents(root, child, zip, entries)
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
                        val buffer = ByteArray(BUFFER_SIZE)
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

    @Throws(IOException::class)
    private fun createTemporaryImportDirectory(): File {
        val cache = context.cacheDir ?: throw IOException("应用缓存目录不可用")
        val directory = File.createTempFile("save_zip_", "", cache)
        if (!directory.delete() || !directory.mkdirs()) {
            throw IOException("无法创建临时解压目录")
        }
        return directory.canonicalFile
    }

    @Throws(IOException::class)
    private fun extractZipToDirectory(sourceUri: Uri, destination: File): Int {
        val rootPath = destination.canonicalPath
        val entries = mutableSetOf<String>()
        var extracted = 0
        var totalBytes = 0L
        val raw = context.contentResolver.openInputStream(sourceUri)
            ?: throw IOException("无法读取导入压缩包")
        ZipInputStream(raw).use { zip ->
            val buffer = ByteArray(BUFFER_SIZE)
            var entry = zip.nextEntry
            while (entry != null) {
                val name = safeZipEntryName(entry.name)
                rejectGamePayloadEntry(name)
                if (!entries.add(name)) throw IOException("压缩包包含重复文件：$name")
                if (entries.size > MAX_SAVE_ZIP_FILES) {
                    throw IOException("压缩包文件数量过多，不是有效的存档备份")
                }
                if (entry.size > MAX_SAVE_ZIP_BYTES) {
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
                            if (totalBytes > MAX_SAVE_ZIP_BYTES) {
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
    private fun copyDirectoryContentsToDocument(source: File, destination: DocumentFile): Int {
        val children = source.listFiles() ?: return 0
        var copied = 0
        for (child in children) {
            var target = destination.findFile(child.name)
            if (child.isDirectory) {
                if (target != null && !target.isDirectory) throw IOException("导出目录已存在同名文件：" + child.name)
                if (target == null) target = destination.createDirectory(child.name)
                if (target == null) throw IOException("无法创建导出目录：" + child.name)
                copied += copyDirectoryContentsToDocument(child, target)
            } else if (child.isFile) {
                if (target != null) throw IOException("导出目录已存在同名文件：" + child.name)
                target = destination.createFile("application/octet-stream", child.name)
                if (target == null) throw IOException("无法创建导出文件：" + child.name)
                copyFileToDocument(child, target)
                copied++
            }
        }
        return copied
    }

    @Throws(IOException::class)
    private fun copyFileToDocument(source: File, target: DocumentFile) {
        val buffer = ByteArray(BUFFER_SIZE)
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
    private fun copyDocumentContentsToDirectory(source: DocumentFile, destination: File): Int {
        val children = source.listFiles() ?: return 0
        var copied = 0
        for (child in children) {
            val name = child.name ?: continue
            if (name.trim().isEmpty()) continue
            val target = File(destination, name)
            if (child.isDirectory) {
                if (target.exists() && !target.isDirectory) throw IOException("游戏存档目录已存在同名文件：$name")
                if (!target.exists() && !target.mkdirs()) throw IOException("无法创建存档目录：$name")
                copied += copyDocumentContentsToDirectory(child, target)
            } else if (child.isFile) {
                if (target.exists()) throw IOException("游戏存档目录已存在同名文件：$name")
                copyDocumentToFile(child, target)
                copied++
            }
        }
        return copied
    }

    @Throws(IOException::class)
    private fun copyDocumentToFile(source: DocumentFile, target: File) {
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw IOException("无法创建存档目录：$parent")
        val buffer = ByteArray(BUFFER_SIZE)
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

    companion object {
        private const val PREFS_NAME = "yukihub_game_save_paths"
        private const val KEY_PREFIX = "save_path."
        private const val BUFFER_SIZE = 64 * 1024

        // 纯函数（不依赖实例字段），Java 原版为 private static，移至 companion object 保留语义。
        private fun gameKey(game: Game): String {
            val rootUri = GameRepository.normalizeRootUriKey(game.rootUri)
            if (rootUri.isNotEmpty()) {
                return "root." + Base64.encodeToString(
                    rootUri.toByteArray(StandardCharsets.UTF_8),
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                )
            }
            if (game.id > 0) return "id." + game.id
            throw IllegalArgumentException("game must have rootUri or id")
        }
        // A save backup should never contain an engine payload. This is also a
        // practical upper bound for screenshot-heavy saves while rejecting a
        // mistakenly selected multi-gigabyte game archive.
        private const val MAX_SAVE_ZIP_BYTES = 512L * 1024L * 1024L
        private const val MAX_SAVE_ZIP_FILES = 4_000

        private fun isBuiltInPackage(game: Game): Boolean {
            val pkg = game.emulatorPackage?.trim()?.lowercase(Locale.ROOT) ?: ""
            if (pkg.isEmpty()) return game.engine == EngineType.KIRIKIRI || game.engine == EngineType.ARTEMIS
                || game.engine == EngineType.ONS || game.engine == EngineType.TYRANO
            return when (game.engine) {
                EngineType.KIRIKIRI -> pkg.startsWith("internal.krkr") || "org.tvp.kirikiri2.internal" == pkg
                EngineType.ARTEMIS -> pkg.startsWith("internal.artemis")
                EngineType.ONS -> pkg.startsWith("internal.ons") || "com.yuki.yukihub.ons" == pkg
                EngineType.TYRANO -> pkg.startsWith("internal.tyrano") || "com.yuki.yukihub.tyrano" == pkg
                else -> false
            }
        }

        @Throws(IOException::class)
        private fun requireDirectory(directory: File?, label: String): File {
            if (directory == null) throw IOException("$label 不能为空")
            if (!directory.exists() && !directory.mkdirs()) throw IOException("无法创建 $label：$directory")
            if (!directory.isDirectory) throw IOException("$label 不是目录：$directory")
            return directory.canonicalFile
        }

        @Throws(IOException::class)
        private fun requireExistingDirectory(directory: File?, label: String): File {
            if (directory == null || !directory.isDirectory) {
                throw IOException("$label 不存在或不是目录：$directory")
            }
            return directory.canonicalFile
        }

        private fun collectFiles(directory: File, output: MutableList<File>) {
            val children = directory.listFiles() ?: return
            for (child in children) {
                if (child.isDirectory) collectFiles(child, output)
                else if (child.isFile) output.add(child)
            }
        }

        @Throws(IOException::class)
        private fun safeZipEntryName(name: String?): String {
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
        private fun rejectGamePayloadEntry(name: String?) {
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
        private fun rejectGamePayload(directory: File) {
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
        private fun copyDirectoryContents(source: File, destination: File, replaceExisting: Boolean): Int {
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
        private fun copyFile(source: File, target: File) {
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
        private fun clearDirectory(directory: File) {
            val children = directory.listFiles() ?: return
            for (child in children) deleteRecursively(child)
        }

        @Throws(IOException::class)
        private fun deleteRecursively(file: File) {
            if (file.isDirectory) {
                val children = file.listFiles()
                if (children != null) for (child in children) deleteRecursively(child)
            }
            if (!file.delete()) throw IOException("无法删除：$file")
        }

        @Throws(IOException::class)
        private fun rejectNestedDirectories(source: File, destination: File) {
            val sourcePath = source.canonicalPath
            val destinationPath = destination.canonicalPath
            if (destinationPath.startsWith(sourcePath + File.separator)
                || sourcePath.startsWith(destinationPath + File.separator)
            ) {
                throw IOException("源目录与目标目录不能互为父子目录")
            }
        }

        @Throws(IOException::class)
        private fun samePath(first: File, second: File): Boolean {
            return first.canonicalPath == second.canonicalPath
        }
    }
}
