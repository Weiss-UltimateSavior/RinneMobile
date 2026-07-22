package com.yuki.yukihub.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.yuki.yukihub.model.EngineType
import java.io.File
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** 外部模拟器策略注册、GameHub/Winlator 协议和通用包启动回退。 */
internal object ExternalGameLaunchers {
    private const val TAG = "EmulatorLauncher"
    private val strategies = CopyOnWriteArrayList<EngineLaunchStrategy>()

    init {
        addBuiltIn(InternalStrategy(EngineType.KIRIKIRI, "internal.krkr", "org.tvp.kirikiri2.internal") {
            KrkrLauncher.buildIntent(it, rootUri, launchTarget)
        })
        addBuiltIn(InternalStrategy(EngineType.TYRANO, "internal.tyrano", "com.yuki.yukihub.tyrano") {
            ScriptEngineLaunchers.buildTyranoIntent(it, rootUri, launchTarget)
        })
        addBuiltIn(InternalStrategy(EngineType.ONS, "internal.ons", "internal.onscripter", "com.yuki.yukihub.ons") {
            ScriptEngineLaunchers.buildOnsIntent(it, rootUri, launchTarget)
        })
        addBuiltIn(InternalStrategy(
            EngineType.ARTEMIS,
            "internal.artemis", "com.yuki.yukihub.artemis", "internal.artemis.compat",
            "internal.artemis.compatible", "internal.artemis.compat.v2", "internal.artemis.compatible.v2",
        ) { ArtemisLauncher.buildIntent(it, packageName, rootUri, launchTarget) })
        addBuiltIn(PspStrategy)
        addBuiltIn(CitraStrategy)
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
            request.packageName.equals("internal.psp", true) ||
                request.packageName.equals("org.ppsspp.ppsspp", true) ||
                request.packageName.lowercase(Locale.ROOT).contains("ppsspp")

        override fun launch(context: Context, request: LaunchRequest): Boolean {
            if (!HandheldLaunchers.isPpssppInstalled(context)) return false
            return start(context, HandheldLaunchers.buildPspIntent(context, request.rootUri, request.launchTarget))
        }
    }

    private object CitraStrategy : BaseStrategy(EngineType.NINTENDO_3DS) {
        override fun supports(request: LaunchRequest): Boolean {
            val pkg = request.packageName.lowercase(Locale.ROOT)
            return pkg == "internal.citra" || pkg == "io.github.azaharplus.android" ||
                pkg == "org.citra.citra_emu" || pkg == "org.azahar_emu.azahar" ||
                "lime3ds" in pkg || "citra" in pkg || "azahar" in pkg
        }

        override fun launch(context: Context, request: LaunchRequest): Boolean {
            if (!HandheldLaunchers.isCitraInstalled(context)) return false
            return start(context, HandheldLaunchers.buildCitraIntent(context, request.rootUri, request.launchTarget))
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
            isWinlatorPackage(request.packageName) && isWinlatorTarget(request.launchTarget)

        override fun launch(context: Context, request: LaunchRequest): Boolean = launchWinlator(
            context, request.packageName, request.rootUri, request.launchTarget, request.winlatorLaunchMode,
        )
    }

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

    private fun launchWinlator(
        context: Context,
        pkg: String,
        rootUri: String?,
        launchTarget: String?,
        mode: String?,
    ): Boolean {
        val desktopPath = resolveDesktopPath(context, rootUri, launchTarget)?.takeIf(String::isNotBlank)
            ?: return false
        var containerId = parseWinlatorContainerId(desktopPath)
        val execPath = resolveWinlatorExecPath(desktopPath, pkg)
        if (containerId <= 0 && isWinlatorPackage(pkg)) containerId = 1
        val launchMode = mode?.trim()?.lowercase(Locale.ROOT) ?: "game"
        if (launchMode == "program" || launchMode == "normal") return launchPackage(context, pkg)
        val intents = mutableListOf<Intent>()
        arrayOf("XServerDisplayActivity", "XrActivity").forEach { simpleName ->
            arrayOf("$pkg.$simpleName", "$pkg.activities.$simpleName").forEach { className ->
                intents += addWinlatorExtras(explicit(pkg, className, Intent.ACTION_MAIN, null), desktopPath, execPath, containerId)
            }
        }
        intents += addWinlatorExtras(
            Intent(Intent.ACTION_VIEW).setPackage(pkg)
                .setDataAndType(Uri.fromFile(File(desktopPath)), "application/x-desktop"),
            desktopPath,
            null,
            containerId,
        )
        context.packageManager.getLaunchIntentForPackage(pkg)?.let {
            intents += addWinlatorExtras(it, desktopPath, null, containerId)
        }
        intents += addWinlatorExtras(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg),
            desktopPath, null, containerId,
        )
        intents += addWinlatorExtras(explicit(pkg, "$pkg.MainActivity", Intent.ACTION_MAIN, null)
            .addCategory(Intent.CATEGORY_LAUNCHER), desktopPath, null, containerId)
        intents += addWinlatorExtras(explicit(pkg, "$pkg.activities.MainActivity", Intent.ACTION_MAIN, null)
            .addCategory(Intent.CATEGORY_LAUNCHER), desktopPath, null, containerId)
        intents.forEach { intent ->
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            try {
                context.startActivity(intent)
                return true
            } catch (_: Throwable) {
                // Try the next Winlator fork contract.
            }
        }
        return false
    }

    private fun addWinlatorExtras(
        intent: Intent,
        desktopPath: String,
        execPath: String?,
        containerId: Int,
    ): Intent {
        if (containerId > 0) intent.putExtra("container_id", containerId)
        intent.putExtra("shortcut_path", desktopPath)
        intent.putExtra("desktop_path", desktopPath)
        intent.putExtra("path", desktopPath)
        intent.putExtra("file", desktopPath)
        intent.putExtra("rom", desktopPath)
        if (!execPath.isNullOrBlank()) {
            intent.putExtra("exec_path", execPath)
            intent.putExtra("path", execPath)
            dirname(execPath)?.let { intent.putExtra("start_path", it) }
        }
        return intent
    }

    @JvmStatic
    fun resolveWinlatorExecPath(desktopPath: String?, pkg: String?): String? {
        return try {
            val file = desktopPath?.let(::File)?.takeIf(File::isFile) ?: return null
            var exec: String? = null
            var workingPath: String? = null
            file.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val text = line.trim()
                    if (text.startsWith("Exec=")) exec = text.substring(5).trim()
                    else if (text.startsWith("Path=")) workingPath = text.substring(5).trim()
                }
            }
            var executable = extractDesktopExecutable(exec) ?: return null
            executable = executable.replace('\\', '/')
            if (Regex("^[A-Za-z]:/.*").matches(executable)) {
                if (!workingPath.isNullOrBlank()) {
                    val fileName = executable.substringAfterLast('/')
                    val unixPath = workingPath!!.replace('\\', '/')
                    return unixPath + if (unixPath.endsWith('/')) fileName else "/$fileName"
                }
                val drive = executable[0].lowercaseChar()
                val packageForPath = pkg?.trim()?.takeIf(String::isNotEmpty) ?: "com.winlator"
                return "/data/user/0/$packageForPath/files/rootfs/home/xuser/.wine/dosdevices/$drive:${executable.substring(2)}"
            }
            executable
        } catch (_: Throwable) {
            null
        }
    }

    @JvmStatic
    fun extractDesktopExecutable(exec: String?): String? {
        var value = exec?.trim() ?: return null
        val wineIndex = value.lowercase(Locale.ROOT).lastIndexOf("wine ")
        if (wineIndex >= 0) value = value.substring(wineIndex + 5).trim()
        if (value.startsWith('"')) {
            val end = value.indexOf('"', 1)
            if (end > 1) return value.substring(1, end)
        }
        val exeIndex = value.lowercase(Locale.ROOT).indexOf(".exe")
        return if (exeIndex >= 0) value.substring(0, exeIndex + 4).trim() else value
    }

    @JvmStatic
    fun parseWinlatorContainerId(desktopPath: String?): Int {
        desktopPath ?: return 0
        val first = desktopPath.indexOf("/xuser-")
        val markerStart = if (first >= 0) first + 7 else {
            val fallback = desktopPath.indexOf("xuser-")
            if (fallback < 0) return 0 else fallback + 6
        }
        val digits = desktopPath.substring(markerStart).takeWhile(Char::isDigit)
        return digits.toIntOrNull() ?: 0
    }

    private fun resolveDesktopPath(context: Context, rootUri: String?, launchTarget: String?): String? {
        val target = launchTarget?.trim().orEmpty()
        if (target.startsWith('/') || target.startsWith("file://")) {
            return ScriptEngineLaunchers.stripFileScheme(target)
        }
        val rootPath = ScriptEngineLaunchers.uriToFilePath(rootUri)
        if (rootPath.isNullOrBlank()) return target
        if (rootPath.lowercase(Locale.ROOT).endsWith(".desktop")) {
            return ScriptEngineLaunchers.stripFileScheme(rootPath)
        }
        if (rootPath.startsWith("content://")) {
            try {
                var current = rootUri?.let { DocumentFile.fromTreeUri(context, Uri.parse(it)) }
                target.split('/').filter(String::isNotEmpty).forEach { current = current?.findFile(it) }
                current?.uri?.toString()?.let(ScriptEngineLaunchers::uriToFilePath)?.let { childPath ->
                    if (!childPath.startsWith("content://")) return childPath
                }
            } catch (_: Throwable) {
                // Preserve the content URI fallback.
            }
            return rootPath
        }
        return if (rootPath.endsWith('/')) rootPath + target else "$rootPath/$target"
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
        pkg?.trim()?.lowercase(Locale.ROOT) in setOf("com.xiaoji.egggame", "com.xiaoji.egggamz")

    private fun isWinlatorPackage(pkg: String?): Boolean {
        val value = pkg?.lowercase(Locale.ROOT) ?: return false
        return listOf("winlator", "glibc", "proot", "mobox", "winalator").any(value::contains)
    }

    private fun isWinlatorTarget(target: String?): Boolean {
        val value = target?.trim()?.lowercase(Locale.ROOT) ?: return false
        return value.endsWith(".desktop") || value.endsWith(".exe")
    }

    private fun dirname(path: String?): String? = path?.lastIndexOf('/')?.takeIf { it > 0 }?.let(path::substring)

    private fun engineIntentFlags(): Int = Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

    private fun logWarn(message: String, error: Throwable) {
        runCatching { Log.w(TAG, message, error) }
    }
}
