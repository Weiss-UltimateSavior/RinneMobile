package com.apps.agent;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Local registry for explicitly approved remote Streamable HTTP MCP servers. */
final class McpServerStore {
    private static final String PREFS = "rinne_mcp_servers";
    private static final String KEY_SERVERS = "servers";
    private static final int MAX_SERVERS = 12;

    static final class Server {
        final String id;
        final String name;
        final String endpoint;
        final long createdAt;

        Server(String id, String name, String endpoint, long createdAt) {
            this.id = id; this.name = name; this.endpoint = endpoint; this.createdAt = createdAt;
        }
    }

    private McpServerStore() { }

    static Server add(Context context, String name, String endpoint) throws Exception {
        String safeName = validateName(name);
        String safeEndpoint = validateEndpoint(endpoint);
        List<Server> servers = read(context);
        for (Server server : servers) {
            if (server.endpoint.equalsIgnoreCase(safeEndpoint)) {
                // Model providers may retry or emit duplicate tool calls. Treat adding the same
                // endpoint as an idempotent success so a real first write is not reported as failed.
                return server;
            }
        }
        if (servers.size() >= MAX_SERVERS) throw new IllegalStateException("最多添加 " + MAX_SERVERS + " 个 MCP 服务器");
        Server value = new Server(UUID.randomUUID().toString(), safeName, safeEndpoint, System.currentTimeMillis());
        servers.add(value);
        write(context, servers);
        return value;
    }

    static Server get(Context context, String id) throws Exception {
        if (id == null || !id.matches("[0-9a-fA-F-]{36}")) throw new IllegalArgumentException("server_id 格式错误");
        for (Server server : read(context)) if (server.id.equalsIgnoreCase(id)) return server;
        throw new IllegalArgumentException("未找到 MCP 服务器");
    }

    static Server remove(Context context, String id) throws Exception {
        List<Server> servers = read(context);
        Server target = null;
        for (Server server : servers) if (server.id.equalsIgnoreCase(id)) { target = server; break; }
        if (target == null) throw new IllegalArgumentException("未找到 MCP 服务器");
        servers.remove(target);
        write(context, servers);
        return target;
    }

    static String list(Context context) throws Exception {
        JSONArray items = new JSONArray();
        for (Server server : read(context)) items.put(toJson(server));
        return new JSONObject().put("success", true)
                .put("source", "local_confirmed_registry")
                .put("message", "以下 MCP 服务器均已经用户本机确认并保存")
                .put("servers", items).toString();
    }

    static String trustedModelContext(Context context) throws Exception {
        return "设备本地 MCP 注册表（这是可信本地状态，不是模型推测）：" + list(context);
    }

    static String savedSummary(Context context) throws Exception {
        List<Server> servers = read(context);
        if (servers.isEmpty()) return "";
        StringBuilder value = new StringBuilder("本机当前已确认并保存的 MCP：");
        for (Server server : servers) {
            value.append("\n- ").append(server.name).append("（").append(server.endpoint).append("）");
        }
        return value.toString();
    }

    static Server findByEndpoint(Context context, String endpoint) throws Exception {
        String safeEndpoint = validateEndpoint(endpoint);
        for (Server server : read(context)) {
            if (server.endpoint.equalsIgnoreCase(safeEndpoint)) return server;
        }
        return null;
    }

    static String preview(Server server) {
        return "名称：" + server.name + "\n地址：" + server.endpoint
                + "\n传输：Streamable HTTP\n\n该服务器及其工具描述会发送给你配置的模型服务。"
                + "每次调用远程工具仍需本机确认。";
    }

    static String validateName(String value) {
        String result = value == null ? "" : value.trim();
        if (result.isEmpty() || result.length() > 80) throw new IllegalArgumentException("MCP 名称长度应为 1-80 个字符");
        if (result.matches(".*[\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069].*")) throw new IllegalArgumentException("MCP 名称包含不允许的字符");
        return result;
    }

    static String validateEndpoint(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        if (result.isEmpty() || result.length() > 2048) throw new IllegalArgumentException("MCP 地址长度不正确");
        try {
            URI uri = new URI(result);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || host.trim().isEmpty() || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("MCP 地址格式不正确");
            }
            boolean cleartextLoopback = isLoopbackHost(host);
            if (!"https".equalsIgnoreCase(scheme) && !(cleartextLoopback && "http".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("MCP 地址必须使用 HTTPS；仅 localhost 或 127.0.0.1 允许 HTTP");
            }
            return result;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("MCP 地址格式不正确", error);
        }
    }

    static boolean isPrivateNetworkHost(String value) {
        String host = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if ("localhost".equals(host) || "::1".equals(host)) return true;
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            if (!host.contains(":") || host.contains(".")) return false;
            try {
                InetAddress address = InetAddress.getByName(host);
                if (!(address instanceof Inet6Address)) return false;
                byte[] bytes = address.getAddress();
                int first = bytes[0] & 0xff;
                int second = bytes[1] & 0xff;
                return (first & 0xfe) == 0xfc || (first == 0xfe && (second & 0xc0) == 0x80);
            } catch (Exception ignored) {
                return false;
            }
        }
        int[] octets = new int[4];
        try {
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].isEmpty() || (parts[i].length() > 1 && parts[i].startsWith("0"))) return false;
                octets[i] = Integer.parseInt(parts[i]);
                if (octets[i] < 0 || octets[i] > 255) return false;
            }
        } catch (NumberFormatException ignored) { return false; }
        return octets[0] == 127 || octets[0] == 10
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 192 && octets[1] == 168)
                || (octets[0] == 169 && octets[1] == 254);
    }

    private static boolean isLoopbackHost(String value) {
        String host = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        return "localhost".equals(host) || "127.0.0.1".equals(host);
    }

    private static List<Server> read(Context context) throws Exception {
        String raw = prefs(context).getString(KEY_SERVERS, "[]");
        return decode(raw);
    }

    static List<Server> decode(String raw) throws Exception {
        JSONArray values = new JSONArray(raw == null ? "[]" : raw);
        List<Server> result = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) {
            JSONObject item = values.optJSONObject(i);
            if (item == null) continue;
            // Current records are serialized as server_id. Accept the old id spelling as a
            // migration fallback, but never discard a valid current record on the next run.
            String id = item.optString("server_id", item.optString("id"));
            try {
                if (!id.matches("[0-9a-fA-F-]{36}")) continue;
                result.add(new Server(id, validateName(item.optString("name")),
                        validateEndpoint(item.optString("endpoint")), item.optLong("created_at")));
            } catch (Throwable ignored) { }
        }
        return result;
    }

    private static void write(Context context, List<Server> servers) throws Exception {
        String encoded = encode(servers);
        if (!prefs(context).edit().putString(KEY_SERVERS, encoded).commit()) {
            throw new IllegalStateException("保存 MCP 服务器失败");
        }
    }

    static String encode(List<Server> servers) throws Exception {
        JSONArray values = new JSONArray();
        for (Server server : servers) values.put(toJson(server));
        return values.toString();
    }

    private static JSONObject toJson(Server server) throws Exception {
        return new JSONObject().put("server_id", server.id).put("name", server.name)
                .put("endpoint", server.endpoint).put("transport", "streamable_http")
                .put("confirmed_and_saved", true)
                .put("created_at", server.createdAt);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
