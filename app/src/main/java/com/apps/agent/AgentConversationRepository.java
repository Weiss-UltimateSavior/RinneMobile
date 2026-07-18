package com.apps.agent;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Separate local database: agent history is not included in account or WebDAV sync. */
public final class AgentConversationRepository {
    private static final String THREAD = "default";
    private static final int MAX_MESSAGE_CHARS = 128 * 1024;
    private static final int MAX_STORED_MESSAGES = 500;
    private final Helper helper;

    public AgentConversationRepository(Context context) {
        helper = new Helper(context.getApplicationContext());
    }

    public static final class Message {
        public final long id;
        public final String role;
        public String content;
        public String name;
        public final long createdAt;

        public Message(long id, String role, String content, String name, long createdAt) {
            this.id = id;
            this.role = role == null ? "" : role;
            this.content = content == null ? "" : content;
            this.name = name == null ? "" : name;
            this.createdAt = createdAt;
        }
    }

    public long add(String role, String content, String name) {
        ContentValues values = new ContentValues();
        values.put("thread_id", THREAD);
        values.put("role", role == null ? "" : role);
        String safeContent = content == null ? "" : content;
        if (safeContent.length() > MAX_MESSAGE_CHARS) safeContent = safeContent.substring(0, MAX_MESSAGE_CHARS) + "…";
        values.put("content", safeContent);
        values.put("name", name == null ? "" : name);
        values.put("created_at", System.currentTimeMillis());
        SQLiteDatabase db = helper.getWritableDatabase();
        long id = db.insertOrThrow("agent_messages", null, values);
        db.execSQL("DELETE FROM agent_messages WHERE thread_id=? AND id NOT IN " +
                        "(SELECT id FROM agent_messages WHERE thread_id=? ORDER BY id DESC LIMIT ?)",
                new Object[]{THREAD, THREAD, MAX_STORED_MESSAGES});
        return id;
    }

    public List<Message> recent(int limit) {
        return recentInternal(limit, false);
    }

    public List<Message> recentConversation(int limit) {
        return recentInternal(limit, true);
    }

    private List<Message> recentInternal(int limit, boolean conversationOnly) {
        int safeLimit = Math.max(1, Math.min(200, limit));
        List<Message> result = new ArrayList<>();
        Cursor cursor = helper.getReadableDatabase().query(
                "agent_messages", new String[]{"id", "role", "content", "name", "created_at"},
                conversationOnly ? "thread_id=? AND role IN ('user','assistant')" : "thread_id=?",
                new String[]{THREAD}, null, null, "id DESC", String.valueOf(safeLimit));
        try {
            while (cursor.moveToNext()) {
                String role = cursor.getString(1);
                String content = sanitizeLegacyNullPrefix(role, cursor.getString(2));
                result.add(new Message(cursor.getLong(0), role, content,
                        cursor.getString(3), cursor.getLong(4)));
            }
        } finally {
            cursor.close();
        }
        Collections.reverse(result);
        return result;
    }

    static String sanitizeLegacyNullPrefix(String role, String content) {
        String value = content == null ? "" : content;
        if (!"assistant".equals(role)) return value;
        int offset = 0;
        int count = 0;
        while (value.startsWith("null", offset)) { offset += 4; count++; }
        return count >= 2 ? value.substring(offset) : value;
    }

    public void delete(long id) {
        if (id > 0) helper.getWritableDatabase().delete("agent_messages", "id=?", new String[]{String.valueOf(id)});
    }

    public void clear() {
        helper.getWritableDatabase().delete("agent_messages", "thread_id=?", new String[]{THREAD});
    }

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context context) { super(context, "rinne_local_agent.db", null, 1); }

        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE agent_messages (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "thread_id TEXT NOT NULL," +
                    "role TEXT NOT NULL," +
                    "content TEXT NOT NULL," +
                    "name TEXT NOT NULL DEFAULT ''," +
                    "created_at INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX idx_agent_messages_thread ON agent_messages(thread_id,id)");
        }

        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }
    }
}
