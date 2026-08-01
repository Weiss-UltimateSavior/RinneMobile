package com.core.agent.workspace

import java.util.Locale
import java.util.regex.Pattern

/** Validates model-supplied paths before they reach a game SAF tree. */
object AgentRelativePath {
    private val SLASH = Pattern.compile("/")
    @JvmStatic
    fun normalize(value: String?, allowEmpty: Boolean): String {
        val original = value ?: ""
        val path = original.trim()
        if (original != path) throw IllegalArgumentException("relative_path 不能包含首尾空白")
        if (path.isEmpty()) {
            if (allowEmpty) return ""
            throw IllegalArgumentException("relative_path 不能为空")
        }
        require(path.length <= 512) { "relative_path 过长" }
        require(!path.startsWith("/") && !path.startsWith("\\") && !path.contains(":")
            && !path.contains("\\") && !path.contains("%") && path.indexOf('\u0000') < 0
        ) { "relative_path 必须是游戏目录内的普通相对路径" }

        // 复刻 Java 原版 path.split("/", -1) 行为：limit = -1 保留尾部空字符串，
        // 使 "data/" 这类带尾斜杠的输入仍能被下方 segment.isEmpty() 拦截。
        // Kotlin 原生 split 不支持负数 limit，改用 Pattern.split(input, -1)。
        val raw = SLASH.split(path, -1)
        val safe = ArrayList<String>(raw.size)
        for (segment in raw) {
            require(segment.isNotEmpty() && segment != "." && segment != "..") { "relative_path 包含非法路径段" }
            require(segment.length <= 128) { "relative_path 路径段过长" }
            for (c in segment) {
                require(!c.isISOControl() && c !in '\u202A'..'\u202E' && c !in '\u2066'..'\u2069') {
                    "relative_path 包含不可显示控制字符"
                }
            }
            safe.add(segment)
        }
        return safe.joinToString("/")
    }

    @JvmStatic
    fun isSensitive(normalizedPath: String?): Boolean {
        val value = (normalizedPath ?: "")
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .lowercase(Locale.ROOT)
        for (segment in value.split("/")) {
            val stem = segment.substringBefore('.')
            val tokens = segment.replace(Regex("[^a-z0-9]+"), "_")
            val parts = tokens.split("_").toHashSet()
            if (segment == ".env" || segment.startsWith(".env.") || segment == ".npmrc"
                || segment == ".netrc" || segment == ".ssh" || segment == "id_rsa"
                || segment == ".git-credentials" || tokens.contains("api_key")
                || tokens.contains("client_secret") || stem == "session" || stem == "sessions"
                || stem == "cookie" || stem == "cookies"
                || parts.contains("token") || parts.contains("password") || parts.contains("passwd")
                || parts.contains("credential") || parts.contains("credentials")
                || parts.contains("account") || parts.contains("session") || parts.contains("cookie")
                || parts.contains("cookies")
                || stem == "credential" || stem == "credentials" || stem == "secret"
                || stem == "secrets" || stem == "token" || stem == "password"
                || stem == "passwd" || stem == "auth" || stem == "account"
                || stem == "accounts" || stem == "profile" || stem == "save"
                || stem == "saves" || stem == "savegame" || stem == "savegames"
                || stem == "savedata" || stem == "save_data" || stem == "userdata"
                || segment.endsWith(".rpgsave") || segment.endsWith(".rvdata") || segment.endsWith(".rvdata2")
                || segment.endsWith(".sav")
                || segment.endsWith(".pem") || segment.endsWith(".key") || segment.endsWith(".p12")
                || segment.endsWith(".pfx") || segment.endsWith(".jks") || segment.endsWith(".keystore")
            ) {
                return true
            }
        }
        return false
    }
}
