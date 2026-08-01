package com.core.launcherbridge

import android.content.Context
import android.content.SharedPreferences
import com.core.userdata.LauncherUserData
import com.core.util.AppExecutors
import com.core.util.RxMainScheduler
import org.json.JSONObject

/**
 * 启动器认证桥接层（核心 + 门面）。
 *
 * 本文件只保留核心职责：
 * - Token 存储与会话失效通知（saveToken / clearToken / expireSession / getToken / isLoggedIn）
 * - 用户信息缓存读写（saveUserInfo / getNickname / getEmail / fetchAndSaveUserInfo）
 * - 登录/注册/验证码/密码重置/邮件订阅等认证 API（login / register / sendXxxCode / resetPassword / ...）
 *
 * 已拆出的实现：
 * - [LauncherAuthHttpClient]：HTTP 请求/响应/错误解析等底层工具（`internal`）。
 * - [LauncherAuthCallbacks]：所有 callback 接口与 LlmConfig / PlaySession / LeaderboardEntry / MyRank 数据类。
 * - [LauncherUserBridge]：fetchUserInfo / updateUsername / updatePassword / fetchLlmConfig / updateLlmConfig。
 * - [LauncherConfigSyncBridge]：fetchConfig / uploadConfig / fetchPlayData / uploadPlayData。
 * - [LauncherPlayTimeBridge]：startPlayTimeSession / heartbeatPlayTimeSession / finishPlayTimeSession /
 *   uploadPlayTime / fetchPlayTime / fetchPlayTimeLeaderboard / fetchMyPlayTimeRank。
 *
 * 兼容策略：下方 `// ========== 门面委托 ==========` 区域内的 @JvmStatic 方法仅做一行委托，
 * 保留旧调用方在迁移期内无需改动方法名即可继续工作。所有调用点迁移至新 Bridge 后可删除门面方法。
 */
object LauncherAuthBridge {

    private const val PREFS_NAME = "yukihub_prefs"
    private const val KEY_AUTH_ACCESS_TOKEN = "auth_access_token"
    private const val KEY_AUTH_NICKNAME = "auth_nickname"
    private const val KEY_AUTH_EMAIL = "auth_email"
    private const val KEY_AUTH_STATUS = "auth_status"

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
        sessionExpiredListener?.let { listener -> RxMainScheduler.post { listener.onSessionRestored() } }
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
        sessionExpiredListener?.let { listener -> RxMainScheduler.post { listener.onSessionExpired() } }
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

    // ========== 认证 API ==========

    @JvmStatic
    fun login(context: Context, email: String, password: String, callback: AuthCallback) {
        AppExecutors.runOnIo {
            try {
                val body = JSONObject()
                body.put("email", email)
                body.put("password", password)
                val response = LauncherAuthHttpClient.post("/auth/login", body, null)
                val json = JSONObject(response ?: "{}")
                val token = json.optString("access_token", "")
                if (token.isEmpty()) throw RuntimeException("登录失败：服务器未返回令牌")
                saveToken(context, token)
                fetchAndSaveUserInfo(context)
                RxMainScheduler.post { callback.onSuccess(token) }
            } catch (t: Throwable) {
                var msg = LauncherAuthHttpClient.parseErrorMessage(t, "登录失败")
                if (msg.contains("401") || msg.contains("邮箱或密码错误")) {
                    msg = "邮箱或密码错误"
                } else if (msg.contains("429")) {
                    msg = "请求过于频繁，请稍后重试"
                }
                val errMsg = msg
                RxMainScheduler.post { callback.onError(errMsg) }
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
                val response = LauncherAuthHttpClient.post("/auth/register", body, null)
                val json = JSONObject(response ?: "{}")
                val token = json.optString("access_token", "")
                if (token.isEmpty()) throw RuntimeException("注册失败：服务器未返回令牌")
                saveToken(context, token)
                fetchAndSaveUserInfo(context)
                RxMainScheduler.post { callback.onSuccess(token) }
            } catch (t: Throwable) {
                var msg = LauncherAuthHttpClient.parseErrorMessage(t, "注册失败")
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
                RxMainScheduler.post { callback.onError(errMsg) }
            }
        }
    }

    /** 发送注册邮箱验证码。 */
    @JvmStatic
    fun sendRegistrationVerificationCode(context: Context, email: String, inviteCode: String,
                                         callback: SimpleCallback) {
        AppExecutors.runOnIo {
            try {
                val body = JSONObject()
                body.put("email", email)
                body.put("deviceId", LauncherUserData.getRealtimePlaytimeDeviceId(context))
                body.put("invite_code", inviteCode)
                LauncherAuthHttpClient.post("/auth/verify-code", body, null)
                RxMainScheduler.post { callback.onSuccess() }
            } catch (t: Throwable) {
                var message = normalizeEmailCodeError(LauncherAuthHttpClient.parseErrorMessage(t, "验证码发送失败"))
                if (message.contains("邀请码无效") || message.contains("已过期")) {
                    message = "邀请码无效或已过期"
                }
                RxMainScheduler.post { callback.onError(message) }
            }
        }
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
                LauncherAuthHttpClient.post("/auth/reset-password", body, null)
                clearToken(context)
                RxMainScheduler.post { callback.onSuccess() }
            } catch (t: Throwable) {
                val message = normalizeEmailCodeError(LauncherAuthHttpClient.parseErrorMessage(t, "密码重置失败"))
                RxMainScheduler.post { callback.onError(message) }
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
                val json = JSONObject(LauncherAuthHttpClient.get("/auth/subscription", token))
                val subscribed = json.optBoolean("subscribed", false)
                RxMainScheduler.post { callback.onSuccess(subscribed) }
            } catch (t: Throwable) {
                var message = LauncherAuthHttpClient.parseErrorMessage(t, "获取邮件订阅状态失败")
                if (message.contains("401")) {
                    expireSession(context)
                    message = "登录已过期，请重新登录"
                }
                val error = message
                RxMainScheduler.post { callback.onError(error) }
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
                val json = JSONObject(LauncherAuthHttpClient.put("/auth/subscription", body, token))
                RxMainScheduler.post { callback.onSuccess(json.optBoolean("subscribed", subscribed)) }
            } catch (t: Throwable) {
                var message = LauncherAuthHttpClient.parseErrorMessage(t, "更新邮件订阅失败")
                if (message.contains("401")) {
                    expireSession(context)
                    message = "登录已过期，请重新登录"
                }
                val error = message
                RxMainScheduler.post { callback.onError(error) }
            }
        }
    }

    private fun sendEmailCode(context: Context, path: String, email: String, fallback: String, callback: SimpleCallback) {
        AppExecutors.runOnIo {
            try {
                val body = JSONObject()
                body.put("email", email)
                body.put("deviceId", LauncherUserData.getRealtimePlaytimeDeviceId(context))
                LauncherAuthHttpClient.post(path, body, null)
                RxMainScheduler.post { callback.onSuccess() }
            } catch (t: Throwable) {
                val message = normalizeEmailCodeError(LauncherAuthHttpClient.parseErrorMessage(t, fallback))
                RxMainScheduler.post { callback.onError(message) }
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
        val response = LauncherAuthHttpClient.get("/auth/me", token)
        val json = JSONObject(response ?: "{}")
        val username = json.optString("username", "")
        val email = json.optString("email", "")
        saveUserInfo(context, username, email)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ========== 门面委托（迁移期保留，调用点全部切换至新 Bridge 后可整体删除） ==========

    /** @see LauncherUserBridge.fetchUserInfo */
    @JvmStatic
    fun fetchUserInfo(context: Context, callback: UserInfoCallback) {
        LauncherUserBridge.fetchUserInfo(context, callback)
    }

    /** @see LauncherUserBridge.updateUsername */
    @JvmStatic
    fun updateUsername(context: Context, newUsername: String, callback: AuthCallback) {
        LauncherUserBridge.updateUsername(context, newUsername, callback)
    }

    /** @see LauncherUserBridge.updatePassword */
    @JvmStatic
    fun updatePassword(context: Context, oldPassword: String, newPassword: String, callback: AuthCallback) {
        LauncherUserBridge.updatePassword(context, oldPassword, newPassword, callback)
    }

    /** @see LauncherUserBridge.fetchLlmConfig */
    @JvmStatic
    fun fetchLlmConfig(context: Context, callback: LlmConfigCallback) {
        LauncherUserBridge.fetchLlmConfig(context, callback)
    }

    /** @see LauncherUserBridge.updateLlmConfig */
    @JvmStatic
    fun updateLlmConfig(context: Context, config: LlmConfig?, callback: LlmConfigCallback) {
        LauncherUserBridge.updateLlmConfig(context, config, callback)
    }

    /** @see LauncherConfigSyncBridge.fetchConfig */
    @JvmStatic
    fun fetchConfig(context: Context, callback: ConfigCallback) {
        LauncherConfigSyncBridge.fetchConfig(context, callback)
    }

    /** @see LauncherConfigSyncBridge.uploadConfig */
    @JvmStatic
    fun uploadConfig(context: Context, configJson: String, callback: ConfigCallback) {
        LauncherConfigSyncBridge.uploadConfig(context, configJson, callback)
    }

    /** @see LauncherConfigSyncBridge.fetchPlayData */
    @JvmStatic
    fun fetchPlayData(context: Context, callback: PlayDataCallback) {
        LauncherConfigSyncBridge.fetchPlayData(context, callback)
    }

    /** @see LauncherConfigSyncBridge.uploadPlayData */
    @JvmStatic
    fun uploadPlayData(context: Context, playSql: String, callback: PlayDataCallback) {
        LauncherConfigSyncBridge.uploadPlayData(context, playSql, callback)
    }

    /** @see LauncherPlayTimeBridge.startPlayTimeSession */
    @JvmStatic
    fun startPlayTimeSession(context: Context, gameId: Long, gameTitle: String?, deviceId: String?,
                             callback: PlaySessionCallback) {
        LauncherPlayTimeBridge.startPlayTimeSession(context, gameId, gameTitle, deviceId, callback)
    }

    /** @see LauncherPlayTimeBridge.heartbeatPlayTimeSession */
    @JvmStatic
    fun heartbeatPlayTimeSession(context: Context, sessionId: String, callback: PlaySessionCallback) {
        LauncherPlayTimeBridge.heartbeatPlayTimeSession(context, sessionId, callback)
    }

    /** @see LauncherPlayTimeBridge.finishPlayTimeSession */
    @JvmStatic
    fun finishPlayTimeSession(context: Context, sessionId: String, callback: PlaySessionCallback) {
        LauncherPlayTimeBridge.finishPlayTimeSession(context, sessionId, callback)
    }

    /** @see LauncherPlayTimeBridge.uploadPlayTime */
    @JvmStatic
    fun uploadPlayTime(context: Context, records: List<JSONObject>?, callback: PlayTimeCallback) {
        LauncherPlayTimeBridge.uploadPlayTime(context, records, callback)
    }

    /** @see LauncherPlayTimeBridge.fetchPlayTime */
    @JvmStatic
    fun fetchPlayTime(context: Context, callback: PlayTimeCallback) {
        LauncherPlayTimeBridge.fetchPlayTime(context, callback)
    }

    /** @see LauncherPlayTimeBridge.fetchPlayTimeLeaderboard */
    @JvmStatic
    fun fetchPlayTimeLeaderboard(context: Context, callback: LeaderboardCallback) {
        LauncherPlayTimeBridge.fetchPlayTimeLeaderboard(context, callback)
    }

    /** @see LauncherPlayTimeBridge.fetchMyPlayTimeRank */
    @JvmStatic
    fun fetchMyPlayTimeRank(context: Context, callback: MyRankCallback) {
        LauncherPlayTimeBridge.fetchMyPlayTimeRank(context, callback)
    }
}
