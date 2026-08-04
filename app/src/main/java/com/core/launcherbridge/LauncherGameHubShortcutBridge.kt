package com.core.launcherbridge

import android.content.pm.PackageManager
import com.core.launcher.EnginePackages
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * 通过用户授权的 Shizuku 服务读取 GameHub 桌面快捷方式元数据。
 */
object LauncherGameHubShortcutBridge {

    private const val MAX_RESPONSE_BYTES = 1024L * 1024L

    @JvmStatic
    fun isShizukuRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Exception) {
        // Shizuku 服务不可用时视为未运行
        false
    }

    @JvmStatic
    fun hasShizukuPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        // Shizuku 不可用时视为未授权
        false
    }

    @JvmStatic
    fun requestShizukuPermission(requestCode: Int) {
        Shizuku.requestPermission(requestCode)
    }

    /**
     * 返回包含可启动 localGameId 的 GameHub 快捷方式。仅读取快捷方式元数据；
     * 不修改 GameHub 或执行任何游戏命令。
     */
    @JvmStatic
    @Throws(Exception::class)
    fun loadShortcuts(): List<Shortcut> {
        if (!isShizukuRunning() || !hasShizukuPermission()) return emptyList()
        val output = runShizukuCommand(
            "uid=\$(am get-current-user 2>/dev/null); case \"\$uid\" in ''|*[!0-9]*) uid=0;; esac; "
                    + "for u in \$uid 0; do "
                    + "cmd shortcut get-shortcuts --user \$u --flags 31 ${EnginePackages.EXTERNAL_GAMEHUB_LEGACY} 2>&1; "
                    + "cmd shortcut get-shortcuts --user \$u --flags 31 ${EnginePackages.EXTERNAL_GAMEHUB} 2>&1; "
                    + "done; dumpsys shortcut 2>&1 | grep -i -A 40 -B 12 '${EnginePackages.EXTERNAL_GAMEHUB_LEGACY}\\|${EnginePackages.EXTERNAL_GAMEHUB}\\|localGameId\\|local_\\|steamAppId' 2>&1"
        )

        val items = ArrayList<Shortcut>()
        val seen = HashSet<String>()
        val text = (output ?: "").replace('\u0000', ' ')
        for (line in text.split(Regex("\\r?\\n"))) {
            addIfValid(parseShortcut(line), items, seen)
        }
        val matcher = Pattern.compile(
            "(?i)(localGameId\\s*[:=]\\s*([^,}\\]\\s]+)|\\blocal_[0-9a-f-]{8,}\\b|steamAppI[dD]\\s*[:=]\\s*[^0-9]*([0-9]+))"
        ).matcher(text)
        while (matcher.find()) {
            val start = maxOf(0, matcher.start() - 700)
            val end = minOf(text.length, matcher.end() + 1600)
            addIfValid(parseShortcut(text.substring(start, end)), items, seen)
        }
        items.sortWith(Comparator { a, b -> a.displayLabel.compareTo(b.displayLabel, ignoreCase = true) })
        return items
    }

    private fun parseShortcut(text: String?): Shortcut? {
        if (text == null) return null
        var localGameId = matchFirst(text, "(?i)\\blocalGameId\\b\\s*[:=]\\s*([^,}\\]\\s]+)")
        if (localGameId == null) localGameId = matchFirst(text, "(?i)\\b(local_[0-9a-f-]{8,})\\b")
        val steamAppId = matchFirst(text, "(?i)\\bsteamAppI[dD]\\b\\s*[:=]\\s*[^0-9]*([0-9]+)")
        var storedId = cleanValue(localGameId)
        if (storedId.isNullOrEmpty() && !steamAppId.isNullOrBlank()) {
            storedId = "steam:${steamAppId.trim()}"
        }
        if (storedId.isNullOrEmpty()) return null
        var appName = cleanValue(matchFirst(text, "(?i)\\blocalAppName\\b\\s*[:=]\\s*([^,}\\]\\r\\n]+)"))
        if (appName.isNullOrEmpty()) appName = storedId
        var label = cleanValue(matchFirst(text, "(?i)\\b(shortLabel|longLabel|title|name)\\b\\s*[:=]\\s*([^,}\\]\\r\\n]+)"))
        if (label.isNullOrEmpty()) label = appName
        return Shortcut(label, appName, storedId)
    }

    private fun addIfValid(item: Shortcut?, items: MutableList<Shortcut>, seen: HashSet<String>) {
        if (item == null || item.localGameId.isEmpty()) return
        if (seen.add(item.localGameId.lowercase(Locale.ROOT))) items.add(item)
    }

    private fun matchFirst(text: String, expression: String): String? = try {
        val matcher = Pattern.compile(expression).matcher(text)
        if (matcher.find()) matcher.group(matcher.groupCount()) else null
    } catch (_: Exception) {
        // 正则匹配失败返回 null，按无匹配处理
        null
    }

    private fun cleanValue(value: String?): String? {
        if (value == null) return null
        val cleaned = value.trim().replace(Regex("^[\\\"'=]+|[\\\"',;]+$"), "")
        return cleaned.ifEmpty { null }
    }

    /**
     * 通过 Shizuku 执行 shell 命令并读取输出。
     *
     * 使用反射调用非公开的 Shizuku.newProcess：Shizuku 未公开跨进程 Process 启动 API，
     * 反射为权宜实现。Shizuku 版本升级可能调整该内部方法签名/可见性，导致反射失败
     * （本方法每次调用都会尝试，失败时抛异常由调用方兜底）。若后续 Shizuku 提供公开等价
     * API，应优先替换并移除本反射。
     */
    @Throws(Exception::class)
    private fun runShizukuCommand(command: String): String {
        val method = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        method.isAccessible = true
        val process = method.invoke(null, arrayOf("/system/bin/sh", "-c", command), null, null) as Process
        val readers = Executors.newFixedThreadPool(2)
        try {
            val stdout = readers.submit<String> { readProcessStream(process.inputStream) }
            val stderr = readers.submit<String> { readProcessStream(process.errorStream) }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw java.io.IOException("Shizuku command timed out")
            }
            return stdout.get(2, TimeUnit.SECONDS) + "\n" + stderr.get(2, TimeUnit.SECONDS)
        } finally {
            readers.shutdownNow()
            process.destroy()
        }
    }

    private fun readProcessStream(input: InputStream?): String {
        if (input == null) return ""
        return try {
            input.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var count: Int
                while (stream.read(buffer).also { count = it } >= 0) {
                    if (output.size() + count > MAX_RESPONSE_BYTES) throw java.io.IOException("command output too large")
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
        } catch (_: Exception) {
            // 读取子进程输出失败时返回空串
            ""
        }
    }

    class Shortcut(
        displayLabel: String?,
        localAppName: String?,
        localGameId: String?
    ) {
        @JvmField val displayLabel: String = displayLabel ?: ""
        @JvmField val localAppName: String = localAppName ?: ""
        @JvmField val localGameId: String = localGameId ?: ""
    }
}
