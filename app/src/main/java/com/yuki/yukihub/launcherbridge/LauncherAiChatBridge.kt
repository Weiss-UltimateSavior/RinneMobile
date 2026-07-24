package com.yuki.yukihub.launcherbridge

import android.content.Context
import com.yuki.yukihub.util.AppExecutors
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 用户侧 AI 聊天桥接。仅暴露已文档化的 /ai/chat 用户端点。
 */
object LauncherAiChatBridge {

    private const val API_BASE = "https://api.rinne.cyou:9999"
    private const val MAX_RESPONSE_BYTES = 1024L * 1024L

    class Message(role: String?, content: String?, name: String?) {
        @JvmField val role: String = role ?: ""
        @JvmField val content: String = content ?: ""
        @JvmField val name: String = name ?: ""
    }

    interface HistoryCallback {
        fun onSuccess(messages: List<Message>)
        fun onError(message: String)
    }

    interface ReplyCallback {
        fun onSuccess(reply: String)
        fun onError(message: String)
    }

    interface Callback {
        fun onSuccess()
        fun onError(message: String)
    }

    @JvmStatic
    fun loadHistory(context: Context, threadId: String, callback: HistoryCallback) {
        AppExecutors.runOnIo {
            try {
                val array = JSONObject(request(context, "GET", "/ai/chat/history/${encode(threadId)}", null))
                    .optJSONArray("messages")
                val messages = ArrayList<Message>()
                if (array != null) {
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        messages.add(Message(item.optString("role"), item.optString("content"), item.optString("name")))
                    }
                }
                postToMain { callback.onSuccess(messages) }
            } catch (t: Throwable) {
                postError(context, t, "获取聊天记录失败") { callback.onError(it) }
            }
        }
    }

    @JvmStatic
    fun send(context: Context, message: String, persona: String, threadId: String, callback: ReplyCallback) {
        AppExecutors.runOnIo {
            try {
                val body = JSONObject()
                body.put("message", message)
                body.put("persona", persona)
                body.put("thread_id", threadId)
                val json = JSONObject(request(context, "POST", "/ai/chat", body))
                postToMain { callback.onSuccess(json.optString("message", "")) }
            } catch (t: Throwable) {
                postError(context, t, "聊天请求失败") { callback.onError(it) }
            }
        }
    }

    @JvmStatic
    fun clearHistory(context: Context, threadId: String, callback: Callback) {
        AppExecutors.runOnIo {
            try {
                request(context, "DELETE", "/ai/chat/history/${encode(threadId)}", null)
                postToMain { callback.onSuccess() }
            } catch (t: Throwable) {
                postError(context, t, "清空聊天记录失败") { callback.onError(it) }
            }
        }
    }

    @Throws(Exception::class)
    private fun request(context: Context, method: String, path: String, body: JSONObject?): String {
        val token = LauncherAuthBridge.getToken(context)
        if (token.isNullOrBlank()) throw RuntimeException("未登录")
        return LauncherBridgeHttp.request(
            url = API_BASE + path, method = method, body = body?.toString(), bearerToken = token,
            readTimeoutMs = 30_000, maxResponseBytes = MAX_RESPONSE_BYTES
        )
    }

    @Throws(Exception::class)
    private fun encode(value: String?): String =
        URLEncoder.encode(value ?: "", "UTF-8")

    private fun postError(context: Context, error: Throwable?, fallback: String, sink: (String) -> Unit) {
        var message = error?.message ?: fallback
        if (message.contains("401")) {
            LauncherAuthBridge.expireSession(context)
            message = "登录已过期，请重新登录"
        } else if (message.contains("502") || message.contains("CHAT_SERVICE_UNAVAILABLE")) {
            message = "聊天服务暂时不可用，请稍后重试"
        } else if (message.contains("422")) {
            message = "消息内容或会话参数不符合要求"
        }
        val finalMessage = message
        postToMain { sink(finalMessage) }
    }
}
