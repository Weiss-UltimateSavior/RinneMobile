package com.yuki.yukihub.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.system.Os
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.akira.tyranoemu.remote.Kirikiroid126
import com.akira.tyranoemu.remote.Kirikiroid134
import com.akira.tyranoemu.remote.Kirikiroid139
import com.apps.LauncherActivity
import com.apps.theme.LauncherTheme
import org.tvp.kirikiri2.KR2Activity
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

/** KRKR 入口解析、引擎版本路由和独立存档重定向。 */
internal object KrkrLauncher {
    private const val TAG = "EmulatorLauncher"
    private const val PREFS_NAME = "yukihub_prefs"

    data class SaveLocation(
        @JvmField val directory: File?,
        @JvmField val description: String,
        @JvmField val available: Boolean,
    )

    @JvmStatic
    @JvmOverloads
    fun buildIntent(
        context: Context,
        gamePath: String?,
        launchTarget: String?,
        originMode: Boolean = false,
        engineVersion: String? = "auto",
        safFileFallback: Boolean = false,
    ): Intent {
        val resolvedPath = if (originMode) null else resolvePath(context, gamePath, launchTarget)
        val rawRootPath = ScriptEngineLaunchers.stripFileScheme(
            ScriptEngineLaunchers.uriToFilePath(gamePath),
        )
        val path = ScriptEngineLaunchers.stripFileScheme(resolvedPath)
        val rootPath = rootForPath(rawRootPath, path)
        val globalScoped = isScopedSaveEnabled(context)
        val scoped = globalScoped
        val autoSdCardMirror = false
        val saveLocation = if (originMode) null else resolveSaveLocation(
            context,
            gamePath,
            launchTarget,
            globalScoped,
        )
        if (!originMode && (saveLocation?.available != true || saveLocation.directory == null)) {
            throw IllegalStateException(saveLocation?.description ?: "无法解析实际存档目录")
        }

        val saveName = safeSaveName(rootPath)
        var scopedSaveRoot: String? = null
        if (!originMode && scoped) {
            val directory = requireNotNull(saveLocation?.directory)
            if (!prepareScopedSaveDirectory(context, directory, saveName)) {
                throw IllegalStateException("无法创建 KRKR 应用独立存档目录")
            }
            scopedSaveRoot = directory.absolutePath
            logInfo(
                "KRKR direct save redirect root=$rootPath path=$path save=$scopedSaveRoot " +
                    "globalScoped=$globalScoped",
            )
        }

        val resolvedVersion = if (originMode) "auto" else normalizeEngineVersion(engineVersion)
        val use126 = !originMode && resolvedVersion == "1.2.6"
        val use134 = !originMode && resolvedVersion == "1.3.4"
        val activityClass = when {
            originMode -> KR2Activity::class.java
            use126 -> Kirikiroid126::class.java
            use134 -> Kirikiroid134::class.java
            else -> Kirikiroid139::class.java
        }
        logInfo(
            "internal KRKR originMode=$originMode engineVersion=$resolvedVersion use126=$use126 " +
                "use134=$use134 root=$gamePath target=$launchTarget resolved=$resolvedPath " +
                "rootPath=$rootPath globalScoped=$globalScoped scoped=$scoped autoSdMirror=$autoSdCardMirror",
        )
        return Intent(context, activityClass).apply {
            if (!path.isNullOrEmpty()) {
                putExtra("path", path)
                putExtra("gamePath", path)
            }
            if (!rootPath.isNullOrEmpty()) {
                putExtra("projectRoot", rootPath)
                putExtra("gamedir", rootPath)
            }
            putExtra("rootUri", gamePath)
            putExtra("launchTarget", launchTarget)
            putExtra("originMode", originMode)
            putExtra("focus", "true")
            putExtra("krEngineVersion", when { use126 -> "1.2.6"; use134 -> "1.3.4"; else -> "1.3.9" })
            putExtra("orientation", 6)
            putExtra("launchMode", if (originMode) "internal.krkr.origin" else "internal.krkr")
            putExtra("scopedSaveDir", scoped)
            putExtra("globalScopedSaveDir", globalScoped)
            putExtra("autoKrMirror", autoSdCardMirror)
            putExtra("terminateKrProcessOnDestroy", scoped || safFileFallback || autoSdCardMirror)
            putExtra("scopedSaveName", saveName)
            scopedSaveRoot?.let { putExtra("scopedSaveRoot", it) }
            putExtra("safFileFallback", safFileFallback)
            addFlags(engineIntentFlags())
            appendThemeColors(this, context)
        }
    }

    @JvmStatic
    fun resolveSaveLocation(
        context: Context?,
        rootUri: String?,
        launchTarget: String?,
        scoped: Boolean,
    ): SaveLocation {
        if (context == null) return SaveLocation(null, "应用上下文不可用", false)
        return try {
            val resolved = resolvePath(context, rootUri, launchTarget)
            val rawRoot = ScriptEngineLaunchers.stripFileScheme(
                ScriptEngineLaunchers.uriToFilePath(rootUri),
            )
            val root = rootForPath(rawRoot, ScriptEngineLaunchers.stripFileScheme(resolved))
            if (root.isNullOrBlank() || root.startsWith("content://")) {
                return SaveLocation(null, "无法解析 KRKR 实际游戏目录", false)
            }
            if (!scoped) {
                SaveLocation(File(root, "savedata"), "KRKR 游戏目录存档", true)
            } else {
                val internal = context.filesDir
                    ?: return SaveLocation(null, "应用内部存储目录不可用", false)
                val mirrorRoot = File(File(internal, "krkr_mirror"), safeSaveName(root))
                SaveLocation(File(mirrorRoot, "savedata"), "KRKR 独立存档目录", true)
            }
        } catch (error: Throwable) {
            logWarn("resolve KRKR save location failed root=$rootUri", error)
            SaveLocation(null, "无法解析实际存档目录", false)
        }
    }

    @JvmStatic
    fun isScopedSaveEnabled(context: Context?): Boolean = context == null ||
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("kr_scoped_save_dir", true)

    @JvmStatic
    fun resolvePath(context: Context?, rootUri: String?, launchTarget: String?): String? {
        val rootPath = ScriptEngineLaunchers.stripFileScheme(
            ScriptEngineLaunchers.uriToFilePath(rootUri),
        )
        if (rootPath.isNullOrEmpty()) return rootUri
        val target = launchTarget?.trim().orEmpty()
        if (target.isEmpty() || target == "[游戏目录]" || target.equals("DIR", ignoreCase = true)) {
            return rootPath
        }
        if (target.equals("XP3_FIRST", ignoreCase = true)) {
            return findFirstChildBySuffix(rootPath, ".xp3")
                ?: findPreferredEntryFromTree(context, rootUri, rootPath)
                ?: rootPath
        }
        if (target.startsWith('/')) {
            val file = File(target)
            return if (file.isFile) file.absolutePath else target
        }
        val targetFile = File(rootPath, target)
        if (targetFile.isFile || targetFile.isDirectory) return targetFile.absolutePath
        findTargetFromTree(context, rootUri, rootPath, target)?.let { return it }
        return if (
            target.endsWith(".xp3") || target.endsWith(".tjs") ||
            target.endsWith(".exe") || target.endsWith(".dll")
        ) targetFile.absolutePath else rootPath
    }

    @JvmStatic
    fun rootForPath(rawRootPath: String?, launchPath: String?): String? = try {
        rawRootPath?.takeIf(String::isNotBlank)?.let { rawPath ->
            val raw = File(rawPath)
            if (raw.isDirectory) return raw.absolutePath
            raw.parentFile?.let { return it.absolutePath }
        }
        launchPath?.takeIf(String::isNotBlank)?.let { path ->
            val launch = File(path)
            if (launch.isDirectory) return launch.absolutePath
            launch.parentFile?.let { return it.absolutePath }
        }
        rawRootPath
    } catch (_: Throwable) {
        rawRootPath
    }

    @JvmStatic
    fun normalizeEngineVersion(engineVersion: String?): String {
        return when (engineVersion?.trim()?.lowercase(Locale.ROOT) ?: "auto") {
            "134", "1.3.4", "kr134", "kirikiroid134" -> "1.3.4"
            "126", "1.2.6", "kr126", "kirikiroid126" -> "1.2.6"
            "139", "1.3.9", "kr139", "kirikiroid139" -> "1.3.9"
            else -> "auto"
        }
    }

    private fun findPreferredEntryFromTree(context: Context?, rootUri: String?, rootPath: String): String? {
        arrayOf("data.xp3", "startup.tjs", "patch.xp3").forEach { name ->
            findTargetFromTree(context, rootUri, rootPath, name)?.let { return it }
        }
        return try {
            val directory = if (context == null || rootUri == null) null else {
                DocumentFile.fromTreeUri(context, Uri.parse(rootUri))
            }
            directory?.takeIf(DocumentFile::isDirectory)?.listFiles()?.firstOrNull { file ->
                file?.isFile == true && file.name?.lowercase(Locale.ROOT)?.endsWith(".xp3") == true
            }?.name?.let { name -> if (rootPath.endsWith('/')) rootPath + name else "$rootPath/$name" }
        } catch (_: Throwable) {
            null
        }
    }

    private fun findTargetFromTree(
        context: Context?,
        rootUri: String?,
        rootPath: String,
        target: String?,
    ): String? {
        if (context == null || rootUri == null || target.isNullOrBlank()) return null
        return try {
            var current = DocumentFile.fromTreeUri(context, Uri.parse(rootUri))
            if (current?.isDirectory != true) return null
            target.split('/').forEach { part ->
                if (part.isEmpty() || part == ".") return@forEach
                current = current?.findFile(part) ?: return null
            }
            if (current?.exists() == true && current?.isFile == true) {
                val cleanTarget = target.trimStart('/')
                if (rootPath.endsWith('/')) rootPath + cleanTarget else "$rootPath/$cleanTarget"
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun findFirstChildBySuffix(rootPath: String?, suffix: String?): String? {
        if (rootPath.isNullOrBlank() || suffix.isNullOrBlank()) return null
        return try {
            val normalizedSuffix = suffix.lowercase(Locale.ROOT)
            File(rootPath).listFiles()?.firstOrNull { child ->
                child?.isFile == true && child.name?.lowercase(Locale.ROOT)?.endsWith(normalizedSuffix) == true
            }?.absolutePath
        } catch (_: Throwable) {
            null
        }
    }

    private fun prepareScopedSaveDirectory(context: Context, saveDirectory: File, saveName: String?): Boolean {
        return try {
            val internal = context.filesDir ?: return false
            val external = context.getExternalFilesDir(null)
            val name = saveName?.trim().orEmpty()
            if (name.isEmpty()) return false
            if (isSymlink(saveDirectory) && !saveDirectory.delete()) return false
            if (saveDirectory.exists() && !saveDirectory.isDirectory) return false
            if (!saveDirectory.exists() && !saveDirectory.mkdirs()) return false
            external?.let {
                val legacyRoot = File(File(it, "save"), name)
                val migrated = copyRegularFilesRecursively(legacyRoot, saveDirectory, onlyNewer = true)
                if (migrated > 0) logInfo("migrated KRKR external saves count=$migrated from=$legacyRoot to=$saveDirectory")
            }
            val previousInternalRoot = File(File(internal, "save"), name)
            val migrated = copyRegularFilesRecursively(previousInternalRoot, saveDirectory, onlyNewer = true)
            if (migrated > 0) {
                logInfo("migrated KRKR internal saves count=$migrated from=$previousInternalRoot to=$saveDirectory")
            }
            true
        } catch (error: Throwable) {
            logWarn("prepare KRKR scoped save directory failed save=$saveDirectory", error)
            false
        }
    }

    private fun copyRegularFilesRecursively(fromDirectory: File?, toDirectory: File?, onlyNewer: Boolean): Int {
        if (fromDirectory?.isDirectory != true || toDirectory == null) return 0
        if (!toDirectory.isDirectory && !toDirectory.mkdirs()) return 0
        return fromDirectory.listFiles()?.sumOf { child ->
            if (child == null || isSymlink(child)) return@sumOf 0
            val target = File(toDirectory, child.name)
            when {
                child.isDirectory -> copyRegularFilesRecursively(child, target, onlyNewer)
                child.isFile && onlyNewer && target.exists() &&
                    target.lastModified() >= child.lastModified() && target.length() == child.length() -> 0
                child.isFile && copyFile(child, target) -> 1
                else -> 0
            }
        } ?: 0
    }

    private fun copyFile(source: File, destination: File): Boolean = try {
        destination.parentFile?.let { parent ->
            if (!parent.isDirectory && !parent.mkdirs()) return false
        }
        FileInputStream(source).use { input ->
            FileOutputStream(destination, false).use { output -> input.copyTo(output, 64 * 1024) }
        }
        destination.setLastModified(source.lastModified())
        true
    } catch (error: Throwable) {
        logWarn("copy file failed $source -> $destination", error)
        false
    }

    private fun isSymlink(file: File): Boolean = try {
        Os.readlink(file.absolutePath)
        true
    } catch (_: Throwable) {
        false
    }

    private fun safeSaveName(rootPath: String?): String {
        if (rootPath.isNullOrBlank()) return "default"
        return try {
            var name = File(rootPath).name
            if (name.isBlank()) name = kotlin.math.abs(rootPath.hashCode()).toString()
            name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "default" }
        } catch (_: Throwable) {
            "default"
        }
    }

    private fun appendThemeColors(intent: Intent, context: Context) {
        try {
            val primary = LauncherTheme.primary(context)
            intent.putExtra("primaryColor", primary)
            intent.putExtra("darkMode", LauncherActivity.isLauncherDarkMode(context))
            intent.putExtra("themeColorPrimary", primary)
            intent.putExtra("themeColorOnPrimary", LauncherTheme.onPrimary(context))
            intent.putExtra("themeColorCard", LauncherTheme.card(context))
            intent.putExtra("themeColorText", LauncherTheme.text(context))
            intent.putExtra("themeColorTextMuted", LauncherTheme.textMuted(context))
        } catch (error: Throwable) {
            logWarn("appendThemeColors failed", error)
        }
    }

    private fun engineIntentFlags(): Int = Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

    private fun logInfo(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    private fun logWarn(message: String, error: Throwable? = null) {
        runCatching { if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error) }
    }
}
