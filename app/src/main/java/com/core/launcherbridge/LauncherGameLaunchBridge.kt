package com.core.launcherbridge

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import androidx.documentfile.provider.DocumentFile
import com.core.R
import com.core.diagnostics.GameDiagnostics
import com.core.data.GameRepository
import com.core.launcher.EmulatorLauncher
import com.core.model.EngineType
import com.core.model.Game
import com.core.util.AppExecutors
import java.util.Locale

/**
 * 游戏启动桥接：数据库准备、SAF 校验、模拟器分发。
 */
object LauncherGameLaunchBridge {

    private const val KEY_KR_ENGINE_VERSION = "kr_engine_version"
    private const val LAUNCH_GATE_PREFS = "launcher_active_game_gate"
    private const val KEY_ACTIVE_SESSION_ID = "active_session_id"
    private const val KEY_ACTIVE_GAME_ID = "active_game_id"
    private const val KEY_ACTIVE_GAME_TITLE = "active_game_title"
    private const val KEY_ACTIVE_EMULATOR_PACKAGE = "active_emulator_package"
    private const val KEY_ACTIVE_STARTED_AT = "active_started_at"
    private const val ACTIVE_PROCESS_GRACE_MS = 5_000L
    private const val MAX_PLAY_SESSION_MS = 12L * 60L * 60L * 1000L
    private val launchGateLock = Any()
    // Values from releases before the core package refactor may still be stored in games.db.
    private const val LEGACY_INTERNAL_TYRANO_PACKAGE = "com.yuki.yukihub.tyrano"
    private const val LEGACY_INTERNAL_ONS_PACKAGE = "com.yuki.yukihub.ons"

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
            postToMain { callback.onResult(result) }
        }
    }

    @JvmStatic
    fun launch(context: Context?, game: Game?): LaunchResult {
        if (context == null) return LaunchResult.failure("上下文不可用")
        if (game == null) return LaunchResult.failure(context.getString(R.string.core_game_not_found))
        val appContext = context.applicationContext
        val repository = GameRepository(appContext)
        val emulatorPackage = resolveEmulatorPackage(context, game)
        val launchTarget = resolveLaunchTarget(game)
        val validationError = validate(context, game, emulatorPackage)
        if (validationError != null) {
            GameDiagnostics.recordLaunch(appContext, game, false, validationError, launchTarget, "validation_failed")
            return LaunchResult.failure(validationError)
        }

        val gate = acquireLaunchGate(appContext, repository, game, emulatorPackage)
        if (gate.conflict != null) {
            return LaunchResult.activeGame(
                gate.conflict.gameTitle,
                context.getString(R.string.core_current_game),
                context.getString(R.string.core_active_game_message, gate.conflict.gameTitle),
            )
        }
        if (gate.sessionId <= 0L) {
            return LaunchResult.failure(context.getString(R.string.core_game_session_create_failed))
        }

        val sessionId = gate.sessionId
        val attempt = startGameActivity(context, game, emulatorPackage, launchTarget)
        if (attempt.success) {
            GameDiagnostics.recordLaunch(
                appContext,
                game,
                true,
                context.getString(R.string.core_launch_request_sent),
                launchTarget,
            )
            return LaunchResult.success(sessionId)
        }
        repository.cancelPlaySession(sessionId)
        releaseLaunchGate(appContext, sessionId)
        val message = context.getString(R.string.core_emulator_launch_failed)
        GameDiagnostics.recordLaunch(appContext, game, false, message, launchTarget, attempt.errorCategory, attempt.error)
        return LaunchResult.failure(message)
    }

    @JvmStatic
    fun finishSession(context: Context?, sessionId: Long, minDuration: Long, maxDuration: Long) {
        if (context == null || sessionId <= 0L) return
        val appContext = context.applicationContext
        GameRepository(appContext).finishPlaySession(
            sessionId,
            System.currentTimeMillis(),
            minDuration,
            maxDuration
        )
        releaseLaunchGate(appContext, sessionId)
    }

    /**
     * 在所有启动入口使用同一提示：不会结束或强制关闭当前游戏，用户需自行退出/划掉它。
     */
    @JvmStatic
    fun showActiveGameDialog(context: Context?, activeGameTitle: String?) {
        if (context == null) return
        val title = activeGameTitle?.trim().takeUnless { it.isNullOrEmpty() }
            ?: context.getString(R.string.core_current_game)
        try {
            AlertDialog.Builder(context)
                .setTitle(R.string.core_active_game_title)
                .setMessage(context.getString(R.string.core_active_game_dialog_message, title))
                .setPositiveButton(R.string.core_got_it, null)
                .show()
        } catch (_: Throwable) {
            // A non-Activity context cannot own a dialog window. Callers still receive the message.
        }
    }

    private fun acquireLaunchGate(
        context: Context,
        repository: GameRepository,
        game: Game,
        emulatorPackage: String,
    ): GateAcquireResult = synchronized(launchGateLock) {
        val prefs = context.getSharedPreferences(LAUNCH_GATE_PREFS, Context.MODE_PRIVATE)
        val active = readActiveGate(prefs)
        if (active != null) {
            if (isActiveGameStillRunning(context, active)) return@synchronized GateAcquireResult(conflict = active)
            // The launcher process may have died while the game was open.  Once its process is
            // gone, close that persisted session before admitting the next game.
            repository.finishPlaySession(active.sessionId, System.currentTimeMillis(), 0L, MAX_PLAY_SESSION_MS)
            clearActiveGate(prefs)
        }

        val startedAt = System.currentTimeMillis()
        val sessionId = repository.startPlaySession(game.id, startedAt, resolveLaunchType(emulatorPackage))
        if (sessionId <= 0L) return@synchronized GateAcquireResult()
        val gate = ActiveGameGate(
            sessionId = sessionId,
            gameId = game.id,
            gameTitle = safeTitle(context, game),
            emulatorPackage = emulatorPackage,
            startedAt = startedAt,
        )
        // commit() makes the reservation durable before startActivity() can hand execution to an engine process.
        if (!writeActiveGate(prefs, gate)) {
            repository.cancelPlaySession(sessionId)
            return@synchronized GateAcquireResult()
        }
        GateAcquireResult(sessionId = sessionId)
    }

    private fun releaseLaunchGate(context: Context, sessionId: Long) = synchronized(launchGateLock) {
        val prefs = context.getSharedPreferences(LAUNCH_GATE_PREFS, Context.MODE_PRIVATE)
        val active = readActiveGate(prefs)
        if (active?.sessionId == sessionId) clearActiveGate(prefs)
    }

    /**
     * The persisted lock survives a launcher-process death.  On a later launch, release it only
     * after its engine/external emulator process has disappeared; the short grace period prevents
     * a rapid double tap from winning before Android has spawned that process.
     */
    private fun isActiveGameStillRunning(context: Context, active: ActiveGameGate): Boolean {
        if (System.currentTimeMillis() - active.startedAt < ACTIVE_PROCESS_GRACE_MS) return true
        val target = activeProcessName(context, active.emulatorPackage)
        return isProcessRunning(context, target)
    }

    private fun activeProcessName(context: Context, emulatorPackage: String): String {
        val pkg = emulatorPackage.trim().lowercase(Locale.ROOT)
        val ownPackage = context.packageName
        return when {
            pkg.startsWith("internal.krkr") || pkg == "org.tvp.kirikiri2.internal" -> "$ownPackage:kirikiri2"
            pkg.startsWith("internal.tyrano") || pkg == "com.core.tyrano" || pkg == LEGACY_INTERNAL_TYRANO_PACKAGE -> "$ownPackage:tyrano"
            pkg.startsWith("internal.ons") || pkg == "com.core.ons" || pkg == LEGACY_INTERNAL_ONS_PACKAGE -> "$ownPackage:ons"
            pkg.startsWith("internal.artemis") -> "$ownPackage:artemis"
            pkg.startsWith("internal.psp") -> "org.ppsspp.ppsspp"
            else -> emulatorPackage.trim()
        }
    }

    private fun isProcessRunning(context: Context, processOrPackage: String): Boolean = try {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        manager.runningAppProcesses.orEmpty().any { process ->
            process.processName == processOrPackage || process.pkgList?.any { it == processOrPackage } == true
        }
    } catch (_: Throwable) {
        // Keep the gate when the platform refuses process visibility rather than permitting a second game.
        true
    }

    private fun readActiveGate(prefs: android.content.SharedPreferences): ActiveGameGate? {
        val sessionId = prefs.getLong(KEY_ACTIVE_SESSION_ID, -1L)
        if (sessionId <= 0L) return null
        return ActiveGameGate(
            sessionId,
            prefs.getLong(KEY_ACTIVE_GAME_ID, -1L),
            prefs.getString(KEY_ACTIVE_GAME_TITLE, "当前游戏") ?: "当前游戏",
            prefs.getString(KEY_ACTIVE_EMULATOR_PACKAGE, "") ?: "",
            prefs.getLong(KEY_ACTIVE_STARTED_AT, 0L),
        )
    }

    private fun writeActiveGate(prefs: android.content.SharedPreferences, active: ActiveGameGate): Boolean =
        prefs.edit()
            .putLong(KEY_ACTIVE_SESSION_ID, active.sessionId)
            .putLong(KEY_ACTIVE_GAME_ID, active.gameId)
            .putString(KEY_ACTIVE_GAME_TITLE, active.gameTitle)
            .putString(KEY_ACTIVE_EMULATOR_PACKAGE, active.emulatorPackage)
            .putLong(KEY_ACTIVE_STARTED_AT, active.startedAt)
            .commit()

    private fun clearActiveGate(prefs: android.content.SharedPreferences) {
        prefs.edit().clear().commit()
    }

    private data class ActiveGameGate(
        val sessionId: Long,
        val gameId: Long,
        val gameTitle: String,
        val emulatorPackage: String,
        val startedAt: Long,
    )

    private data class GateAcquireResult(
        val sessionId: Long = -1L,
        val conflict: ActiveGameGate? = null,
    )

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
        val root = game.rootUri?.trim()
        if (!root.isNullOrEmpty() && root.startsWith("content://")) {
            val readable = try { DocumentFile.fromTreeUri(context, android.net.Uri.parse(root))?.canRead() == true } catch (_: Throwable) { false }
            if (!readable) {
                val message = context.getString(R.string.core_game_directory_permission_lost)
                GameDiagnostics.recordSafPermissionInvalid(context, game, message)
                return message
            }
        }
        if (game.engine == EngineType.GAMEHUB) {
            val ghMode = game.gamehubLaunchMode?.trim()?.lowercase(Locale.ROOT) ?: "game"
            if (ghMode != "program" && ghMode != "normal"
                && game.gamehubLocalGameId.isNullOrBlank()
            ) {
                return context.getString(R.string.core_gamehub_local_id_required)
            }
        }
        if (emulatorPackage.isEmpty()) {
            return context.getString(R.string.core_emulator_package_required)
        }
        if ((emulatorPackage.startsWith("internal.psp") || emulatorPackage == "org.ppsspp.ppsspp")
            && !EmulatorLauncher.isPPSSPPInstalled(context)
        ) {
            return context.getString(R.string.core_ppsspp_required)
        }
        // 外部插件启用状态拦截：模块被禁用时拒绝启动，引导用户去模块兼容页启用。
        if (LauncherModuleBridge.isRpgMakerPluginPackage(emulatorPackage)
            && LauncherModuleBridge.isRpgMakerModuleInstalled(context)
            && !LauncherModuleBridge.isRpgMakerModuleEnabled(context)
        ) {
            return context.getString(R.string.core_rpgm_module_disabled)
        }
        if (LauncherModuleBridge.isRenPyPluginPackage(emulatorPackage)
            && LauncherModuleBridge.isRenPyModuleInstalled(context)
            && !LauncherModuleBridge.isRenPyModuleEnabled(context)
        ) {
            return context.getString(R.string.core_renpy_module_disabled)
        }
        if (LauncherModuleBridge.isGodotPluginPackage(emulatorPackage)
            && LauncherModuleBridge.isGodotModuleInstalled(context)
            && !LauncherModuleBridge.isGodotModuleEnabled(context)
        ) {
            return context.getString(R.string.core_godot_module_disabled)
        }
        return null
    }

    private fun resolveEmulatorPackage(context: Context, game: Game): String {
        val emulatorPackage = game.emulatorPackage?.trim() ?: ""
        if (emulatorPackage.isNotEmpty()) return emulatorPackage
        if (game.engine == EngineType.AUTO) {
            val detected = try {
                LauncherScanBridge.detectEngine(
                    DocumentFile.fromTreeUri(context, android.net.Uri.parse(game.rootUri)), 2
                )
            } catch (_: Throwable) {
                null
            }
            if (detected != null && detected.confidence > 0) {
                return defaultPackageForDetectedEngine(detected.engine, detected.rpgMakerSubtype, detected.renpySubtype, detected.godotSubtype)
            }
            // A root.pfs is an unambiguous Artemis launch target even when SAF enumeration fails.
            if (game.launchTarget?.trim()?.endsWith(".pfs", ignoreCase = true) == true) return "internal.artemis"
        }
        if (emulatorPackage.isEmpty() && game.engine == EngineType.KIRIKIRI) return "internal.krkr"
        if (emulatorPackage.isEmpty() && game.engine == EngineType.ONS) return "internal.ons"
        if (emulatorPackage.isEmpty() && game.engine == EngineType.TYRANO) return "internal.tyrano"
        if (emulatorPackage.isEmpty() && game.engine == EngineType.PSP) return "org.ppsspp.ppsspp"
        if (emulatorPackage.isEmpty() && game.engine == EngineType.NINTENDO_3DS) return "io.github.azaharplus.android"
        if (emulatorPackage.isEmpty() && game.engine == EngineType.NINTENDO_SWITCH) return "dev.eden.eden_emulator"
        if (game.engine == EngineType.ARTEMIS && emulatorPackage.isEmpty()) return "internal.artemis"
        return emulatorPackage
    }

    private fun defaultPackageForDetectedEngine(
        engine: EngineType,
        rpgMakerSubtype: String,
        renpySubtype: String,
        godotSubtype: String,
    ): String = when (engine) {
        EngineType.KIRIKIRI -> "internal.krkr"
        EngineType.ONS -> "internal.ons"
        EngineType.TYRANO -> "internal.tyrano"
        EngineType.ARTEMIS -> "internal.artemis"
        EngineType.PSP -> "org.ppsspp.ppsspp"
        EngineType.NINTENDO_3DS -> "io.github.azaharplus.android"
        EngineType.NINTENDO_SWITCH -> "dev.eden.eden_emulator"
        EngineType.RPGMAKER -> "internal." + rpgMakerSubtype.ifBlank { "rpgmxp" }
        EngineType.RENPY -> "internal." + renpySubtype.ifBlank { "renpy" }
        EngineType.GODOT -> "internal." + godotSubtype.ifBlank { "godot4" }
        else -> ""
    }

    private fun resolveLaunchTarget(game: Game): String? {
        // Preserve an explicit target such as root.pfs.  Artemis/Tyrano still default to the
        // directory when the field is absent, but must not silently discard a user selection.
        if ((game.engine == EngineType.ARTEMIS || game.engine == EngineType.TYRANO)
            && game.launchTarget.isNullOrBlank()) return "[游戏目录]"
        if (game.engine == EngineType.GAMEHUB) {
            return game.title?.trim().takeUnless { it.isNullOrEmpty() }
        }
        return canonicalLaunchTarget(game.launchTarget)
    }

    /**
     * Releases briefly persisted localized directory labels from builds that translated the
     * launchTarget sentinel. This value is protocol data, not UI text.
     */
    private fun canonicalLaunchTarget(target: String?): String? {
        val value = target?.trim() ?: return null
        return if (
            value.equals("DIR", ignoreCase = true)
            || value == "[游戏目录]"
            || value == "[Game folder]"
            || value == "[Game directory]"
            || value == "[ゲームフォルダー]"
            || value == "[ゲームディレクトリ]"
        ) {
            "[游戏目录]"
        } else {
            target
        }
    }

    private fun startGameActivity(context: Context, game: Game, emulatorPackage: String, launchTarget: String?): StartAttempt {
        val pkg = emulatorPackage.trim()
        try {
            if (pkg.startsWith("internal.krkr") || pkg == "org.tvp.kirikiri2.internal") {
                val prefs = context.yukiPrefs()
                val krEngineVersion = prefs.getString(KEY_KR_ENGINE_VERSION, "auto")
                return startActivitySafely(context, EmulatorLauncher.buildInternalKrkrIntent(context, game.rootUri, launchTarget, false, krEngineVersion, false))
            }
            if (pkg.startsWith("internal.tyrano") || pkg == "com.core.tyrano"
                || pkg == LEGACY_INTERNAL_TYRANO_PACKAGE) {
                return startActivitySafely(context, EmulatorLauncher.buildInternalTyranoIntent(context, game.rootUri, launchTarget))
            }
            if (pkg.startsWith("internal.ons") || pkg == "com.core.ons"
                || pkg == LEGACY_INTERNAL_ONS_PACKAGE) {
                return startActivitySafely(context, EmulatorLauncher.buildInternalOnsIntent(context, game.rootUri, launchTarget, game.id))
            }
            if (pkg.startsWith("internal.artemis")) {
                return startActivitySafely(context, EmulatorLauncher.buildInternalArtemisIntent(context, pkg, game.rootUri, launchTarget))
            }
            if (pkg.startsWith("internal.psp") || pkg == "org.ppsspp.ppsspp") {
                if (!EmulatorLauncher.isPPSSPPInstalled(context)) {
                    return StartAttempt.failure("emulator_missing")
                }
                return startActivitySafely(context, EmulatorLauncher.buildInternalPspIntent(
                    context, resolvePspLaunchUri(context, game.rootUri, launchTarget), launchTarget))
            }
            return if (EmulatorLauncher.launchGame(context, pkg, game.rootUri, launchTarget, game.winlatorLaunchMode, game.gamehubLaunchMode, game.gamehubLocalGameId)) {
                StartAttempt.success()
            } else {
                StartAttempt.failure("activity_unavailable_or_rejected")
            }
        } catch (error: Throwable) {
            return StartAttempt.failure("activity_exception", error)
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

    private fun startActivitySafely(context: Context?, intent: Intent?): StartAttempt {
        if (context == null || intent == null) return StartAttempt.failure("invalid_intent")
        return try {
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            StartAttempt.success()
        } catch (error: Throwable) {
            StartAttempt.failure("activity_exception", error)
        }
    }

    private data class StartAttempt(
        val success: Boolean,
        val errorCategory: String? = null,
        val error: Throwable? = null,
    ) {
        companion object {
            fun success() = StartAttempt(true)
            fun failure(category: String, error: Throwable? = null) = StartAttempt(false, category, error)
        }
    }

    private fun resolveLaunchType(emulatorPackage: String?): String {
        val pkg = emulatorPackage?.trim()?.lowercase(Locale.ROOT) ?: ""
        if (pkg.startsWith("internal.krkr") || pkg == "org.tvp.kirikiri2.internal") return "internal.krkr"
        if (pkg.startsWith("internal.ons") || pkg == "com.core.ons" || pkg == LEGACY_INTERNAL_ONS_PACKAGE) return "internal.ons"
        if (pkg.startsWith("internal.tyrano") || pkg == "com.core.tyrano" || pkg == LEGACY_INTERNAL_TYRANO_PACKAGE) return "internal.tyrano"
        if (pkg.startsWith("internal.artemis")) return pkg
        return "external"
    }

    private fun safeTitle(context: Context, game: Game?): String {
        val title = game?.title
        if (title.isNullOrBlank()) return context.getString(R.string.core_untitled_game)
        return title.trim()
    }

    class LaunchResult private constructor(
        @JvmField val success: Boolean,
        @JvmField val sessionId: Long,
        @JvmField val message: String,
        @JvmField val activeGameConflict: Boolean,
        @JvmField val activeGameTitle: String,
    ) {
        companion object {
            @JvmStatic
            fun success(sessionId: Long): LaunchResult =
                LaunchResult(true, sessionId, "", false, "")

            @JvmStatic
            fun failure(message: String?): LaunchResult =
                LaunchResult(false, -1L, if (message.isNullOrBlank()) "启动失败" else message, false, "")

            @JvmStatic
            fun activeGame(
                gameTitle: String?,
                defaultTitle: String = "当前游戏",
                message: String? = null,
            ): LaunchResult {
                val title = gameTitle?.trim().takeUnless { it.isNullOrEmpty() } ?: defaultTitle
                return LaunchResult(
                    false,
                    -1L,
                    message ?: "已有游戏正在运行：$title",
                    true,
                    title,
                )
            }
        }
    }
}
