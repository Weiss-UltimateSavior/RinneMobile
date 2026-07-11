package com.yuki.yukihub.launcherbridge;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.yuki.yukihub.data.GameRepository;
import com.yuki.yukihub.data.YukiDatabaseHelper;
import com.yuki.yukihub.model.Game;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bridge for com.apps to access GameRepository without directly importing
 * com.yuki.yukihub.data.GameRepository. Keeps com.apps decoupled from core
 * data layer implementation details.
 *
 * Note: Game and EngineType model classes remain shared across packages
 * since they are plain data holders, not behavioral classes.
 */
public final class LauncherRepositoryBridge {
    private LauncherRepositoryBridge() {
    }

    /** Returns all non-hidden games ordered by last played time desc. */
    public static List<Game> getAllGames(Context context) {
        if (context == null) return java.util.Collections.emptyList();
        return new GameRepository(context.getApplicationContext()).getAll();
    }

    /** Finds a game by its primary id. Returns null if not found. */
    public static Game findGameById(Context context, long id) {
        if (context == null || id <= 0) return null;
        return new GameRepository(context.getApplicationContext()).findById(id);
    }

    /** Inserts a game if no existing entry matches its root uri. Returns new id or -1. */
    public static long insertGameIfNotExists(Context context, Game game) {
        if (context == null || game == null) return -1L;
        return new GameRepository(context.getApplicationContext()).insertIfNotExists(game);
    }

    /** Updates an existing game row. Returns affected row count. */
    public static int updateGame(Context context, Game game) {
        if (context == null || game == null) return 0;
        return new GameRepository(context.getApplicationContext()).update(game);
    }

    /** Deletes a game by id. Returns deleted row count. */
    public static int deleteGame(Context context, long id) {
        if (context == null || id <= 0) return 0;
        return new GameRepository(context.getApplicationContext()).delete(id);
    }

    /**
     * Returns a map of game title -> total play duration for sessions whose
     * end_time falls within [startInclusive, endExclusive).
     */
    public static Map<String, Long> getPlayDurationsBetween(Context context, long startInclusive, long endExclusive) {
        if (context == null) return java.util.Collections.emptyMap();
        return new GameRepository(context.getApplicationContext())
                .getPlayDurationsBetween(startInclusive, endExclusive);
    }

    /**
     * Replaces total play time for a game with the given duration. Clears all
     * existing play_sessions for the game and inserts one synthetic manual session
     * when duration > 0.
     */
    public static void setManualPlayTimeForGame(Context context, long gameId, long totalDurationMs) {
        if (context == null || gameId <= 0) return;
        new GameRepository(context.getApplicationContext())
                .setManualPlayTimeForGame(gameId, totalDurationMs);
    }

    /**
     * Returns the most recent finished play sessions, newest first.
     * Use {@link RecentActivity} fields for display instead of GameRepository.PlayActivity.
     */
    public static List<RecentActivity> getRecentPlayActivities(Context context, int limit) {
        if (context == null) return java.util.Collections.emptyList();
        List<GameRepository.PlayActivity> source = new GameRepository(context.getApplicationContext())
                .getRecentPlayActivities(limit);
        if (source == null || source.isEmpty()) return java.util.Collections.emptyList();
        List<RecentActivity> result = new java.util.ArrayList<>(source.size());
        for (GameRepository.PlayActivity a : source) {
            if (a == null) continue;
            result.add(new RecentActivity(
                    a.sessionId, a.sessionUuid, a.gameId, a.gameTitle,
                    a.startTime, a.endTime, a.duration, a.launchType, a.playStatus
            ));
        }
        return result;
    }

    /**
     * Equivalent value class for GameRepository.PlayActivity, exposed so that
     * com.apps does not need to import the core data layer.
     */
    public static final class RecentActivity {
        public final long sessionId;
        public final String sessionUuid;
        public final long gameId;
        public final String gameTitle;
        public final long startTime;
        public final long endTime;
        public final long duration;
        public final String launchType;
        public final String playStatus;

        public RecentActivity(long sessionId, String sessionUuid, long gameId, String gameTitle,
                              long startTime, long endTime, long duration, String launchType,
                              String playStatus) {
            this.sessionId = sessionId;
            this.sessionUuid = sessionUuid;
            this.gameId = gameId;
            this.gameTitle = gameTitle;
            this.startTime = startTime;
            this.endTime = endTime;
            this.duration = duration;
            this.launchType = launchType;
            this.playStatus = playStatus;
        }
    }

    // ══════════════════════════════════════════════════
    //  游玩记录 SQL 导出/导入
    // ══════════════════════════════════════════════════

    /**
     * 导出游玩记录为 SQL INSERT 语句字符串。涵盖 games、play_sessions、metadata_cache、settings 四张表。
     * 输出格式兼容旧版 LauncherUserData，可在 {@link #importPlaySql} 中完整恢复。
     */
    public static String exportPlaySql(Context context) {
        if (context == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("-- Launcher Play Data Export\n");
        sb.append("-- Export time: ").append(System.currentTimeMillis()).append("\n\n");

        YukiDatabaseHelper helper = new YukiDatabaseHelper(context.getApplicationContext());
        SQLiteDatabase db = helper.getReadableDatabase();

        try {
            sb.append("-- games\n");
            Cursor gc = db.query("games", null, null, null, null, null, "id ASC");
            sb.append(tableToInsertSql(gc, "games"));
            gc.close();

            sb.append("\n-- play_sessions\n");
            Cursor pc = db.query("play_sessions", null, null, null, null, null, "id ASC");
            sb.append(tableToInsertSql(pc, "play_sessions"));
            pc.close();

            sb.append("\n-- metadata_cache\n");
            Cursor mc = db.query("metadata_cache", null, null, null, null, null, "game_id ASC, source ASC");
            sb.append(tableToInsertSql(mc, "metadata_cache"));
            mc.close();

            sb.append("\n-- settings\n");
            Cursor sc = db.query("settings", null, null, null, null, null, "\"key\" ASC");
            sb.append(tableToInsertSql(sc, "settings"));
            sc.close();
        } finally {
            db.close();
        }

        return sb.toString();
    }

    /**
     * 导入旧版游玩记录 SQL。旧格式携带数据库本地主键，只能作为完整快照恢复：先校验全部语句，
     * 再在同一事务中清空相关表并导入；任一语句失败都会整体回滚。
     *
     * @return 完整恢复成功返回 true；校验或执行失败返回 false，数据库保持恢复前状态
     */
    public static boolean importPlaySql(Context context, String sql) {
        if (context == null || sql == null || sql.trim().isEmpty()) return false;

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

    private static String tableToInsertSql(Cursor cursor, String tableName) {
        StringBuilder sb = new StringBuilder();
        String[] columns = cursor.getColumnNames();
        int colCount = columns.length;

        while (cursor.moveToNext()) {
            sb.append("INSERT OR REPLACE INTO ").append(tableName).append(" (");
            for (int i = 0; i < colCount; i++) {
                if (i > 0) sb.append(", ");
                appendIdentifier(sb, columns[i]);
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
                        appendEscapedString(sb, cursor.getString(i));
                    }
                }
            }
            sb.append(");\n");
        }
        return sb.toString();
    }

    private static void appendIdentifier(StringBuilder sb, String id) {
        sb.append('"').append(id.replace("\"", "\"\"")).append('"');
    }

    private static void appendEscapedString(StringBuilder sb, String value) {
        sb.append('\'');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\'') sb.append("''");
            else sb.append(c);
        }
        sb.append('\'');
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
}
