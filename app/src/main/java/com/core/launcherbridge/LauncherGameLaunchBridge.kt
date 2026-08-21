package com.core.launcherbridge

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.documentfile.provider.DocumentFile
import com.core.R
import com.core.diagnostics.GameDiagnostics
import com.core.data.GameRepository
import com.core.launcher.ArtemisLauncher
import com.core.launcher.ArtemisPfsUnpacker
import com.core.launcher.EmulatorLauncher
import com.core.launcher.EnginePackages
import com.core.launcher.KrkrLauncher
import com.core.launcher.ScriptEngineLaunchers
import com.core.model.EngineType
import com.core.model.Game
import com.core.util.AppExecutors
import java.util.Locale

/**
 * 游戏启动桥接：数据库准备、SAF 校验、模拟器分发。
 */
object LauncherGameLaunchBridge {

    private const val LAUNCH_GATE_PREFS = "launcher_active_game_gate"
    private const val KEY_ACTIVE_SESSION_ID = "active_session_id"
    private const val KEY_ACTIVE_GAME_ID = "active_game_id"
    private const val KEY_ACTIVE_GAME_TITLE = "active_game_title"
    private const val KEY_ACTIVE_EMULATOR_PACKAGE = "active_emulator_package"
    private const val KEY_ACTIVE_STARTED_AT = "active_started_at"
    private const val ACTIVE_PROCESS_GRACE_MS = 5_000L
    private const val MAX_PLAY_SESSION_MS = 12L * 60L * 60L * 1000L
    private const val MAX_RECOVERED_PLAY_SESSION_MS = 30L * 60L * 1000L
    private val launchGateLock = Any()

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
        val message = attempt.userMessage ?: context.getString(R.string.core_emulator_launch_failed)
        GameDiagnostics.recordLaunch(appContext, game, false, message, launchTarget, attempt.errorCategory, attempt.error)
        return LaunchResult.failure(message, attempt.errorCategory)
    }

    /**
     * 按单调时钟累计的有效时长结算会话；结束时刻仍使用墙上时间供历史排序与展示。
     */
    @JvmStatic
    fun finishSessionWithDuration(
        context: Context?,
        sessionId: Long,
        effectiveDuration: Long,
        minDuration: Long,
        maxDuration: Long,
    ) {
        if (context == null || sessionId <= 0L) return
        val appContext = context.applicationContext
        GameRepository(appContext).finishPlaySession(
            sessionId,
            System.currentTimeMillis(),
            effectiveDuration,
            minDuration,
            maxDuration,
        )
        releaseLaunchGate(appContext, sessionId)
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
            repository.finishPlaySession(active.sessionId, System.currentTimeMillis(), 0L, MAX_RECOVERED_PLAY_SESSION_MS)
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
        // Artemis 三个引擎版本各自独立进程（:artemis / :artemis.compat / :artemis.compat.v2），
        // 任一存活都视为游戏仍在运行。
        val targets = activeProcessNames(context, active.emulatorPackage)
        return targets.any { isProcessRunning(context, it) }
    }

    private fun activeProcessNames(context: Context, emulatorPackage: String): List<String> {
        val pkg = emulatorPackage.trim().lowercase(Locale.ROOT)
        val ownPackage = context.packageName
        return when {
            EnginePackages.isInternalArtemis(pkg) ->
                listOf("$ownPackage:artemis", "$ownPackage:artemis.compat", "$ownPackage:artemis.compat.v2")
            EnginePackages.isInternalKrkr(pkg) -> listOf("$ownPackage:kirikiri2", "$ownPackage:krkrsdl3")
            EnginePackages.isInternalTyrano(pkg) -> listOf("$ownPackage:tyrano")
            EnginePackages.isInternalOns(pkg) -> listOf("$ownPackage:ons")
            pkg.startsWith(EnginePackages.INTERNAL_PSP) -> listOf(EnginePackages.EXTERNAL_PPSSPP)
            else -> listOf(emulatorPackage.trim())
        }
    }

    private fun isProcessRunning(context: Context, processOrPackage: String): Boolean = try {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        manager.runningAppProcesses.orEmpty().any { process ->
            process.processName == processOrPackage || process.pkgList?.any { it == processOrPackage } == true
        }
    } catch (_: Exception) {
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
     * origin 模式不注入字体偏好：引擎原生界面直接读写 XML 偏好（含我们注入并标记的
     * 字体键）；启动游戏时 applyFontPreferences 的 marker 归属机制会保护用户在引擎
     * 内手动改过的值不被清除。
     *
     * @return 可用于 [Activity.startActivity] 的 Intent；上下文无效时返回 null
     */
    @JvmStatic
    fun buildInternalKrkrOriginIntent(context: Context?): Intent? {
        if (context == null) return null
        if (LauncherModuleBridge.kirikiroid2ReadyCode(context) != "ready") return null
        return EmulatorLauncher.buildInternalKrkrIntent(context, "", "", true)
    }

    private fun validate(context: Context, game: Game, emulatorPackage: String): String? {
        val root = game.rootUri?.trim()
        if (!root.isNullOrEmpty() && root.startsWith("content://")) {
            val readable = try { DocumentFile.fromTreeUri(context, android.net.Uri.parse(root))?.canRead() == true } catch (_: Exception) { /* 尽力而为 */ false }
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
        if ((emulatorPackage.startsWith(EnginePackages.INTERNAL_PSP) || emulatorPackage == EnginePackages.EXTERNAL_PPSSPP)
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
        if (EnginePackages.isInternalKrkr(emulatorPackage)) {
            val krEngineKernel = LauncherKrkrGameSettingsBridge.resolveEngineKernel(context, game.id)
            if (krEngineKernel != LauncherKrkrBridge.KERNEL_KRKRSDL3) {
                when (LauncherModuleBridge.kirikiroid2ReadyCode(context)) {
                    "ready" -> Unit
                    "disabled" -> return context.getString(R.string.core_kirikiroid2_module_disabled)
                    "invalid" -> return context.getString(R.string.core_kirikiroid2_module_invalid)
                    else -> return context.getString(R.string.core_kirikiroid2_module_required)
                }
            }
        }
        if (EnginePackages.isInternalOns(emulatorPackage)) {
            when (LauncherModuleBridge.onsReadyCode(context)) {
                "ready" -> Unit
                "disabled" -> return context.getString(R.string.core_ons_module_disabled)
                "invalid" -> return context.getString(R.string.core_ons_module_invalid)
                else -> return context.getString(R.string.core_ons_module_required)
            }
        }
        if (EnginePackages.isInternalArtemis(emulatorPackage)) {
            when (LauncherModuleBridge.artemisReadyCode(context)) {
                "ready" -> Unit
                "disabled" -> return context.getString(R.string.core_artemis_module_disabled)
                "invalid" -> return context.getString(R.string.core_artemis_module_invalid)
                else -> return context.getString(R.string.core_artemis_module_required)
            }
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
            } catch (_: Exception) {
                // 引擎探测失败时回退到默认包名
                null
            }
            if (detected != null && detected.confidence > 0) {
                return defaultPackageForDetectedEngine(detected.engine, detected.rpgMakerSubtype, detected.renpySubtype, detected.godotSubtype)
            }
            // A root.pfs is an unambiguous Artemis launch target even when SAF enumeration fails.
            if (game.launchTarget?.trim()?.endsWith(".pfs", ignoreCase = true) == true) return EnginePackages.INTERNAL_ARTEMIS
        }
        if (emulatorPackage.isEmpty() && game.engine == EngineType.KIRIKIRI) return EnginePackages.INTERNAL_KRKR
        if (emulatorPackage.isEmpty() && game.engine == EngineType.ONS) return EnginePackages.INTERNAL_ONS
        if (emulatorPackage.isEmpty() && game.engine == EngineType.TYRANO) return EnginePackages.INTERNAL_TYRANO
        if (emulatorPackage.isEmpty() && game.engine == EngineType.PSP) return EnginePackages.EXTERNAL_PPSSPP
        if (emulatorPackage.isEmpty() && game.engine == EngineType.NINTENDO_3DS) return EnginePackages.EXTERNAL_AZAHAR
        if (emulatorPackage.isEmpty() && game.engine == EngineType.NINTENDO_SWITCH) return EnginePackages.EXTERNAL_EDEN
        if (emulatorPackage.isEmpty() && game.engine == EngineType.ARMSX3) return EnginePackages.EXTERNAL_ARMSX3
        if (game.engine == EngineType.ARTEMIS && emulatorPackage.isEmpty()) return EnginePackages.INTERNAL_ARTEMIS
        return emulatorPackage
    }

    private fun defaultPackageForDetectedEngine(
        engine: EngineType,
        rpgMakerSubtype: String,
        renpySubtype: String,
        godotSubtype: String,
    ): String = when (engine) {
        EngineType.KIRIKIRI -> EnginePackages.INTERNAL_KRKR
        EngineType.ONS -> EnginePackages.INTERNAL_ONS
        EngineType.TYRANO -> EnginePackages.INTERNAL_TYRANO
        EngineType.ARTEMIS -> EnginePackages.INTERNAL_ARTEMIS
        EngineType.PSP -> EnginePackages.EXTERNAL_PPSSPP
        EngineType.NINTENDO_3DS -> EnginePackages.EXTERNAL_AZAHAR
        EngineType.NINTENDO_SWITCH -> EnginePackages.EXTERNAL_EDEN
        EngineType.ARMSX3 -> EnginePackages.EXTERNAL_ARMSX3
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
            if (EnginePackages.isInternalKrkr(pkg)) {
                // 内核决策：per-game 覆盖优先，无覆盖时回退全局。一次 load 取整个覆盖
                // 快照（engineVersion/scopedSaveDir/kernel 是叠加后的生效值，字体两键是
                // 覆盖状态 null=跟随全局），避免同一 gameId 重复解析 JSON。
                val krkr = LauncherKrkrGameSettingsBridge.load(context, game.id)
                if (krkr.engineKernel == LauncherKrkrBridge.KERNEL_KRKRSDL3) {
                    return startActivitySafely(
                        context,
                        EmulatorLauncher.buildKrkrsdl3Intent(
                            context, game.rootUri, launchTarget, krkr.scopedSaveDir,
                        ),
                    )
                }
                // Kirikiroid2 路由（内核=auto/kirikiri2 时走原有逻辑）。
                // krkr2 字体偏好：两键作用域独立判定（有覆盖→写游戏目录
                // Kirikiroid2Preference.xml，无覆盖→写全局 GlobalPreference.xml
                // 并清游戏目录残留键，保证跟随全局）。
                val defaultFont = krkr.defaultFont ?: LauncherKrkrBridge.getDefaultFont(context)
                val forceDefaultFont = krkr.forceDefaultFont
                    ?: LauncherKrkrBridge.isForceDefaultFont(context)
                val fontScopeDefault = if (krkr.defaultFont != null) "game" else "global"
                val fontScopeForce = if (krkr.forceDefaultFont != null) "game" else "global"
                // 渲染/内存引擎偏好：从同一快照组装 JSON（覆盖优先、空值跟随全局、按键独立作用域）。
                val enginePrefs = LauncherKrkrBridge.buildEnginePrefsJson(context) { key ->
                    LauncherKrkrGameSettingsBridge.enginePrefOverride(krkr, key)
                }
                return startActivitySafely(
                    context,
                    EmulatorLauncher.buildInternalKrkrIntent(
                        context, game.rootUri, launchTarget, false, krkr.engineVersion, false,
                        krkr.scopedSaveDir, defaultFont, forceDefaultFont,
                        fontScopeDefault, fontScopeForce, enginePrefs,
                    ),
                )
            }
            if (EnginePackages.isInternalTyrano(pkg)) {
                return startActivitySafely(context, EmulatorLauncher.buildInternalTyranoIntent(context, game.rootUri, launchTarget))
            }
            if (EnginePackages.isInternalOns(pkg)) {
                return startActivitySafely(context, EmulatorLauncher.buildInternalOnsIntent(context, game.rootUri, launchTarget, game.id))
            }
            if (EnginePackages.isInternalArtemis(pkg)) {
                // 应用级/游戏级 Artemis 设置：引擎版本（确定性选择，替代纯试错）+ 画面反转 + 基础补丁策略。
                val settings = LauncherArtemisGameSettingsBridge.load(context, game.id)
                applyArtemisBasePatchIfNeeded(game, settings.autoPatch)
                return startActivitySafely(
                    context,
                    EmulatorLauncher.buildInternalArtemisIntent(
                        context, pkg, game.rootUri, launchTarget,
                        settings.engineVersion, settings.rotateScreen,
                    ),
                )
            }
            if (pkg.startsWith(EnginePackages.INTERNAL_PSP) || pkg == EnginePackages.EXTERNAL_PPSSPP) {
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
        } catch (error: KrkrLauncher.MissingSaveDataDirectoryException) {
            return StartAttempt.failure("krkr_savedata_missing", error, error.message)
        } catch (error: Exception) {
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
        } catch (_: Exception) {
            // SAF 遍历失败时回退到根目录 URI
        }
        return rootUri
    }

    /**
     * Artemis 基础补丁：目录缺少 system.ini 且存在 .pfs 封包时，按策略解包引擎必需文件。
     * off 跳过；auto 静默解包；ask 交由 app 层确认后调用 [applyArtemisBasePatch]
     * （core 层不依赖 app 的 LauncherDialogFactory，见 com_apps_refactor_plan.md 注意事项）。
     */
    private fun applyArtemisBasePatchIfNeeded(game: Game, strategy: String) {
        if (LauncherArtemisGameSettingsBridge.AUTO_PATCH_ASK == strategy) return
        if (LauncherArtemisGameSettingsBridge.AUTO_PATCH_OFF == strategy) return
        val rootPath = artemisRootPath(game) ?: return
        if (!ArtemisPfsUnpacker.needsBasePatch(rootPath)) return
        ArtemisPfsUnpacker.applyBasePatch(rootPath)
    }

    /** 解析 Artemis 游戏数据根目录（去 file:// 前缀）；非法或 content:// 返回 null。 */
    private fun artemisRootPath(game: Game?): String? {
        if (game == null) return null
        val rootPath = ScriptEngineLaunchers.stripFileScheme(
            ArtemisLauncher.resolveGamePath(game.rootUri, game.launchTarget),
        )
        if (rootPath.isNullOrBlank() || rootPath.startsWith("content://")) return null
        return rootPath
    }

    /**
     * 该游戏是否需要「启动前询问」基础补丁：最终生效策略为 ask，且目录缺 system.ini 且存在 .pfs。
     * 供 app 层在启动前弹出确认对话框（应在 IO 线程调用，避免主线程目录遍历）。
     */
    @JvmStatic
    fun needsArtemisBasePatchConfirmation(context: Context?, game: Game?): Boolean {
        if (context == null || game == null) return false
        if (LauncherArtemisGameSettingsBridge.AUTO_PATCH_ASK !=
            LauncherArtemisGameSettingsBridge.resolveAutoPatch(context, game.id)
        ) {
            return false
        }
        val rootPath = artemisRootPath(game) ?: return false
        return ArtemisPfsUnpacker.needsBasePatch(rootPath)
    }

    /**
     * 立即应用 Artemis 基础补丁（幂等）。由 app 层在用户确认、或找不到 Activity 宿主时调用。
     * 失败不抛异常，不阻塞后续启动。
     */
    @JvmStatic
    fun applyArtemisBasePatch(game: Game?): Boolean {
        val rootPath = artemisRootPath(game) ?: return false
        return ArtemisPfsUnpacker.applyBasePatch(rootPath)
    }

    private fun startActivitySafely(context: Context?, intent: Intent?): StartAttempt {
        if (context == null || intent == null) return StartAttempt.failure("invalid_intent")
        return try {
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            StartAttempt.success()
        } catch (error: Exception) {
            StartAttempt.failure("activity_exception", error)
        }
    }

    private data class StartAttempt(
        val success: Boolean,
        val errorCategory: String? = null,
        val error: Throwable? = null,
        val userMessage: String? = null,
    ) {
        companion object {
            fun success() = StartAttempt(true)
            fun failure(category: String, error: Throwable? = null, userMessage: String? = null) =
                StartAttempt(false, category, error, userMessage)
        }
    }

    private fun resolveLaunchType(emulatorPackage: String?): String {
        val pkg = emulatorPackage?.trim()?.lowercase(Locale.ROOT) ?: ""
        if (EnginePackages.isInternalKrkr(pkg)) return EnginePackages.INTERNAL_KRKR
        if (EnginePackages.isInternalOns(pkg)) return EnginePackages.INTERNAL_ONS
        if (EnginePackages.isInternalTyrano(pkg)) return EnginePackages.INTERNAL_TYRANO
        if (EnginePackages.isInternalArtemis(pkg)) return pkg
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
        @JvmField val errorCategory: String,
    ) {
        companion object {
            @JvmStatic
            fun success(sessionId: Long): LaunchResult =
                LaunchResult(true, sessionId, "", false, "", "")

            @JvmStatic
            fun failure(message: String?): LaunchResult =
                failure(message, "")

            @JvmStatic
            fun failure(message: String?, errorCategory: String?): LaunchResult =
                LaunchResult(
                    false,
                    -1L,
                    if (message.isNullOrBlank()) "启动失败" else message,
                    false,
                    "",
                    errorCategory.orEmpty(),
                )

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
                    "",
                )
            }
        }
    }
}
