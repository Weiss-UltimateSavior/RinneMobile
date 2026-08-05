package com.core.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.Locale

/**
 * Winlator 外部模拟器启动协议（重构计划 3.5 阶段 98 拆分自 ExternalGameLaunchers）。
 *
 * 负责 Winlator 系（winlator/glibc/proot/mobox/winalator）的 .desktop/.exe 目标解析、
 * 容器 ID 推断与多种 fork 启动契约回退。行为与原 ExternalGameLaunchers 私有实现逐字等价。
 */
internal object WinlatorLauncher {

    fun isWinlatorPackage(pkg: String?): Boolean {
        val value = pkg?.lowercase(Locale.ROOT) ?: return false
        return listOf("winlator", "glibc", "proot", "mobox", "winalator").any(value::contains)
    }

    fun isWinlatorTarget(target: String?): Boolean {
        val value = target?.trim()?.lowercase(Locale.ROOT) ?: return false
        return value.endsWith(".desktop") || value.endsWith(".exe")
    }

    /** 按 [LaunchRequest] 启动 Winlator 系模拟器（原 WinlatorStrategy.launch）。 */
    fun launch(context: Context, request: LaunchRequest): Boolean = launchWinlator(
        context, request.packageName, request.rootUri, request.launchTarget, request.winlatorLaunchMode,
    )

    fun launchWinlator(
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
        if (launchMode == "program" || launchMode == "normal") return ExternalGameLaunchers.launchPackage(context, pkg)
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
            } catch (_: Exception) {
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
        // 空判定用 trim().isEmpty() 复刻 Java trim() 语义（仅移除 <= U+0020），而非 isNullOrBlank() 的 Unicode 空白判定。
        if (execPath != null && execPath.trim().isNotEmpty()) {
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
                val wp = workingPath
                // 空判定用 trim().isEmpty() 复刻 Java trim() 语义（仅移除 <= U+0020），而非 isNullOrBlank() 的 Unicode 空白判定。
                if (wp != null && wp.trim().isNotEmpty()) {
                    val fileName = executable.substringAfterLast('/')
                    val unixPath = wp.replace('\\', '/')
                    return unixPath + if (unixPath.endsWith('/')) fileName else "/$fileName"
                }
                val drive = executable[0].lowercaseChar()
                val packageForPath = pkg?.trim()?.takeIf(String::isNotEmpty) ?: "com.winlator"
                return "/data/user/0/$packageForPath/files/rootfs/home/xuser/.wine/dosdevices/$drive:${executable.substring(2)}"
            }
            executable
        } catch (_: Exception) {
            // 尽力而为：desktop 解析失败时返回 null，由上层回退
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
        // 空判定用 trim().isEmpty() 复刻 Java trim() 语义（仅移除 <= U+0020），而非 isNullOrBlank() 的 Unicode 空白判定。
        if (rootPath == null || rootPath.trim().isEmpty()) return target
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
            } catch (_: Exception) {
                // Preserve the content URI fallback.
            }
            return rootPath
        }
        return if (rootPath.endsWith('/')) rootPath + target else "$rootPath/$target"
    }

    private fun explicit(pkg: String, className: String, action: String, uri: Uri?): Intent =
        Intent(action).setClassName(pkg, className).apply { if (uri != null) data = uri }

    private fun dirname(path: String?): String? = path?.lastIndexOf('/')?.takeIf { it > 0 }?.let(path::substring)
}
