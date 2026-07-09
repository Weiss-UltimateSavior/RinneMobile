package com.yuki.yukihub.launcherbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.yuki.yukihub.util.AppExecutors;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 启动器认证桥接层。
 * 提供登录、注册、获取用户信息等 API 调用，
 * 结果通过 Callback 回调到主线程。
 */
public final class LauncherAuthBridge {
    private static final String API_BASE = "http://192.168.1.5:8000";
    private static final String PREFS_NAME = "yukihub_prefs";
    private static final String KEY_AUTH_ACCESS_TOKEN = "auth_access_token";
    private static final String KEY_AUTH_NICKNAME = "auth_nickname";
    private static final String KEY_AUTH_EMAIL = "auth_email";
    private static final String KEY_AUTH_STATUS = "auth_status";

    private LauncherAuthBridge() {
    }

    // ========== Token 存储 ==========

    public static void saveToken(Context context, String token) {
        prefs(context).edit().putString(KEY_AUTH_ACCESS_TOKEN, token).apply();
    }

    public static void clearToken(Context context) {
        prefs(context).edit()
                .remove(KEY_AUTH_ACCESS_TOKEN)
                .remove(KEY_AUTH_NICKNAME)
                .remove(KEY_AUTH_EMAIL)
                .putString(KEY_AUTH_STATUS, "")
                .apply();
    }

    public static String getToken(Context context) {
        return prefs(context).getString(KEY_AUTH_ACCESS_TOKEN, "");
    }

    public static boolean isLoggedIn(Context context) {
        String token = getToken(context);
        return token != null && !token.trim().isEmpty();
    }

    public static void saveUserInfo(Context context, String nickname, String email) {
        prefs(context).edit()
                .putString(KEY_AUTH_NICKNAME, nickname)
                .putString(KEY_AUTH_EMAIL, email != null ? email : "")
                .putString(KEY_AUTH_STATUS, "online")
                .apply();
    }

    public static String getNickname(Context context) {
        return prefs(context).getString(KEY_AUTH_NICKNAME, "");
    }

    public static String getEmail(Context context) {
        return prefs(context).getString(KEY_AUTH_EMAIL, "");
    }

    // ========== API 调用 ==========

    public static void login(Context context, String email, String password, AuthCallback callback) {
        AppExecutors.runOnIo(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("email", email);
                body.put("password", password);
                String response = post("/auth/login", body, null);
                JSONObject json = new JSONObject(response == null ? "{}" : response);
                String token = json.optString("access_token", "");
                if (token.isEmpty()) throw new RuntimeException("登录失败：服务器未返回令牌");
                saveToken(context, token);
                fetchAndSaveUserInfo(context);
                postMain(() -> callback.onSuccess(token));
            } catch (Throwable t) {
                String msg = parseErrorMessage(t, "登录失败");
                if (msg.contains("401") || msg.contains("邮箱或密码错误")) {
                    msg = "邮箱或密码错误";
                } else if (msg.contains("429")) {
                    msg = "请求过于频繁，请稍后重试";
                }
                final String errMsg = msg;
                postMain(() -> callback.onError(errMsg));
            }
        });
    }

    public static void register(Context context, String username, String email, String password, String inviteCode, AuthCallback callback) {
        AppExecutors.runOnIo(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("username", username);
                body.put("email", email);
                body.put("password", password);
                body.put("invite_code", inviteCode);
                String response = post("/auth/register", body, null);
                JSONObject json = new JSONObject(response == null ? "{}" : response);
                String token = json.optString("access_token", "");
                if (token.isEmpty()) throw new RuntimeException("注册失败：服务器未返回令牌");
                saveToken(context, token);
                fetchAndSaveUserInfo(context);
                postMain(() -> callback.onSuccess(token));
            } catch (Throwable t) {
                String msg = parseErrorMessage(t, "注册失败");
                if (msg.contains("邀请码无效") || msg.contains("已过期")) {
                    msg = "邀请码无效或已过期";
                } else if (msg.contains("用户名已存在")) {
                    msg = "用户名已存在";
                } else if (msg.contains("邮箱已被注册")) {
                    msg = "该邮箱已被注册，请直接登录";
                } else if (msg.contains("429")) {
                    msg = "请求过于频繁，请稍后重试";
                } else if (msg.contains("422")) {
                    msg = "输入信息格式有误，请检查后重试";
                }
                final String errMsg = msg;
                postMain(() -> callback.onError(errMsg));
            }
        });
    }

    public static void fetchAndSaveUserInfo(Context context) throws Exception {
        String token = getToken(context);
        if (token.isEmpty()) return;
        String response = get("/auth/me", token);
        JSONObject json = new JSONObject(response == null ? "{}" : response);
        String username = json.optString("username", "");
        String email = json.optString("email", "");
        saveUserInfo(context, username, email);
    }

    public static void fetchUserInfo(Context context, UserInfoCallback callback) {
        AppExecutors.runOnIo(() -> {
            try {
                fetchAndSaveUserInfo(context);
                String nickname = getNickname(context);
                String email = getEmail(context);
                postMain(() -> callback.onSuccess(nickname, email));
            } catch (Throwable t) {
                String msg = parseErrorMessage(t, "获取用户信息失败");
                if (msg.contains("401")) {
                    clearToken(context);
                    msg = "登录已过期，请重新登录";
                }
                final String errMsg = msg;
                postMain(() -> callback.onError(errMsg));
            }
        });
    }

    // ========== 用户信息修改 ==========

    public static void updateUsername(Context context, String newUsername, AuthCallback callback) {
        AppExecutors.runOnIo(() -> {
            try {
                String token = getToken(context);
                if (token.isEmpty()) throw new RuntimeException("未登录");
                JSONObject body = new JSONObject();
                body.put("new_username", newUsername);
                String response = put("/auth/username", body, token);
                JSONObject json = new JSONObject(response == null ? "{}" : response);
                String username = json.optString("username", "");
                String email = json.optString("email", "");
                saveUserInfo(context, username, email);
                postMain(() -> callback.onSuccess(token));
            } catch (Throwable t) {
                String msg = parseErrorMessage(t, "修改用户名失败");
                if (msg.contains("401")) {
                    clearToken(context);
                    msg = "登录已过期，请重新登录";
                } else if (msg.contains("用户名已存在")) {
                    msg = "该用户名已存在";
                } else if (msg.contains("422")) {
                    msg = "用户名格式有误，需3-32位字母、数字或下划线";
                }
                final String errMsg = msg;
                postMain(() -> callback.onError(errMsg));
            }
        });
    }

    public static void updatePassword(Context context, String oldPassword, String newPassword, AuthCallback callback) {
        AppExecutors.runOnIo(() -> {
            try {
                String token = getToken(context);
                if (token.isEmpty()) throw new RuntimeException("未登录");
                JSONObject body = new JSONObject();
                body.put("old_password", oldPassword);
                body.put("new_password", newPassword);
                put("/auth/password", body, token);
                // 修改密码后 Token 全部吊销，清除本地 Token
                clearToken(context);
                postMain(() -> callback.onSuccess(""));
            } catch (Throwable t) {
                String msg = parseErrorMessage(t, "修改密码失败");
                if (msg.contains("401")) {
                    clearToken(context);
                    msg = "登录已过期，请重新登录";
                } else if (msg.contains("旧密码错误")) {
                    msg = "旧密码错误";
                } else if (msg.contains("422")) {
                    msg = "密码格式有误，需6-128位";
                }
                final String errMsg = msg;
                postMain(() -> callback.onError(errMsg));
            }
        });
    }

    // ========== 配置同步 ==========

    /**
     * 从服务端获取 Launcher 配置 JSON。
     */
    public static void fetchConfig(Context context, ConfigCallback callback) {
        AppExecutors.runOnIo(() -> {
            try {
                String token = getToken(context);
                if (token.isEmpty()) throw new RuntimeException("未登录");
                String response = get("/auth/config", token);
                postMain(() -> callback.onSuccess(response));
            } catch (Throwable t) {
                String msg = parseErrorMessage(t, "获取配置失败");
                if (msg.contains("401")) { clearToken(context); msg = "登录已过期，请重新登录"; }
                final String errMsg = msg;
                postMain(() -> callback.onError(errMsg));
            }
        });
    }

    /**
     * 上传 Launcher 配置到服务端。
     */
    public static void uploadConfig(Context context, String configJson, ConfigCallback callback) {
        AppExecutors.runOnIo(() -> {
            try {
                String token = getToken(context);
                if (token.isEmpty()) throw new RuntimeException("未登录");
                JSONObject body = new JSONObject(configJson);
                put("/auth/config", body, token);
                postMain(() -> callback.onSuccess(configJson));
            } catch (Throwable t) {
                String msg = parseErrorMessage(t, "上传配置失败");
                if (msg.contains("401")) { clearToken(context); msg = "登录已过期，请重新登录"; }
                final String errMsg = msg;
                postMain(() -> callback.onError(errMsg));
            }
        });
    }

    /**
     * 从服务端获取游玩记录 SQL。
     */
    public static void fetchPlayData(Context context, PlayDataCallback callback) {
        AppExecutors.runOnIo(() -> {
            try {
                String token = getToken(context);
                if (token.isEmpty()) throw new RuntimeException("未登录");
                String response = get("/auth/config/play-data", token);
                JSONObject json = new JSONObject(response == null ? "{}" : response);
                String playData = json.optString("play_data", "");
                postMain(() -> callback.onSuccess(playData));
            } catch (Throwable t) {
                String msg = parseErrorMessage(t, "获取游玩记录失败");
                if (msg.contains("401")) { clearToken(context); msg = "登录已过期，请重新登录"; }
                final String errMsg = msg;
                postMain(() -> callback.onError(errMsg));
            }
        });
    }

    /**
     * 上传游玩记录 SQL 到服务端。
     */
    public static void uploadPlayData(Context context, String playSql, PlayDataCallback callback) {
        AppExecutors.runOnIo(() -> {
            try {
                String token = getToken(context);
                if (token.isEmpty()) throw new RuntimeException("未登录");
                JSONObject body = new JSONObject();
                body.put("play_data", playSql);
                put("/auth/config/play-data", body, token);
                postMain(() -> callback.onSuccess(playSql));
            } catch (Throwable t) {
                String msg = parseErrorMessage(t, "上传游玩记录失败");
                if (msg.contains("401")) { clearToken(context); msg = "登录已过期，请重新登录"; }
                final String errMsg = msg;
                postMain(() -> callback.onError(errMsg));
            }
        });
    }

    // ========== 网络工具 ==========

    private static String put(String path, JSONObject body, String authToken) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API_BASE + path).openConnection();
        c.setRequestMethod("PUT");
        c.setDoOutput(true);
        c.setConnectTimeout(10000);
        c.setReadTimeout(12000);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        if (authToken != null && !authToken.isEmpty()) {
            c.setRequestProperty("Authorization", "Bearer " + authToken);
        }
        byte[] bytes = body.toString().getBytes("UTF-8");
        c.setFixedLengthStreamingMode(bytes.length);
        c.getOutputStream().write(bytes);
        c.getOutputStream().flush();
        return readResponse(c);
    }

    private static String post(String path, JSONObject body, String authToken) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API_BASE + path).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(10000);
        c.setReadTimeout(12000);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        if (authToken != null && !authToken.isEmpty()) {
            c.setRequestProperty("Authorization", "Bearer " + authToken);
        }
        byte[] bytes = body.toString().getBytes("UTF-8");
        c.setFixedLengthStreamingMode(bytes.length);
        c.getOutputStream().write(bytes);
        c.getOutputStream().flush();
        return readResponse(c);
    }

    private static String get(String path, String authToken) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API_BASE + path).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(10000);
        c.setReadTimeout(12000);
        c.setRequestProperty("Accept", "application/json");
        if (authToken != null && !authToken.isEmpty()) {
            c.setRequestProperty("Authorization", "Bearer " + authToken);
        }
        return readResponse(c);
    }

    private static String readResponse(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        String text = readSmallText(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
        if (code < 200 || code >= 300) {
            String detail = text;
            try {
                JSONObject err = new JSONObject(text == null ? "{}" : text);
                // 适配统一错误格式: {"detail": {"code": "...", "message": "..."}}
                Object detailObj = err.get("detail");
                if (detailObj instanceof JSONObject) {
                    JSONObject detailJson = (JSONObject) detailObj;
                    if (detailJson.has("message")) {
                        detail = detailJson.optString("message", text);
                    } else {
                        detail = detailObj.toString();
                    }
                } else {
                    detail = String.valueOf(detailObj);
                }
            } catch (Throwable ignored) {
            }
            throw new RuntimeException("HTTP " + code + ": " + trim(detail, 200));
        }
        return text;
    }

    private static String readSmallText(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int total = 0, len;
        while ((len = is.read(buf)) != -1 && total < 64 * 1024) {
            bos.write(buf, 0, len);
            total += len;
        }
        return bos.toString("UTF-8");
    }

    private static String trim(String text, int max) {
        if (text == null) return "";
        String t = text.trim();
        if (max <= 0 || t.length() <= max) return t;
        return t.substring(0, max) + "...";
    }

    private static String parseErrorMessage(Throwable t, String fallback) {
        if (t == null) return fallback;
        String msg = t.getMessage();
        if (msg == null || msg.trim().isEmpty()) return fallback;
        return msg;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static void postMain(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }

    // ========== 回调接口 ==========

    public interface AuthCallback {
        void onSuccess(String token);
        void onError(String message);
    }

    public interface UserInfoCallback {
        void onSuccess(String nickname, String email);
        void onError(String message);
    }

    public interface ConfigCallback {
        void onSuccess(String configJson);
        void onError(String message);
    }

    public interface PlayDataCallback {
        void onSuccess(String playSql);
        void onError(String message);
    }
}
