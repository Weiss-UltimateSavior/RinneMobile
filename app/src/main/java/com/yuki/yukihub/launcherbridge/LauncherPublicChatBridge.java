package com.yuki.yukihub.launcherbridge;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.yuki.yukihub.util.AppExecutors;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** User-side client for the system public chat. It deliberately contains no admin endpoints. */
public final class LauncherPublicChatBridge {
    private static final String API_BASE = "http://192.168.1.5:8000";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final OkHttpClient WEB_SOCKET_CLIENT = new OkHttpClient();

    private LauncherPublicChatBridge() { }

    public static void loadInitial(Context context, ChatCallback callback) {
        AppExecutors.runOnIo(() -> {
            try {
                String token = requireToken(context);
                JSONObject response = new JSONObject(get("/chat/public/messages?limit=50", token));
                List<Message> messages = parseMessages(response.optJSONArray("messages"));
                Integer cursor = response.isNull("next_before_id") ? null : response.optInt("next_before_id");
                post(() -> callback.onSuccess(messages, cursor));
            } catch (Throwable error) {
                post(() -> callback.onError(errorMessage(error, "加载消息失败")));
            }
        });
    }

    public static void loadOlder(Context context, int beforeId, ChatCallback callback) {
        AppExecutors.runOnIo(() -> {
            try {
                String token = requireToken(context);
                JSONObject response = new JSONObject(get("/chat/public/messages?limit=50&before_id=" + beforeId, token));
                List<Message> messages = parseMessages(response.optJSONArray("messages"));
                Integer cursor = response.isNull("next_before_id") ? null : response.optInt("next_before_id");
                post(() -> callback.onSuccess(messages, cursor));
            } catch (Throwable error) {
                post(() -> callback.onError(errorMessage(error, "加载历史消息失败")));
            }
        });
    }

    public static void send(Context context, String content, MessageCallback callback) {
        AppExecutors.runOnIo(() -> {
            try {
                String token = requireToken(context);
                JSONObject body = new JSONObject().put("content", content);
                Message message = parseMessage(new JSONObject(postJson("/chat/public/messages", body, token)));
                post(() -> callback.onSuccess(message));
            } catch (Throwable error) {
                post(() -> callback.onError(errorMessage(error, "发送失败")));
            }
        });
    }

    public static void loadStatus(Context context, StatusCallback callback) {
        AppExecutors.runOnIo(() -> {
            try {
                JSONObject json = new JSONObject(get("/chat/public/status", requireToken(context)));
                post(() -> callback.onSuccess(new Status(json.optBoolean("readonly"), json.optBoolean("muted"),
                        json.isNull("muted_until") ? null : json.optLong("muted_until"), json.optString("mute_reason"))));
            } catch (Throwable error) {
                post(() -> callback.onError(errorMessage(error, "获取频道状态失败")));
            }
        });
    }

    public static void loadAnnouncements(Context context, AnnouncementsCallback callback) {
        AppExecutors.runOnIo(() -> {
            try {
                JSONArray array = new JSONArray(get("/chat/public/announcements", requireToken(context)));
                List<Announcement> announcements = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) announcements.add(parseAnnouncement(array.getJSONObject(i)));
                post(() -> callback.onSuccess(announcements));
            } catch (Throwable error) {
                post(() -> callback.onError(errorMessage(error, "获取公告失败")));
            }
        });
    }

    public static WebSocket connect(Context context, RealtimeListener listener) {
        String token = LauncherAuthBridge.getToken(context);
        if (token == null || token.trim().isEmpty()) {
            post(() -> listener.onError("请先登录后再进入公共聊天室"));
            return null;
        }
        try {
            String wsBase = API_BASE.startsWith("https://") ? "wss://" + API_BASE.substring(8) : "ws://" + API_BASE.substring(7);
            String url = wsBase + "/chat/public/ws?token=" + URLEncoder.encode(token, "UTF-8");
            return WEB_SOCKET_CLIENT.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
                @Override public void onOpen(WebSocket webSocket, Response response) { post(listener::onConnected); }
                @Override public void onMessage(WebSocket webSocket, String text) {
                    try {
                        JSONObject event = new JSONObject(text);
                        String type = event.optString("type");
                        JSONObject data = event.optJSONObject("data");
                        if ("message_created".equals(type)) listener.onMessageCreated(parseMessage(data.getJSONObject("message")));
                        else if ("message_deleted".equals(type)) listener.onMessageDeleted(data.optInt("message_id"));
                        else if ("message_pinned".equals(type)) listener.onMessagePinned(parseMessage(data.getJSONObject("message")));
                        else if ("readonly_updated".equals(type)) listener.onReadonlyChanged(data.optBoolean("readonly"));
                        else if ("user_muted".equals(type)) listener.onMuted(true, data.isNull("muted_until") ? null : data.optLong("muted_until"), data.optString("reason"));
                        else if ("user_unmuted".equals(type)) listener.onMuted(false, null, "");
                        else if ("announcement_created".equals(type) || "announcement_updated".equals(type)) listener.onAnnouncementChanged(parseAnnouncement(data.getJSONObject("announcement")));
                    } catch (Throwable ignored) { }
                }
                @Override public void onFailure(WebSocket webSocket, Throwable error, Response response) { post(() -> listener.onError("实时连接已断开")); }
            });
        } catch (Throwable error) {
            post(() -> listener.onError("无法建立实时连接"));
            return null;
        }
    }

    private static String requireToken(Context context) {
        String token = LauncherAuthBridge.getToken(context);
        if (token == null || token.trim().isEmpty()) throw new IllegalStateException("请先登录后再进入公共聊天室");
        return token;
    }

    private static String get(String path, String token) throws Exception { return request(path, "GET", null, token); }
    private static String postJson(String path, JSONObject body, String token) throws Exception { return request(path, "POST", body, token); }
    private static String request(String path, String method, JSONObject body, String token) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(API_BASE + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) {
            byte[] bytes = body.toString().getBytes("UTF-8");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(bytes.length);
            connection.getOutputStream().write(bytes);
        }
        int code = connection.getResponseCode();
        String text = read(code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream());
        if (code < 200 || code >= 300) throw new RuntimeException("HTTP " + code + ": " + detail(text));
        return text;
    }

    private static String read(InputStream input) throws Exception {
        if (input == null) return "";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096]; int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toString("UTF-8");
    }
    private static String detail(String response) { try { return new JSONObject(response).optString("detail", response); } catch (Throwable ignored) { return response; } }
    private static String errorMessage(Throwable error, String fallback) { String message = error.getMessage(); return message == null || message.trim().isEmpty() ? fallback : message; }
    private static void post(Runnable runnable) { MAIN.post(runnable); }
    private static List<Message> parseMessages(JSONArray array) throws Exception { List<Message> result = new ArrayList<>(); if (array != null) for (int i = 0; i < array.length(); i++) result.add(parseMessage(array.getJSONObject(i))); return result; }
    private static Message parseMessage(JSONObject json) { return new Message(json.optInt("id"), json.optString("sender_type"), json.optString("sender_id"), json.optString("sender_name"), json.optString("content"), json.optBoolean("is_pinned"), json.optLong("created_at")); }
    private static Announcement parseAnnouncement(JSONObject json) { return new Announcement(json.optInt("id"), json.optString("title"), json.optString("content"), json.optBoolean("is_active", true), json.optLong("created_at")); }

    public static final class Message { public final int id; public final String senderType, senderId, senderName, content; public final boolean pinned; public final long createdAt; Message(int id, String senderType, String senderId, String senderName, String content, boolean pinned, long createdAt) { this.id=id; this.senderType=senderType; this.senderId=senderId; this.senderName=senderName; this.content=content; this.pinned=pinned; this.createdAt=createdAt; } }
    public static final class Status { public final boolean readonly, muted; public final Long mutedUntil; public final String muteReason; Status(boolean readonly, boolean muted, Long mutedUntil, String muteReason) { this.readonly=readonly; this.muted=muted; this.mutedUntil=mutedUntil; this.muteReason=muteReason; } }
    public static final class Announcement { public final int id; public final String title, content; public final boolean active; public final long createdAt; Announcement(int id, String title, String content, boolean active, long createdAt) { this.id=id; this.title=title; this.content=content; this.active=active; this.createdAt=createdAt; } }
    public interface ChatCallback { void onSuccess(List<Message> messages, Integer nextBeforeId); void onError(String message); }
    public interface MessageCallback { void onSuccess(Message message); void onError(String message); }
    public interface StatusCallback { void onSuccess(Status status); void onError(String message); }
    public interface AnnouncementsCallback { void onSuccess(List<Announcement> announcements); void onError(String message); }
    public interface RealtimeListener { void onConnected(); void onMessageCreated(Message message); void onMessageDeleted(int messageId); void onMessagePinned(Message message); void onReadonlyChanged(boolean readonly); void onMuted(boolean muted, Long mutedUntil, String reason); void onAnnouncementChanged(Announcement announcement); void onError(String message); }
}
