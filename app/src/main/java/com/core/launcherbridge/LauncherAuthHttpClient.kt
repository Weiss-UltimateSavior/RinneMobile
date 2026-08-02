package com.core.launcherbridge

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 认证桥内部共享的 HTTP 客户端工具。
 *
 * - 仅模块内部可见（`internal`），不暴露给 Java 调用方；
 * - 集中管理 `API_BASE`、各路超时、响应缓冲上限与统一错误解析，
 *   供 [LauncherAuthBridge] / [LauncherUserBridge] / [LauncherConfigSyncBridge] /
 *   [LauncherPlayTimeBridge] 复用，避免每个桥各自维护一份网络代码。
 * - 大数据量请求与响应使用 `MAX_PLAY_DATA_RESPONSE_BYTES` 上限，防止异常服务端耗尽内存。
 */
internal object LauncherAuthHttpClient {

    internal const val API_BASE = "https://api.rinne.cyou:9999"

    /** 普通响应读取上限：256KiB。 */
    internal const val MAX_RESPONSE_BYTES = 256L * 1024L

    /** 错误响应读取上限：64KiB。 */
    internal const val MAX_ERROR_RESPONSE_BYTES = 64L * 1024L

    /** 账户游玩数据的云备份/恢复上限；与本地和 WebDAV 快照上限保持一致量级。 */
    internal const val MAX_PLAY_DATA_RESPONSE_BYTES = 16L * 1024L * 1024L

    // ========== 请求方法 ==========

    /** 大数据量 PUT：超时更长，响应缓冲更大（适配游玩记录上传/下载）。 */
    @Throws(Exception::class)
    internal fun putLarge(path: String, body: JSONObject, authToken: String?): String {
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
    internal fun put(path: String, body: JSONObject, authToken: String?): String {
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
    internal fun post(path: String, body: JSONObject, authToken: String?): String {
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
    internal fun postNoBody(path: String, authToken: String?): String {
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
    internal fun putLlm(path: String, body: JSONObject, authToken: String?): String {
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
    internal fun get(path: String, authToken: String?): String {
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
    internal fun getLarge(path: String, authToken: String?): String {
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

    // ========== 响应处理 ==========

    @Throws(Exception::class)
    internal fun readResponse(c: HttpURLConnection): String {
        val code = c.responseCode
        val text = readTextLimited(c, if (code in 200..299) c.inputStream else c.errorStream,
            if (code in 200..299) MAX_RESPONSE_BYTES else MAX_ERROR_RESPONSE_BYTES, "服务器响应")
        return checkResponse(code, text)
    }

    /** 大响应读取：仅用于账户游玩数据，仍设置明确上限避免异常服务端耗尽内存。 */
    @Throws(Exception::class)
    internal fun readLargeResponse(c: HttpURLConnection): String {
        val code = c.responseCode
        val text = readTextLimited(c, if (code in 200..299) c.inputStream else c.errorStream,
            if (code in 200..299) MAX_PLAY_DATA_RESPONSE_BYTES else MAX_ERROR_RESPONSE_BYTES, "账户游玩数据响应")
        return checkResponse(code, text)
    }

    @Throws(Exception::class)
    internal fun checkResponse(code: Int, text: String): String {
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
            } catch (_: JSONException) {
                // 错误体解析失败时保留原始文本，由下方统一抛出
            }
            throw RuntimeException("HTTP $code: ${trim(detail, 200)}")
        }
        return text
    }

    @Throws(Exception::class)
    internal fun writeRequestBody(connection: HttpURLConnection, body: JSONObject, maxBytes: Long, label: String) {
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
    internal fun readTextLimited(connection: HttpURLConnection, inputStream: InputStream?, maxBytes: Long, label: String): String {
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

    // ========== 工具方法 ==========

    internal fun trim(text: String?, max: Int): String {
        if (text == null) return ""
        val t = text.trim()
        if (max <= 0 || t.length <= max) return t
        return t.substring(0, max) + "..."
    }

    @Throws(Exception::class)
    internal fun utf8Length(text: String?): Int =
        text?.toByteArray(Charsets.UTF_8)?.size ?: 0

    internal fun parseErrorMessage(t: Throwable?, fallback: String): String {
        if (t == null) return fallback
        val msg = t.message
        if (msg.isNullOrBlank()) return fallback
        return msg
    }
}
