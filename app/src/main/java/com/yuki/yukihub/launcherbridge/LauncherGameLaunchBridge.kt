package com.yuki.yukihub.launcherbridge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.documentfile.provider.DocumentFile
import com.yuki.yukihub.data.GameRepository
import com.yuki.yukihub.launcher.EmulatorLauncher
import com.yuki.yukihub.model.EngineType
import com.yuki.yukihub.model.Game
import com.yuki.yukihub.util.AppExecutors
import com.yuki.yukihub.util.RxMainScheduler
import java.util.Locale

/**
 * 游戏启动桥接：数据库准备、SAF 校验、模拟器分发。
 */
object LauncherGameLaunchBridge {

    private const val APP_PREFS = "yukihub_prefs"
    private const val KEY_KR_ENGINE_VERSION = "kr_engine_version"

    interface LaunchCallback {
        fun onResult(result: LaunchResult)
    }

    /** 在 IO 线程执行数据库、SAF 和文件准备。 */
    @JvmStatic
    fun launchAsync(context: Context?, game: Game?, callback: LaunchCallback?) {
        if (callback == null) return
        val app = context?.applicationContext
        AppExecutors.runOnIo {
            val result = launch(app, game)
            RxMainScheduler.post { callback.onResult(result) }
        }
    }

    @JvmStatic
    fun launch(context: Context?, game: Game?): LaunchResult {
        if (context == null) return LaunchResult.failure("上下文不可用")
        if (game == null) return LaunchResult.failure("游戏不存在")
        val appContext = context.applicationContext
        val repository = GameRepository(appContext)
        val emulatorPackage = resolveEmulatorPackage(game)
        val launchTarget = resolveLaunchTarget(game)
        val validationError = validate(context, game, emulatorPackage)
        if (validationError != null) return LaunchResult.failure(validationError)

        val sessionId = repository.startPlaySession(game.id, System.currentTimeMillis(), resolveLaunchType(emulatorPackage))
        if (startGameActivity(context, game, emulatorPackage, launchTarget)) {
            return LaunchResult.success(sessionId)
        }
        repository.cancelPlaySession(sessionId)
        return LaunchResult.failure("启动失败：未找到该模拟器，或该模拟器不接受当前启动目标")
    }

    @JvmStatic
    fun finishSession(context: Context?, sessionId: Long, minDuration: Long, maxDuration: Long) {
        if (context == null || sessionId <= 0L) return
        GameRepository(context.applicationContext).finishPlaySession(
            sessionId,
            System.currentTimeMillis(),
            minDuration,
            maxDuration
        )
    }

    /**
     * 构建进入原生 KRKR 引擎（origin 模式、无具体游戏路径）的 Intent。
     * 供设置页面"进入原生 KRKR"入口使用，避免 com.apps 直接依赖 EmulatorLauncher。
     *
     * @return 可用于 [Activity.startActivity] 的 Intent；上下文无效时返回 null
     */
    @JvmStatic
    fun buildInternalKrkrOriginIntent(context: Context?): Intent? {
        if (context == null) return null
        return EmulatorLauncher.buildInternalKrkrIntent(context, "", "", true)
    }

    private fun validate(context: Context, game: Game, emulatorPackage: String): String? {
        if (game.engine == EngineType.GAMEHUB) {
            val ghMode = game.gamehubLaunchMode?.trim()?.lowercase(Locale.ROOT) ?: "game"
            if (ghMode != "program" && ghMode != "normal"
                && game.gamehubLocalGameId.isNullOrBlank()
            ) {
                return "请先在游戏中心编辑游戏，导入 GameHub localGameId。"
            }
        }
        if (emulatorPackage.isEmpty()) {
            return "请先在游戏中心编辑游戏，填写模拟器包名。"
        }
        if ((emulatorPackage.startsWith("internal.psp") || emulatorPackage == "org.ppsspp.ppsspp")
            && !EmulatorLauncher.isPPSSPPInstalled(context)
        ) {
            return "启动 PSP 游戏需要安装 PPSSPP 模拟器。"
        }
        // 外部插件启用状态拦截：模块被禁用时拒绝启动，引导用户去模块兼容页启用。
        if (LauncherModuleBridge.isRpgMakerPluginPackage(emulatorPackage)
            && LauncherModuleBridge.isRpgMakerModuleInstalled(context)
            && !LauncherModuleBridge.isRpgMakerModuleEnabled(context)
        ) {
            return "RPGM 模块未启用，请在「模块兼容」页面启用后再试。"
        }
        if (LauncherModuleBridge.isRenPyPluginPackage(emulatorPackage)
            && LauncherModuleBridge.isRenPyModuleInstalled(context)
            && !LauncherModuleBridge.isRenPyModuleEnabled(context)
        ) {
            return "RenPy 模块未启用，请在「模块兼容」页面启用后再试。"
        }
        if (LauncherModuleBridge.isGodotPluginPackage(emulatorPackage)
            && LauncherModuleBridge.isGodotModuleInstalled(context)
            && !LauncherModuleBridge.isGodotModuleEnabled(context)
        ) {
            return "Godot 模块未启用，请在「模块兼容」页面启用后再试。"
        }
        return null
    }

    private fun resolveEmulatorPackage(game: Game): String {
        val emulatorPackage = game.emulatorPackage?.trim() ?: ""
        if (emulatorPackage.isEmpty() && game.engine == EngineType.KIRIKIRI) return "internal.krkr"
        if (emulatorPackage.isEmpty() && game.engine == EngineType.ONS) return "internal.ons"
        if (emulatorPackage.isEmpty() && game.engine == EngineType.TYRANO) return "internal.tyrano"
        if (emulatorPackage.isEmpty() && game.engine == EngineType.PSP) return "org.ppsspp.ppsspp"
        if (game.engine == EngineType.ARTEMIS && emulatorPackage.isEmpty()) return "internal.artemis"
        return emulatorPackage
    }

    private fun resolveLaunchTarget(game: Game): String? {
        if (game.engine == EngineType.ARTEMIS || game.engine == EngineType.TYRANO) return "[游戏目录]"
        if (game.engine == EngineType.GAMEHUB) return safeTitle(game)
        return game.launchTarget
    }

    private fun startGameActivity(context: Context, game: Game, emulatorPackage: String, launchTarget: String?): Boolean {
        val pkg = emulatorPackage.trim()
        try {
            if (pkg.startsWith("internal.krkr") || pkg == "org.tvp.kirikiri2.internal") {
                val prefs: SharedPreferences =
                    context.applicationContext.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
                val krEngineVersion = prefs.getString(KEY_KR_ENGINE_VERSION, "auto")
                return startActivitySafely(context, EmulatorLauncher.buildInternalKrkrIntent(context, game.rootUri, launchTarget, false, krEngineVersion, false))
            }
            if (pkg.startsWith("internal.tyrano") || pkg == "com.yuki.yukihub.tyrano") {
                return startActivitySafely(context, EmulatorLauncher.buildInternalTyranoIntent(context, game.rootUri, launchTarget))
            }
            if (pkg.startsWith("internal.ons") || pkg == "com.yuki.yukihub.ons") {
                return startActivitySafely(context, EmulatorLauncher.buildInternalOnsIntent(context, game.rootUri, launchTarget, game.id))
            }
            if (pkg.startsWith("internal.artemis")) {
                return startActivitySafely(context, EmulatorLauncher.buildInternalArtemisIntent(context, pkg, game.rootUri, launchTarget))
            }
            if (pkg.startsWith("internal.psp") || pkg == "org.ppsspp.ppsspp") {
                if (!EmulatorLauncher.isPPSSPPInstalled(context)) {
                    return false
                }
                return startActivitySafely(context, EmulatorLauncher.buildInternalPspIntent(
                    context, resolvePspLaunchUri(context, game.rootUri, launchTarget), launchTarget))
            }
            return EmulatorLauncher.launchGame(context, pkg, game.rootUri, launchTarget, game.winlatorLaunchMode, game.gamehubLaunchMode, game.gamehubLocalGameId)
        } catch (_: Throwable) {
            return false
        }
    }

    /** PPSSPP 需要接收选中的光盘文件而非其所在的 SAF 目录树。 */
    private fun resolvePspLaunchUri(context: Context, rootUri: String?, launchTarget: String?): String? {
        if (rootUri.isNullOrBlank() || launchTarget.isNullOrBlank() || launchTarget == "[游戏目录]") return rootUri
        try {
            var current = DocumentFile.fromTreeUri(context, android.net.Uri.parse(rootUri))
            for (segment in launchTarget.split("/")) {
                if (current == null || segment.isEmpty()) continue
                current = current.findFile(segment)
            }
            if (current != null && current.isFile) return current.uri.toString()
        } catch (_: Throwable) {
        }
        return rootUri
    }

    private fun startActivitySafely(context: Context?, intent: Intent?): Boolean {
        if (context == null || intent == null) return false
        return try {
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun resolveLaunchType(emulatorPackage: String?): String {
        val pkg = emulatorPackage?.trim()?.lowercase(Locale.ROOT) ?: ""
        if (pkg.startsWith("internal.krkr") || pkg == "org.tvp.kirikiri2.internal") return "internal.krkr"
        if (pkg.startsWith("internal.ons") || pkg == "com.yuki.yukihub.ons") return "internal.ons"
        if (pkg.startsWith("internal.tyrano") || pkg == "com.yuki.yukihub.tyrano") return "internal.tyrano"
        if (pkg.startsWith("internal.artemis")) return pkg
        return "external"
    }

    private fun safeTitle(game: Game?): String {
        val title = game?.title
        if (title.isNullOrBlank()) return "未命名游戏"
        return title.trim()
    }

    class LaunchResult private constructor(
        @JvmField val success: Boolean,
        @JvmField val sessionId: Long,
        @JvmField val message: String
    ) {
        companion object {
            @JvmStatic
            fun success(sessionId: Long): LaunchResult =
                LaunchResult(true, sessionId, "")

            @JvmStatic
            fun failure(message: String?): LaunchResult =
                LaunchResult(false, -1L, if (message.isNullOrBlank()) "启动失败" else message)
        }
    }
}
