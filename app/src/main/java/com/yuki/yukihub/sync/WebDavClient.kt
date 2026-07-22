package com.yuki.yukihub.sync

import android.util.Log
import com.yuki.yukihub.net.HttpClient
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * WebDAV 客户端
 * 支持坚果云、OneDrive、NextCloud 等任意 WebDAV 服务器
 *
 * 基于 OkHttp 直接构建请求，避免 Retrofit 对 ResponseBody 的消费问题。
 */
class WebDavClient(serverUrl: String, username: String, password: String) {

    private val serverUrl: String = normalizeServerUrl(serverUrl)
    private val username: String
    private val password: String
    private val client: OkHttpClient

    init {
        if (isInsecureHttp(this.serverUrl)) {
            throw IllegalArgumentException(
                "WebDAV 不支持远程明文 HTTP 连接。" +
                    "Android 9+ 禁止非 HTTPS 明文流量，请使用具有系统信任证书的 HTTPS 地址或反向代理。"
            )
        }
        this.username = username
        this.password = password

        val credential = Credentials.basic(username, password)

        client = HttpClient.defaultBuilder()
            .addInterceptor { chain ->
                val original = chain.request()
                val request = original.newBuilder()
                    .header("Authorization", credential)
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    /**
     * 规范化 WebDAV 地址。
     * 坚果云必须使用 https://dav.jianguoyun.com/dav/ ，如果用户漏填 /dav/ 自动补上。
     */
    private fun normalizeServerUrl(raw: String?): String {
        var s = raw?.trim() ?: ""
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        val lower = s.lowercase()
        if (lower.contains("dav.jianguoyun.com") && !lower.contains("/dav")) {
            if (!s.endsWith("/")) s += "/"
            s += "dav/"
        }
        return if (s.endsWith("/")) s else "$s/"
    }

    /**
     * 测试连接
     */
    fun testConnection(): Boolean {
        return try {
            testConnectionOrThrow()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed", e)
            false
        }
    }

    /**
     * 测试连接，失败时抛出具体原因。
     */
    @Throws(IOException::class)
    fun testConnectionOrThrow() {
        val testPath = "YukiHub/YukiHub_connection_test.txt"
        writeText(testPath, "ok")
        delete(testPath)
    }

    /**
     * 创建目录（如果不存在）
     */
    fun mkdirs(path: String): Boolean {
        return try {
            val parts = path.split("/")
            var current = ""
            for (part in parts) {
                if (part.isNotEmpty()) {
                    current += "$part/"
                    if (!exists(current)) {
                        mkcol(current)
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "mkdirs failed: $path", e)
            false
        }
    }

    /**
     * MKCOL 创建目录
     */
    @Throws(IOException::class)
    private fun mkcol(path: String): Boolean {
        val request = Request.Builder()
            .url(resolveUrl(path))
            .method("MKCOL", null)
            .build()
        client.newCall(request).execute().use { response ->
            val code = response.code
            return (code in 200..299) || code == 405 || code == 409
        }
    }

    /**
     * 检查文件/目录是否存在
     */
    fun exists(path: String): Boolean {
        val request = Request.Builder()
            .url(resolveUrl(path))
            .head()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                response.code in 200..299
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 读取文件内容，可选限制最大字节数。调用同步/导入路径时必须传入上限，
     * 防止异常 WebDAV 服务端用未知长度的响应耗尽应用内存。
     */
    @Throws(IOException::class)
    fun readFileLimited(path: String, maxBytes: Long): ByteArray {
        if (maxBytes == 0L || maxBytes < -1) throw IllegalArgumentException("maxBytes must be positive or -1")
        val request = Request.Builder()
            .url(resolveUrl(path))
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                var err = ""
                val errBody = response.body
                if (errBody != null) err = String(readBodyLimited(errBody, 64 * 1024L, "WebDAV 错误响应"), StandardCharsets.UTF_8)
                throw IOException("HTTP ${response.code}" + if (err.isEmpty()) "" else ": $err")
            }
            val body = response.body ?: throw IOException("Empty response body")
            return readBodyLimited(body, maxBytes, "远程同步文件")
        }
    }

    /**
     * 写入文件（二进制，用于 gzip 压缩数据）
     */
    @Throws(IOException::class)
    fun writeFile(path: String, data: ByteArray) {
        val body = data.toRequestBody(MEDIA_TYPE_OCTET_STREAM)
        val request = Request.Builder()
            .url(resolveUrl(path))
            .put(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                var err = ""
                val errBody = response.body
                if (errBody != null) err = String(readBodyLimited(errBody, 64 * 1024L, "WebDAV 错误响应"), StandardCharsets.UTF_8)
                throw IOException("PUT $path failed: HTTP ${response.code}" + if (err.isEmpty()) "" else ": $err")
            }
        }
    }

    /**
     * 写入文本文件
     */
    @Throws(IOException::class)
    fun writeText(path: String, text: String) {
        writeFile(path, text.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * 删除文件
     */
    fun delete(path: String): Boolean {
        val request = Request.Builder()
            .url(resolveUrl(path))
            .delete()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Delete failed: $path", e)
            false
        }
    }

    /**
     * 列出目录内容
     */
    @Throws(IOException::class)
    fun listFiles(path: String): List<WebDavItem> {
        val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<D:propfind xmlns:D=\"DAV:\">\n" +
            "  <D:allprop/>\n" +
            "</D:propfind>"

        val body = xml.toByteArray(StandardCharsets.UTF_8).toRequestBody(MEDIA_TYPE_XML)
        val request = Request.Builder()
            .url(resolveUrl(path))
            .method("PROPFIND", body)
            .header("Depth", "1")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                var err = ""
                val errBody = response.body
                if (errBody != null) err = String(readBodyLimited(errBody, 64 * 1024L, "WebDAV 错误响应"), StandardCharsets.UTF_8)
                throw IOException("HTTP ${response.code}: $err")
            }
            val resBody = response.body ?: throw IOException("Empty response body")
            val responseText = String(readBodyLimited(resBody, 1024 * 1024L, "WebDAV 目录列表"), StandardCharsets.UTF_8)
            return parsePropfindResponse(responseText, path)
        }
    }

    /**
     * 获取文件最后修改时间
     */
    fun getLastModified(path: String): Long {
        val request = Request.Builder()
            .url(resolveUrl(path))
            .head()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                HttpClient.parseHttpDate(response.header("Last-Modified"))
            }
        } catch (e: Exception) {
            0
        }
    }

    // ---- 内部辅助方法 ----

    private fun resolveUrl(path: String): String {
        val fullPath = if (path.startsWith("/")) path.substring(1) else path
        return serverUrl + fullPath
    }

    /**
     * 解析 PROPFIND 响应
     */
    private fun parsePropfindResponse(xml: String, basePath: String): List<WebDavItem> {
        val items = ArrayList<WebDavItem>()
        val responses = xml.split(Regex("<D:response>|<d:response>"))

        for (i in 1 until responses.size) {
            val resp = responses[i]
            val href = extractTag(resp, "D:href", "d:href") ?: continue
            val isDir = resp.contains("<D:collection/>") || resp.contains("<d:collection/>")

            var lastModified = 0L
            val lastModStr = extractTag(resp, "D:getlastmodified", "d:getlastmodified")
            if (lastModStr != null) {
                lastModified = HttpClient.parseHttpDate(lastModStr)
            }

            var name = href
            if (name.endsWith("/")) name = name.substring(0, name.length - 1)
            val lastSlash = name.lastIndexOf('/')
            if (lastSlash >= 0) name = name.substring(lastSlash + 1)
            if (name.isEmpty() || name == ".") continue

            items.add(WebDavItem(name, href, isDir, lastModified))
        }
        return items
    }

    private fun extractTag(xml: String, vararg tags: String): String? {
        for (tag in tags) {
            val start = xml.indexOf("<$tag>")
            if (start >= 0) {
                val contentStart = start + tag.length + 2
                val end = xml.indexOf("</$tag>", contentStart)
                if (end > contentStart) return xml.substring(contentStart, end).trim()
            }
        }
        return null
    }

    /**
     * WebDAV 文件/目录项
     */
    data class WebDavItem(
        @JvmField val name: String,
        @JvmField val href: String,
        @JvmField val isDirectory: Boolean,
        @JvmField val lastModified: Long
    ) {
        override fun toString(): String {
            return (if (isDirectory) "\uD83D\uDCC1 " else "\uD83D\uDCC4 ") + name
        }
    }

    companion object {
        private const val TAG = "WebDavClient"

        private val MEDIA_TYPE_OCTET_STREAM = "application/octet-stream".toMediaType()
        private val MEDIA_TYPE_XML = "application/xml".toMediaType()

        /**
         * 判断 URL 是否为不安全的明文 HTTP（非 localhost/127.0.0.1）。
         * 这两个回环主机在 network_security_config 中已精确放行。
         */
        @JvmStatic
        fun isInsecureHttp(url: String?): Boolean {
            if (url == null) return false
            return try {
                val parsed = URI.create(url)
                if (!"http".equals(parsed.scheme, ignoreCase = true)) return false
                val host = parsed.host
                !("localhost".equals(host, ignoreCase = true) || "127.0.0.1" == host)
            } catch (ignored: IllegalArgumentException) {
                // Malformed values beginning with HTTP must not bypass validation.
                url.regionMatches(0, "http://", 0, "http://".length, ignoreCase = true)
            }
        }

        @Throws(IOException::class)
        private fun readBodyLimited(body: ResponseBody, maxBytes: Long, label: String): ByteArray {
            val declaredLength = body.contentLength()
            if (maxBytes >= 0 && declaredLength > maxBytes) {
                throw IOException("$label 过大（服务端声明 $declaredLength 字节，最大允许 $maxBytes 字节）")
            }
            body.byteStream().use { input: InputStream ->
                ByteArrayOutputStream(
                    if (declaredLength > 0 && declaredLength <= Int.MAX_VALUE) declaredLength.toInt() else 8192
                ).use { out ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        total += read
                        if (maxBytes >= 0 && total > maxBytes) {
                            throw IOException("$label 过大（读取超过最大允许 $maxBytes 字节）")
                        }
                        out.write(buffer, 0, read)
                    }
                    return out.toByteArray()
                }
            }
        }
    }
}
