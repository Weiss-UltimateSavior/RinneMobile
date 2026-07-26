package com.core.launcherbridge

import android.content.Context
import com.core.util.AppExecutors
import com.core.util.RxMainScheduler
import org.json.JSONObject
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.Locale

/**
 * 用户信息相关 API 桥接层。
 *
 * 从 [LauncherAuthBridge] 拆出，负责：
 * - 获取用户信息（fetchUserInfo）
 * - 修改用户名/密码（updateUsername / updatePassword）
 * - 用户级 LLM 配置读写（fetchLlmConfig / updateLlmConfig）
 *
 * 依赖 [LauncherAuthBridge] 提供 Token 存储与失效通知，
 * 依赖 [LauncherAuthHttpClient] 提供底层网络工具。
 */
object LauncherUserBridge {

    @JvmStatic
    fun fetchUserInfo(context: Context, callback: UserInfoCallback) {
        AppExecutors.runOnIo {
            try {
                LauncherAuthBridge.fetchAndSaveUserInfo(context)
                val nickname = LauncherAuthBridge.getNickname(context)
                val email = LauncherAuthBridge.getEmail(context)
                RxMainScheduler.post { callback.onSuccess(nickname, email) }
            } catch (t: Throwable) {
                var msg = LauncherAuthHttpClient.parseErrorMessage(t, "获取用户信息失败")
                if (msg.contains("401")) {
                    LauncherAuthBridge.expireSession(context)
                    msg = "登录已过期，请重新登录"
                }
                val errMsg = msg
                RxMainScheduler.post { callback.onError(errMsg) }
            }
        }
    }

    @JvmStatic
    fun updateUsername(context: Context, newUsername: String, callback: AuthCallback) {
        AppExecutors.runOnIo {
            try {
                val token = LauncherAuthBridge.getToken(context)
                if (token.isEmpty()) throw RuntimeException("未登录")
                val body = JSONObject()
                body.put("new_username", newUsername)
                val response = LauncherAuthHttpClient.put("/auth/username", body, token)
                val json = JSONObject(response ?: "{}")
                val username = json.optString("username", "")
                val email = json.optString("email", "")
                LauncherAuthBridge.saveUserInfo(context, username, email)
                RxMainScheduler.post { callback.onSuccess(token) }
            } catch (t: Throwable) {
                var msg = LauncherAuthHttpClient.parseErrorMessage(t, "修改用户名失败")
                if (msg.contains("401")) {
                    LauncherAuthBridge.expireSession(context)
                    msg = "登录已过期，请重新登录"
                } else if (msg.contains("用户名已存在")) {
                    msg = "该用户名已存在"
                } else if (msg.contains("422")) {
                    msg = "用户名格式有误，需3-32位字母、数字或下划线"
                }
                val errMsg = msg
                RxMainScheduler.post { callback.onError(errMsg) }
            }
        }
    }

    @JvmStatic
    fun updatePassword(context: Context, oldPassword: String, newPassword: String, callback: AuthCallback) {
        AppExecutors.runOnIo {
            try {
                val token = LauncherAuthBridge.getToken(context)
                if (token.isEmpty()) throw RuntimeException("未登录")
                val body = JSONObject()
                body.put("old_password", oldPassword)
                body.put("new_password", newPassword)
                LauncherAuthHttpClient.put("/auth/password", body, token)
                // 修改密码后 Token 全部吊销，清除本地 Token
                LauncherAuthBridge.clearToken(context)
                RxMainScheduler.post { callback.onSuccess("") }
            } catch (t: Throwable) {
                var msg = LauncherAuthHttpClient.parseErrorMessage(t, "修改密码失败")
                if (msg.contains("401")) {
                    LauncherAuthBridge.expireSession(context)
                    msg = "登录已过期，请重新登录"
                } else if (msg.contains("旧密码错误")) {
                    msg = "旧密码错误"
                } else if (msg.contains("422")) {
                    msg = "密码格式有误，需6-128位"
                }
                val errMsg = msg
                RxMainScheduler.post { callback.onError(errMsg) }
            }
        }
    }

    @JvmStatic
    fun fetchLlmConfig(context: Context, callback: LlmConfigCallback) {
        AppExecutors.runOnIo {
            try {
                val token = LauncherAuthBridge.getToken(context)
                if (token.isEmpty()) throw RuntimeException("未登录")
                val json = JSONObject(LauncherAuthHttpClient.get("/auth/llm", token))
                RxMainScheduler.post { callback.onSuccess(LlmConfig.fromJson(json)) }
            } catch (t: Throwable) {
                postLlmError(context, t, "获取模型配置失败", callback)
            }
        }
    }

    @JvmStatic
    fun updateLlmConfig(context: Context, config: LlmConfig?, callback: LlmConfigCallback) {
        AppExecutors.runOnIo {
            try {
                val token = LauncherAuthBridge.getToken(context)
                if (token.isEmpty()) throw RuntimeException("未登录")
                val json = JSONObject(LauncherAuthHttpClient.putLlm("/auth/llm", config?.toJson() ?: LlmConfig().toJson(), token))
                val result = json.optJSONObject("llm")
                RxMainScheduler.post { callback.onSuccess(LlmConfig.fromJson(result ?: json)) }
            } catch (t: Throwable) {
                postLlmError(context, t, "保存模型配置失败", callback)
            }
        }
    }

    private fun postLlmError(context: Context, error: Throwable?, fallback: String, callback: LlmConfigCallback) {
        var message = LauncherAuthHttpClient.parseErrorMessage(error, fallback)
        if (isNetworkTimeout(error, message)) message = "模型服务不可达或响应超时，请检查接口地址和网络连接"
        else if (message.contains("401")) { LauncherAuthBridge.expireSession(context); message = "登录已过期，请重新登录" }
        else if (message.contains("AI_CONNECTION_TEST_FAILED")) message = "模型连通性验证失败，请检查接口地址、API Key 和模型名称"
        else if (message.contains("422")) message = "模型配置格式不正确，请检查地址、模型名与温度"
        else if (message.contains("404") || message.contains("用户不存在")) message = "用户不存在，请重新登录后重试"
        val finalMessage = message
        RxMainScheduler.post { callback.onError(finalMessage) }
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
}
