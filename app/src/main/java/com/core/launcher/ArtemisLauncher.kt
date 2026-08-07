package com.core.launcher

import android.content.Context
import android.content.Intent
import android.util.Log
import com.akira.tyranoemu.remote.ArtemisActivityV1
import com.akira.tyranoemu.remote.ArtemisActivityV2
import com.akira.tyranoemu.remote.ArtemisActivityV3
import com.core.CorePreferences
import java.io.File
import java.util.Locale

/**
 * Artemis 引擎的入口解析、兼容版本路由与存档位置解析。
 *
 * 不采用 scoped 镜像：Artemis 引擎（Rev.3049）在 symlink 镜像数据根上二次启动会挂起黑屏
 * （实机对照实验确认——镜像数据根 + 引擎自身 system.dat 即挂起；直跑游戏目录则带存档多次
 * 启动均正常）。因此固定直跑游戏目录（非 scoped），引擎存档写入游戏目录根
 * （autosave/saveg/system/saveXXXX.dat），由存档管理通过 [isResourceName] 过滤游戏资源条目。
 */
internal object ArtemisLauncher {
    private const val TAG = "EmulatorLauncher"
    private const val PREFS_NAME = CorePreferences.APP_PREFS
    private const val ENGINE_PREF_PREFIX = "artemis_engine."

    /** 引擎版本取值（应用级/游戏级设置，主源）：自动（V1 起 + 试错回退）/ 固定 1/2/3。 */
    const val ENGINE_VERSION_AUTO = "auto"
    const val ENGINE_VERSION_V1 = "1"
    const val ENGINE_VERSION_V2 = "2"
    const val ENGINE_VERSION_V3 = "3"

    /** SCREEN_ORIENTATION_* 常量（避免直接硬编码 6/8）。 */
    private const val ORIENTATION_SENSOR_LANDSCAPE = 6
    private const val ORIENTATION_REVERSE_LANDSCAPE = 8

    data class SaveLocation(
        @JvmField val directory: File?,
        @JvmField val description: String,
        @JvmField val available: Boolean,
    )

    /**
     * @param engineVersion 应用级/游戏级解析后的引擎版本（auto/1/2/3），
     *                      仅当 packageName 为内部自动包名（internal.artemis）时生效；
     *                      显式版本直接启动对应引擎，不做试错回退（参考 tyranor 确定性选择）。
     * @param rotateScreen  true 时强制反向横屏（orientation=8），修复画面倒置游戏。
     */
    @JvmStatic
    fun buildIntent(
        context: Context,
        packageName: String?,
        gamePath: String?,
        launchTarget: String?,
        engineVersion: String = ENGINE_VERSION_AUTO,
        rotateScreen: Boolean = false,
    ): Intent {
        val resolvedPath = resolveGamePath(gamePath, launchTarget)
        val rootPath = ScriptEngineLaunchers.stripFileScheme(resolvedPath)
        // 非 scoped：引擎直接跑游戏目录（symlink 镜像会导致二次启动黑屏，见类注释）。
        val launchPath = rootPath
        val requestedPackage = packageName?.trim().orEmpty()
        val autoCandidate = requestedPackage.equals(EnginePackages.INTERNAL_ARTEMIS, ignoreCase = true)
        val (effectivePackage, autoFallback) = when {
            // 用户显式指定非自动包名（添加游戏时选择）：尊重选择，不做试错回退
            !autoCandidate -> requestedPackage to false
            // 设置显式指定引擎版本：直接启动对应版本
            engineVersion == ENGINE_VERSION_V1 -> EnginePackages.INTERNAL_ARTEMIS to false
            engineVersion == ENGINE_VERSION_V2 -> EnginePackages.ARTEMIS_COMPAT to false
            engineVersion == ENGINE_VERSION_V3 -> EnginePackages.ARTEMIS_COMPAT_V2 to false
            // 自动：V1 起 + 启动失败早退回退链 + 跨启动版本记忆
            else -> preferredPackage(context, requestedPackage, rootPath) to true
        }
        val activityClass = chooseActivity(effectivePackage)
        logInfo(
            "ARTEMIS_NONSCOPED pkg=$requestedPackage effectivePkg=$effectivePackage " +
                "engineVersion=$engineVersion rotate=$rotateScreen " +
                "activity=${activityClass.simpleName} root=$gamePath target=$launchTarget " +
                "resolved=$resolvedPath path=$launchPath",
        )
        return Intent(context, activityClass).apply {
            if (!launchPath.isNullOrEmpty()) {
                putExtra("path", launchPath)
                putExtra("gamePath", launchPath)
            }
            putExtra("rootUri", gamePath)
            putExtra("launchTarget", launchTarget)
            putExtra("launchMode", EnginePackages.INTERNAL_ARTEMIS)
            putExtra("orientation", if (rotateScreen) ORIENTATION_REVERSE_LANDSCAPE else ORIENTATION_SENSOR_LANDSCAPE)
            putExtra("scopedSaveDir", false)
            putExtra("artemisAutoFallback", autoFallback)
            putExtra("artemisFallbackStage", fallbackStage(effectivePackage))
            addFlags(engineIntentFlags())
            LauncherUiBridge.appendEngineThemeExtrasSafely(this, context)
        }
    }

    @JvmStatic
    fun resolveGamePath(rootUri: String?, launchTarget: String?): String? {
        val root = ScriptEngineLaunchers.uriToFilePath(rootUri)?.takeUnless(String::isEmpty) ?: rootUri
        // Artemis receives its selected entry in the separate launchTarget extra.  Its native
        // resource resolver must still receive the game directory, including for root.pfs.
        return root
    }

    /**
     * 解析 Artemis 存档位置：非 scoped 下引擎存档直接写在游戏目录根。
     * 存档管理操作须经 [isResourceName] 排除游戏资源（root.pfs/system/movie 等）。
     */
    @JvmStatic
    fun resolveSaveLocation(context: Context?, rootPath: String?): SaveLocation {
        if (rootPath.isNullOrBlank() || rootPath.startsWith("content://")) {
            return SaveLocation(null, "无法解析游戏本地目录", false)
        }
        return SaveLocation(File(rootPath), "Artemis 游戏目录存档", true)
    }

    /**
     * Artemis 游戏资源条目判定：镜像/游戏目录中这些条目属于游戏本体，存档管理必须跳过。
     * 存档文件（autosave/saveg/system/saveXXXX.dat 及 png 缩略图）不命中本列表。
     */
    @JvmStatic
    fun isResourceName(name: String?): Boolean {
        val normalized = name?.trim()?.lowercase(Locale.ROOT) ?: return false
        return normalized == "system" || normalized == "movie" ||
            normalized == "artemisengine.exe" || normalized == "system.ini" ||
            normalized.startsWith("root.pfs") || normalized.endsWith(".pfs") ||
            normalized.endsWith(".xp3") || normalized.endsWith(".arc") ||
            normalized.endsWith(".pak") || normalized.endsWith(".dat.arc")
    }

    @JvmStatic
    fun fallbackStage(packageName: String?): Int {
        val normalized = packageName?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when {
            "compat.v2" in normalized || "compatible_v2" in normalized || normalized.endsWith(".2") -> 2
            "compat" in normalized -> 1
            else -> 0
        }
    }

    /**
     * 读取该游戏记忆的兼容引擎版本。非 scoped 下引擎侧 retry 写入的键与 launcher 读取键一致
     * （都以游戏目录路径 hashCode 为键），跨启动版本记忆稳定。
     */
    private fun preferredPackage(context: Context, requestedPackage: String, rootPath: String?): String {
        if (rootPath.isNullOrBlank()) return requestedPackage
        val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(ENGINE_PREF_PREFIX + Integer.toHexString(rootPath.hashCode()), null)
        return saved?.takeIf(String::isNotBlank) ?: requestedPackage
    }

    private fun chooseActivity(packageName: String?): Class<out android.app.Activity> =
        when (fallbackStage(packageName)) {
            2 -> ArtemisActivityV3::class.java
            1 -> ArtemisActivityV2::class.java
            else -> ArtemisActivityV1::class.java
        }

    private fun engineIntentFlags(): Int = Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

    private fun logInfo(message: String) {
        runCatching { Log.i(TAG, message) }
    }
}
