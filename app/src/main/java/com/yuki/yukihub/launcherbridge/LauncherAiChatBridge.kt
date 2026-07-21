package com.yuki.yukihub.launcherbridge

import android.content.Context
import com.yuki.yukihub.util.AppExecutors
import com.yuki.yukihub.util.RxMainScheduler
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
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
                postMain { callback.onSuccess(messages) }
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
                postMain { callback.onSuccess(json.optString("message", "")) }
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
                postMain { callback.onSuccess() }
            } catch (t: Throwable) {
                postError(context, t, "清空聊天记录失败") { callback.onError(it) }
            }
        }
    }

    @Throws(Exception::class)
    private fun request(context: Context, method: String, path: String, body: JSONObject?): String {
        val token = LauncherAuthBridge.getToken(context)
        if (token.isNullOrBlank()) throw RuntimeException("未登录")
        val connection = URL(API_BASE + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            val raw: InputStream? = if (code in 200..299) connection.inputStream else connection.errorStream
            val output = ByteArrayOutputStream()
            raw?.use { stream ->
                val buffer = ByteArray(4096)
                var length: Int
                while (stream.read(buffer).also { length = it } != -1) {
                    if (output.size() + length > MAX_RESPONSE_BYTES) throw java.io.IOException("response too large")
                    output.write(buffer, 0, length)
                }
            }
            val response = output.toString("UTF-8")
            if (code !in 200..299) {
                var detail = response
                try {
                    val value = JSONObject(response).opt("detail")
                    if (value is JSONObject) detail = value.optString("message", response)
                } catch (_: Throwable) {
                }
                throw RuntimeException("HTTP $code: $detail")
            }
            return response
        } finally {
            connection.disconnect()
        }
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
        postMain { sink(finalMessage) }
    }

    private fun postMain(runnable: Runnable) {
        RxMainScheduler.post(runnable)
    }
}
