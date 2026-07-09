package com.apps.UserData;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.yuki.yukihub.data.YukiDatabaseHelper;

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
    private static final int VERSION = 1;

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
     * 导入游玩记录 SQL。
     * 逐条执行 INSERT 语句，单条失败仅跳过（不回滚已成功的语句），
     * 确保部分数据损坏/截断时不丢失全部数据。
     *
     * @return 全部成功返回 true，有跳过返回 false（但已执行的数据仍保留）
     */
    public static boolean importPlaySql(Context context, String sql) {
    if (sql == null || sql.trim().isEmpty()) return true;

    YukiDatabaseHelper helper = new YukiDatabaseHelper(context.getApplicationContext());
    SQLiteDatabase db = helper.getWritableDatabase();

    boolean allOk = true;

    try {
        List<String> statements = splitSqlStatements(sql);

        db.beginTransaction();

        for (String stmt : statements) {
            String trimmed = removeLeadingSqlComments(stmt).trim();
            if (trimmed.isEmpty()) continue;

            try {
                db.execSQL(trimmed);
            } catch (Exception e) {
                e.printStackTrace();
                allOk = false;
            }
        }

        db.setTransactionSuccessful();
    } catch (Exception e) {
        e.printStackTrace();
        allOk = false;
    } finally {
        try {
            db.endTransaction();
        } catch (Exception ignored) {
        }
        db.close();
    }

    return allOk;
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
