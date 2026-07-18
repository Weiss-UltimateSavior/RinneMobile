package com.apps.agent;

import com.yuki.yukihub.net.HttpClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Minimal Streamable HTTP JSON-RPC client; deliberately does not support auth headers from model text. */
final class McpHttpClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_BODY_BYTES = 256 * 1024;
    private static final String DEFAULT_PROTOCOL_VERSION = "2025-06-18";
    private final OkHttpClient client = HttpClient.defaultBuilder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS).build();
    private final Runnable onToolRequestStarted;
    private volatile Call activeCall;
    private volatile boolean cancelled;

    McpHttpClient() { this(() -> { }); }

    McpHttpClient(Runnable onToolRequestStarted) {
        this.onToolRequestStarted = onToolRequestStarted == null ? () -> { } : onToolRequestStarted;
    }

    static final class Session {
        final McpServerStore.Server server;
        final String sessionId;
        final String protocolVersion;
        Session(McpServerStore.Server server, String sessionId, String protocolVersion) {
            this.server = server; this.sessionId = sessionId; this.protocolVersion = protocolVersion;
        }
    }

    Session open(McpServerStore.Server server) throws Exception {
        JSONObject params = new JSONObject().put("protocolVersion", DEFAULT_PROTOCOL_VERSION)
                .put("capabilities", new JSONObject())
                .put("clientInfo", new JSONObject().put("name", "Rinne").put("version", "1"));
        RpcResponse initialized = request(server, null, DEFAULT_PROTOCOL_VERSION, "initialize", params, true);
        String negotiated = initialized.result.optString("protocolVersion", DEFAULT_PROTOCOL_VERSION).trim();
        try {
            if (!negotiated.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IOException("MCP 返回的协议版本无效");
            request(server, initialized.sessionId, negotiated, "notifications/initialized", new JSONObject(), false);
            return new Session(server, initialized.sessionId, negotiated);
        } catch (Throwable error) {
            terminateSessionBestEffort(server, initialized.sessionId,
                    negotiated.matches("\\d{4}-\\d{2}-\\d{2}") ? negotiated : DEFAULT_PROTOCOL_VERSION);
            throw error;
        }
    }

    JSONObject listTools(Session session) throws Exception {
        RpcResponse response = request(session.server, session.sessionId, session.protocolVersion,
                "tools/list", new JSONObject(), true);
        JSONArray tools = response.result.optJSONArray("tools");
        if (tools == null) throw new IOException("MCP tools/list 响应缺少 tools");
        return new JSONObject().put("server_id", session.server.id).put("server_name", session.server.name)
                .put("protocol_version", session.protocolVersion)
                .put("tool_count", tools.length()).put("tools", compactTools(tools));
    }

    JSONObject callTool(Session session, String toolName, JSONObject arguments) throws Exception {
        RpcResponse response = request(session.server, session.sessionId, session.protocolVersion, "tools/call",
                new JSONObject().put("name", toolName).put("arguments", arguments), true);
        return new JSONObject().put("server_id", session.server.id).put("server_name", session.server.name)
                .put("tool_name", toolName).put("result", response.result);
    }

    private RpcResponse request(McpServerStore.Server server, String sessionId, String protocolVersion, String method,
                                JSONObject params, boolean expectsResponse) throws Exception {
        String id = expectsResponse ? UUID.randomUUID().toString() : null;
        JSONObject payload = new JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params);
        if (id != null) payload.put("id", id);
        Request.Builder builder = new Request.Builder().url(server.endpoint)
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", protocolVersion)
                .post(RequestBody.create(payload.toString(), JSON));
        if (sessionId != null && !sessionId.isEmpty()) builder.header("Mcp-Session-Id", sessionId);
        if (cancelled) throw new InterruptedException("MCP 调用已取消");
        Call call = client.newCall(builder.build());
        activeCall = call;
        if (cancelled) {
            call.cancel();
            activeCall = null;
            throw new InterruptedException("MCP 调用已取消");
        }
        if ("tools/call".equals(method)) onToolRequestStarted.run();
        try (Response response = call.execute()) {
            String returnedSession = response.header("Mcp-Session-Id", sessionId == null ? "" : sessionId);
            ResponseBody body = response.body();
            String raw = body == null ? "" : readLimited(body);
            if (!response.isSuccessful()) throw new IOException("MCP HTTP " + response.code() + diagnostic(raw));
            if (!expectsResponse) return new RpcResponse(new JSONObject(), returnedSession);
            JSONObject value = parseRpcPayload(raw);
            if (value.has("error")) throw new IOException("MCP 错误：" + rpcErrorMessage(value.opt("error")));
            JSONObject result = value.optJSONObject("result");
            if (result == null) throw new IOException("MCP 响应缺少 result");
            return new RpcResponse(result, returnedSession);
        } finally {
            if (activeCall == call) activeCall = null;
        }
    }

    void cancel() {
        cancelled = true;
        Call call = activeCall;
        if (call != null) call.cancel();
    }

    private void terminateSessionBestEffort(McpServerStore.Server server, String sessionId,
                                            String protocolVersion) {
        if (sessionId == null || sessionId.trim().isEmpty()) return;
        Request request = new Request.Builder().url(server.endpoint)
                .header("MCP-Protocol-Version", protocolVersion)
                .header("Mcp-Session-Id", sessionId)
                .delete().build();
        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) { }
            @Override public void onResponse(Call call, Response response) { response.close(); }
        });
    }

    static String rpcErrorMessage(Object error) {
        String value;
        if (error instanceof JSONObject) {
            value = ((JSONObject) error).optString("message", "未知错误");
        } else if (error == null || error == JSONObject.NULL) {
            value = "未知错误";
        } else {
            value = String.valueOf(error);
        }
        value = value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ").trim();
        if (value.isEmpty()) value = "未知错误";
        return value.substring(0, Math.min(300, value.length()));
    }

    static JSONArray compactTools(JSONArray tools) throws Exception {
        JSONArray compact = new JSONArray();
        int count = Math.min(tools == null ? 0 : tools.length(), 128);
        for (int i = 0; i < count; i++) {
            JSONObject source = tools.optJSONObject(i);
            if (source == null) continue;
            String name = source.optString("name", "").trim();
            if (name.isEmpty() || name.length() > 120) continue;
            JSONObject item = new JSONObject().put("name", name);
            putTruncated(item, "title", source.optString("title", ""), 200);
            putTruncated(item, "description", source.optString("description", ""), 2000);
            JSONObject inputSchema = source.optJSONObject("inputSchema");
            if (inputSchema != null) {
                String encoded = inputSchema.toString();
                if (encoded.length() <= 12 * 1024) item.put("inputSchema", inputSchema);
                else item.put("inputSchema", new JSONObject().put("type", "object")
                        .put("description", "该工具的输入结构过大，请根据工具说明谨慎调用"));
            }
            JSONObject annotations = source.optJSONObject("annotations");
            if (annotations != null) item.put("annotations", annotations);
            compact.put(item);
        }
        return compact;
    }

    private static void putTruncated(JSONObject target, String key, String value, int max) throws Exception {
        String text = value == null ? "" : value.trim();
        if (!text.isEmpty()) target.put(key, text.length() <= max ? text : text.substring(0, max) + "…");
    }

    static JSONObject parseRpcPayload(String raw) throws Exception {
        String value = raw == null ? "" : raw.trim();
        if (value.contains("\ndata:") || value.startsWith("data:")) {
            String[] lines = value.split("\\r?\\n");
            JSONObject fallback = null;
            for (String line : lines) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty()) continue;
                try {
                    JSONObject candidate = new JSONObject(data);
                    if (candidate.has("result") || candidate.has("error")) return candidate;
                    fallback = candidate;
                } catch (Exception ignored) {
                    // A progress event may contain non-JSON text. Keep looking for the RPC result.
                }
            }
            if (fallback != null) return fallback;
            value = "";
        }
        if (value.isEmpty()) throw new IOException("MCP 返回空响应");
        return new JSONObject(value);
    }

    private static String readLimited(ResponseBody body) throws Exception {
        try (InputStream input = body.byteStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                total += count;
                if (total > MAX_BODY_BYTES) throw new IOException("MCP 响应过大");
                output.write(buffer, 0, count);
            }
            return output.toString(java.nio.charset.StandardCharsets.UTF_8.name());
        }
    }

    private static String diagnostic(String value) {
        String text = value == null ? "" : value.replaceAll("[\\r\\n]+", " ").trim();
        return text.isEmpty() ? "" : "：" + text.substring(0, Math.min(300, text.length()));
    }

    private static final class RpcResponse {
        final JSONObject result;
        final String sessionId;
        RpcResponse(JSONObject result, String sessionId) { this.result = result; this.sessionId = sessionId; }
    }
}
