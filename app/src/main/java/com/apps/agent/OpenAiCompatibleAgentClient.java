package com.apps.agent;

import com.yuki.yukihub.net.HttpClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Direct device-to-provider client. Cross-origin redirects are disabled to protect Authorization. */
final class OpenAiCompatibleAgentClient {
    private static final int MAX_ERROR_CHARS = 4096;
    private static final int MAX_LINE_BYTES = 256 * 1024;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_CONTENT_CHARS = 512 * 1024;
    private static final int MAX_TOOL_ARGUMENT_CHARS = 256 * 1024;
    private static final int MAX_TOOL_CALLS = 16;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient client = HttpClient.defaultBuilder()
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build();
    private volatile Call activeCall;

    interface DeltaCallback {
        void onDelta(String text);
        default void onReasoningDelta(String text) { }
    }

    static final class ModelMessage {
        final String role;
        final String content;
        final String name;
        final String toolCallId;
        final JSONArray toolCalls;
        final String reasoningContent;

        ModelMessage(String role, String content) { this(role, content, "", "", null, ""); }
        ModelMessage(String role, String content, String name, String toolCallId, JSONArray toolCalls) {
            this(role, content, name, toolCallId, toolCalls, "");
        }
        ModelMessage(String role, String content, String name, String toolCallId, JSONArray toolCalls,
                     String reasoningContent) {
            this.role = role;
            this.content = content == null ? "" : content;
            this.name = name == null ? "" : name;
            this.toolCallId = toolCallId == null ? "" : toolCallId;
            this.toolCalls = toolCalls;
            this.reasoningContent = reasoningContent == null ? "" : reasoningContent;
        }

        JSONObject toJson() throws Exception {
            JSONObject value = new JSONObject().put("role", role);
            if ("assistant".equals(role) && toolCalls != null && toolCalls.length() > 0) {
                value.put("content", content.isEmpty() ? JSONObject.NULL : content).put("tool_calls", toolCalls);
            } else {
                value.put("content", content);
            }
            if ("assistant".equals(role) && !reasoningContent.isEmpty()) {
                value.put("reasoning_content", reasoningContent);
            }
            if (!name.isEmpty()) value.put("name", name);
            if (!toolCallId.isEmpty()) value.put("tool_call_id", toolCallId);
            return value;
        }
    }

    static final class ToolCall {
        final String id;
        final String name;
        final String arguments;
        ToolCall(String id, String name, String arguments) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
            this.arguments = arguments == null ? "" : arguments;
        }
    }

    static final class Result {
        final String content;
        final String reasoningContent;
        final List<ToolCall> toolCalls;
        Result(String content, String reasoningContent, List<ToolCall> toolCalls) {
            this.content = content == null ? "" : content;
            this.reasoningContent = reasoningContent == null ? "" : reasoningContent;
            this.toolCalls = toolCalls;
        }

        ModelMessage assistantMessage() throws Exception {
            JSONArray values = new JSONArray();
            for (ToolCall call : toolCalls) {
                values.put(new JSONObject().put("id", call.id).put("type", "function")
                        .put("function", new JSONObject().put("name", call.name)
                                .put("arguments", call.arguments)));
            }
            return new ModelMessage("assistant", content, "", "", values, reasoningContent);
        }
    }

    Result execute(android.content.Context context, AgentConfigStore.Config config,
                   List<ModelMessage> messages, JSONArray tools, DeltaCallback callback) throws Exception {
        JSONObject body = new JSONObject()
                .put("model", config.model)
                .put("temperature", config.temperature)
                .put("stream", true)
                .put("tools", tools)
                .put("tool_choice", "auto");
        JSONArray messageValues = new JSONArray();
        for (ModelMessage message : messages) messageValues.put(message.toJson());
        body.put("messages", messageValues);
        String apiKey = AgentConfigStore.getApiKey(context);
        if (apiKey.isEmpty()) throw new IllegalStateException("请先配置 API Key");
        String validatedBaseUrl = AgentConfigStore.validateBaseUrl(config.baseUrl);
        Request request = new Request.Builder()
                .url(AgentConfigStore.chatCompletionsUrl(validatedBaseUrl))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "text/event-stream, application/json")
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        Call call = client.newCall(request);
        activeCall = call;
        try (Response response = call.execute()) {
            ResponseBody responseBody = response.body();
            if (!response.isSuccessful()) {
                String detail = responseBody == null ? "" : readTruncated(responseBody.byteStream(), MAX_ERROR_CHARS);
                throw new IOException("模型接口返回 HTTP " + response.code() + sanitizeError(detail, apiKey));
            }
            if (responseBody == null) throw new IOException("模型接口返回空响应");
            if (responseBody.contentLength() > MAX_RESPONSE_BYTES) throw new IOException("模型响应过大");
            String contentType = response.header("Content-Type", "");
            if (contentType.toLowerCase(java.util.Locale.ROOT).contains("text/event-stream")) {
                return parseStream(responseBody.byteStream(), callback);
            }
            String value = readLimited(responseBody.byteStream(), MAX_RESPONSE_BYTES);
            return parseComplete(new JSONObject(value), callback);
        } finally {
            if (activeCall == call) activeCall = null;
        }
    }

    void cancel() {
        Call call = activeCall;
        if (call != null) call.cancel();
    }

    Result parseStream(InputStream rawSource, DeltaCallback callback) throws Exception {
        LimitedLineReader source = new LimitedLineReader(rawSource, MAX_RESPONSE_BYTES);
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        int[] consumedBytes = new int[]{0};
        Map<Integer, MutableToolCall> calls = new LinkedHashMap<>();
        while (true) {
            String line = source.readLine(MAX_LINE_BYTES, consumedBytes);
            if (line == null) break;
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if (data.isEmpty()) continue;
            if ("[DONE]".equals(data)) break;
            JSONObject event = new JSONObject(data);
            if (event.has("error")) throw new IOException(providerError(event, "模型流式接口返回错误"));
            JSONArray choices = event.optJSONArray("choices");
            if (choices == null || choices.length() == 0) continue;
            JSONObject choice = choices.optJSONObject(0);
            JSONObject delta = choice == null ? null : choice.optJSONObject("delta");
            if (delta == null) continue;
            String text = stringValue(delta, "content");
            if (!text.isEmpty()) {
                if (content.length() + text.length() > MAX_CONTENT_CHARS) throw new IOException("模型输出内容过大");
                content.append(text);
                if (callback != null) callback.onDelta(text);
            }
            String thought = firstStringValue(delta, "reasoning_content", "reasoning", "analysis");
            if (!thought.isEmpty()) {
                if (reasoning.length() + thought.length() > MAX_CONTENT_CHARS) throw new IOException("模型思考内容过大");
                reasoning.append(thought);
                if (callback != null) callback.onReasoningDelta(thought);
            }
            JSONArray toolDeltas = delta.optJSONArray("tool_calls");
            if (toolDeltas == null) continue;
            for (int i = 0; i < toolDeltas.length(); i++) {
                JSONObject part = toolDeltas.optJSONObject(i);
                if (part == null) continue;
                int index = part.optInt("index", i);
                if (index < 0 || index >= MAX_TOOL_CALLS) throw new IOException("模型工具调用数量过多");
                MutableToolCall call = calls.get(index);
                if (call == null) {
                    if (calls.size() >= MAX_TOOL_CALLS) throw new IOException("模型工具调用数量过多");
                    call = new MutableToolCall(); calls.put(index, call);
                }
                String id = stringValue(part, "id");
                if (!id.isEmpty()) call.id = id;
                JSONObject function = part.optJSONObject("function");
                if (function != null) {
                    String name = stringValue(function, "name");
                    if (!name.isEmpty()) {
                        if (call.name.length() + name.length() > 128) throw new IOException("模型工具名称过长");
                        call.name.append(name);
                    }
                    String args = stringValue(function, "arguments");
                    if (call.arguments.length() + args.length() > MAX_TOOL_ARGUMENT_CHARS) throw new IOException("模型工具参数过大");
                    call.arguments.append(args);
                }
            }
        }
        return new Result(content.toString(), reasoning.toString(), freeze(calls));
    }

    Result parseComplete(JSONObject response, DeltaCallback callback) throws Exception {
        if (response.has("error")) throw new IOException(providerError(response, "模型接口返回错误"));
        JSONArray choices = response.optJSONArray("choices");
        if (choices == null || choices.length() == 0) throw new IOException("模型响应缺少 choices");
        JSONObject choice = choices.optJSONObject(0);
        JSONObject message = choice == null ? null : choice.optJSONObject("message");
        if (message == null) throw new IOException("模型响应缺少 message");
        String content = stringValue(message, "content");
        String reasoning = firstStringValue(message, "reasoning_content", "reasoning", "analysis");
        if (content.length() > MAX_CONTENT_CHARS) throw new IOException("模型输出内容过大");
        if (reasoning.length() > MAX_CONTENT_CHARS) throw new IOException("模型思考内容过大");
        if (!reasoning.isEmpty() && callback != null) callback.onReasoningDelta(reasoning);
        if (!content.isEmpty() && callback != null) callback.onDelta(content);
        List<ToolCall> calls = new ArrayList<>();
        JSONArray values = message.optJSONArray("tool_calls");
        if (values != null && values.length() > MAX_TOOL_CALLS) throw new IOException("模型工具调用数量过多");
        if (values != null) for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.optJSONObject(i);
            JSONObject function = value == null ? null : value.optJSONObject("function");
            if (function != null) {
                String name = stringValue(function, "name");
                String arguments = stringValue(function, "arguments");
                if (arguments.isEmpty()) arguments = "{}";
                if (name.length() > 128 || arguments.length() > MAX_TOOL_ARGUMENT_CHARS) throw new IOException("模型工具调用过大");
                String id = stringValue(value, "id");
                calls.add(new ToolCall(id.isEmpty() ? "call_" + i : id, name, arguments));
            }
        }
        return new Result(content, reasoning, calls);
    }

    private static String providerError(JSONObject response, String fallback) {
        Object raw = response == null ? null : response.opt("error");
        String message = "";
        String type = "";
        String code = "";
        if (raw instanceof JSONObject) {
            JSONObject error = (JSONObject) raw;
            message = error.optString("message", "").trim();
            type = error.optString("type", "").trim();
            code = error.optString("code", "").trim();
        } else if (raw != null && raw != JSONObject.NULL) {
            message = String.valueOf(raw).trim();
        }
        StringBuilder value = new StringBuilder(fallback);
        if (!message.isEmpty()) value.append("：").append(message);
        if (!type.isEmpty()) value.append(" [type=").append(type).append(']');
        if (!code.isEmpty()) value.append(" [code=").append(code).append(']');
        return value.length() <= 1000 ? value.toString() : value.substring(0, 1000) + "…";
    }

    /** org.json optString turns JSONObject.NULL into the literal "null" on some runtimes. */
    private static String stringValue(JSONObject value, String key) {
        if (value == null || key == null) return "";
        Object raw = value.opt(key);
        return raw instanceof String ? (String) raw : "";
    }

    private static String firstStringValue(JSONObject value, String... keys) {
        if (value == null || keys == null) return "";
        for (String key : keys) {
            String result = stringValue(value, key);
            if (!result.isEmpty()) return result;
        }
        return "";
    }

    private static List<ToolCall> freeze(Map<Integer, MutableToolCall> values) {
        List<ToolCall> result = new ArrayList<>();
        for (Map.Entry<Integer, MutableToolCall> entry : values.entrySet()) {
            MutableToolCall call = entry.getValue();
            result.add(new ToolCall(call.id.isEmpty() ? "call_" + entry.getKey() : call.id,
                    call.name.toString(), call.arguments.length() == 0 ? "{}" : call.arguments.toString()));
        }
        return result;
    }

    static String sanitizeError(String value, String apiKey) {
        if (value == null || value.trim().isEmpty()) return "";
        String safe = value;
        if (apiKey != null && !apiKey.isEmpty()) safe = safe.replace(apiKey, "[已隐藏]");
        safe = safe.replaceAll("(?i)bearer\\s+[A-Za-z0-9._~+/-]+", "Bearer [已隐藏]")
                .replaceAll("(?i)sk-[A-Za-z0-9_-]{8,}", "[已隐藏]").trim();
        if (safe.length() > MAX_ERROR_CHARS) safe = safe.substring(0, MAX_ERROR_CHARS) + "…";
        return "：" + safe;
    }

    static String readLimited(InputStream source, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[4096];
        int total = 0;
        int count;
        while ((count = source.read(buffer)) != -1) {
            total += count;
            if (total > maxBytes) throw new IOException("模型响应过大");
            output.write(buffer, 0, count);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    static String readTruncated(InputStream source, int maxBytes) throws IOException {
        byte[] buffer = new byte[Math.max(1, Math.min(maxBytes, 8192))];
        int count = source.read(buffer);
        if (count <= 0) return "";
        return new String(buffer, 0, count, StandardCharsets.UTF_8);
    }

    private static final class LimitedLineReader {
        private final InputStream source;
        private final int maxTotalBytes;
        private final byte[] buffer = new byte[8192];
        private int offset;
        private int count;

        LimitedLineReader(InputStream source, int maxTotalBytes) {
            this.source = source;
            this.maxTotalBytes = maxTotalBytes;
        }

        String readLine(int maxLineBytes, int[] totalBytes) throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream(Math.min(1024, maxLineBytes));
            while (true) {
                int value = nextByte();
                if (value == -1) {
                    if (line.size() == 0) return null;
                    break;
                }
                totalBytes[0]++;
                if (totalBytes[0] > maxTotalBytes) throw new IOException("模型流式响应过大");
                if (value == '\n') break;
                if (line.size() >= maxLineBytes) throw new IOException("模型流式响应单帧过大");
                if (value != '\r') line.write(value);
            }
            return line.toString(StandardCharsets.UTF_8.name());
        }

        private int nextByte() throws IOException {
            if (offset >= count) {
                count = source.read(buffer);
                offset = 0;
                if (count < 0) return -1;
            }
            return buffer[offset++] & 0xff;
        }
    }

    private static final class MutableToolCall {
        String id = "";
        final StringBuilder name = new StringBuilder();
        final StringBuilder arguments = new StringBuilder();
    }
}
