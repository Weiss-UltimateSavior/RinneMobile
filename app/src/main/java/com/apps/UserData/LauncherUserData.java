package com.apps.UserData;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.yuki.yukihub.data.YukiDatabaseHelper;
import com.yuki.yukihub.sync.SyncManager;

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
    private static final int VERSION = 1;
    private static final int PLAY_RECORDS_VERSION = 1;
    // 单条游玩记录最长 12 小时，与主项目 MAX_PLAY_SESSION_MS 保持一致
    private static final long MAX_PLAY_RECORD_MS = 12L * 60L * 60L * 1000L;

    // ── SharedPreferences 文件名 ──
    private static final String PREFS_MAIN = "yukihub_prefs";
    private static final String PREFS_PROFILE = "launcher_profile_prefs";
    private static final String PREFS_ACCOUNT_SETTINGS = "launcher_account_settings";

    // ── yukihub_prefs 键 ──
    private static final String[] MAIN_PREF_KEYS = {
            "launcher_dark_mode",
            "launcher_theme_style",
            "launcher_particles_enabled",
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
            String sql = exportPlaySql(context);
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
        try {
            JSONObject root = new SyncManager(context.getApplicationContext()).exportSnapshotForLocalBackup();
            root.put("backup_type", "launcher_cloud_play_v2");
            return root.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 导入账户云备份。新版 JSON 走 SyncManager 的稳定身份合并；旧版 SQL 继续兼容。
     */
    public static boolean importCloudPlayData(Context context, String playData) {
        if (context == null || playData == null || playData.trim().isEmpty()) return false;
        String trimmed = playData.trim();
        if (trimmed.startsWith("{")) {
            try {
                JSONObject root = new JSONObject(trimmed);
                if (!"YukiHub".equals(root.optString("app", ""))
                        || root.optJSONArray("games") == null
                        || root.optJSONArray("play_sessions") == null) {
                    return false;
                }
                new SyncManager(context.getApplicationContext()).importSnapshotFromLocalBackup(root);
                return true;
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        }
        return importPlaySql(context, playData);
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
                String json = readText(settingsFile);
                ok = importSettingsFromJson(context, json);
            } catch (Exception e) {
                ok = false;
            }
        }

        File sqlFile = new File(dir, PLAY_SQL_FILE);
        if (sqlFile.exists()) {
            try {
                String sql = readText(sqlFile);
                ok = importPlaySql(context, sql) && ok;
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
            return readText(file);
        } catch (Exception e) {
            return null;
        }
    }

    public static String readExportedSql(Context context) {
        File file = getPlaySqlFile(context);
        if (!file.exists()) return null;
        try {
            return readText(file);
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
    //  游玩记录 SQL 导出/导入
    // ══════════════════════════════════════════════════

    private static String exportPlaySql(Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- Launcher Play Data Export\n");
        sb.append("-- Export time: ").append(System.currentTimeMillis()).append("\n\n");

        YukiDatabaseHelper helper = new YukiDatabaseHelper(context.getApplicationContext());
        SQLiteDatabase db = helper.getReadableDatabase();

        try {
            // games 表
            sb.append("-- games\n");
            Cursor gc = db.query("games", null, null, null, null, null, "id ASC");
            sb.append(tableToInsertSql(gc, "games"));
            gc.close();

            // play_sessions 表
            sb.append("\n-- play_sessions\n");
            Cursor pc = db.query("play_sessions", null, null, null, null, null, "id ASC");
            sb.append(tableToInsertSql(pc, "play_sessions"));
            pc.close();

            // metadata_cache 表（VNDB/Bangumi/Ymgal 元数据，复合主键 game_id+source）
            sb.append("\n-- metadata_cache\n");
            Cursor mc = db.query("metadata_cache", null, null, null, null, null, "game_id ASC, source ASC");
            sb.append(tableToInsertSql(mc, "metadata_cache"));
            mc.close();

            // settings 表（键值对，主键 key）
            sb.append("\n-- settings\n");
            Cursor sc = db.query("settings", null, null, null, null, null, "\"key\" ASC");
            sb.append(tableToInsertSql(sc, "settings"));
            sc.close();
        } finally {
            db.close();
        }

        return sb.toString();
    }

    private static String tableToInsertSql(Cursor cursor, String tableName) {
        StringBuilder sb = new StringBuilder();
        String[] columns = cursor.getColumnNames();
        int colCount = columns.length;

        while (cursor.moveToNext()) {
            sb.append("INSERT OR REPLACE INTO ").append(tableName).append(" (");
            for (int i = 0; i < colCount; i++) {
                if (i > 0) sb.append(", ");
                sbappendIdentifier(sb, columns[i]);
            }
            sb.append(") VALUES (");
            for (int i = 0; i < colCount; i++) {
                if (i > 0) sb.append(", ");
                if (cursor.isNull(i)) {
                    sb.append("NULL");
                } else {
                    int type = cursor.getType(i);
                    if (type == Cursor.FIELD_TYPE_INTEGER) {
                        sb.append(cursor.getLong(i));
                    } else if (type == Cursor.FIELD_TYPE_FLOAT) {
                        sb.append(cursor.getDouble(i));
                    } else {
                        sbappendEscapedString(sb, cursor.getString(i));
                    }
                }
            }
            sb.append(");\n");
        }
        return sb.toString();
    }

    private static void sbappendIdentifier(StringBuilder sb, String id) {
        sb.append('"').append(id.replace("\"", "\"\"")).append('"');
    }

    private static void sbappendEscapedString(StringBuilder sb, String value) {
        sb.append('\'');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\'') sb.append("''");
            else sb.append(c);
        }
        sb.append('\'');
    }

    /**
     * 导入旧版游玩记录 SQL。
     * 旧格式携带数据库本地主键，只能作为完整快照恢复：先校验全部语句，
     * 再在同一事务中清空相关表并导入；任一语句失败都会整体回滚。
     *
     * @return 完整恢复成功返回 true；校验或执行失败返回 false，数据库保持恢复前状态
     */
    public static boolean importPlaySql(Context context, String sql) {
    if (sql == null || sql.trim().isEmpty()) return false;

    YukiDatabaseHelper helper = new YukiDatabaseHelper(context.getApplicationContext());
    SQLiteDatabase db = helper.getWritableDatabase();

    try {
        List<String> statements = splitSqlStatements(sql);
        List<String> validated = new ArrayList<>();
        for (String stmt : statements) {
            String trimmed = removeLeadingSqlComments(stmt).trim();
            if (trimmed.isEmpty()) continue;
            if (!isAllowedLegacyInsert(trimmed)) return false;
            validated.add(trimmed);
        }
        if (validated.isEmpty()) return false;

        db.beginTransaction();
        db.delete("play_sessions", null, null);
        db.delete("metadata_cache", null, null);
        db.delete("settings", null, null);
        db.delete("games", null, null);
        for (String stmt : validated) db.execSQL(stmt);
        db.setTransactionSuccessful();
        return true;
    } catch (Exception e) {
        e.printStackTrace();
        return false;
    } finally {
        try {
            db.endTransaction();
        } catch (Exception ignored) {
        }
        db.close();
    }
}

private static boolean isAllowedLegacyInsert(String sql) {
    String normalized = sql.replaceAll("\\s+", " ").trim().toLowerCase(java.util.Locale.ROOT);
    if (!normalized.startsWith("insert or replace into ")) return false;
    String rest = normalized.substring("insert or replace into ".length()).trim();
    if (rest.startsWith("\"")) rest = rest.substring(1);
    return rest.startsWith("games\"") || rest.startsWith("games ") || rest.startsWith("games(")
            || rest.startsWith("play_sessions\"") || rest.startsWith("play_sessions ") || rest.startsWith("play_sessions(")
            || rest.startsWith("metadata_cache\"") || rest.startsWith("metadata_cache ") || rest.startsWith("metadata_cache(")
            || rest.startsWith("settings\"") || rest.startsWith("settings ") || rest.startsWith("settings(");
}

private static String removeLeadingSqlComments(String stmt) {
    String[] lines = stmt.split("\\r?\\n");
    StringBuilder sb = new StringBuilder();

    for (String line : lines) {
        String trimmed = line.trim();
        if (trimmed.startsWith("--")) {
            continue;
        }
        sb.append(line).append('\n');
    }

    return sb.toString();
}

    /**
     * 正确拆分 SQL 语句，跳过字符串字面量中的分号。
     * 例如: INSERT INTO t VALUES ('a;b'); INSERT INTO t VALUES('c');
     * → ["INSERT INTO t VALUES ('a;b')", "INSERT INTO t VALUES('c')"]
     */
    private static List<String> splitSqlStatements(String sql) {
    List<String> result = new ArrayList<>();
    StringBuilder sb = new StringBuilder();
    boolean inSingleQuote = false;

    for (int i = 0; i < sql.length(); i++) {
        char c = sql.charAt(i);

        if (c == '\'') {
            if (inSingleQuote && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                // 关键修复：SQL 字符串里的转义单引号必须保留两个 ''
                sb.append("''");
                i++;
                continue;
            }

            inSingleQuote = !inSingleQuote;
            sb.append(c);
            continue;
        }

        if (c == ';' && !inSingleQuote) {
            String stmt = sb.toString().trim();
            if (!stmt.isEmpty()) {
                result.add(stmt);
            }
            sb.setLength(0);
        } else {
            sb.append(c);
        }
    }

    String remaining = sb.toString().trim();
    if (!remaining.isEmpty()) {
        result.add(remaining);
    }

    return result;
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

    private static JSONArray readPlayRecordsArray(Context context) {
        File file = getPlayRecordsFile(context);
        if (!file.exists()) return new JSONArray();
        try {
            String json = readText(file);
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

    private static String readText(File file) throws IOException {
        FileInputStream fis = new FileInputStream(file);
        try {
            byte[] buf = new byte[(int) file.length()];
            fis.read(buf);
            return new String(buf, StandardCharsets.UTF_8);
        } finally {
            fis.close();
        }
    }
}
