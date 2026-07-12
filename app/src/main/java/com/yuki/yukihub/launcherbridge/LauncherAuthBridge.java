package com.yuki.yukihub.launcherbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.apps.UserData.LauncherUserData;
import com.yuki.yukihub.util.AppExecutors;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * 启动器认证桥接层。
 * 提供登录、注册、获取用户信息等 API 调用，
 * 结果通过 Callback 回调到主线程。
 */
public final class LauncherAuthBridge {
    private static final String API_BASE = "https://api.rinne.cyou:9999";
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

    public static void register(Context context, String username, String email, String password, String inviteCode,
                                String verificationCode, AuthCallback callback) {
        AppExecutors.runOnIo(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("username", username);
                body.put("email", email);
                body.put("password", password);
                body.put("invite_code", inviteCode);
                body.put("verification_code", verificationCode);
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
                } else if (msg.contains("验证码已过期")) {
                    msg = "验证码已过期，请重新获取";
                } else if (msg.contains("验证码错误")) {
                    msg = "验证码错误，请检查后重试";
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

    /** 发送注册邮箱验证码。 */
    public static void sendRegistrationVerificationCode(Context context, String email, SimpleCallback callback) {
        sendEmailCode(context, "/auth/verify-code", email, "验证码发送失败", callback);
    }

    /** 发送密码重置验证码；该验证码与注册验证码在服务端独立存储。 */
    public static void sendPasswordResetCode(Context context, String email, SimpleCallback callback) {
        sendEmailCode(context, "/auth/forgot-password", email, "验证码发送失败", callback);
    }

    /** 通过邮箱验证码重置密码，成功后清除本机登录状态。 */
    public static void resetPassword(Context context, String email, String verificationCode, String newPassword,
                                     SimpleCallback callback) {
        AppExecutors.runOnIo(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("email", email);
                body.put("verification_code", verificationCode);
                body.put("new_password", newPassword);
                post("/auth/reset-password", body, null);
                clearToken(context);
                postMain(callback::onSuccess);
            } catch (Throwable t) {
                final String message = normalizeEmailCodeError(parseErrorMessage(t, "密码重置失败"));
                postMain(() -> callback.onError(message));
            }
        });
    }

    /** 获取当前用户的邮件订阅状态。 */
    public static void fetchEmailSubscription(Context context, SubscriptionCallback callback) {
        AppExecutors.runOnIo(() -> {
            try {
                String token = getToken(context);
                if (token.isEmpty()) throw new RuntimeException("未登录");
                JSONObject json = new JSONObject(get("/auth/subscription", token));
                boolean subscribed = json.optBoolean("subscribed", false);
                postMain(() -> callback.onSuccess(subscribed));
            } catch (Throwable t) {
                String message = parseErrorMessage(t, "获取邮件订阅状态失败");
                if (message.contains("401")) {
                    clearToken(context);
                    message = "登录已过期，请重新登录";
                }
                final String error = message;
                postMain(() -> callback.onError(error));
            }
        });
    }

    /** 更新当前用户的邮件订阅状态。 */
    public static void updateEmailSubscription(Context context, boolean subscribed, SubscriptionCallback callback) {
        AppExecutors.runOnIo(() -> {
            try {
                String token = getToken(context);
                if (token.isEmpty()) throw new RuntimeException("未登录");
                JSONObject body = new JSONObject();
                body.put("subscribed", subscribed);
                JSONObject json = new JSONObject(put("/auth/subscription", body, token));
                postMain(() -> callback.onSuccess(json.optBoolean("subscribed", subscribed)));
            } catch (Throwable t) {
                String message = parseErrorMessage(t, "更新邮件订阅失败");
                if (message.contains("401")) {
                    clearToken(context);
                    message = "登录已过期，请重新登录";
                }
                final String error = message;
                postMain(() -> callback.onError(error));
            }
        });
    }

    private static void sendEmailCode(Context context, String path, String email, String fallback, SimpleCallback callback) {
        AppExecutors.runOnIo(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("email", email);
                body.put("deviceId", LauncherUserData.getRealtimePlaytimeDeviceId(context));
                post(path, body, null);
                postMain(callback::onSuccess);
            } catch (Throwable t) {
                final String message = normalizeEmailCodeError(parseErrorMessage(t, fallback));
                postMain(() -> callback.onError(message));
            }
        });
    }

    private static String normalizeEmailCodeError(String message) {
        String msg = message == null ? "操作失败，请稍后重试" : message;
        if (msg.contains("429") || msg.contains("请求过于频繁") || msg.contains("冷却")) return "操作过于频繁，请稍后再试";
        if (msg.contains("验证码已过期")) return "验证码已过期，请重新获取";
        if (msg.contains("验证码错误")) return "验证码错误，请检查后重试";
        if (msg.contains("邮箱未注册") || msg.contains("用户不存在")) return "该邮箱尚未注册";
        if (msg.contains("邮件发送失败")) return "验证码发送失败，请稍后重试";
        if (msg.contains("422")) return "邮箱或验证码格式不正确";
        return msg;
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

    /** 用户级 LLM 覆盖配置；空字段由服务端回退至系统默认。 */
    public static void fetchLlmConfig(Context context, LlmConfigCallback callback) {
        AppExecutors.runOnIo(() -> {
            try {
                String token = getToken(context);
                if (token.isEmpty()) throw new RuntimeException("未登录");
                JSONObject json = new JSONObject(get("/auth/llm", token));
                postMain(() -> callback.onSuccess(LlmConfig.fromJson(json)));
            } catch (Throwable t) {
                postLlmError(context, t, "获取模型配置失败", callback);
            }
        });
    }

    public static void updateLlmConfig(Context context, LlmConfig config, LlmConfigCallback callback) {
        AppExecutors.runOnIo(() -> {
            try {
                String token = getToken(context);
                if (token.isEmpty()) throw new RuntimeException("未登录");
                JSONObject json = new JSONObject(putLlm("/auth/llm", config == null ? new LlmConfig().toJson() : config.toJson(), token));
                JSONObject result = json.optJSONObject("llm");
                postMain(() -> callback.onSuccess(LlmConfig.fromJson(result == null ? json : result)));
            } catch (Throwable t) {
                postLlmError(context, t, "保存模型配置失败", callback);
            }
        });
    }

    private static void postLlmError(Context context, Throwable error, String fallback, LlmConfigCallback callback) {
        String message = parseErrorMessage(error, fallback);
        if (isNetworkTimeout(error, message)) message = "模型服务不可达或响应超时，请检查接口地址和网络连接";
        else if (message.contains("401")) { clearToken(context); message = "登录已过期，请重新登录"; }
        else if (message.contains("AI_CONNECTION_TEST_FAILED")) message = "模型连通性验证失败，请检查接口地址、API Key 和模型名称";
        else if (message.contains("422")) message = "模型配置格式不正确，请检查地址、模型名与温度";
        else if (message.contains("404") || message.contains("用户不存在")) message = "用户不存在，请重新登录后重试";
        final String finalMessage = message;
        postMain(() -> callback.onError(finalMessage));
    }

    private static boolean isNetworkTimeout(Throwable error, String message) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof java.net.SocketTimeoutException || current instanceof java.net.ConnectException) return true;
        }
        String text = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        return text.contains("timed out") || text.contains("timeout") || text.contains("failed to connect") || text.contains("network is unreachable");
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
     * 使用更长的超时和更大的响应缓冲，适配大数据量场景。
     */
    public static void fetchPlayData(Context context, PlayDataCallback callback) {
        AppExecutors.runOnIo(() -> {
            try {
                String token = getToken(context);
                if (token.isEmpty()) throw new RuntimeException("未登录");
                String response = getLarge("/auth/config/play-data", token);
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
     * 使用更长的超时和更大的响应缓冲，适配大数据量场景。
     */
    public static void uploadPlayData(Context context, String playSql, PlayDataCallback callback) {
        AppExecutors.runOnIo(() -> {
            try {
                String token = getToken(context);
                if (token.isEmpty()) throw new RuntimeException("未登录");
                JSONObject body = new JSONObject();
                body.put("play_data", playSql);
                putLarge("/auth/config/play-data", body, token);
                postMain(() -> callback.onSuccess(playSql));
            } catch (Throwable t) {
                String msg = parseErrorMessage(t, "上传游玩记录失败");
                if (msg.contains("401")) { clearToken(context); msg = "登录已过期，请重新登录"; }
                final String errMsg = msg;
                postMain(() -> callback.onError(errMsg));
            }
        });
    }

    // ========== 游玩时长统计 ==========

    public static void startPlayTimeSession(Context context, long gameId, String gameTitle, String deviceId,
                                            PlaySessionCallback callback) {
        AppExecutors.runOnIo(() -> {
            try {
                String token = getToken(context);
                if (token.isEmpty()) throw new RuntimeException("未登录");
                JSONObject body = new JSONObject();
                body.put("gameId", gameId);
                body.put("gameTitle", gameTitle == null ? "" : gameTitle);
                body.put("deviceId", deviceId == null ? "" : deviceId);
                PlaySession session = parsePlaySession(new JSONObject(post("/auth/play-time/sessions/start", body, token)));
                postMain(() -> callback.onSuccess(session));
            } catch (Throwable t) {
                String msg = parseErrorMessage(t, "启动服务端游玩会话失败");
                if (msg.contains("401")) { clearToken(context); msg = "登录已过期，请重新登录"; }
                final String errMsg = msg;
                postMain(() -> callback.onError(errMsg));
            }
        });
    }

    public static void heartbeatPlayTimeSession(Context context, String sessionId, PlaySessionCallback callback) {
        postPlayTimeSessionEvent(context, sessionId, "heartbeat", "服务端游玩心跳失败", callback);
    }

    public static void finishPlayTimeSession(Context context, String sessionId, PlaySessionCallback callback) {
        postPlayTimeSessionEvent(context, sessionId, "finish", "结束服务端游玩会话失败", callback);
    }

    private static void postPlayTimeSessionEvent(Context context, String sessionId, String action,
                                                 String fallback, PlaySessionCallback callback) {
        AppExecutors.runOnIo(() -> {
            try {
                String token = getToken(context);
                if (token.isEmpty()) throw new RuntimeException("未登录");
                if (sessionId == null || sessionId.trim().isEmpty()) throw new RuntimeException("会话不存在");
                PlaySession session = parsePlaySession(new JSONObject(postNoBody(
                        "/auth/play-time/sessions/" + sessionId.trim() + "/" + action,
                        token)));
                postMain(() -> callback.onSuccess(session));
            } catch (Throwable t) {
                String msg = parseErrorMessage(t, fallback);
                if (msg.contains("401")) { clearToken(context); msg = "登录已过期，请重新登录"; }
                final String errMsg = msg;
                postMain(() -> callback.onError(errMsg));
            }
        });
    }

    private static PlaySession parsePlaySession(JSONObject json) {
        return new PlaySession(
                json.optString("session_id", ""),
                json.optLong("game_id"),
                json.optString("status", ""),
                json.optLong("started_at"),
                json.optLong("last_heartbeat_at"),
                json.optLong("ended_at"),
                json.optLong("duration_ms"));
    }

    /**
     * 旧版实际游玩记录上传入口，仅保留兼容。
     * 新流程必须使用 startPlayTimeSession/heartbeatPlayTimeSession/finishPlayTimeSession，
     * 不再向 /auth/play-time 提交前端计算的 duration。
     *
     * @param records 游玩记录列表（每条为 JSONObject，字段与 LauncherUserData.appendPlayRecord 一致）
     * @param callback 回调：onSuccess 返回服务端累计统计 JSON 数组字符串
     */
    public static void uploadPlayTime(Context context, List<JSONObject> records, PlayTimeCallback callback) {
        AppExecutors.runOnIo(() -> {
            try {
                String token = getToken(context);
                if (token.isEmpty()) throw new RuntimeException("未登录");
                if (records == null || records.isEmpty()) throw new RuntimeException("记录列表为空");
                JSONObject body = new JSONObject();
                JSONArray arr = new JSONArray();
                for (JSONObject r : records) arr.put(r);
                body.put("records", arr);
                String response = post("/auth/play-time", body, token);
                postMain(() -> callback.onSuccess(response));
            } catch (Throwable t) {
                String msg = parseErrorMessage(t, "上传游玩时长失败");
                if (msg.contains("401")) { clearToken(context); msg = "登录已过期，请重新登录"; }
                final String errMsg = msg;
                postMain(() -> callback.onError(errMsg));
            }
        });
    }

    /**
     * 获取当前用户所有游戏的累计游玩时长统计（按时长降序）。
     *
     * @param callback 回调：onSuccess 返回 JSON 数组字符串，每项含 game_id/game_title/total_duration_ms/play_count/last_played_at
     */
    public static void fetchPlayTime(Context context, PlayTimeCallback callback) {
        AppExecutors.runOnIo(() -> {
            try {
                String token = getToken(context);
                if (token.isEmpty()) throw new RuntimeException("未登录");
                String response = get("/auth/play-time", token);
                postMain(() -> callback.onSuccess(response));
            } catch (Throwable t) {
                String msg = parseErrorMessage(t, "获取游玩时长失败");
                if (msg.contains("401")) { clearToken(context); msg = "登录已过期，请重新登录"; }
                final String errMsg = msg;
                postMain(() -> callback.onError(errMsg));
            }
        });
    }

    /** 获取全站游玩时长前 15 名，仅使用普通用户鉴权。 */
    public static void fetchPlayTimeLeaderboard(Context context, LeaderboardCallback callback) {
        AppExecutors.runOnIo(() -> {
            try {
                String token = getToken(context);
                if (token.isEmpty()) throw new RuntimeException("未登录");
                JSONArray array = new JSONArray(get("/auth/play-time/leaderboard", token));
                java.util.ArrayList<LeaderboardEntry> entries = new java.util.ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    entries.add(new LeaderboardEntry(item.optInt("rank"), item.optString("username"), item.optLong("total_duration_ms")));
                }
                postMain(() -> callback.onSuccess(entries));
            } catch (Throwable t) {
                String msg = parseErrorMessage(t, "获取游玩时长排行榜失败");
                if (msg.contains("401")) { clearToken(context); msg = "登录已过期，请重新登录"; }
                final String errMsg = msg;
                postMain(() -> callback.onError(errMsg));
            }
        });
    }

    /** 获取当前用户的全站游玩时长排名。 */
    public static void fetchMyPlayTimeRank(Context context, MyRankCallback callback) {
        AppExecutors.runOnIo(() -> {
            try {
                String token = getToken(context);
                if (token.isEmpty()) throw new RuntimeException("未登录");
                JSONObject item = new JSONObject(get("/auth/play-time/rank", token));
                MyRank rank = new MyRank(item.optInt("rank"), item.optLong("total_duration_ms"));
                postMain(() -> callback.onSuccess(rank));
            } catch (Throwable t) {
                String msg = parseErrorMessage(t, "获取我的游玩时长排名失败");
                if (msg.contains("401")) { clearToken(context); msg = "登录已过期，请重新登录"; }
                final String errMsg = msg;
                postMain(() -> callback.onError(errMsg));
            }
        });
    }

    // ========== 网络工具 ==========

    /** 大数据量 PUT：超时更长，响应缓冲更大（适配游玩记录上传/下载）。 */
    private static String putLarge(String path, JSONObject body, String authToken) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API_BASE + path).openConnection();
        c.setRequestMethod("PUT");
        c.setDoOutput(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        if (authToken != null && !authToken.isEmpty()) {
            c.setRequestProperty("Authorization", "Bearer " + authToken);
        }
        byte[] bytes = body.toString().getBytes("UTF-8");
        c.setFixedLengthStreamingMode(bytes.length);
        c.getOutputStream().write(bytes);
        c.getOutputStream().flush();
        return readLargeResponse(c);
    }

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

    private static String postNoBody(String path, String authToken) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API_BASE + path).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(10000);
        c.setReadTimeout(12000);
        c.setRequestProperty("Accept", "application/json");
        if (authToken != null && !authToken.isEmpty()) {
            c.setRequestProperty("Authorization", "Bearer " + authToken);
        }
        return readResponse(c);
    }

    /** LLM 保存会触发服务端真实连通性测试，因此允许更长的读取时间。 */
    private static String putLlm(String path, JSONObject body, String authToken) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API_BASE + path).openConnection();
        c.setRequestMethod("PUT");
        c.setDoOutput(true);
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        if (authToken != null && !authToken.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + authToken);
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

    /** 大数据量 GET：超时更长，响应缓冲更大。 */
    private static String getLarge(String path, String authToken) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API_BASE + path).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestProperty("Accept", "application/json");
        if (authToken != null && !authToken.isEmpty()) {
            c.setRequestProperty("Authorization", "Bearer " + authToken);
        }
        return readLargeResponse(c);
    }

    private static String readResponse(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        String text = readSmallText(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
        return checkResponse(code, text);
    }

    /** 大响应读取：不限制缓冲大小，适配服务端回传大数据。 */
    private static String readLargeResponse(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        String text = readLargeText(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
        return checkResponse(code, text);
    }

    private static String checkResponse(int code, String text) throws Exception {
        if (code < 200 || code >= 300) {
            String detail = text;
            try {
                JSONObject err = new JSONObject(text == null ? "{}" : text);
                // 适配统一错误格式: {"detail": {"code": "...", "message": "..."}}
                Object detailObj = err.get("detail");
                if (detailObj instanceof JSONObject) {
                    JSONObject detailJson = (JSONObject) detailObj;
                    if (detailJson.has("message")) {
                        String codeName = detailJson.optString("code", "");
                        detail = (codeName.isEmpty() ? "" : codeName + ": ") + detailJson.optString("message", text);
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

    /** 大响应读取：无 64KB 限制，适配游玩记录等大数据回传。 */
    private static String readLargeText(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = is.read(buf)) != -1) {
            bos.write(buf, 0, len);
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

    public interface SimpleCallback {
        void onSuccess();
        void onError(String message);
    }

    public interface SubscriptionCallback {
        void onSuccess(boolean subscribed);
        void onError(String message);
    }

    public static final class LlmConfig {
        public String baseUrl = "";
        public String apiKey = "";
        public String model = "";
        public String temperature = "";

        static LlmConfig fromJson(JSONObject json) {
            LlmConfig config = new LlmConfig();
            if (json == null) return config;
            config.baseUrl = json.isNull("base_url") ? "" : json.optString("base_url", "");
            config.apiKey = json.isNull("api_key") ? "" : json.optString("api_key", "");
            config.model = json.isNull("model") ? "" : json.optString("model", "");
            if (!json.isNull("temperature")) config.temperature = String.valueOf(json.optDouble("temperature"));
            return config;
        }

        JSONObject toJson() throws Exception {
            JSONObject json = new JSONObject();
            json.put("base_url", nullable(baseUrl));
            json.put("api_key", nullable(apiKey));
            json.put("model", nullable(model));
            if (temperature == null || temperature.trim().isEmpty()) json.put("temperature", JSONObject.NULL);
            else json.put("temperature", Double.parseDouble(temperature.trim()));
            return json;
        }

        private static Object nullable(String value) { return value == null || value.trim().isEmpty() ? JSONObject.NULL : value.trim(); }
    }

    public interface LlmConfigCallback {
        void onSuccess(LlmConfig config);
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

    public interface PlayTimeCallback {
        void onSuccess(String statsJson);
        void onError(String message);
    }

    public static final class PlaySession {
        public final String sessionId;
        public final long gameId;
        public final String status;
        public final long startedAt;
        public final long lastHeartbeatAt;
        public final long endedAt;
        public final long durationMs;

        PlaySession(String sessionId, long gameId, String status, long startedAt,
                    long lastHeartbeatAt, long endedAt, long durationMs) {
            this.sessionId = sessionId;
            this.gameId = gameId;
            this.status = status;
            this.startedAt = startedAt;
            this.lastHeartbeatAt = lastHeartbeatAt;
            this.endedAt = endedAt;
            this.durationMs = durationMs;
        }
    }

    public interface PlaySessionCallback {
        void onSuccess(PlaySession session);
        void onError(String message);
    }

    public static final class LeaderboardEntry {
        public final int rank;
        public final String username;
        public final long totalDurationMs;

        public LeaderboardEntry(int rank, String username, long totalDurationMs) {
            this.rank = rank;
            this.username = username;
            this.totalDurationMs = totalDurationMs;
        }
    }

    public static final class MyRank {
        public final int rank;
        public final long totalDurationMs;

        MyRank(int rank, long totalDurationMs) {
            this.rank = rank;
            this.totalDurationMs = totalDurationMs;
        }
    }

    public interface LeaderboardCallback {
        void onSuccess(List<LeaderboardEntry> entries);
        void onError(String message);
    }

    public interface MyRankCallback {
        void onSuccess(MyRank rank);
        void onError(String message);
    }
}
