package com.core.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.core.model.EngineType
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 外部模拟器策略注册、GameHub 协议和通用包启动回退。
 *
 * 职责切分（重构计划 3.5 阶段 98，§8:323 按职责切片）：
 *   - Winlator 启动协议（.desktop/.exe 解析 + fork 契约回退）→ [WinlatorLauncher]
 * 本类保留策略注册表、手持机/内置策略与通用 Kirikiri 回退，@JvmStatic 签名不变。
 */
internal object ExternalGameLaunchers {
    private const val TAG = "EmulatorLauncher"
    private val strategies = CopyOnWriteArrayList<EngineLaunchStrategy>()

    init {
        addBuiltIn(InternalStrategy(EngineType.KIRIKIRI, EnginePackages.INTERNAL_KRKR, EnginePackages.LEGACY_KRKR) {
            KrkrLauncher.buildIntent(it, rootUri, launchTarget)
        })
        addBuiltIn(InternalStrategy(EngineType.TYRANO, EnginePackages.INTERNAL_TYRANO, EnginePackages.LEGACY_TYRANO) {
            ScriptEngineLaunchers.buildTyranoIntent(it, rootUri, launchTarget)
        })
        addBuiltIn(InternalStrategy(EngineType.ONS, EnginePackages.INTERNAL_ONS, EnginePackages.INTERNAL_ONSCRIPTER, EnginePackages.LEGACY_ONS) {
            ScriptEngineLaunchers.buildOnsIntent(it, rootUri, launchTarget)
        })
        addBuiltIn(InternalStrategy(
            EngineType.ARTEMIS,
            EnginePackages.INTERNAL_ARTEMIS, EnginePackages.LEGACY_ARTEMIS, EnginePackages.ARTEMIS_COMPAT,
            EnginePackages.ARTEMIS_COMPATIBLE, EnginePackages.ARTEMIS_COMPAT_V2, EnginePackages.ARTEMIS_COMPATIBLE_V2,
        ) { ArtemisLauncher.buildIntent(it, packageName, rootUri, launchTarget) })
        addBuiltIn(PspStrategy)
        addBuiltIn(CitraStrategy)
        addBuiltIn(EdenStrategy)
        addBuiltIn(GameHubStrategy)
        addBuiltIn(WinlatorStrategy)
        addBuiltIn(ExternalRpgMakerPluginStrategy())
        addBuiltIn(ExternalRenPyPluginStrategy())
        addBuiltIn(ExternalGodotPluginStrategy())
    }

    @JvmStatic
    fun launchGame(
        context: Context?,
        engineType: EngineType?,
        packageName: String?,
        rootUri: String?,
        launchTarget: String?,
        winlatorLaunchMode: String?,
        gameHubLaunchMode: String?,
        gameHubLocalGameId: String?,
    ): Boolean {
        val request = LaunchRequest(
            engineType, packageName, rootUri, launchTarget, winlatorLaunchMode,
            gameHubLaunchMode, gameHubLocalGameId,
        )
        if (context == null || request.packageName.isEmpty()) return false
        strategies.firstOrNull { it.supports(request) }?.let { strategy ->
            return try {
                strategy.launch(context, request)
            } catch (error: Exception) {
                logWarn("Launch strategy failed: ${strategy.javaClass.simpleName}", error)
                false
            }
        }
        return launchGenericKirikiriCompatible(context, request)
    }

    @JvmStatic
    fun registerStrategy(strategy: EngineLaunchStrategy?) {
        if (strategy != null) strategies.add(0, strategy)
    }

    @JvmStatic
    fun registeredEngineTypes(): List<EngineType> {
        val types = mutableListOf<EngineType>()
        strategies.forEach { strategy ->
            (strategy.getEngineType() as EngineType?)?.let { if (it !in types) types.add(it) }
        }
        return Collections.unmodifiableList(types)
    }

    @JvmStatic
    fun launchPackage(context: Context?, packageName: String?): Boolean {
        if (context == null || packageName.isNullOrBlank()) return false
        val pkg = packageName.trim()
        context.packageManager.getLaunchIntentForPackage(pkg)?.let { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return true
        }
        arrayOf(
            "$pkg.MainActivity", "$pkg.AppActivity", "$pkg.TyranoActivity",
            "$pkg.PlayerActivity", "$pkg.activity.MainActivity",
        ).forEach { className ->
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(pkg, className)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return true
            } catch (_: Exception) {
                // Try the next common launcher class.
            }
        }
        return false
    }

    private fun addBuiltIn(strategy: EngineLaunchStrategy) = strategies.add(strategy)

    private abstract class BaseStrategy(private val engineType: EngineType) : EngineLaunchStrategy {
        override fun getEngineType(): EngineType = engineType

        protected fun start(context: Context, intent: Intent): Boolean = try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            // 尽力而为：startActivity 失败时返回 false，由上层尝试其他启动契约
            false
        }
    }

    private class InternalStrategy(
        engineType: EngineType,
        private vararg val aliases: String,
        private val intentBuilder: LaunchRequest.(Context) -> Intent,
    ) : BaseStrategy(engineType) {
        override fun supports(request: LaunchRequest): Boolean =
            aliases.any { it.equals(request.packageName, ignoreCase = true) }

        override fun launch(context: Context, request: LaunchRequest): Boolean =
            start(context, request.intentBuilder(context))
    }

    private object PspStrategy : BaseStrategy(EngineType.PSP) {
        override fun supports(request: LaunchRequest): Boolean =
            request.packageName.equals(EnginePackages.INTERNAL_PSP, true) ||
                request.packageName.equals(EnginePackages.EXTERNAL_PPSSPP, true) ||
                request.packageName.lowercase(Locale.ROOT).contains("ppsspp")

        override fun launch(context: Context, request: LaunchRequest): Boolean {
            if (!HandheldLaunchers.isPpssppInstalled(context)) return false
            return start(context, HandheldLaunchers.buildPspIntent(context, request.rootUri, request.launchTarget))
        }
    }

    private object CitraStrategy : BaseStrategy(EngineType.NINTENDO_3DS) {
        override fun supports(request: LaunchRequest): Boolean {
            val pkg = request.packageName.lowercase(Locale.ROOT)
            return pkg == EnginePackages.INTERNAL_CITRA || pkg == EnginePackages.EXTERNAL_AZAHAR ||
                pkg == "org.citra.citra_emu" || pkg == "org.azahar_emu.azahar" ||
                "lime3ds" in pkg || "citra" in pkg || "azahar" in pkg
        }

        override fun launch(context: Context, request: LaunchRequest): Boolean {
            if (!HandheldLaunchers.isCitraInstalled(context)) return false
            return start(context, HandheldLaunchers.buildCitraIntent(
                context, resolveSelectedDocumentUri(context, request.rootUri, request.launchTarget), request.launchTarget,
            ))
        }
    }

    private object EdenStrategy : BaseStrategy(EngineType.NINTENDO_SWITCH) {
        override fun supports(request: LaunchRequest): Boolean =
            request.packageName.equals(EnginePackages.EXTERNAL_EDEN, true)

        override fun launch(context: Context, request: LaunchRequest): Boolean {
            if (!HandheldLaunchers.isEdenInstalled(context)) return false
            return start(context, HandheldLaunchers.buildEdenIntent(
                context, resolveSelectedDocumentUri(context, request.rootUri, request.launchTarget), request.launchTarget,
            ))
        }
    }

    /** A scanner result can be either the ROM itself or a SAF tree plus a relative ROM target. */
    private fun resolveSelectedDocumentUri(context: Context, rootUri: String?, launchTarget: String?): String? {
        if (rootUri.isNullOrBlank() || launchTarget.isNullOrBlank() || launchTarget == "[游戏目录]") return rootUri
        return try {
            var current = DocumentFile.fromTreeUri(context, Uri.parse(rootUri))
            launchTarget.split('/').filter(String::isNotEmpty).forEach { current = current?.findFile(it) }
            current?.takeIf { it.isFile }?.uri?.toString() ?: rootUri
        } catch (_: Exception) {
            // 尽力而为：SAF 解析失败时回退根 URI
            rootUri
        }
    }

    private object GameHubStrategy : BaseStrategy(EngineType.GAMEHUB) {
        override fun supports(request: LaunchRequest): Boolean = isGameHubPackage(request.packageName)

        override fun launch(context: Context, request: LaunchRequest): Boolean {
            val mode = request.gameHubLaunchMode?.trim()?.lowercase(Locale.ROOT) ?: "game"
            if (mode == "program" || mode == "normal") return launchPackage(context, request.packageName)
            val gameId = request.gameHubLocalGameId?.trim()?.takeIf(String::isNotEmpty) ?: return false
            val appName = guessGameHubAppName(request.launchTarget)
            if (start(context, buildGameHubIntent(request.packageName, gameId, appName, detail = true))) return true
            return start(context, buildGameHubIntent(request.packageName, gameId, appName, detail = false))
        }
    }

    private object WinlatorStrategy : BaseStrategy(EngineType.WINLATOR) {
        override fun supports(request: LaunchRequest): Boolean =
            WinlatorLauncher.isWinlatorPackage(request.packageName) && WinlatorLauncher.isWinlatorTarget(request.launchTarget)

        override fun launch(context: Context, request: LaunchRequest): Boolean =
            WinlatorLauncher.launch(context, request)
    }

    // ── Winlator 解析委托（保留 @JvmStatic 签名，ExternalGameLaunchersTest 零变更）──

    @JvmStatic
    fun resolveWinlatorExecPath(desktopPath: String?, pkg: String?): String? =
        WinlatorLauncher.resolveWinlatorExecPath(desktopPath, pkg)

    @JvmStatic
    fun extractDesktopExecutable(exec: String?): String? =
        WinlatorLauncher.extractDesktopExecutable(exec)

    @JvmStatic
    fun parseWinlatorContainerId(desktopPath: String?): Int =
        WinlatorLauncher.parseWinlatorContainerId(desktopPath)

    private fun launchGenericKirikiriCompatible(context: Context, request: LaunchRequest): Boolean {
        if (!request.rootUri.isNullOrBlank()) {
            buildKirikiriLaunchUris(context, request.rootUri, request.launchTarget).forEach { uri ->
                buildLaunchIntents(request.packageName, uri, request.rootUri, request.launchTarget).forEach { intent ->
                    intent.addFlags(engineIntentFlags())
                    try {
                        context.startActivity(intent)
                        return true
                    } catch (_: Exception) {
                        // Try the next known external contract.
                    }
                }
            }
        }
        return launchPackage(context, request.packageName)
    }

    private fun buildLaunchIntents(pkg: String, uri: Uri, rootUri: String?, launchTarget: String?): List<Intent> {
        val uriText = uri.toString()
        val rootText = rootUri ?: uriText
        val target = launchTarget.orEmpty()
        if (pkg == "com.akira.tyranoemu") {
            val name = guessName(target, rootText)
            fun tyranoExtras(intent: Intent) = intent
                .putExtra("path", uriText).putExtra("uri", uriText).putExtra("projectRoot", rootText)
                .putExtra("launchFile", target).putExtra("filename", target).putExtra("game", uriText)
                .putExtra("gamedir", rootText).putExtra("gamename", name).putExtra("gametitle", name)
                .putExtra("gameargs", target)
            return listOf(
                tyranoExtras(explicit(pkg, "com.akira.tyranoemu.remote.WebActivity", "android.intent.action.WebGame", uri)),
                tyranoExtras(explicit(pkg, "com.akira.tyranoemu.app.TyActivity", Intent.ACTION_MAIN, uri)),
                Intent(Intent.ACTION_VIEW).setPackage(pkg).setDataAndType(uri, "text/html")
                    .putExtra("path", uriText).putExtra("projectRoot", rootText)
                    .putExtra("launchFile", target).putExtra("gameargs", target),
                Intent(Intent.ACTION_VIEW).setPackage(pkg).setData(uri)
                    .putExtra("path", uriText).putExtra("projectRoot", rootText)
                    .putExtra("launchFile", target).putExtra("gameargs", target),
            )
        }
        return listOf(
            Intent(Intent.ACTION_VIEW).setPackage(pkg).setDataAndType(uri, "application/x-kirikiri"),
            Intent(Intent.ACTION_VIEW).setPackage(pkg).setDataAndType(uri, "application/octet-stream"),
            Intent(Intent.ACTION_VIEW).setPackage(pkg).setDataAndType(uri, "resource/folder"),
            Intent(Intent.ACTION_VIEW).setPackage(pkg).setDataAndType(uri, "inode/directory"),
            Intent(Intent.ACTION_VIEW).setPackage(pkg).setDataAndType(uri, "application/x-directory"),
            Intent(Intent.ACTION_VIEW).setPackage(pkg).setData(uri),
            Intent(Intent.ACTION_MAIN).setPackage(pkg)
                .putExtra("path", uriText).putExtra("uri", uriText).putExtra("game", uriText)
                .putExtra("startup", uriText).putExtra("projectRoot", rootText).putExtra("launchFile", target),
        )
    }

    private fun buildKirikiriLaunchUris(context: Context, rootUri: String, launchTarget: String?): List<Uri> {
        val output = mutableListOf<Uri>()
        val root = Uri.parse(rootUri)
        val directory = DocumentFile.fromTreeUri(context, root)
        val target = launchTarget?.takeIf(String::isNotEmpty) ?: "data.xp3"
        if (directory?.isDirectory == true) {
            when {
                target == "[游戏目录]" || target.equals("DIR", true) -> output += root
                target.equals("XP3_FIRST", true) -> directory.listFiles().firstOrNull {
                    it?.isFile == true && it.name?.lowercase(Locale.ROOT)?.endsWith(".xp3") == true
                }?.uri?.let(output::add)
                else -> directory.findFile(target)?.takeIf { it.exists() && it.isFile }?.uri?.let(output::add)
            }
        }
        if (root !in output) output += root
        return output
    }

    private fun buildGameHubIntent(
        pkg: String,
        localGameId: String,
        appName: String,
        detail: Boolean,
    ): Intent {
        val storedId = localGameId.trim()
        val isSteam = storedId.lowercase(Locale.ROOT).startsWith("steam:")
        val steamAppId = if (isSteam) storedId.substring("steam:".length).trim() else ""
        val realLocalGameId = if (isSteam) "" else storedId
        val className = if (detail) {
            "com.xj.landscape.launcher.ui.gamedetail.GameDetailActivity"
        } else {
            "com.xj.app.DeepLinkRouterActivity"
        }
        return Intent(Intent.ACTION_VIEW).setPackage(pkg).setClassName(pkg, className).apply {
            putExtra("gameType", 0)
            putExtra("steamAppId", steamAppId)
            putExtra("id", 0)
            putExtra("type", 1)
            putExtra("localMobileAppId", "")
            putExtra("localGameId", realLocalGameId)
            putExtra("autoStartGame", true)
            putExtra("localPkg", "")
            putExtra("localAppName", appName.trim().ifEmpty { storedId })
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }

    private fun explicit(pkg: String, className: String, action: String, uri: Uri?): Intent =
        Intent(action).setClassName(pkg, className).apply { if (uri != null) data = uri }

    private fun guessName(target: String?, rootText: String?): String {
        if (!target.isNullOrBlank() && target != "[游戏目录]") return target
        if (rootText.isNullOrEmpty()) return "YukiHubGame"
        val slash = maxOf(rootText.lastIndexOf('/'), rootText.lastIndexOf('%'))
        return if (slash >= 0 && slash + 1 < rootText.length) rootText.substring(slash + 1) else "YukiHubGame"
    }

    private fun guessGameHubAppName(target: String?): String =
        target?.trim()?.takeUnless { it.isEmpty() || it.startsWith('[') }.orEmpty()

    private fun isGameHubPackage(pkg: String?): Boolean =
        pkg?.trim()?.lowercase(Locale.ROOT) in setOf(EnginePackages.EXTERNAL_GAMEHUB, EnginePackages.EXTERNAL_GAMEHUB_LEGACY)

    private fun engineIntentFlags(): Int = Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

    private fun logWarn(message: String, error: Throwable) {
        runCatching { Log.w(TAG, message, error) }
    }
}
