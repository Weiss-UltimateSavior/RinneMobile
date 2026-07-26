package com.core.launcherbridge

import org.json.JSONObject

/**
 * 认证桥公共回调接口与数据类。
 *
 * - 顶层声明，Java/Kotlin 调用方均通过 `import com.core.launcherbridge.AuthCallback` 等使用；
 * - 所有调用点已直接使用顶层类型，无需兼容别名；
 * - `SessionExpiredListener` 仍保留在 [LauncherAuthBridge] 内部，因为它与会话状态管理强耦合。
 */

interface AuthCallback {
    fun onSuccess(token: String)
    fun onError(message: String)
}

interface SimpleCallback {
    fun onSuccess()
    fun onError(message: String)
}

interface SubscriptionCallback {
    fun onSuccess(subscribed: Boolean)
    fun onError(message: String)
}

/** 用户级 LLM 覆盖配置；空字段由服务端回退至系统默认。 */
class LlmConfig {
    @JvmField var baseUrl: String = ""
    @JvmField var apiKey: String = ""
    @JvmField var model: String = ""
    @JvmField var temperature: String = ""

    companion object {
        @JvmStatic
        fun fromJson(json: JSONObject?): LlmConfig {
            val config = LlmConfig()
            if (json == null) return config
            config.baseUrl = if (json.isNull("base_url")) "" else json.optString("base_url", "")
            config.apiKey = if (json.isNull("api_key")) "" else json.optString("api_key", "")
            config.model = if (json.isNull("model")) "" else json.optString("model", "")
            if (!json.isNull("temperature")) config.temperature = json.optDouble("temperature").toString()
            return config
        }
    }

    @Throws(Exception::class)
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("base_url", nullable(baseUrl))
        json.put("api_key", nullable(apiKey))
        json.put("model", nullable(model))
        if (temperature.isBlank()) json.put("temperature", JSONObject.NULL)
        else json.put("temperature", temperature.trim().toDouble())
        return json
    }

    private fun nullable(value: String?): Any =
        if (value.isNullOrBlank()) JSONObject.NULL else value.trim()
}

interface LlmConfigCallback {
    fun onSuccess(config: LlmConfig)
    fun onError(message: String)
}

interface UserInfoCallback {
    fun onSuccess(nickname: String, email: String)
    fun onError(message: String)
}

interface ConfigCallback {
    fun onSuccess(configJson: String)
    fun onError(message: String)
}

interface PlayDataCallback {
    fun onSuccess(playSql: String)
    fun onError(message: String)
}

interface PlayTimeCallback {
    fun onSuccess(statsJson: String)
    fun onError(message: String)
}

class PlaySession(
    @JvmField val sessionId: String,
    @JvmField val gameId: Long,
    @JvmField val status: String,
    @JvmField val startedAt: Long,
    @JvmField val lastHeartbeatAt: Long,
    @JvmField val endedAt: Long,
    @JvmField val durationMs: Long
)

interface PlaySessionCallback {
    fun onSuccess(session: PlaySession)
    fun onError(message: String)
}

class LeaderboardEntry(
    @JvmField val rank: Int,
    @JvmField val username: String,
    @JvmField val totalDurationMs: Long
)

class MyRank(
    @JvmField val rank: Int,
    @JvmField val totalDurationMs: Long
)

interface LeaderboardCallback {
    fun onSuccess(entries: List<LeaderboardEntry>)
    fun onError(message: String)
}

interface MyRankCallback {
    fun onSuccess(rank: MyRank)
    fun onError(message: String)
}
