package com.yuki.yukihub.launcherbridge

import android.content.Context
import android.content.SharedPreferences
import com.apps.UserData.LauncherUserData
import com.yuki.yukihub.util.AppExecutors
import com.yuki.yukihub.util.RxMainScheduler
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Locale

/**
 * 启动器认证桥接层。
 * 提供登录、注册、获取用户信息等 API 调用，
 * 结果通过 Callback 回调到主线程。
 */
object LauncherAuthBridge {

    private const val API_BASE = "https://api.rinne.cyou:9999"
    private const val PREFS_NAME = "yukihub_prefs"
    private const val KEY_AUTH_ACCESS_TOKEN = "auth_access_token"
    private const val KEY_AUTH_NICKNAME = "auth_nickname"
    private const val KEY_AUTH_EMAIL = "auth_email"
    private const val KEY_AUTH_STATUS = "auth_status"
    private const val MAX_RESPONSE_BYTES = 256L * 1024L
    private const val MAX_ERROR_RESPONSE_BYTES = 64L * 1024L

    /** 账户游玩数据的云备份/恢复上限；与本地和 WebDAV 快照上限保持一致量级。 */
    private const val MAX_PLAY_DATA_RESPONSE_BYTES = 16L * 1024L * 1024L

    @Volatile
    private var sessionExpiredListener: SessionExpiredListener? = null

    /**
     * UI 层可注册一个全局会话监听器。认证桥只负责判定失效和清理凭据，
     * 不直接持有 Activity，避免后台游玩心跳等调用泄漏界面。
     */
    interface SessionExpiredListener {
        fun onSessionExpired()
        fun onSessionRestored()
    }

    // ========== Token 存储 ==========

    @JvmStatic
    fun saveToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_AUTH_ACCESS_TOKEN, token).apply()
        sessionExpiredListener?.let { listener -> postMain { listener.onSessionRestored() } }
    }

    @JvmStatic
    fun setSessionExpiredListener(listener: SessionExpiredListener?) {
        sessionExpiredListener = listener
    }

    @JvmStatic
    fun clearToken(context: Context) {
        clearSession(context, "")
    }

    /** 清理无效凭据，并通知前台统一提供重新登录入口。 */
    @JvmStatic
    fun expireSession(context: Context) {
        clearSession(context, "expired")
        sessionExpiredListener?.let { listener -> postMain { listener.onSessionExpired() } }
    }

    private fun clearSession(context: Context, status: String) {
        prefs(context).edit()
            .remove(KEY_AUTH_ACCESS_TOKEN)
            .remove(KEY_AUTH_NICKNAME)
            .remove(KEY_AUTH_EMAIL)
            .putString(KEY_AUTH_STATUS, status)
            .apply()
    }

    @JvmStatic
    fun getToken(context: Context): String =
        prefs(context).getString(KEY_AUTH_ACCESS_TOKEN, "") ?: ""

    @JvmStatic
    fun isLoggedIn(context: Context): Boolean {
        val token = getToken(context)
        return token.isNotBlank()
    }

    @JvmStatic
    fun saveUserInfo(context: Context, nickname: String, email: String?) {
        prefs(context).edit()
            .putString(KEY_AUTH_NICKNAME, nickname)
            .putString(KEY_AUTH_EMAIL, email ?: "")
            .putString(KEY_AUTH_STATUS, "online")
            .apply()
    }

    @JvmStatic
    fun getNickname(context: Context): String =
        prefs(context).getString(KEY_AUTH_NICKNAME, "") ?: ""

    @JvmStatic
    fun getEmail(context: Context): String =
        prefs(context).getString(KEY_AUTH_EMAIL, "") ?: ""

    // ========== API 调用 ==========

    @JvmStatic
    fun login(context: Context, email: String, password: String, callback: AuthCallback) {
        AppExecutors.runOnIo {
            try {
                val body = JSONObject()
                body.put("email", email)
                body.put("password", password)
                val response = post("/auth/login", body, null)
                val json = JSONObject(response ?: "{}")
                val token = json.optString("access_token", "")
                if (token.isEmpty()) throw RuntimeException("登录失败：服务器未返回令牌")
                saveToken(context, token)
                fetchAndSaveUserInfo(context)
                postMain { callback.onSuccess(token) }
            } catch (t: Throwable) {
                var msg = parseErrorMessage(t, "登录失败")
                if (msg.contains("401") || msg.contains("邮箱或密码错误")) {
                    msg = "邮箱或密码错误"
                } else if (msg.contains("429")) {
                    msg = "请求过于频繁，请稍后重试"
                }
                val errMsg = msg
                postMain { callback.onError(errMsg) }
            }
        }
    }

    @JvmStatic
    fun register(context: Context, username: String, email: String, password: String, inviteCode: String,
                 verificationCode: String, callback: AuthCallback) {
        AppExecutors.runOnIo {
            try {
                val body = JSONObject()
                body.put("username", username)
                body.put("email", email)
                body.put("password", password)
                body.put("invite_code", inviteCode)
                body.put("verification_code", verificationCode)
                val response = post("/auth/register", body, null)
                val json = JSONObject(response ?: "{}")
                val token = json.optString("access_token", "")
                if (token.isEmpty()) throw RuntimeException("注册失败：服务器未返回令牌")
                saveToken(context, token)
                fetchAndSaveUserInfo(context)
                postMain { callback.onSuccess(token) }
            } catch (t: Throwable) {
                var msg = parseErrorMessage(t, "注册失败")
                msg = when {
                    msg.contains("邀请码无效") || msg.contains("已过期") -> "邀请码无效或已过期"
                    msg.contains("用户名已存在") -> "用户名已存在"
                    msg.contains("邮箱已被注册") -> "该邮箱已被注册，请直接登录"
                    msg.contains("验证码已过期") -> "验证码已过期，请重新获取"
                    msg.contains("验证码错误") -> "验证码错误，请检查后重试"
                    msg.contains("429") -> "请求过于频繁，请稍后重试"
                    msg.contains("422") -> "输入信息格式有误，请检查后重试"
                    else -> msg
                }
                val errMsg = msg
                postMain { callback.onError(errMsg) }
            }
        }
    }

    /** 发送注册邮箱验证码。 */
    @JvmStatic
    fun sendRegistrationVerificationCode(context: Context, email: String, callback: SimpleCallback) {
        sendEmailCode(context, "/auth/verify-code", email, "验证码发送失败", callback)
    }

    /** 发送密码重置验证码；该验证码与注册验证码在服务端独立存储。 */
    @JvmStatic
    fun sendPasswordResetCode(context: Context, email: String, callback: SimpleCallback) {
        sendEmailCode(context, "/auth/forgot-password", email, "验证码发送失败", callback)
    }

    /** 通过邮箱验证码重置密码，成功后清除本机登录状态。 */
    @JvmStatic
    fun resetPassword(context: Context, email: String, verificationCode: String, newPassword: String,
                      callback: SimpleCallback) {
        AppExecutors.runOnIo {
            try {
                val body = JSONObject()
                body.put("email", email)
                body.put("verification_code", verificationCode)
                body.put("new_password", newPassword)
                post("/auth/reset-password", body, null)
                clearToken(context)
                postMain { callback.onSuccess() }
            } catch (t: Throwable) {
                val message = normalizeEmailCodeError(parseErrorMessage(t, "密码重置失败"))
                postMain { callback.onError(message) }
            }
        }
    }

    /** 获取当前用户的邮件订阅状态。 */
    @JvmStatic
    fun fetchEmailSubscription(context: Context, callback: SubscriptionCallback) {
        AppExecutors.runOnIo {
            try {
                val token = getToken(context)
                if (token.isEmpty()) throw RuntimeException("未登录")
                val json = JSONObject(get("/auth/subscription", token))
                val subscribed = json.optBoolean("subscribed", false)
                postMain { callback.onSuccess(subscribed) }
            } catch (t: Throwable) {
                var message = parseErrorMessage(t, "获取邮件订阅状态失败")
                if (message.contains("401")) {
                    expireSession(context)
                    message = "登录已过期，请重新登录"
                }
                val error = message
                postMain { callback.onError(error) }
            }
        }
    }

    /** 更新当前用户的邮件订阅状态。 */
    @JvmStatic
    fun updateEmailSubscription(context: Context, subscribed: Boolean, callback: SubscriptionCallback) {
        AppExecutors.runOnIo {
            try {
                val token = getToken(context)
                if (token.isEmpty()) throw RuntimeException("未登录")
                val body = JSONObject()
                body.put("subscribed", subscribed)
                val json = JSONObject(put("/auth/subscription", body, token))
                postMain { callback.onSuccess(json.optBoolean("subscribed", subscribed)) }
            } catch (t: Throwable) {
                var message = parseErrorMessage(t, "更新邮件订阅失败")
                if (message.contains("401")) {
                    expireSession(context)
                    message = "登录已过期，请重新登录"
                }
                val error = message
                postMain { callback.onError(error) }
            }
        }
    }

    private fun sendEmailCode(context: Context, path: String, email: String, fallback: String, callback: SimpleCallback) {
        AppExecutors.runOnIo {
            try {
                val body = JSONObject()
                body.put("email", email)
                body.put("deviceId", LauncherUserData.getRealtimePlaytimeDeviceId(context))
                post(path, body, null)
                postMain { callback.onSuccess() }
            } catch (t: Throwable) {
                val message = normalizeEmailCodeError(parseErrorMessage(t, fallback))
                postMain { callback.onError(message) }
            }
        }
    }

    private fun normalizeEmailCodeError(message: String?): String {
        val msg = message ?: "操作失败，请稍后重试"
        if (msg.contains("429") || msg.contains("请求过于频繁") || msg.contains("冷却")) return "操作过于频繁，请稍后再试"
        if (msg.contains("验证码已过期")) return "验证码已过期，请重新获取"
        if (msg.contains("验证码错误")) return "验证码错误，请检查后重试"
        if (msg.contains("邮箱未注册") || msg.contains("用户不存在")) return "该邮箱尚未注册"
        if (msg.contains("邮件发送失败")) return "验证码发送失败，请稍后重试"
        if (msg.contains("422")) return "邮箱或验证码格式不正确"
        return msg
    }

    @JvmStatic
    @Throws(Exception::class)
    fun fetchAndSaveUserInfo(context: Context) {
        val token = getToken(context)
        if (token.isEmpty()) return
        val response = get("/auth/me", token)
        val json = JSONObject(response ?: "{}")
        val username = json.optString("username", "")
        val email = json.optString("email", "")
        saveUserInfo(context, username, email)
    }

    @JvmStatic
    fun fetchUserInfo(context: Context, callback: UserInfoCallback) {
        AppExecutors.runOnIo {
            try {
                fetchAndSaveUserInfo(context)
                val nickname = getNickname(context)
                val email = getEmail(context)
                postMain { callback.onSuccess(nickname, email) }
            } catch (t: Throwable) {
                var msg = parseErrorMessage(t, "获取用户信息失败")
                if (msg.contains("401")) {
                    expireSession(context)
                    msg = "登录已过期，请重新登录"
                }
                val errMsg = msg
                postMain { callback.onError(errMsg) }
            }
        }
    }

    // ========== 用户信息修改 ==========

    @JvmStatic
    fun updateUsername(context: Context, newUsername: String, callback: AuthCallback) {
        AppExecutors.runOnIo {
            try {
                val token = getToken(context)
                if (token.isEmpty()) throw RuntimeException("未登录")
                val body = JSONObject()
                body.put("new_username", newUsername)
                val response = put("/auth/username", body, token)
                val json = JSONObject(response ?: "{}")
                val username = json.optString("username", "")
                val email = json.optString("email", "")
                saveUserInfo(context, username, email)
                postMain { callback.onSuccess(token) }
            } catch (t: Throwable) {
                var msg = parseErrorMessage(t, "修改用户名失败")
                if (msg.contains("401")) {
                    expireSession(context)
                    msg = "登录已过期，请重新登录"
                } else if (msg.contains("用户名已存在")) {
                    msg = "该用户名已存在"
                } else if (msg.contains("422")) {
                    msg = "用户名格式有误，需3-32位字母、数字或下划线"
                }
                val errMsg = msg
                postMain { callback.onError(errMsg) }
            }
        }
    }

    @JvmStatic
    fun updatePassword(context: Context, oldPassword: String, newPassword: String, callback: AuthCallback) {
        AppExecutors.runOnIo {
            try {
                val token = getToken(context)
                if (token.isEmpty()) throw RuntimeException("未登录")
                val body = JSONObject()
                body.put("old_password", oldPassword)
                body.put("new_password", newPassword)
                put("/auth/password", body, token)
                // 修改密码后 Token 全部吊销，清除本地 Token
                clearToken(context)
                postMain { callback.onSuccess("") }
            } catch (t: Throwable) {
                var msg = parseErrorMessage(t, "修改密码失败")
                if (msg.contains("401")) {
                    expireSession(context)
                    msg = "登录已过期，请重新登录"
                } else if (msg.contains("旧密码错误")) {
                    msg = "旧密码错误"
                } else if (msg.contains("422")) {
                    msg = "密码格式有误，需6-128位"
                }
                val errMsg = msg
                postMain { callback.onError(errMsg) }
            }
        }
    }

    /** 用户级 LLM 覆盖配置；空字段由服务端回退至系统默认。 */
    @JvmStatic
    fun fetchLlmConfig(context: Context, callback: LlmConfigCallback) {
        AppExecutors.runOnIo {
            try {
                val token = getToken(context)
                if (token.isEmpty()) throw RuntimeException("未登录")
                val json = JSONObject(get("/auth/llm", token))
                postMain { callback.onSuccess(LlmConfig.fromJson(json)) }
            } catch (t: Throwable) {
                postLlmError(context, t, "获取模型配置失败", callback)
            }
        }
    }

    @JvmStatic
    fun updateLlmConfig(context: Context, config: LlmConfig?, callback: LlmConfigCallback) {
        AppExecutors.runOnIo {
            try {
                val token = getToken(context)
                if (token.isEmpty()) throw RuntimeException("未登录")
                val json = JSONObject(putLlm("/auth/llm", config?.toJson() ?: LlmConfig().toJson(), token))
                val result = json.optJSONObject("llm")
                postMain { callback.onSuccess(LlmConfig.fromJson(result ?: json)) }
            } catch (t: Throwable) {
                postLlmError(context, t, "保存模型配置失败", callback)
            }
        }
    }

    private fun postLlmError(context: Context, error: Throwable?, fallback: String, callback: LlmConfigCallback) {
        var message = parseErrorMessage(error, fallback)
        if (isNetworkTimeout(error, message)) message = "模型服务不可达或响应超时，请检查接口地址和网络连接"
        else if (message.contains("401")) { expireSession(context); message = "登录已过期，请重新登录" }
        else if (message.contains("AI_CONNECTION_TEST_FAILED")) message = "模型连通性验证失败，请检查接口地址、API Key 和模型名称"
        else if (message.contains("422")) message = "模型配置格式不正确，请检查地址、模型名与温度"
        else if (message.contains("404") || message.contains("用户不存在")) message = "用户不存在，请重新登录后重试"
        val finalMessage = message
        postMain { callback.onError(finalMessage) }
    }

    private fun isNetworkTimeout(error: Throwable?, message: String): Boolean {
        var current = error
        while (current != null) {
            if (current is SocketTimeoutException || current is ConnectException) return true
            current = current.cause
        }
        val text = message.lowercase(Locale.ROOT)
        return text.contains("timed out") || text.contains("timeout") || text.contains("failed to connect") || text.contains("network is unreachable")
    }

    // ========== 配置同步 ==========

    /**
     * 从服务端获取 Launcher 配置 JSON。
     */
    @JvmStatic
    fun fetchConfig(context: Context, callback: ConfigCallback) {
        AppExecutors.runOnIo {
            try {
                val token = getToken(context)
                if (token.isEmpty()) throw RuntimeException("未登录")
                val response = get("/auth/config", token)
                postMain { callback.onSuccess(response) }
            } catch (t: Throwable) {
                var msg = parseErrorMessage(t, "获取配置失败")
                if (msg.contains("401")) { expireSession(context); msg = "登录已过期，请重新登录" }
                val errMsg = msg
                postMain { callback.onError(errMsg) }
            }
        }
    }

    /**
     * 上传 Launcher 配置到服务端。
     */
    @JvmStatic
    fun uploadConfig(context: Context, configJson: String, callback: ConfigCallback) {
        AppExecutors.runOnIo {
            try {
                val token = getToken(context)
                if (token.isEmpty()) throw RuntimeException("未登录")
                val body = JSONObject(configJson)
                put("/auth/config", body, token)
                postMain { callback.onSuccess(configJson) }
            } catch (t: Throwable) {
                var msg = parseErrorMessage(t, "上传配置失败")
                if (msg.contains("401")) { expireSession(context); msg = "登录已过期，请重新登录" }
                val errMsg = msg
                postMain { callback.onError(errMsg) }
            }
        }
    }

    /**
     * 从服务端获取游玩记录 SQL。
     * 使用更长的超时和更大的响应缓冲，适配大数据量场景。
     */
    @JvmStatic
    fun fetchPlayData(context: Context, callback: PlayDataCallback) {
        AppExecutors.runOnIo {
            try {
                val token = getToken(context)
                if (token.isEmpty()) throw RuntimeException("未登录")
                val response = getLarge("/auth/config/play-data", token)
                val json = JSONObject(response ?: "{}")
                val playData = json.optString("play_data", "")
                postMain { callback.onSuccess(playData) }
            } catch (t: Throwable) {
                var msg = parseErrorMessage(t, "获取游玩记录失败")
                if (msg.contains("401")) { expireSession(context); msg = "登录已过期，请重新登录" }
                val errMsg = msg
                postMain { callback.onError(errMsg) }
            }
        }
    }

    /**
     * 上传游玩记录 SQL 到服务端。
     * 使用更长的超时和更大的响应缓冲，适配大数据量场景。
     */
    @JvmStatic
    fun uploadPlayData(context: Context, playSql: String, callback: PlayDataCallback) {
        AppExecutors.runOnIo {
            try {
                val token = getToken(context)
                if (token.isEmpty()) throw RuntimeException("未登录")
                if (utf8Length(playSql) > MAX_PLAY_DATA_RESPONSE_BYTES) {
                    throw RuntimeException("游玩数据过大（最大允许 $MAX_PLAY_DATA_RESPONSE_BYTES 字节）")
                }
                val body = JSONObject()
                body.put("play_data", playSql)
                putLarge("/auth/config/play-data", body, token)
                postMain { callback.onSuccess(playSql) }
            } catch (t: Throwable) {
                var msg = parseErrorMessage(t, "上传游玩记录失败")
                if (msg.contains("401")) { expireSession(context); msg = "登录已过期，请重新登录" }
                val errMsg = msg
                postMain { callback.onError(errMsg) }
            }
        }
    }

    // ========== 游玩时长统计 ==========

    @JvmStatic
    fun startPlayTimeSession(context: Context, gameId: Long, gameTitle: String?, deviceId: String?,
                             callback: PlaySessionCallback) {
        AppExecutors.runOnIo {
            try {
                val token = getToken(context)
                if (token.isEmpty()) throw RuntimeException("未登录")
                val body = JSONObject()
                body.put("gameId", gameId)
                body.put("gameTitle", gameTitle ?: "")
                body.put("deviceId", deviceId ?: "")
                val session = parsePlaySession(JSONObject(post("/auth/play-time/sessions/start", body, token)))
                postMain { callback.onSuccess(session) }
            } catch (t: Throwable) {
                var msg = parseErrorMessage(t, "启动服务端游玩会话失败")
                if (msg.contains("401")) { expireSession(context); msg = "登录已过期，请重新登录" }
                val errMsg = msg
                postMain { callback.onError(errMsg) }
            }
        }
    }

    @JvmStatic
    fun heartbeatPlayTimeSession(context: Context, sessionId: String, callback: PlaySessionCallback) {
        postPlayTimeSessionEvent(context, sessionId, "heartbeat", "服务端游玩心跳失败", callback)
    }

    @JvmStatic
    fun finishPlayTimeSession(context: Context, sessionId: String, callback: PlaySessionCallback) {
        postPlayTimeSessionEvent(context, sessionId, "finish", "结束服务端游玩会话失败", callback)
    }

    private fun postPlayTimeSessionEvent(context: Context, sessionId: String?, action: String,
                                         fallback: String, callback: PlaySessionCallback) {
        AppExecutors.runOnIo {
            try {
                val token = getToken(context)
                if (token.isEmpty()) throw RuntimeException("未登录")
                if (sessionId.isNullOrBlank()) throw RuntimeException("会话不存在")
                val session = parsePlaySession(JSONObject(postNoBody(
                    "/auth/play-time/sessions/${sessionId.trim()}/$action",
                    token)))
                postMain { callback.onSuccess(session) }
            } catch (t: Throwable) {
                var msg = parseErrorMessage(t, fallback)
                if (msg.contains("401")) { expireSession(context); msg = "登录已过期，请重新登录" }
                val errMsg = msg
                postMain { callback.onError(errMsg) }
            }
        }
    }

    private fun parsePlaySession(json: JSONObject): PlaySession = PlaySession(
        json.optString("session_id", ""),
        json.optLong("game_id"),
        json.optString("status", ""),
        json.optLong("started_at"),
        json.optLong("last_heartbeat_at"),
        json.optLong("ended_at"),
        json.optLong("duration_ms")
    )

    /**
     * 旧版实际游玩记录上传入口，仅保留兼容。
     * 新流程必须使用 startPlayTimeSession/heartbeatPlayTimeSession/finishPlayTimeSession，
     * 不再向 /auth/play-time 提交前端计算的 duration。
     *
     * @param records 游玩记录列表（每条为 JSONObject，字段与 LauncherUserData.appendPlayRecord 一致）
     * @param callback 回调：onSuccess 返回服务端累计统计 JSON 数组字符串
     */
    @JvmStatic
    fun uploadPlayTime(context: Context, records: List<JSONObject>?, callback: PlayTimeCallback) {
        AppExecutors.runOnIo {
            try {
                val token = getToken(context)
                if (token.isEmpty()) throw RuntimeException("未登录")
                if (records.isNullOrEmpty()) throw RuntimeException("记录列表为空")
                val body = JSONObject()
                val arr = JSONArray()
                for (r in records) arr.put(r)
                body.put("records", arr)
                val response = post("/auth/play-time", body, token)
                postMain { callback.onSuccess(response) }
            } catch (t: Throwable) {
                var msg = parseErrorMessage(t, "上传游玩时长失败")
                if (msg.contains("401")) { expireSession(context); msg = "登录已过期，请重新登录" }
                val errMsg = msg
                postMain { callback.onError(errMsg) }
            }
        }
    }

    /**
     * 获取当前用户所有游戏的累计游玩时长统计（按时长降序）。
     *
     * @param callback 回调：onSuccess 返回 JSON 数组字符串，每项含 game_id/game_title/total_duration_ms/play_count/last_played_at
     */
    @JvmStatic
    fun fetchPlayTime(context: Context, callback: PlayTimeCallback) {
        AppExecutors.runOnIo {
            try {
                val token = getToken(context)
                if (token.isEmpty()) throw RuntimeException("未登录")
                val response = get("/auth/play-time", token)
                postMain { callback.onSuccess(response) }
            } catch (t: Throwable) {
                var msg = parseErrorMessage(t, "获取游玩时长失败")
                if (msg.contains("401")) { expireSession(context); msg = "登录已过期，请重新登录" }
                val errMsg = msg
                postMain { callback.onError(errMsg) }
            }
        }
    }

    /** 获取全站游玩时长前 15 名，仅使用普通用户鉴权。 */
    @JvmStatic
    fun fetchPlayTimeLeaderboard(context: Context, callback: LeaderboardCallback) {
        AppExecutors.runOnIo {
            try {
                val token = getToken(context)
                if (token.isEmpty()) throw RuntimeException("未登录")
                val array = JSONArray(get("/auth/play-time/leaderboard", token))
                val entries = ArrayList<LeaderboardEntry>()
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    entries.add(LeaderboardEntry(item.optInt("rank"), item.optString("username"), item.optLong("total_duration_ms")))
                }
                postMain { callback.onSuccess(entries) }
            } catch (t: Throwable) {
                var msg = parseErrorMessage(t, "获取游玩时长排行榜失败")
                if (msg.contains("401")) { expireSession(context); msg = "登录已过期，请重新登录" }
                val errMsg = msg
                postMain { callback.onError(errMsg) }
            }
        }
    }

    /** 获取当前用户的全站游玩时长排名。 */
    @JvmStatic
    fun fetchMyPlayTimeRank(context: Context, callback: MyRankCallback) {
        AppExecutors.runOnIo {
            try {
                val token = getToken(context)
                if (token.isEmpty()) throw RuntimeException("未登录")
                val item = JSONObject(get("/auth/play-time/rank", token))
                val rank = MyRank(item.optInt("rank"), item.optLong("total_duration_ms"))
                postMain { callback.onSuccess(rank) }
            } catch (t: Throwable) {
                var msg = parseErrorMessage(t, "获取我的游玩时长排名失败")
                if (msg.contains("401")) { expireSession(context); msg = "登录已过期，请重新登录" }
                val errMsg = msg
                postMain { callback.onError(errMsg) }
            }
        }
    }

    // ========== 网络工具 ==========

    /** 大数据量 PUT：超时更长，响应缓冲更大（适配游玩记录上传/下载）。 */
    @Throws(Exception::class)
    private fun putLarge(path: String, body: JSONObject, authToken: String?): String {
        val c = URL(API_BASE + path).openConnection() as HttpURLConnection
        c.requestMethod = "PUT"
        c.doOutput = true
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "application/json")
        if (!authToken.isNullOrEmpty()) {
            c.setRequestProperty("Authorization", "Bearer $authToken")
        }
        writeRequestBody(c, body, MAX_PLAY_DATA_RESPONSE_BYTES, "游玩数据请求")
        return readLargeResponse(c)
    }

    @Throws(Exception::class)
    private fun put(path: String, body: JSONObject, authToken: String?): String {
        val c = URL(API_BASE + path).openConnection() as HttpURLConnection
        c.requestMethod = "PUT"
        c.doOutput = true
        c.connectTimeout = 10000
        c.readTimeout = 12000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "application/json")
        if (!authToken.isNullOrEmpty()) {
            c.setRequestProperty("Authorization", "Bearer $authToken")
        }
        writeRequestBody(c, body, -1, "请求")
        return readResponse(c)
    }

    @Throws(Exception::class)
    private fun post(path: String, body: JSONObject, authToken: String?): String {
        val c = URL(API_BASE + path).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 10000
        c.readTimeout = 12000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "application/json")
        if (!authToken.isNullOrEmpty()) {
            c.setRequestProperty("Authorization", "Bearer $authToken")
        }
        writeRequestBody(c, body, -1, "请求")
        return readResponse(c)
    }

    @Throws(Exception::class)
    private fun postNoBody(path: String, authToken: String?): String {
        val c = URL(API_BASE + path).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 10000
        c.readTimeout = 12000
        c.setRequestProperty("Accept", "application/json")
        if (!authToken.isNullOrEmpty()) {
            c.setRequestProperty("Authorization", "Bearer $authToken")
        }
        return readResponse(c)
    }

    /** LLM 保存会触发服务端真实连通性测试，因此允许更长的读取时间。 */
    @Throws(Exception::class)
    private fun putLlm(path: String, body: JSONObject, authToken: String?): String {
        val c = URL(API_BASE + path).openConnection() as HttpURLConnection
        c.requestMethod = "PUT"
        c.doOutput = true
        c.connectTimeout = 10000
        c.readTimeout = 30000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "application/json")
        if (!authToken.isNullOrEmpty()) c.setRequestProperty("Authorization", "Bearer $authToken")
        writeRequestBody(c, body, -1, "请求")
        return readResponse(c)
    }

    @Throws(Exception::class)
    private fun get(path: String, authToken: String?): String {
        val c = URL(API_BASE + path).openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = 10000
        c.readTimeout = 12000
        c.setRequestProperty("Accept", "application/json")
        if (!authToken.isNullOrEmpty()) {
            c.setRequestProperty("Authorization", "Bearer $authToken")
        }
        return readResponse(c)
    }

    /** 大数据量 GET：超时更长，响应缓冲更大。 */
    @Throws(Exception::class)
    private fun getLarge(path: String, authToken: String?): String {
        val c = URL(API_BASE + path).openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.setRequestProperty("Accept", "application/json")
        if (!authToken.isNullOrEmpty()) {
            c.setRequestProperty("Authorization", "Bearer $authToken")
        }
        return readLargeResponse(c)
    }

    @Throws(Exception::class)
    private fun readResponse(c: HttpURLConnection): String {
        val code = c.responseCode
        val text = readTextLimited(c, if (code in 200..299) c.inputStream else c.errorStream,
            if (code in 200..299) MAX_RESPONSE_BYTES else MAX_ERROR_RESPONSE_BYTES, "服务器响应")
        return checkResponse(code, text)
    }

    /** 大响应读取：仅用于账户游玩数据，仍设置明确上限避免异常服务端耗尽内存。 */
    @Throws(Exception::class)
    private fun readLargeResponse(c: HttpURLConnection): String {
        val code = c.responseCode
        val text = readTextLimited(c, if (code in 200..299) c.inputStream else c.errorStream,
            if (code in 200..299) MAX_PLAY_DATA_RESPONSE_BYTES else MAX_ERROR_RESPONSE_BYTES, "账户游玩数据响应")
        return checkResponse(code, text)
    }

    @Throws(Exception::class)
    private fun checkResponse(code: Int, text: String): String {
        if (code !in 200..299) {
            var detail = text
            try {
                val err = JSONObject(text ?: "{}")
                // 适配统一错误格式: {"detail": {"code": "...", "message": "..."}}
                val detailObj = err.get("detail")
                if (detailObj is JSONObject) {
                    if (detailObj.has("message")) {
                        val codeName = detailObj.optString("code", "")
                        detail = (if (codeName.isEmpty()) "" else "$codeName: ") + detailObj.optString("message", text)
                    } else {
                        detail = detailObj.toString()
                    }
                } else {
                    detail = detailObj.toString()
                }
            } catch (_: Throwable) {
            }
            throw RuntimeException("HTTP $code: ${trim(detail, 200)}")
        }
        return text
    }

    @Throws(Exception::class)
    private fun writeRequestBody(connection: HttpURLConnection, body: JSONObject, maxBytes: Long, label: String) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        if (maxBytes > 0 && bytes.size > maxBytes) {
            connection.disconnect()
            throw RuntimeException("$label 过大（最大允许 $maxBytes 字节）")
        }
        connection.setFixedLengthStreamingMode(bytes.size)
        try {
            connection.outputStream.use { output ->
                output.write(bytes)
                output.flush()
            }
        } catch (e: Exception) {
            connection.disconnect()
            throw e
        }
    }

    @Throws(Exception::class)
    private fun readTextLimited(connection: HttpURLConnection, inputStream: InputStream?, maxBytes: Long, label: String): String {
        if (inputStream == null) return ""
        val declaredLength = connection.contentLengthLong
        if (declaredLength > maxBytes) {
            val oversized = RuntimeException("$label 过大（服务端声明 $declaredLength 字节，最大允许 $maxBytes 字节）")
            try {
                inputStream.close()
            } catch (closeError: Exception) {
                oversized.addSuppressed(closeError)
            } finally {
                connection.disconnect()
            }
            throw oversized
        }
        try {
            inputStream.use { input ->
                val bos = ByteArrayOutputStream(
                    if (declaredLength > 0 && declaredLength <= Int.MAX_VALUE) declaredLength.toInt() else 8192
                )
                val buf = ByteArray(8192)
                var total = 0
                var len: Int
                while (input.read(buf).also { len = it } != -1) {
                    total += len
                    if (total > maxBytes) throw RuntimeException("$label 过大（读取超过最大允许 $maxBytes 字节）")
                    bos.write(buf, 0, len)
                }
                return bos.toString("UTF-8")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun trim(text: String?, max: Int): String {
        if (text == null) return ""
        val t = text.trim()
        if (max <= 0 || t.length <= max) return t
        return t.substring(0, max) + "..."
    }

    @Throws(Exception::class)
    private fun utf8Length(text: String?): Int =
        text?.toByteArray(Charsets.UTF_8)?.size ?: 0

    private fun parseErrorMessage(t: Throwable?, fallback: String): String {
        if (t == null) return fallback
        val msg = t.message
        if (msg.isNullOrBlank()) return fallback
        return msg
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun postMain(r: Runnable) {
        RxMainScheduler.post(r)
    }

    // ========== 回调接口 ==========

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
}
