package com.yuki.yukihub.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.HashMap;
import java.util.Map;

public class YukiDatabaseHelper extends SQLiteOpenHelper {
    public static final String DB_NAME = "yukihub.db";
    // Keep this monotonic for the production package.  The launcher now uses the
    // same applicationId as existing installs; lowering this value makes SQLite
    // reject those installs before the launcher can load any data.
    public static final int DB_VERSION = 14;

    public YukiDatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        // Foreign-key declarations are otherwise only documentation in SQLite.  This
        // must run before every create/upgrade/open, not only when the schema changes.
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE games (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "title TEXT NOT NULL," +
                "original_title TEXT," +
                "engine TEXT NOT NULL," +
                "root_uri TEXT NOT NULL," +
                "root_uri_key TEXT NOT NULL DEFAULT ''," +
                "cover_uri TEXT," +
                "cover_persist_uri TEXT," +
                "cover_source_type INTEGER DEFAULT 0," +
                "emulator_package TEXT," +
                "launch_target TEXT DEFAULT 'data.xp3'," +
"winlator_launch_mode TEXT DEFAULT 'game'," +
"description TEXT," +
                "tags TEXT," +
                "gamehub_local_game_id TEXT," +
                "gamehub_launch_mode TEXT DEFAULT 'game'," +
                "gaishi_local_game_id TEXT," +
                "play_status TEXT DEFAULT 'unplayed'," +
                "total_play_time INTEGER DEFAULT 0," +
                "last_played_at INTEGER DEFAULT 0," +
                "playtime_reset_at INTEGER DEFAULT 0," +
                "created_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL," +
                "hidden INTEGER DEFAULT 0," +
                "favorite INTEGER DEFAULT 0" +
                ")");
        db.execSQL("CREATE TABLE play_sessions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "game_id INTEGER NOT NULL," +
                "start_time INTEGER NOT NULL," +
                "end_time INTEGER," +
                "duration INTEGER DEFAULT 0," +
                "launch_type TEXT," +
                "session_uuid TEXT," +
                "device_id TEXT," +
                "created_at INTEGER DEFAULT 0," +
                "updated_at INTEGER DEFAULT 0," +
                "dirty INTEGER DEFAULT 1," +
                "deleted INTEGER DEFAULT 0," +
                "FOREIGN KEY(game_id) REFERENCES games(id) ON DELETE CASCADE" +
                ")");
        db.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT)");
        createMetadataCacheTable(db);
        try { db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_play_sessions_uuid ON play_sessions(session_uuid)"); } catch (Exception ignored) { }
        createRootUriKeyIndex(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            safeAlter(db, "ALTER TABLE games ADD COLUMN cover_persist_uri TEXT");
            safeAlter(db, "ALTER TABLE games ADD COLUMN cover_source_type INTEGER DEFAULT 0");
            safeAlter(db, "ALTER TABLE games ADD COLUMN launch_target TEXT DEFAULT 'data.xp3'");
        }
        if (oldVersion < 3) {
            createMetadataCacheTable(db);
        }
        if (oldVersion < 4) {
            upgradePlaySessionsForSync(db);
        }
        if (oldVersion < 5) {
safeAlter(db, "ALTER TABLE games ADD COLUMN play_status TEXT DEFAULT 'unplayed'");
}
if (oldVersion < 6) {
safeAlter(db, "ALTER TABLE games ADD COLUMN winlator_launch_mode TEXT DEFAULT 'game'");
}
if (oldVersion < 7) {
safeAlter(db, "ALTER TABLE games ADD COLUMN playtime_reset_at INTEGER DEFAULT 0");
}
if (oldVersion < 8) {
safeAlter(db, "ALTER TABLE games ADD COLUMN gaishi_local_game_id TEXT");
}
        if (oldVersion < 9) {
            safeAlter(db, "ALTER TABLE games ADD COLUMN gamehub_local_game_id TEXT");
            try { db.execSQL("UPDATE games SET gamehub_local_game_id=gaishi_local_game_id WHERE (gamehub_local_game_id IS NULL OR gamehub_local_game_id='') AND gaishi_local_game_id IS NOT NULL"); } catch (Exception ignored) { }
        }
        if (oldVersion < 10) {
            safeAlter(db, "ALTER TABLE games ADD COLUMN gamehub_launch_mode TEXT DEFAULT 'game'");
        }
        if (oldVersion < 11) {
            safeAlter(db, "ALTER TABLE games ADD COLUMN favorite INTEGER DEFAULT 0");
        }
        if (oldVersion < 12) {
            upgradeMetadataCachePrimaryKey(db);
        }
        if (oldVersion < 13) {
            upgradeRootUriKeysAndIntegrity(db);
        }
    }

    private void createMetadataCacheTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS metadata_cache (" +
                "game_id INTEGER NOT NULL," +
                "source TEXT NOT NULL," +
                "source_id TEXT," +
                "json TEXT NOT NULL," +
                "updated_at INTEGER NOT NULL," +
                "PRIMARY KEY(game_id, source)" +
                ")");
    }

    private void upgradeMetadataCachePrimaryKey(SQLiteDatabase db) {
        try {
            db.beginTransaction();
            db.execSQL("CREATE TABLE IF NOT EXISTS metadata_cache_new (" +
                    "game_id INTEGER NOT NULL," +
                    "source TEXT NOT NULL," +
                    "source_id TEXT," +
                    "json TEXT NOT NULL," +
                    "updated_at INTEGER NOT NULL," +
                    "PRIMARY KEY(game_id, source)" +
                    ")");
            db.execSQL("INSERT OR REPLACE INTO metadata_cache_new(game_id,source,source_id,json,updated_at) " +
                    "SELECT game_id,source,source_id,json,updated_at FROM metadata_cache");
            db.execSQL("DROP TABLE IF EXISTS metadata_cache");
            db.execSQL("ALTER TABLE metadata_cache_new RENAME TO metadata_cache");
            db.setTransactionSuccessful();
        } catch (Exception ignored) {
            try { createMetadataCacheTable(db); } catch (Exception ignored2) { }
        } finally {
            try { db.endTransaction(); } catch (Exception ignored) { }
        }
    }

    private void upgradePlaySessionsForSync(SQLiteDatabase db) {
        safeAlter(db, "ALTER TABLE play_sessions ADD COLUMN session_uuid TEXT");
        safeAlter(db, "ALTER TABLE play_sessions ADD COLUMN device_id TEXT");
        safeAlter(db, "ALTER TABLE play_sessions ADD COLUMN created_at INTEGER DEFAULT 0");
        safeAlter(db, "ALTER TABLE play_sessions ADD COLUMN updated_at INTEGER DEFAULT 0");
        safeAlter(db, "ALTER TABLE play_sessions ADD COLUMN dirty INTEGER DEFAULT 1");
        safeAlter(db, "ALTER TABLE play_sessions ADD COLUMN deleted INTEGER DEFAULT 0");
        try { db.execSQL("UPDATE play_sessions SET session_uuid=lower(hex(randomblob(16))) WHERE session_uuid IS NULL OR session_uuid='' "); } catch (Exception ignored) { }
        try { db.execSQL("UPDATE play_sessions SET created_at=start_time WHERE created_at IS NULL OR created_at=0"); } catch (Exception ignored) { }
        try { db.execSQL("UPDATE play_sessions SET updated_at=COALESCE(end_time,start_time) WHERE updated_at IS NULL OR updated_at=0"); } catch (Exception ignored) { }
        try { db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_play_sessions_uuid ON play_sessions(session_uuid)"); } catch (Exception ignored) { }
    }

    /**
     * Gives URI identity a database-level representation.  Older releases compared
     * normalized URI strings in Java, which left a check-then-insert race and could
     * not protect imports from duplicate cards.
     */
    private void upgradeRootUriKeysAndIntegrity(SQLiteDatabase db) {
        safeAlter(db, "ALTER TABLE games ADD COLUMN root_uri_key TEXT NOT NULL DEFAULT ''");
        db.beginTransaction();
        try {
            // Foreign keys were not enabled in previous app versions, so repair old
            // orphan rows before the new enforcement becomes observable.
            db.execSQL("DELETE FROM play_sessions WHERE NOT EXISTS (SELECT 1 FROM games WHERE games.id=play_sessions.game_id)");

            Map<String, Long> canonicalByKey = new HashMap<>();
            android.database.Cursor cursor = db.rawQuery(
                    "SELECT id,root_uri,total_play_time,last_played_at,created_at,updated_at,hidden,favorite " +
                            "FROM games ORDER BY updated_at DESC,id DESC", null);
            try {
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(0);
                    String key = GameRepository.normalizeRootUriKey(cursor.getString(1));
                    db.execSQL("UPDATE games SET root_uri_key=? WHERE id=?", new Object[]{key, id});
                    if (key.isEmpty()) continue;
                    Long canonicalId = canonicalByKey.get(key);
                    if (canonicalId == null) {
                        canonicalByKey.put(key, id);
                    } else {
                        mergeDuplicateGame(db, canonicalId, id);
                    }
                }
            } finally {
                cursor.close();
            }
            createRootUriKeyIndex(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Moves dependent data before deleting an historical duplicate URI row. */
    private void mergeDuplicateGame(SQLiteDatabase db, long canonicalId, long duplicateId) {
        db.execSQL("INSERT OR REPLACE INTO metadata_cache(game_id,source,source_id,json,updated_at) " +
                "SELECT ?,d.source,d.source_id,d.json,d.updated_at FROM metadata_cache d " +
                "WHERE d.game_id=? AND NOT EXISTS (SELECT 1 FROM metadata_cache c " +
                "WHERE c.game_id=? AND c.source=d.source AND c.updated_at>=d.updated_at)",
                new Object[]{canonicalId, duplicateId, canonicalId});
        db.delete("metadata_cache", "game_id=?", new String[]{String.valueOf(duplicateId)});
        db.execSQL("UPDATE play_sessions SET game_id=? WHERE game_id=?", new Object[]{canonicalId, duplicateId});
        db.execSQL("UPDATE games SET total_play_time=IFNULL(total_play_time,0)+(SELECT IFNULL(total_play_time,0) FROM games WHERE id=?), " +
                        "last_played_at=MAX(IFNULL(last_played_at,0),(SELECT IFNULL(last_played_at,0) FROM games WHERE id=?)), " +
                        "created_at=MIN(IFNULL(created_at,0),(SELECT IFNULL(created_at,0) FROM games WHERE id=?)), " +
                        "updated_at=MAX(IFNULL(updated_at,0),(SELECT IFNULL(updated_at,0) FROM games WHERE id=?)), " +
                        "hidden=MIN(IFNULL(hidden,0),(SELECT IFNULL(hidden,0) FROM games WHERE id=?)), " +
                        "favorite=MAX(IFNULL(favorite,0),(SELECT IFNULL(favorite,0) FROM games WHERE id=?)) WHERE id=?",
                new Object[]{duplicateId, duplicateId, duplicateId, duplicateId, duplicateId, duplicateId, canonicalId});
        db.delete("games", "id=?", new String[]{String.valueOf(duplicateId)});
    }

    private void createRootUriKeyIndex(SQLiteDatabase db) {
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_games_root_uri_key ON games(root_uri_key) WHERE root_uri_key != ''");
    }

    private void safeAlter(SQLiteDatabase db, String sql) {
        try { db.execSQL(sql); } catch (Exception ignored) { }
    }
}
