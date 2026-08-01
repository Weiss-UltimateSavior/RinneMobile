package com.core.agent.store

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale
import java.util.UUID

/** Local registry for explicitly approved remote Streamable HTTP MCP servers. */
object McpServerStore {
    private const val PREFS = "rinne_mcp_servers"
    private const val KEY_SERVERS = "servers"
    private const val MAX_SERVERS = 12

    data class Server(
        @JvmField val id: String?,
        @JvmField val name: String?,
        @JvmField val endpoint: String?,
        @JvmField val createdAt: Long
    )

    @JvmStatic
    @JvmName("add")
    @Throws(Exception::class)
    fun add(context: Context, name: String?, endpoint: String?): Server {
        val safeName = validateName(name)
        val safeEndpoint = validateEndpoint(endpoint)
        val servers = read(context)
        for (server in servers) {
            if (server.endpoint?.equals(safeEndpoint, ignoreCase = true) == true) {
                // Model providers may retry or emit duplicate tool calls. Treat adding the same
                // endpoint as an idempotent success so a real first write is not reported as failed.
                return server
            }
        }
        if (servers.size >= MAX_SERVERS) throw IllegalStateException("最多添加 $MAX_SERVERS 个 MCP 服务器")
        val value = Server(UUID.randomUUID().toString(), safeName, safeEndpoint, System.currentTimeMillis())
        servers.add(value)
        write(context, servers)
        return value
    }

    @JvmStatic
    @JvmName("get")
    @Throws(Exception::class)
    fun get(context: Context, id: String?): Server {
        if (id == null || !id.matches("[0-9a-fA-F-]{36}".toRegex())) throw IllegalArgumentException("server_id 格式错误")
        for (server in read(context)) if (server.id?.equals(id, ignoreCase = true) == true) return server
        throw IllegalArgumentException("未找到 MCP 服务器")
    }

    @JvmStatic
    @JvmName("remove")
    @Throws(Exception::class)
    fun remove(context: Context, id: String?): Server {
        val servers = read(context)
        var target: Server? = null
        for (server in servers) if (server.id?.equals(id, ignoreCase = true) == true) { target = server; break }
        if (target == null) throw IllegalArgumentException("未找到 MCP 服务器")
        servers.remove(target)
        write(context, servers)
        return target
    }

    @JvmStatic
    @JvmName("list")
    @Throws(Exception::class)
    fun list(context: Context): String {
        val items = JSONArray()
        for (server in read(context)) items.put(toJson(server))
        return JSONObject().put("success", true)
            .put("source", "local_confirmed_registry")
            .put("message", "以下 MCP 服务器均已经用户本机确认并保存")
            .put("servers", items)
            .toString()
    }

    @JvmStatic
    @JvmName("trustedModelContext")
    @Throws(Exception::class)
    fun trustedModelContext(context: Context): String {
        return "设备本地 MCP 注册表（这是可信本地状态，不是模型推测）：" + list(context)
    }

    @JvmStatic
    @JvmName("savedSummary")
    @Throws(Exception::class)
    fun savedSummary(context: Context): String {
        val servers = read(context)
        if (servers.isEmpty()) return ""
        val value = StringBuilder("本机当前已确认并保存的 MCP：")
        for (server in servers) {
            value.append("\n- ").append(server.name).append("（").append(server.endpoint).append("）")
        }
        return value.toString()
    }

    @JvmStatic
    @JvmName("findByEndpoint")
    @Throws(Exception::class)
    fun findByEndpoint(context: Context, endpoint: String?): Server? {
        val safeEndpoint = validateEndpoint(endpoint)
        for (server in read(context)) {
            if (server.endpoint?.equals(safeEndpoint, ignoreCase = true) == true) return server
        }
        return null
    }

    @JvmStatic
    @JvmName("preview")
    fun preview(server: Server): String {
        return "名称：${server.name}\n地址：${server.endpoint}\n传输：Streamable HTTP\n\n该服务器及其工具描述会发送给你配置的模型服务。每次调用远程工具仍需本机确认。"
    }

    @JvmStatic
    @JvmName("validateName")
    fun validateName(value: String?): String {
        val result = if (value == null) "" else value.trim { it <= ' ' }
        if (result.isEmpty() || result.length > 80) throw IllegalArgumentException("MCP 名称长度应为 1-80 个字符")
        if (result.matches(".*[\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069].*".toRegex())) throw IllegalArgumentException("MCP 名称包含不允许的字符")
        return result
    }

    @JvmStatic
    @JvmName("validateEndpoint")
    fun validateEndpoint(value: String?): String {
        var result = if (value == null) "" else value.trim { it <= ' ' }
        while (result.endsWith("/")) result = result.substring(0, result.length - 1)
        if (result.isEmpty() || result.length > 2048) throw IllegalArgumentException("MCP 地址长度不正确")
        return try {
            val uri = URI(result)
            val scheme = uri.scheme
            val host = uri.host
            if (scheme == null || host == null || host.trim { it <= ' ' }.isEmpty() || uri.rawUserInfo != null
                || uri.rawQuery != null || uri.rawFragment != null
            ) {
                throw IllegalArgumentException("MCP 地址格式不正确")
            }
            val cleartextLoopback = isLoopbackHost(host)
            if (!"https".equals(scheme, ignoreCase = true) && !(cleartextLoopback && "http".equals(scheme, ignoreCase = true))) {
                throw IllegalArgumentException("MCP 地址必须使用 HTTPS；仅 localhost 或 127.0.0.1 允许 HTTP")
            }
            result
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("MCP 地址格式不正确", error)
        }
    }

    @JvmStatic
    @JvmName("decode")
    @Throws(Exception::class)
    fun decode(raw: String?): MutableList<Server> {
        val values = JSONArray(raw ?: "[]")
        val result = mutableListOf<Server>()
        for (i in 0 until values.length()) {
            val item = values.optJSONObject(i) ?: continue
            // Current records are serialized as server_id. Accept the old id spelling as a
            // migration fallback, but never discard a valid current record on the next run.
            val id = item.optString("server_id", item.optString("id"))
            try {
                if (!id.matches("[0-9a-fA-F-]{36}".toRegex())) continue
                result.add(Server(id, validateName(item.optString("name")),
                    validateEndpoint(item.optString("endpoint")), item.optLong("created_at")))
            } catch (ignored: Throwable) {
                // Skip invalid records during migration
            }
        }
        return result
    }

    @JvmStatic
    @JvmName("encode")
    @Throws(Exception::class)
    fun encode(servers: List<Server>): String {
        val values = JSONArray()
        for (server in servers) values.put(toJson(server))
        return values.toString()
    }

    private fun read(context: Context): MutableList<Server> {
        val raw = prefs(context).getString(KEY_SERVERS, "[]")
        return decode(raw)
    }

    private fun write(context: Context, servers: List<Server>) {
        val encoded = encode(servers)
        if (!prefs(context).edit().putString(KEY_SERVERS, encoded).commit()) {
            throw IllegalStateException("保存 MCP 服务器失败")
        }
    }

    private fun isLoopbackHost(value: String?): Boolean {
        var host = if (value == null) "" else value.trim { it <= ' ' }.lowercase(Locale.ROOT)
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length - 1)
        return host == "localhost" || host == "127.0.0.1"
    }

    private fun toJson(server: Server): JSONObject {
        return JSONObject().put("server_id", server.id).put("name", server.name)
            .put("endpoint", server.endpoint).put("transport", "streamable_http")
            .put("confirmed_and_saved", true)
            .put("created_at", server.createdAt)
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
