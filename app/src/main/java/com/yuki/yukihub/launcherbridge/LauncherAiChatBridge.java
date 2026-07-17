package com.yuki.yukihub.launcherbridge;

import android.content.Context;

import com.yuki.yukihub.util.AppExecutors;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/** User-facing AI chat bridge. Only exposes the documented /ai/chat user endpoints. */
public final class LauncherAiChatBridge {
    private static final String API_BASE = "https://api.rinne.cyou:9999";

    private LauncherAiChatBridge() { }

    public static final class Message {
        public final String role;
        public final String content;
        public final String name;

        public Message(String role, String content, String name) {
            this.role = role == null ? "" : role;
            this.content = content == null ? "" : content;
            this.name = name == null ? "" : name;
        }
    }

    public interface HistoryCallback {
        void onSuccess(List<Message> messages);
        void onError(String message);
    }

    public interface ReplyCallback {
        void onSuccess(String reply);
        void onError(String message);
    }

    public interface Callback {
        void onSuccess();
        void onError(String message);
    }

    public static void loadHistory(Context context, String threadId, HistoryCallback callback) {
        AppExecutors.runOnIo(() -> {
            try {
                JSONArray array = new JSONObject(request(context, "GET", "/ai/chat/history/" + encode(threadId), null)).optJSONArray("messages");
                List<Message> messages = new ArrayList<>();
                if (array != null) {
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject item = array.optJSONObject(i);
                        if (item == null) continue;
                        messages.add(new Message(item.optString("role"), item.optString("content"), item.optString("name")));
                    }
                }
                postMain(() -> callback.onSuccess(messages));
            } catch (Throwable t) {
                postError(context, t, "获取聊天记录失败", callback::onError);
            }
        });
    }

    public static void send(Context context, String message, String persona, String threadId, ReplyCallback callback) {
        AppExecutors.runOnIo(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("message", message);
                body.put("persona", persona);
                body.put("thread_id", threadId);
                JSONObject json = new JSONObject(request(context, "POST", "/ai/chat", body));
                postMain(() -> callback.onSuccess(json.optString("message", "")));
            } catch (Throwable t) {
                postError(context, t, "聊天请求失败", callback::onError);
            }
        });
    }

    public static void clearHistory(Context context, String threadId, Callback callback) {
        AppExecutors.runOnIo(() -> {
            try {
                request(context, "DELETE", "/ai/chat/history/" + encode(threadId), null);
                postMain(callback::onSuccess);
            } catch (Throwable t) {
                postError(context, t, "清空聊天记录失败", callback::onError);
            }
        });
    }

    private static String request(Context context, String method, String path, JSONObject body) throws Exception {
        String token = LauncherAuthBridge.getToken(context);
        if (token == null || token.trim().isEmpty()) throw new RuntimeException("未登录");
        HttpURLConnection connection = (HttpURLConnection) new URL(API_BASE + path).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                byte[] bytes = body.toString().getBytes("UTF-8");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream request = connection.getOutputStream()) { request.write(bytes); }
            }
            int code = connection.getResponseCode();
            java.io.InputStream raw = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            if (raw != null) try (java.io.InputStream stream = raw) {
                byte[] buffer = new byte[4096];
                int length;
                while ((length = stream.read(buffer)) != -1) {
                    if (output.size() + length > 1024 * 1024) throw new java.io.IOException("response too large");
                    output.write(buffer, 0, length);
                }
            }
            String response = output.toString("UTF-8");
            if (code < 200 || code >= 300) {
                String detail = response;
                try {
                    Object value = new JSONObject(response).opt("detail");
                    if (value instanceof JSONObject) detail = ((JSONObject) value).optString("message", response);
                } catch (Throwable ignored) { }
                throw new RuntimeException("HTTP " + code + ": " + detail);
            }
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value == null ? "" : value, "UTF-8");
    }

    private interface ErrorSink { void accept(String message); }

    private static void postError(Context context, Throwable error, String fallback, ErrorSink sink) {
        String message = error == null || error.getMessage() == null ? fallback : error.getMessage();
        if (message.contains("401")) {
            LauncherAuthBridge.expireSession(context);
            message = "登录已过期，请重新登录";
        } else if (message.contains("502") || message.contains("CHAT_SERVICE_UNAVAILABLE")) {
            message = "聊天服务暂时不可用，请稍后重试";
        } else if (message.contains("422")) {
            message = "消息内容或会话参数不符合要求";
        }
        final String finalMessage = message;
        postMain(() -> sink.accept(finalMessage));
    }

    private static void postMain(Runnable runnable) {
        com.yuki.yukihub.util.RxMainScheduler.post(runnable);
    }
}
