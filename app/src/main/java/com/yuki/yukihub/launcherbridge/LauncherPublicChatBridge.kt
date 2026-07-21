package com.yuki.yukihub.launcherbridge

import android.content.Context
import com.yuki.yukihub.util.AppExecutors
import com.yuki.yukihub.util.RxMainScheduler
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 用户侧公共聊天客户端。刻意不包含任何管理员端点。
 */
object LauncherPublicChatBridge {

    private const val API_BASE = "https://api.rinne.cyou:9999"
    private const val MAX_RESPONSE_BYTES = 1024L * 1024L
    private val WEB_SOCKET_CLIENT = OkHttpClient()

    @JvmStatic
    fun loadInitial(context: Context, callback: ChatCallback) {
        AppExecutors.runOnIo {
            try {
                val token = requireToken(context)
                val response = JSONObject(get("/chat/public/messages?limit=50", token))
                val messages = parseMessages(response.optJSONArray("messages"))
                val cursor: Int? = if (response.isNull("next_before_id")) null else response.optInt("next_before_id")
                post { callback.onSuccess(messages, cursor) }
            } catch (error: Throwable) {
                post { callback.onError(errorMessage(context, error, "加载消息失败")) }
            }
        }
    }

    @JvmStatic
    fun loadOlder(context: Context, beforeId: Int, callback: ChatCallback) {
        AppExecutors.runOnIo {
            try {
                val token = requireToken(context)
                val response = JSONObject(get("/chat/public/messages?limit=50&before_id=$beforeId", token))
                val messages = parseMessages(response.optJSONArray("messages"))
                val cursor: Int? = if (response.isNull("next_before_id")) null else response.optInt("next_before_id")
                post { callback.onSuccess(messages, cursor) }
            } catch (error: Throwable) {
                post { callback.onError(errorMessage(context, error, "加载历史消息失败")) }
            }
        }
    }

    @JvmStatic
    fun send(context: Context, content: String, callback: MessageCallback) {
        AppExecutors.runOnIo {
            try {
                val token = requireToken(context)
                val body = JSONObject().put("content", content)
                val message = parseMessage(JSONObject(postJson("/chat/public/messages", body, token)))
                post { callback.onSuccess(message) }
            } catch (error: Throwable) {
                post { callback.onError(errorMessage(context, error, "发送失败")) }
            }
        }
    }

    @JvmStatic
    fun loadStatus(context: Context, callback: StatusCallback) {
        AppExecutors.runOnIo {
            try {
                val json = JSONObject(get("/chat/public/status", requireToken(context)))
                post {
                    callback.onSuccess(Status(
                        json.optBoolean("readonly"),
                        json.optBoolean("muted"),
                        if (json.isNull("muted_until")) null else json.optLong("muted_until"),
                        json.optString("mute_reason")
                    ))
                }
            } catch (error: Throwable) {
                post { callback.onError(errorMessage(context, error, "获取频道状态失败")) }
            }
        }
    }

    @JvmStatic
    fun loadAnnouncements(context: Context, callback: AnnouncementsCallback) {
        AppExecutors.runOnIo {
            try {
                val array = JSONArray(get("/chat/public/announcements", requireToken(context)))
                val announcements = ArrayList<Announcement>()
                for (i in 0 until array.length()) announcements.add(parseAnnouncement(array.getJSONObject(i)))
                post { callback.onSuccess(announcements) }
            } catch (error: Throwable) {
                post { callback.onError(errorMessage(context, error, "获取公告失败")) }
            }
        }
    }

    @JvmStatic
    fun connect(context: Context, listener: RealtimeListener): WebSocket? {
        val token = LauncherAuthBridge.getToken(context)
        if (token.isNullOrBlank()) {
            post { listener.onError("请先登录后再进入公共聊天室") }
            return null
        }
        try {
            val wsBase = if (API_BASE.startsWith("https://")) "wss://${API_BASE.substring(8)}" else "ws://${API_BASE.substring(7)}"
            val url = "$wsBase/chat/public/ws?token=${URLEncoder.encode(token, "UTF-8")}"
            return WEB_SOCKET_CLIENT.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    post { listener.onConnected() }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val event = JSONObject(text)
                        val type = event.optString("type")
                        val data = event.optJSONObject("data")
                        when (type) {
                            "message_created" -> listener.onMessageCreated(parseMessage(data!!.getJSONObject("message")))
                            "message_deleted" -> listener.onMessageDeleted(data!!.optInt("message_id"))
                            "message_pinned" -> listener.onMessagePinned(parseMessage(data!!.getJSONObject("message")))
                            "readonly_updated" -> listener.onReadonlyChanged(data!!.optBoolean("readonly"))
                            "user_muted" -> listener.onMuted(true, if (data!!.isNull("muted_until")) null else data.optLong("muted_until"), data.optString("reason"))
                            "user_unmuted" -> listener.onMuted(false, null, "")
                            "announcement_created", "announcement_updated" -> listener.onAnnouncementChanged(parseAnnouncement(data!!.getJSONObject("announcement")))
                        }
                    } catch (_: Throwable) {
                    }
                }

                override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                    val expired = response != null && response.code == 401
                    if (expired) LauncherAuthBridge.expireSession(context)
                    post { listener.onError(if (expired) "登录已过期，请重新登录" else "实时连接已断开") }
                }
            })
        } catch (_: Throwable) {
            post { listener.onError("无法建立实时连接") }
            return null
        }
    }

    private fun requireToken(context: Context): String {
        val token = LauncherAuthBridge.getToken(context)
        if (token.isNullOrBlank()) throw IllegalStateException("请先登录后再进入公共聊天室")
        return token
    }

    @Throws(Exception::class)
    private fun get(path: String, token: String): String = request(path, "GET", null, token)

    @Throws(Exception::class)
    private fun postJson(path: String, body: JSONObject, token: String): String = request(path, "POST", body, token)

    @Throws(Exception::class)
    private fun request(path: String, method: String, body: JSONObject?, token: String): String {
        val connection = URL(API_BASE + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
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
            val text = read(if (code in 200..299) connection.inputStream else connection.errorStream)
            if (code !in 200..299) throw RuntimeException("HTTP $code: ${detail(text)}")
            return text
        } finally {
            connection.disconnect()
        }
    }

    @Throws(Exception::class)
    private fun read(input: InputStream?): String {
        if (input == null) return ""
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            var count: Int
            while (stream.read(buffer).also { count = it } != -1) {
                if (output.size() + count > MAX_RESPONSE_BYTES) throw java.io.IOException("response too large")
                output.write(buffer, 0, count)
            }
            return output.toString("UTF-8")
        }
    }

    private fun detail(response: String): String = try {
        JSONObject(response).optString("detail", response)
    } catch (_: Throwable) {
        response
    }

    private fun errorMessage(context: Context, error: Throwable?, fallback: String): String {
        val message = error?.message
        if (message != null && message.contains("401")) {
            LauncherAuthBridge.expireSession(context)
            return "登录已过期，请重新登录"
        }
        return if (message.isNullOrBlank()) fallback else message
    }

    private fun post(runnable: Runnable) {
        RxMainScheduler.post(runnable)
    }

    @Throws(Exception::class)
    private fun parseMessages(array: JSONArray?): List<Message> {
        val result = ArrayList<Message>()
        if (array != null) {
            for (i in 0 until array.length()) result.add(parseMessage(array.getJSONObject(i)))
        }
        return result
    }

    private fun parseMessage(json: JSONObject): Message = Message(
        json.optInt("id"),
        json.optString("sender_type"),
        json.optString("sender_id"),
        json.optString("sender_name"),
        json.optString("content"),
        json.optBoolean("is_pinned"),
        json.optLong("created_at")
    )

    private fun parseAnnouncement(json: JSONObject): Announcement = Announcement(
        json.optInt("id"),
        json.optString("title"),
        json.optString("content"),
        json.optBoolean("is_active", true),
        json.optLong("created_at")
    )

    class Message(
        @JvmField val id: Int,
        @JvmField val senderType: String,
        @JvmField val senderId: String,
        @JvmField val senderName: String,
        @JvmField val content: String,
        @JvmField val pinned: Boolean,
        @JvmField val createdAt: Long
    )

    class Status(
        @JvmField val readonly: Boolean,
        @JvmField val muted: Boolean,
        @JvmField val mutedUntil: Long?,
        @JvmField val muteReason: String
    )

    class Announcement(
        @JvmField val id: Int,
        @JvmField val title: String,
        @JvmField val content: String,
        @JvmField val active: Boolean,
        @JvmField val createdAt: Long
    )

    interface ChatCallback {
        fun onSuccess(messages: List<Message>, nextBeforeId: Int?)
        fun onError(message: String)
    }

    interface MessageCallback {
        fun onSuccess(message: Message)
        fun onError(message: String)
    }

    interface StatusCallback {
        fun onSuccess(status: Status)
        fun onError(message: String)
    }

    interface AnnouncementsCallback {
        fun onSuccess(announcements: List<Announcement>)
        fun onError(message: String)
    }

    interface RealtimeListener {
        fun onConnected()
        fun onMessageCreated(message: Message)
        fun onMessageDeleted(messageId: Int)
        fun onMessagePinned(message: Message)
        fun onReadonlyChanged(readonly: Boolean)
        fun onMuted(muted: Boolean, mutedUntil: Long?, reason: String)
        fun onAnnouncementChanged(announcement: Announcement)
        fun onError(message: String)
    }
}
