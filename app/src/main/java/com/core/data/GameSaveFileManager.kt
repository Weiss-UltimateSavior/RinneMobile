package com.core.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import com.core.launcher.ArtemisLauncher
import com.core.launcher.EmulatorLauncher
import com.core.launcher.EnginePackages
import com.core.launcher.ScriptEngineLaunchers
import com.core.model.EngineType
import com.core.model.Game
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Minimal manager for a game's real save directory.
 *
 * Game saves are local directories. Export additionally supports a SAF
 * destination tree so callers can use the system folder picker.
 *
 * 职责切分（重构计划 3.5 阶段 92，§8:323 按职责切片）：
 *   - 纯文件/目录校验与复制原语 → [SaveFileUtils]
 *   - ZIP 打包/解压 → [SaveZipTransfer]
 *   - SAF DocumentFile 复制 → [SaveDocumentTransfer]
 * 本类保留存档位置解析、路径记录与公开 API 编排，调用方零变更。
 */
class GameSaveFileManager(context: Context) {
    private val prefs: SharedPreferences
    private val context: Context
    private val zipTransfer: SaveZipTransfer
    private val documentTransfer: SaveDocumentTransfer

    init {
        this.context = context.applicationContext
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        this.zipTransfer = SaveZipTransfer(this.context)
        this.documentTransfer = SaveDocumentTransfer(this.context)
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
            if (directory.isDirectory) SaveFileUtils.collectFiles(directory, files)
        }
        return files
    }

    /** Exports the automatically resolved built-in save directory. */
    @Throws(IOException::class)
    fun exportInternalSave(game: Game, destinationDirectory: File?): Int {
        val location = resolveInternalSaveLocation(game)
        if (!location.available || location.directory == null) throw IOException(location.reason)
        val source = SaveFileUtils.requireExistingDirectory(location.directory, "游戏存档目录")
        SaveFileUtils.rejectGamePayload(source)
        val destination = SaveFileUtils.requireDirectory(destinationDirectory, "导出目录")
        SaveFileUtils.rejectNestedDirectories(source, destination)
        return SaveFileUtils.copyDirectoryContents(source, destination, false)
    }

    /** Exports the resolved save files into a directory selected through the system picker. */
    @Throws(IOException::class)
    fun exportInternalSaveToTree(game: Game, destinationTreeUri: Uri?): Int {
        val location = resolveInternalSaveLocation(game)
        if (!location.available || location.directory == null) throw IOException(location.reason)
        val source = SaveFileUtils.requireExistingDirectory(location.directory, "游戏存档目录")
        SaveFileUtils.rejectGamePayload(source)
        if (destinationTreeUri == null) throw IOException("导出目录不可用")
        val destination = DocumentFile.fromTreeUri(context, destinationTreeUri)
            ?: throw IOException("无法打开导出目录")
        if (!destination.isDirectory) throw IOException("无法打开导出目录")
        return documentTransfer.copyDirectoryContentsToDocument(source, destination)
    }

    /** Exports all real save files as one ZIP archive selected through the system file picker. */
    @Throws(IOException::class)
    fun exportInternalSaveToZip(game: Game, destinationUri: Uri?): Int {
        val location = resolveInternalSaveLocation(game)
        if (!location.available || location.directory == null) throw IOException(location.reason)
        if (destinationUri == null) throw IOException("导出文件不可用")
        val sources = resolveInternalSaveDirectories(game, location)
        return zipTransfer.exportToZip(sources, destinationUri)
    }

    /** Imports into the automatically resolved built-in save directory. */
    @Throws(IOException::class)
    fun importInternalSave(game: Game, sourceDirectory: File?, overwrite: Boolean): Int {
        val location = resolveInternalSaveLocation(game)
        if (!location.available || location.directory == null) throw IOException(location.reason)
        val source = SaveFileUtils.requireExistingDirectory(sourceDirectory, "导入目录")
        val destination = SaveFileUtils.requireDirectory(location.directory, "游戏存档目录")
        SaveFileUtils.rejectNestedDirectories(source, destination)
        if (SaveFileUtils.samePath(source, destination)) throw IOException("导入目录与游戏存档目录相同")
        if (overwrite) SaveFileUtils.clearDirectory(destination)
        return SaveFileUtils.copyDirectoryContents(source, destination, false)
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
        val destination = SaveFileUtils.requireDirectory(location.directory, "游戏存档目录")
        if (overwrite) SaveFileUtils.clearDirectory(destination)
        return documentTransfer.copyDocumentContentsToDirectory(source, destination)
    }

    /** Imports a ZIP archive selected through the system file picker. */
    @Throws(IOException::class)
    fun importInternalSaveFromZip(game: Game, sourceUri: Uri?, overwrite: Boolean): Int {
        if (sourceUri == null) throw IOException("导入压缩包不可用")
        val temporaryDirectory = zipTransfer.createTemporaryImportDirectory()
        try {
            val extracted = zipTransfer.extractZipToDirectory(sourceUri, temporaryDirectory)
            if (extracted == 0) throw IOException("压缩包中未找到存档文件")
            val location = resolveInternalSaveLocation(game)
            if (!location.available || location.directory == null) throw IOException(location.reason)
            val destinations = resolveInternalSaveDirectories(game, location)
            if (destinations.isEmpty()) throw IOException("无法解析实际存档目录")
            for (destination in destinations) {
                SaveFileUtils.requireDirectory(destination, "游戏存档目录")
                if (overwrite) SaveFileUtils.clearDirectory(destination)
            }
            var copied = 0
            for (destination in destinations) {
                copied = maxOf(
                    copied,
                    SaveFileUtils.copyDirectoryContents(
                        temporaryDirectory,
                        SaveFileUtils.requireDirectory(destination, "游戏存档目录"),
                        false
                    )
                )
            }
            return copied
        } finally {
            try {
                SaveFileUtils.deleteRecursively(temporaryDirectory)
            } catch (ignored: Exception) {
                // 临时目录清理失败可安全忽略（残留由系统/后续操作兜底）
            }
        }
    }

    /** 删除自动解析的内置存档位置中的全部存档文件（保留存档目录本身）。返回删除的顶层条目数。 */
    @Throws(IOException::class)
    fun deleteInternalSave(game: Game): Int {
        val location = resolveInternalSaveLocation(game)
        if (!location.available || location.directory == null) throw IOException(location.reason)
        val directories = resolveInternalSaveDirectories(game, location)
        if (directories.isEmpty()) throw IOException("无法解析实际存档目录")
        var deleted = 0
        for (directory in directories) {
            val existing = SaveFileUtils.requireExistingDirectory(directory, "游戏存档目录")
            deleted += SaveFileUtils.clearDirectoryContents(existing)
        }
        // Artemis scoped：引擎在 mirrorRoot 中运行并写入存档，saveRoot 仅是 FileObserver
        // 实时同步的备份；只清 saveRoot 会遗留 mirror 中的存档，再次启动游戏时存档依旧存在。
        // mirror 内资源为指向原游戏目录的符号链接，clearDirectoryContents 不跟随，仅删链接本身。
        if (game.engine == EngineType.ARTEMIS) {
            val rootPath = ScriptEngineLaunchers.stripFileScheme(
                ArtemisLauncher.resolveGamePath(game.rootUri, game.launchTarget),
            )
            val mirror = ArtemisLauncher.resolveMirrorDirectory(context, rootPath)
            if (mirror != null && mirror.isDirectory) {
                deleted += SaveFileUtils.clearDirectoryContents(mirror)
            }
        }
        return deleted
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
        val directory = SaveFileUtils.requireDirectory(saveDirectory, "存档目录")
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
        SaveFileUtils.collectFiles(directory, files)
        return files
    }

    /**
     * Exports the recorded save directory's contents into `destinationDirectory`.
     * Existing destination files are not replaced.
     */
    @Throws(IOException::class)
    fun exportSave(game: Game, destinationDirectory: File?): Int {
        val source = requireRecordedDirectory(game)
        val destination = SaveFileUtils.requireDirectory(destinationDirectory, "导出目录")
        SaveFileUtils.rejectNestedDirectories(source, destination)
        return SaveFileUtils.copyDirectoryContents(source, destination, false)
    }

    /**
     * Imports a directory into the recorded save directory.
     *
     * @param overwrite when true, clears the recorded directory first; when false,
     *                  importing a file that already exists fails.
     */
    @Throws(IOException::class)
    fun importSave(game: Game, sourceDirectory: File?, overwrite: Boolean): Int {
        val source = SaveFileUtils.requireExistingDirectory(sourceDirectory, "导入目录")
        val destination = requireRecordedDirectory(game)
        SaveFileUtils.rejectNestedDirectories(source, destination)
        if (SaveFileUtils.samePath(source, destination)) throw IOException("导入目录与存档目录相同")
        if (overwrite) SaveFileUtils.clearDirectory(destination)
        return SaveFileUtils.copyDirectoryContents(source, destination, false)
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

    companion object {
        private const val PREFS_NAME = "yukihub_game_save_paths"
        private const val KEY_PREFIX = "save_path."

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

        private fun isBuiltInPackage(game: Game): Boolean {
            val pkg = game.emulatorPackage?.trim()?.lowercase(Locale.ROOT) ?: ""
            if (pkg.isEmpty()) return game.engine == EngineType.KIRIKIRI || game.engine == EngineType.ARTEMIS
                || game.engine == EngineType.ONS || game.engine == EngineType.TYRANO
            return when (game.engine) {
                EngineType.KIRIKIRI -> pkg.startsWith(EnginePackages.INTERNAL_KRKR) || EnginePackages.LEGACY_KRKR == pkg
                EngineType.ARTEMIS -> pkg.startsWith(EnginePackages.INTERNAL_ARTEMIS)
                EngineType.ONS -> pkg.startsWith(EnginePackages.INTERNAL_ONS) || EnginePackages.LEGACY_ONS == pkg
                EngineType.TYRANO -> pkg.startsWith(EnginePackages.INTERNAL_TYRANO) || EnginePackages.LEGACY_TYRANO == pkg
                else -> false
            }
        }
    }
}
