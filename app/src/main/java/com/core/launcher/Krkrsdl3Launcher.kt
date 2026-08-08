package com.core.launcher

import android.content.Context
import android.content.Intent
import com.core.krkrsdl3.Krkrsdl3Activity
import com.core.util.DevLogger
import java.io.File

/**
 * krkrsdl3 引擎启动器（与 [KrkrLauncher] 并列，独立路由）。
 *
 * 职责：
 * 1. 解析启动文件绝对路径（复用 [KrkrLauncher.resolvePath] / [KrkrLauncher.rootForPath]）。
 * 2. 构造 argv 参数列表（首项 = 启动文件路径，后续 = 渲染/存档重定向等引擎开关）。
 * 3. 构建 Intent → [Krkrsdl3Activity]。
 *
 * 不依赖 krkr_bridge hook，不经过 Kirikiroid2 引擎体系。
 */
internal object Krkrsdl3Launcher {
    private const val TAG = "Krkrsdl3Launcher"

    private const val DEFAULT_RENDERER = "software"

    @JvmStatic
    @JvmOverloads
    fun buildIntent(
        context: Context,
        gamePath: String?,
        launchTarget: String?,
        scopedSaveDir: Boolean = false,
        scopedSaveRoot: String? = null,
        renderer: String = DEFAULT_RENDERER,
    ): Intent {
        val initiallyResolvedPath = KrkrLauncher.resolvePath(context, gamePath, launchTarget)
        val resolvedPath = KrkrLauncher.preferEmbeddedStartupExecutable(
            gamePath,
            launchTarget,
            initiallyResolvedPath,
        )
        val rawRootPath = ScriptEngineLaunchers.stripFileScheme(
            ScriptEngineLaunchers.uriToFilePath(gamePath),
        )
        val path = ScriptEngineLaunchers.stripFileScheme(resolvedPath)
        val rootPath = KrkrLauncher.rootForPath(rawRootPath, path)

        // argv[0] = 启动文件绝对路径
        val argv = mutableListOf(resolvedPath ?: gamePath ?: "")

        // 渲染后端
        argv.add("-render=$renderer")

        // scoped 存档重定向
        val saveDir = scopedSaveRoot
            ?: if (scopedSaveDir && rootPath != null) {
                resolveScopedSaveDir(context, rootPath)
            } else {
                null
            }
        if (saveDir != null) {
            argv.add("--save-dir")
            argv.add(saveDir)
        }

        DevLogger.w(
            TAG,
            "krkrsdl3 intent gamePath=$gamePath target=$launchTarget initial=$initiallyResolvedPath " +
                "resolved=$resolvedPath " +
                "rootPath=$rootPath scopedSaveDir=$scopedSaveDir saveDir=$saveDir renderer=$renderer",
        )

        return Intent(context, Krkrsdl3Activity::class.java).apply {
            putStringArrayListExtra("gameargs", ArrayList(argv))
            putExtra("orientation", 6)
            putExtra("launchMode", EnginePackages.INTERNAL_KRKR_KRKRSDL3)
            putExtra("rootUri", gamePath)
            putExtra("launchTarget", launchTarget)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            LauncherUiBridge.appendEngineThemeExtrasSafely(this, context)
        }
    }

    /** 解析 scoped 存档根目录（与 [KrkrLauncher] 的 scoped 存档目录一致）。 */
    private fun resolveScopedSaveDir(context: Context, rootPath: String): String {
        val internal = context.filesDir
            ?: return ""
        val saveName = safeSaveName(rootPath)
        val mirrorRoot = File(File(internal, "krkr_mirror"), saveName)
        val saveDir = File(mirrorRoot, "savedata")
        if (!saveDir.isDirectory && !saveDir.mkdirs()) {
            DevLogger.w(TAG, "failed to create scoped save directory: $saveDir")
        }
        return saveDir.absolutePath
    }

    private fun safeSaveName(rootPath: String): String {
        if (rootPath.isBlank()) return "default"
        return try {
            var name = File(rootPath).name
            if (name.isBlank()) name = kotlin.math.abs(rootPath.hashCode()).toString()
            name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "default" }
        } catch (_: Exception) {
            // 非法/超长文件名的极端输入按默认名兜底，安全忽略
            "default"
        }
    }
}
