package com.yuki.yukihub.importer;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.yuki.yukihub.data.GameRepository;
import com.yuki.yukihub.data.YukiDatabaseHelper;
import com.yuki.yukihub.model.EngineType;
import com.yuki.yukihub.model.Game;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 导入服务：把三方平台解析出的 {@link ImportGameData} 列表写入 YukiHub 数据库。
 *
 * 调用约定：
 * - 本类只做同步 IO，不内建线程；调用方应通过 AppExecutors.runOnSingle 调度。
 * - 预览阶段调用 {@link #markExisting(List)} 标记冲突；
 *   用户确认后调用 {@link #importSelected(List)} 实际写库。
 *
 * 导入策略：
 * - 按游戏标题去重（标题完全一致则跳过，不合并）。去重范围包括 hidden 游戏，
 *   避免重复导入被隐藏的旧条目。
 * - 游戏路径（Windows 路径）不导入到 rootUri，留空
 * - 封面：远程 URL 写入 coverUri 和 coverSourceType=1；
 *        本地文件路径复制到应用内部目录，写入 coverPersistUri 和 coverSourceType=2
 * - 游玩记录：PotatoVN 的 PlayedTime / Vnite 的 Timers / LunaBox 的 Sessions
 *   统一转换成 play_sessions 表条目，launch_type 标记来源
 * - 事务边界：所有写库操作复用同一 {@link #dbHelper} 实例，确保事务内的查询与插入
 *   使用同一连接，避免事务隔离级别依赖实现细节。
 * - 临时目录：写库完成后调用 {@link ImporterIO#cleanupAllTempDirs()} 清理，
 *   避免 deleteOnExit() 在 Android 上失效导致封面文件残留。
 */
public class ImporterService {

    private static final String TAG = "ImporterService";

    /** 封面在应用内部目录的子目录名 */
    private static final String COVER_DIR = "imported_covers";

    /** 封面文件名最大长度（不含扩展名），防止超长名称超出文件系统 255 字节限制 */
    private static final int MAX_COVER_NAME_LENGTH = 80;

    private final Context context;
    private final YukiDatabaseHelper dbHelper;
    private final GameRepository gameRepository;

    public ImporterService(Context context) {
        this.context = context.getApplicationContext();
        this.dbHelper = new YukiDatabaseHelper(this.context);
        // 复用同一 GameRepository 实例，事务内调用 insert(game, db) 使用外部 db
        this.gameRepository = new GameRepository(this.context);
    }

    /**
     * 预览阶段：标记 games 列表中已存在同标题的条目，
     * 默认新游戏 selected=true，已存在的 selected=false。
     * 去重范围包括 hidden 游戏（getAll 不过滤 hidden 的版本）。
     */
    public void markExisting(List<ImportGameData> games) {
        Set<String> existingNames = loadExistingTitleKeys();
        for (ImportGameData g : games) {
            g.exists = existingNames.contains(titleKey(g.name));
            g.selected = !g.exists;
            g.conflictReason = g.exists ? "已存在" : null;
        }
    }

    /**
     * 执行导入：仅写入选中的且不存在的条目。
     * 在后台线程调用。
     */
    public ImportResult importSelected(List<ImportGameData> games) {
        ImportResult result = new ImportResult();
        Map<String, Game> existingByName = loadExistingByName();

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        try {
            db.beginTransaction();
            try {
                for (ImportGameData igd : games) {
                    if (!igd.selected || igd.exists) continue;
                    insertOne(db, igd, existingByName, result);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            // N2: 无论事务成功/异常/取消，临时目录都必须清理
            // 之前在 try-finally 之后，异常路径不可达，会累积封面文件
            try {
                gameRepository.recalculatePlayStats();
            } catch (Exception e) {
                Log.w(TAG, "recalculatePlayStats 失败", e);
            }
            ImporterIO.cleanupAllTempDirs();
        }
        return result;
    }

    /**
     * 取消导入：用户在预览阶段取消时调用，清理已注册的临时目录。
     * 避免"解析→取消"反复操作在 cacheDir 累积封面文件。
     */
    public static void cancelImport() {
        ImporterIO.cleanupAllTempDirs();
    }

    // ==================== 内部写库 ====================

    private void insertOne(SQLiteDatabase db, ImportGameData igd,
                           Map<String, Game> existingByName, ImportResult result) {
        if (igd.name == null || igd.name.trim().isEmpty()) {
            result.failed++;
            result.failedNames.add("(空名称)");
            return;
        }

        String nameKey = titleKey(igd.name);
        if (existingByName.containsKey(nameKey)) {
            result.skipped++;
            result.skippedNames.add(igd.name + " (已存在)");
            return;
        }

        Game game = new Game();
        game.title = igd.name.trim();
        game.originalTitle = (igd.originalName != null && !igd.originalName.isEmpty())
                ? igd.originalName : game.title;
        game.engine = EngineType.UNKNOWN;
        game.rootUri = "";
        game.description = igd.description != null ? igd.description : "";
        if (igd.tags != null && !igd.tags.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < igd.tags.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(igd.tags.get(i));
            }
            game.tags = sb.toString();
        }
        if (igd.playStatus != null && !igd.playStatus.isEmpty()) {
            game.playStatus = igd.playStatus;
        }
        game.createdAt = igd.createdAt > 0 ? igd.createdAt : System.currentTimeMillis();
        game.updatedAt = System.currentTimeMillis();

        applyCover(game, igd);

        // N4: 使用事务 db 写入 games 表，确保 rollback 时无残留行
        // 之前 new GameRepository(context).insert(game) 创建独立 Helper，
        // 实践中虽同路径缓存连接，但非 API 契约，未来行为变更可能破坏事务原子性
        long id = gameRepository.insert(game, db);
        game.id = id;
        existingByName.put(nameKey, game);

        int sessions = importSessions(db, id, igd);
        result.sessionsImported += sessions;

        // 同时有 totalPlayTime 和逐日记录时，逐日记录优先（更精确），
        // 但若 totalPlayTime > sessions 累计时长，差值仍需写入以避免时长丢失。
        if (igd.totalPlayTime > 0) {
            long totalMsFromSessions = sessions > 0 ? sumSessionsDuration(db, id) : 0L;
            long totalMsFromIgd = igd.totalPlayTime * 1000L;
            if (totalMsFromIgd > totalMsFromSessions) {
                db.execSQL("UPDATE games SET total_play_time = ? WHERE id = ?",
                        new Object[]{totalMsFromIgd, id});
            }
        }

        result.success++;
    }

    /** 累计某游戏所有 play_sessions 的 duration 之和（毫秒）。 */
    private long sumSessionsDuration(SQLiteDatabase db, long gameId) {
        Cursor c = db.rawQuery(
                "SELECT IFNULL(SUM(duration), 0) FROM play_sessions WHERE game_id=?",
                new String[]{String.valueOf(gameId)});
        try {
            if (c.moveToFirst()) return c.getLong(0);
            return 0L;
        } finally {
            c.close();
        }
    }

    private void applyCover(Game game, ImportGameData igd) {
        if (igd.coverUrl != null && !igd.coverUrl.isEmpty()
                && (igd.coverUrl.startsWith("http://") || igd.coverUrl.startsWith("https://"))) {
            game.coverUri = igd.coverUrl;
            game.coverSourceType = 1;
        } else if (igd.coverLocalPath != null && !igd.coverLocalPath.isEmpty()) {
            String saved = copyCoverToInternal(igd.coverLocalPath, game.title);
            if (saved != null) {
                game.coverPersistUri = saved;
                game.coverSourceType = 2;
            }
        }
    }

    private int importSessions(SQLiteDatabase db, long gameId, ImportGameData igd) {
        if (igd.playedTimeMap != null && !igd.playedTimeMap.isEmpty()) {
            return importPotatoVnPlaySessions(db, gameId, igd.playedTimeMap);
        }
        if (igd.vniteTimers != null && !igd.vniteTimers.isEmpty()) {
            return importVniteTimers(db, gameId, igd.vniteTimers);
        }
        if (igd.lunaBoxSessions != null && !igd.lunaBoxSessions.isEmpty()) {
            return importLunaBoxSessions(db, gameId, igd.lunaBoxSessions);
        }
        return 0;
    }

    private int importPotatoVnPlaySessions(SQLiteDatabase db, long gameId,
                                           Map<String, Integer> playedTime) {
        int count = 0;
        for (Map.Entry<String, Integer> entry : playedTime.entrySet()) {
            int minutes = entry.getValue();
            if (minutes <= 0) continue;
            long startTime = parsePotatoVnDate(entry.getKey());
            long durationMs = minutes * 60L * 1000L;
            long endTime = startTime + durationMs;
            if (insertSession(db, gameId, startTime, endTime, durationMs,
                    "imported_potatovn", "potatovn_import")) {
                count++;
            }
        }
        return count;
    }

    private int importVniteTimers(SQLiteDatabase db, long gameId,
                                  List<ImportGameData.VniteTimer> timers) {
        int count = 0;
        for (ImportGameData.VniteTimer timer : timers) {
            long startTime = parseIsoTime(timer.start);
            long endTime = parseIsoTime(timer.end);
            long durationMs = Math.max(0L, endTime - startTime);
            if (durationMs <= 0) continue;
            if (insertSession(db, gameId, startTime, endTime, durationMs,
                    "imported_vnite", "vnite_import")) {
                count++;
            }
        }
        return count;
    }

    private int importLunaBoxSessions(SQLiteDatabase db, long gameId,
                                      List<ImportGameData.LunaBoxSession> sessions) {
        int count = 0;
        for (ImportGameData.LunaBoxSession session : sessions) {
            long startTime = LunaBoxImporter.parseLunaBoxTimestamp(session.start);
            long endTime = LunaBoxImporter.parseLunaBoxTimestamp(session.end);
            // LunaBox duration 是秒，转毫秒
            long durationMs = session.durationSeconds > 0
                    ? session.durationSeconds * 1000L
                    : Math.max(0L, endTime - startTime);
            if (durationMs <= 0 && session.durationSeconds <= 0) continue;
            if (insertSession(db, gameId, startTime, endTime, durationMs,
                    "imported_lunabox", "lunabox_import")) {
                count++;
            }
        }
        return count;
    }

    private boolean insertSession(SQLiteDatabase db, long gameId, long startTime, long endTime,
                                  long durationMs, String launchType, String deviceId) {
        try {
            ContentValues v = new ContentValues();
            v.put("game_id", gameId);
            v.put("start_time", startTime);
            v.put("end_time", endTime);
            v.put("duration", durationMs);
            v.put("launch_type", launchType);
            v.put("session_uuid", UUID.randomUUID().toString());
            v.put("device_id", deviceId);
            v.put("created_at", startTime);
            v.put("updated_at", endTime);
            v.put("dirty", 1);
            v.put("deleted", 0);
            return db.insert("play_sessions", null, v) > 0;
        } catch (Exception e) {
            // 不再静默吞掉，便于排查数据库异常（如约束冲突、磁盘满）
            Log.w(TAG, "写入 play_session 失败: gameId=" + gameId
                    + ", start=" + startTime + ", duration=" + durationMs, e);
            return false;
        }
    }

    // ==================== 封面复制 ====================

    private String copyCoverToInternal(String sourcePath, String gameName) {
        File src = new File(sourcePath);
        if (!src.exists()) return null;

        File coverDir = new File(context.getFilesDir(), COVER_DIR);
        if (!coverDir.exists() && !coverDir.mkdirs()) return null;

        // 仅保留中文、字母数字、下划线、连字符，并截断到 MAX_COVER_NAME_LENGTH
        // 防止超长名称超出文件系统 255 字节限制
        String safeName = gameName.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fff_-]", "_");
        if (safeName.length() > MAX_COVER_NAME_LENGTH) {
            safeName = safeName.substring(0, MAX_COVER_NAME_LENGTH);
        }
        String ext = ".jpg";
        String lower = sourcePath.toLowerCase();
        if (lower.endsWith(".png")) ext = ".png";
        else if (lower.endsWith(".webp")) ext = ".webp";

        File dest = new File(coverDir, safeName + "_" + System.currentTimeMillis() + ext);

        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dest)) {
            byte[] tmp = new byte[8192];
            int len;
            while ((len = fis.read(tmp)) > 0) fos.write(tmp, 0, len);
            return dest.getAbsolutePath();
        } catch (Exception e) {
            Log.w(TAG, "复制封面失败: " + sourcePath, e);
            return null;
        }
    }

    // ==================== 已有游戏读取 ====================

    /**
     * 加载所有游戏（包括 hidden）的标题 key，用于去重。
     * 不使用 GameRepository.getAll() 因为它过滤了 hidden=0。
     */
    private Set<String> loadExistingTitleKeys() {
        Set<String> keys = new HashSet<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT title FROM games WHERE title IS NOT NULL", null);
        try {
            while (c.moveToNext()) {
                String title = c.getString(0);
                if (title != null) keys.add(titleKey(title));
            }
        } finally {
            c.close();
        }
        return keys;
    }

    private Map<String, Game> loadExistingByName() {
        Map<String, Game> map = new HashMap<>();
        for (Game g : gameRepository.getAll()) {
            if (g.title != null) map.put(titleKey(g.title), g);
        }
        // getAll() 过滤了 hidden，补齐 hidden 游戏以参与去重
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT id, title FROM games WHERE hidden=1 AND title IS NOT NULL", null);
        try {
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String title = c.getString(1);
                String key = titleKey(title);
                if (!map.containsKey(key)) {
                    Game g = new Game();
                    g.id = id;
                    g.title = title;
                    map.put(key, g);
                }
            }
        } finally {
            c.close();
        }
        return map;
    }

    private static String titleKey(String name) {
        return name == null ? "" : name.trim().toLowerCase();
    }

    // ==================== 时间解析 ====================

    /** PotatoVN 日期格式：2006/1/2 或 2006/01/02 */
    private static long parsePotatoVnDate(String dateStr) {
        for (String fmt : new String[]{"yyyy/M/d", "yyyy/MM/dd"}) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(fmt, Locale.US);
                Calendar cal = Calendar.getInstance();
                cal.setTime(sdf.parse(dateStr));
                cal.set(Calendar.HOUR_OF_DAY, 12);
                return cal.getTimeInMillis();
            } catch (Exception ignored) {
            }
        }
        Log.w(TAG, "无法解析 PotatoVN 日期，fallback 当前时间: " + dateStr);
        return System.currentTimeMillis();
    }

    /** 通用 ISO 时间解析，兼容多种格式 */
    static long parseIsoTime(String raw) {
        if (raw == null || raw.isEmpty()) return System.currentTimeMillis();
        String[] formats = {
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss"
        };
        for (String fmt : formats) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(fmt, Locale.US);
                if (fmt.contains("'Z'")) sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                Date d = sdf.parse(raw);
                if (d != null) return d.getTime();
            } catch (Exception ignored) {
            }
        }
        Log.w(TAG, "无法解析 ISO 时间，fallback 当前时间: " + raw);
        return System.currentTimeMillis();
    }
}
