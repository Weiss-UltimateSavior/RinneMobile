package com.apps.UserData;

import android.content.Context;
import android.content.SharedPreferences;

import com.yuki.yukihub.launcherbridge.LauncherRepositoryBridge;
import com.yuki.yukihub.launcherbridge.LauncherSyncBridge;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import com.apps.LauncherActivity;

/**
 * 统一管理 Launcher 所有用户设置的导出/导入。
 * 数据来源：
 *   1. SharedPreferences（yukihub_prefs / launcher_profile_prefs / launcher_account_settings）→ JSON
 *   2. SQLite 数据库（games + play_sessions）→ SQL
 * 存储在应用私有的 UserData 目录下：
 *   - launcher_user_data.json  （设置）
 *   - launcher_play_data.sql   （游玩记录）
 */
public class LauncherUserData {

    private static final String SETTINGS_FILE = "launcher_user_data.json";
    private static final String PLAY_SQL_FILE = "launcher_play_data.sql";
    private static final String PLAY_RECORDS_FILE = "launcher_play_records.json";
    private static final String PLAY_SERVER_SESSIONS_FILE = "launcher_play_server_sessions.json";
    private static final int VERSION = 1;
    private static final int PLAY_RECORDS_VERSION = 1;
    // 单条游玩记录最长 12 小时，与主项目 MAX_PLAY_SESSION_MS 保持一致
    private static final long MAX_PLAY_RECORD_MS = 12L * 60L * 60L * 1000L;
    private static final int MAX_SETTINGS_BYTES = 1024 * 1024;
    private static final int MAX_PLAY_SQL_BYTES = 32 * 1024 * 1024;
    private static final int MAX_CLOUD_PLAY_DATA_BYTES = 16 * 1024 * 1024;
    private static final int MAX_RUNTIME_RECORDS_BYTES = 2 * 1024 * 1024;

    // ── SharedPreferences 文件名 ──
    private static final String PREFS_MAIN = "yukihub_prefs";
    private static final String PREFS_PROFILE = "launcher_profile_prefs";
    private static final String PREFS_ACCOUNT_SETTINGS = "launcher_account_settings";
    private static final String KEY_REALTIME_DEVICE_ID = "realtime_playtime_device_id";

    // ── yukihub_prefs 键 ──
    private static final String[] MAIN_PREF_KEYS = {
            "launcher_dark_mode",
            "launcher_theme_style",
            "launcher_particles_enabled",
            "launcher_particle_style",
            "launcher_storage_permission_asked",
            "scan_root_uris",
            "scan_root_enabled",
            "last_scan_root_uri",
            "startup_scan_depth",
            "profile_avatar",
            "auth_saved_email",
            "kr_engine_version",
            "kr_compat_mode",
            "kr_scoped_save_dir",
            "artemis_scoped_save_dir"
    };

    // ── launcher_profile_prefs 键 ──
    private static final String[] PROFILE_PREF_KEYS = {
            "custom_cover_uri",
            "custom_avatar_uri"
    };

    // ── launcher_account_settings 键 ──
    private static final String[] ACCOUNT_SETTINGS_KEYS = {
            "sync_config",
            "realtime_playtime",
            "profile_display",
            "model_feature",
            "email_subscribe"
    };

    // ══════════════════════════════════════════════════
    //  导出
    // ══════════════════════════════════════════════════

    /**
     * 导出所有数据：设置→JSON，游玩记录→SQL。
     *
     * @return 导出目录路径，失败返回 null
     */
    public static String exportAll(Context context) {
        try {
            File dir = getUserDataDir(context);

            // 设置 → JSON
            JSONObject root = new JSONObject();
            root.put("version", VERSION);
            root.put("export_time", System.currentTimeMillis());
            root.put("main_prefs", exportSharedPreferences(context, PREFS_MAIN, MAIN_PREF_KEYS));
            root.put("profile_prefs", exportSharedPreferences(context, PREFS_PROFILE, PROFILE_PREF_KEYS));
            root.put("account_settings", exportSharedPreferences(context, PREFS_ACCOUNT_SETTINGS, ACCOUNT_SETTINGS_KEYS));
            writeText(new File(dir, SETTINGS_FILE), root.toString(2));

            // 游玩记录 → SQL
            String sql = LauncherRepositoryBridge.exportPlaySql(context);
            writeText(new File(dir, PLAY_SQL_FILE), sql);

            return dir.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 仅导出设置为 JSON 字符串（不含游玩记录）。
     */
    public static String exportSettingsJson(Context context) {
        try {
            JSONObject root = new JSONObject();
            root.put("version", VERSION);
            root.put("export_time", System.currentTimeMillis());
            root.put("main_prefs", exportSharedPreferences(context, PREFS_MAIN, MAIN_PREF_KEYS));
            root.put("profile_prefs", exportSharedPreferences(context, PREFS_PROFILE, PROFILE_PREF_KEYS));
            root.put("account_settings", exportSharedPreferences(context, PREFS_ACCOUNT_SETTINGS, ACCOUNT_SETTINGS_KEYS));
            return root.toString(2);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 导出用于账户云备份的结构化快照。
     * 游戏按稳定身份恢复，游玩会话按 session_uuid 去重，不携带跨设备无效的本地主键关系。
     */
    public static String exportCloudPlayData(Context context) {
        if (context == null) return null;
        JSONObject root = LauncherSyncBridge.exportCloudSnapshot(context);
        if (root == null) return null;
        return root.toString();
    }

    /**
     * 导入账户云备份。新版 JSON 走 LauncherSyncBridge 的稳定身份合并；旧版 SQL 继续兼容。
     */
    public static boolean importCloudPlayData(Context context, String playData) {
        if (context == null || playData == null || playData.trim().isEmpty()) return false;
        if (utf8Length(playData) > MAX_CLOUD_PLAY_DATA_BYTES) return false;
        String trimmed = playData.trim();
        if (trimmed.startsWith("{")) {
            try {
                JSONObject root = new JSONObject(trimmed);
                if (LauncherSyncBridge.importCloudSnapshot(context, root)) {
                    return true;
                }
                return false;
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        }
        return LauncherRepositoryBridge.importPlaySql(context, playData);
    }

    // ══════════════════════════════════════════════════
    //  导入
    // ══════════════════════════════════════════════════

    /**
     * 从 UserData 目录导入所有数据：设置从 JSON，游玩记录从 SQL。
     *
     * @return 成功返回 true
     */
    public static boolean importAll(Context context) {
        File dir = getUserDataDir(context);
        boolean ok = true;

        File settingsFile = new File(dir, SETTINGS_FILE);
        if (settingsFile.exists()) {
            try {
                String json = readText(settingsFile, MAX_SETTINGS_BYTES, "设置备份");
                ok = importSettingsFromJson(context, json);
            } catch (Exception e) {
                ok = false;
            }
        }

        File sqlFile = new File(dir, PLAY_SQL_FILE);
        if (sqlFile.exists()) {
            try {
                String sql = readText(sqlFile, MAX_PLAY_SQL_BYTES, "游玩记录备份");
                ok = LauncherRepositoryBridge.importPlaySql(context, sql) && ok;
            } catch (Exception e) {
                ok = false;
            }
        }

        return ok;
    }

    /**
     * 导入所有数据并重启 Launcher 使设置生效。
     * 用于在线同步配置后应用变更。
     *
     * @param activity 当前 Activity（用于触发重启）
     * @return 导入是否成功（无论成功与否都会尝试重启）
     */
    public static boolean importAndRestart(android.app.Activity activity) {
        boolean ok = importAll(activity);
        // 重启 LauncherActivity 使所有设置（主题、暗色模式、扫描目录等）生效
        restartLauncher(activity);
        return ok;
    }

    /**
     * 从 JSON 字符串仅导入设置。
     */
    public static boolean importSettingsFromJson(Context context, String json) {
    try {
        if (json == null || json.trim().isEmpty()) return false;

        JSONObject root = new JSONObject(json);

        boolean ok = true;

        if (root.has("main_prefs")) {
            ok = importSharedPreferences(context, PREFS_MAIN, root.getJSONObject("main_prefs")) && ok;
        }

        if (root.has("profile_prefs")) {
            ok = importSharedPreferences(context, PREFS_PROFILE, root.getJSONObject("profile_prefs")) && ok;
        }

        if (root.has("account_settings")) {
            ok = importSharedPreferences(context, PREFS_ACCOUNT_SETTINGS, root.getJSONObject("account_settings")) && ok;
        }

        return ok;
    } catch (Exception e) {
        e.printStackTrace();
        return false;
    }
    }

    // ══════════════════════════════════════════════════
    //  查询
    // ══════════════════════════════════════════════════

    public static File getSettingsFile(Context context) {
        return new File(getUserDataDir(context), SETTINGS_FILE);
    }

    public static File getPlaySqlFile(Context context) {
        return new File(getUserDataDir(context), PLAY_SQL_FILE);
    }

    public static File getUserDataDir(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), "UserData");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static String readExportedJson(Context context) {
        File file = getSettingsFile(context);
        if (!file.exists()) return null;
        try {
            return readText(file, MAX_SETTINGS_BYTES, "设置备份");
        } catch (Exception e) {
            return null;
        }
    }

    public static String readExportedSql(Context context) {
        File file = getPlaySqlFile(context);
        if (!file.exists()) return null;
        try {
            return readText(file, MAX_PLAY_SQL_BYTES, "游玩记录备份");
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean deleteExported(Context context) {
        boolean a = getSettingsFile(context).delete();
        boolean b = getPlaySqlFile(context).delete();
        return a || b;
    }

    // ══════════════════════════════════════════════════
    //  SharedPreferences 导出/导入
    // ══════════════════════════════════════════════════

    private static JSONObject exportSharedPreferences(Context context, String prefsName, String[] keys) throws JSONException {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        JSONObject obj = new JSONObject();
        for (String key : keys) {
            if (!prefs.contains(key)) continue;
            Object value = prefs.getAll().get(key);
            obj.put(key, value);
        }
        return obj;
    }

    /** 从 JSONObject 导入 SharedPreferences 数据。 */
    public static boolean importSharedPreferences(Context context, String prefsName, JSONObject obj) throws JSONException {
    SharedPreferences prefs = context.getApplicationContext()
            .getSharedPreferences(prefsName, Context.MODE_PRIVATE);

    SharedPreferences.Editor editor = prefs.edit();
    Iterator<String> it = obj.keys();

    while (it.hasNext()) {
        String key = it.next();
        Object value = obj.get(key);

        if (value == JSONObject.NULL) {
            editor.remove(key);
        } else if (value instanceof Boolean) {
            editor.putBoolean(key, (Boolean) value);
        } else if (value instanceof Integer) {
            editor.putInt(key, (Integer) value);
        } else if (value instanceof Long) {
            editor.putLong(key, (Long) value);
        } else if (value instanceof Float) {
            editor.putFloat(key, (Float) value);
        } else if (value instanceof Double) {
            double d = (Double) value;
            if (d == Math.rint(d)) {
                editor.putLong(key, (long) d);
            } else {
                editor.putFloat(key, (float) d);
            }
        } else {
            editor.putString(key, String.valueOf(value));
        }
    }

    return editor.commit();
    }

    // ══════════════════════════════════════════════════
    //  重启
    // ══════════════════════════════════════════════════

    /** 直接重启 LauncherActivity，不重新导入数据。用于数据已导入后仅需重启的场景。 */
    public static void restartLauncher(android.app.Activity activity) {
        try {
            android.content.Intent intent = new android.content.Intent(activity, com.apps.LauncherActivity.class);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK | android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
            activity.overridePendingTransition(0, 0);
            activity.finish();
        } catch (Exception ignored) {
            // 非 Launcher 上下文时降级为普通重启
            try {
                android.os.Process.killProcess(android.os.Process.myPid());
            } catch (Exception ignored2) {
            }
        }
    }

    //清理 SQL 注释
    private static String removeSqlLineComments(String sql) {
    StringBuilder sb = new StringBuilder();
    String[] lines = sql.split("\\r?\\n");

    for (String line : lines) {
        String trimmed = line.trim();
        if (trimmed.startsWith("--")) {
            continue;
        }
        sb.append(line).append('\n');
    }

    return sb.toString();
    }

    // ══════════════════════════════════════════════════
    //  实际游玩记录临时存储（供后续上传）
    // ══════════════════════════════════════════════════
    //
    // 设计说明：
    //   主项目通过 GameRepository.startPlaySession/finishPlaySession 将会话写入
    //   play_sessions 表（由 LauncherGameLaunchBridge 透出）。LauncherUserData 在此
    //   之外并行维护一份「实际游玩记录」缓冲，用于后续上传到服务端。
    //   该缓冲为追加式 JSON 文件 launcher_play_records.json，上传成功后可调用
    //   clearPlayRecords 清空。

    /**
     * 追加一条实际游玩记录到临时缓冲。
     * 调用时机：游戏会话结束时（finishDirectPlaySessionIfNeeded）。
     *
     * @param gameId     游戏 id
     * @param gameTitle  游戏标题（用于上传时展示）
     * @param startTime  会话开始时间戳（ms）
     * @param endTime    会话结束时间戳（ms）
     * @param duration   实际游玩时长（ms），<=0 时按 endTime-startTime 推算
     * @param launchType 启动类型（internal.krkr / external 等）
     * @return 生成的记录的 sessionUuid，失败返回 null
     */
    public static String appendPlayRecord(Context context, long gameId, String gameTitle,
                                          long startTime, long endTime, long duration, String launchType) {
        if (context == null || gameId <= 0L || startTime <= 0L) return null;
        long safeEnd = endTime > 0L ? endTime : System.currentTimeMillis();
        long rawDuration = duration > 0L ? duration : Math.max(0L, safeEnd - startTime);
        if (rawDuration <= 0L) return null;
        long safeDuration = Math.min(rawDuration, MAX_PLAY_RECORD_MS);

        JSONObject record = new JSONObject();
        String sessionUuid = UUID.randomUUID().toString();
        try {
            record.put("sessionUuid", sessionUuid);
            record.put("gameId", gameId);
            record.put("gameTitle", gameTitle == null ? "" : gameTitle);
            record.put("startTime", startTime);
            record.put("endTime", safeEnd);
            record.put("duration", safeDuration);
            record.put("launchType", launchType == null ? "external" : launchType);
            record.put("recordedAt", System.currentTimeMillis());
        } catch (JSONException e) {
            return null;
        }

        synchronized (PLAY_RECORDS_LOCK) {
            File file = getPlayRecordsFile(context);
            JSONArray arr = readPlayRecordsArray(context);
            arr.put(record);
            if (writePlayRecordsFile(file, arr)) return sessionUuid;
        }
        return null;
    }

    /**
     * 读取所有暂存的游玩记录（按记录追加顺序）。
     */
    public static List<JSONObject> readPlayRecords(Context context) {
        List<JSONObject> list = new ArrayList<>();
        if (context == null) return list;
        JSONArray arr = readPlayRecordsArray(context);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) list.add(o);
        }
        return list;
    }

    /**
     * 读取暂存的游玩记录条数。
     */
    public static int getPlayRecordCount(Context context) {
        return readPlayRecordsArray(context).length();
    }

    /**
     * 清空所有暂存的游玩记录。建议在上传成功后调用。
     */
    public static boolean clearPlayRecords(Context context) {
        if (context == null) return false;
        synchronized (PLAY_RECORDS_LOCK) {
            File file = getPlayRecordsFile(context);
            if (!file.exists()) return true;
            try {
                JSONObject root = new JSONObject();
                root.put("version", PLAY_RECORDS_VERSION);
                root.put("records", new JSONArray());
                writeText(file, root.toString(2));
                return true;
            } catch (Exception e) {
                return file.delete();
            }
        }
    }

    /**
     * 删除已上传的若干条记录（按 sessionUuid 匹配），用于增量上传场景。
     */
    public static boolean removePlayRecords(Context context, java.util.Collection<String> sessionUuids) {
        if (context == null) return false;
        if (sessionUuids == null || sessionUuids.isEmpty()) return true;
        synchronized (PLAY_RECORDS_LOCK) {
            JSONArray arr = readPlayRecordsArray(context);
            JSONArray remaining = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String uuid = o.optString("sessionUuid", "");
                if (!sessionUuids.contains(uuid)) remaining.put(o);
            }
            return writePlayRecordsFile(getPlayRecordsFile(context), remaining);
        }
    }

    public static File getPlayRecordsFile(Context context) {
        return new File(getUserDataDir(context), PLAY_RECORDS_FILE);
    }

    private static final Object PLAY_RECORDS_LOCK = new Object();
    private static final Object SERVER_SESSIONS_LOCK = new Object();

    /**
     * 取得服务端实际游玩计时使用的设备 ID。安装后生成并持久化，不能每次启动都变化。
     */
    public static String getRealtimePlaytimeDeviceId(Context context) {
        if (context == null) return "";
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS_MAIN, Context.MODE_PRIVATE);
        String existing = prefs.getString(KEY_REALTIME_DEVICE_ID, "");
        if (existing != null && !existing.trim().isEmpty()) return existing;
        String created = UUID.randomUUID().toString();
        prefs.edit().putString(KEY_REALTIME_DEVICE_ID, created).apply();
        return created;
    }

    /**
     * 保存本地 play_session 与服务端 session_id 的映射。应用异常退出后可据此恢复 finish。
     */
    public static boolean rememberServerPlaySession(Context context, long localSessionId, long gameId,
                                                    String gameTitle, String serverSessionId) {
        if (context == null || localSessionId <= 0L || gameId <= 0L || serverSessionId == null || serverSessionId.trim().isEmpty()) {
            return false;
        }
        synchronized (SERVER_SESSIONS_LOCK) {
            JSONArray arr = readServerSessionsArray(context);
            JSONArray kept = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject old = arr.optJSONObject(i);
                if (old == null || old.optLong("localSessionId", -1L) == localSessionId) continue;
                kept.put(old);
            }
            JSONObject item = new JSONObject();
            try {
                item.put("localSessionId", localSessionId);
                item.put("gameId", gameId);
                item.put("gameTitle", gameTitle == null ? "" : gameTitle);
                item.put("serverSessionId", serverSessionId);
                item.put("createdAt", System.currentTimeMillis());
            } catch (JSONException e) {
                return false;
            }
            kept.put(item);
            return writeServerSessionsFile(getServerSessionsFile(context), kept);
        }
    }

    public static String findServerPlaySessionId(Context context, long localSessionId) {
        if (context == null || localSessionId <= 0L) return "";
        JSONArray arr = readServerSessionsArray(context);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item != null && item.optLong("localSessionId", -1L) == localSessionId) {
                return item.optString("serverSessionId", "");
            }
        }
        return "";
    }

    public static boolean removeServerPlaySession(Context context, long localSessionId) {
        if (context == null || localSessionId <= 0L) return false;
        synchronized (SERVER_SESSIONS_LOCK) {
            JSONArray arr = readServerSessionsArray(context);
            JSONArray kept = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null || item.optLong("localSessionId", -1L) == localSessionId) continue;
                kept.put(item);
            }
            return writeServerSessionsFile(getServerSessionsFile(context), kept);
        }
    }

    public static File getServerSessionsFile(Context context) {
        return new File(getUserDataDir(context), PLAY_SERVER_SESSIONS_FILE);
    }

    private static JSONArray readPlayRecordsArray(Context context) {
        File file = getPlayRecordsFile(context);
        if (!file.exists()) return new JSONArray();
        try {
            String json = readText(file, MAX_RUNTIME_RECORDS_BYTES, "游玩记录缓存");
            JSONObject root = new JSONObject(json);
            JSONArray arr = root.optJSONArray("records");
            return arr != null ? arr : new JSONArray();
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static boolean writePlayRecordsFile(File file, JSONArray arr) {
        try {
            JSONObject root = new JSONObject();
            root.put("version", PLAY_RECORDS_VERSION);
            root.put("records", arr);
            writeText(file, root.toString(2));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static JSONArray readServerSessionsArray(Context context) {
        File file = getServerSessionsFile(context);
        if (!file.exists()) return new JSONArray();
        try {
            String json = readText(file, MAX_RUNTIME_RECORDS_BYTES, "游玩会话缓存");
            JSONObject root = new JSONObject(json);
            JSONArray arr = root.optJSONArray("sessions");
            return arr != null ? arr : new JSONArray();
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static boolean writeServerSessionsFile(File file, JSONArray arr) {
        try {
            JSONObject root = new JSONObject();
            root.put("version", PLAY_RECORDS_VERSION);
            root.put("sessions", arr);
            writeText(file, root.toString(2));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ══════════════════════════════════════════════════
    //  文件 I/O 工具
    // ══════════════════════════════════════════════════

    public static void writeText(File file, String text) throws IOException {
        FileOutputStream fos = new FileOutputStream(file);
        try {
            fos.write(text.getBytes(StandardCharsets.UTF_8));
        } finally {
            fos.close();
        }
    }

    private static String readText(File file, int maxBytes, String label) throws IOException {
        if (file == null || !file.isFile()) throw new IOException(label + "不存在或不是普通文件");
        long declaredLength = file.length();
        if (declaredLength > maxBytes) {
            throw new IOException(label + "过大（文件声明 " + declaredLength + " 字节，最大允许 " + maxBytes + " 字节）");
        }
        try (FileInputStream fis = new FileInputStream(file);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream((int) Math.max(0, declaredLength))) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = fis.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) throw new IOException(label + "过大（读取超过最大允许 " + maxBytes + " 字节）");
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static int utf8Length(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }
}
