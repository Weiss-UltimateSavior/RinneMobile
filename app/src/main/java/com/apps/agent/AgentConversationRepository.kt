package com.apps.agent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Separate local database: agent history is not included in account or WebDAV sync. */
class AgentConversationRepository(context: Context) {
    private val helper = Helper(context.applicationContext)

    class Message constructor(
        id: Long,
        role: String?,
        content: String?,
        name: String?,
        createdAt: Long
    ) {
        @JvmField val id: Long = id
        @JvmField val role: String = role ?: ""
        @JvmField var content: String = content ?: ""
        @JvmField var name: String = name ?: ""
        @JvmField val createdAt: Long = createdAt
    }

    fun add(role: String?, content: String?, name: String?): Long {
        val values = ContentValues()
        values.put("thread_id", THREAD)
        values.put("role", role ?: "")
        var safeContent = content ?: ""
        if (safeContent.length > MAX_MESSAGE_CHARS) safeContent = safeContent.substring(0, MAX_MESSAGE_CHARS) + "…"
        values.put("content", safeContent)
        values.put("name", name ?: "")
        values.put("created_at", System.currentTimeMillis())
        val db = helper.writableDatabase
        val id = db.insertOrThrow("agent_messages", null, values)
        db.execSQL(
            "DELETE FROM agent_messages WHERE thread_id=? AND id NOT IN " +
                    "(SELECT id FROM agent_messages WHERE thread_id=? ORDER BY id DESC LIMIT ?)",
            arrayOf(THREAD, THREAD, MAX_STORED_MESSAGES)
        )
        return id
    }

    fun recent(limit: Int): List<Message> = recentInternal(limit, false)

    fun recentConversation(limit: Int): List<Message> = recentInternal(limit, true)

    private fun recentInternal(limit: Int, conversationOnly: Boolean): List<Message> {
        val safeLimit = maxOf(1, minOf(200, limit))
        val result = ArrayList<Message>()
        val selection = if (conversationOnly) "thread_id=? AND role IN ('user','assistant')" else "thread_id=?"
        val cursor = helper.readableDatabase.query(
            "agent_messages", arrayOf("id", "role", "content", "name", "created_at"),
            selection, arrayOf(THREAD), null, null, "id DESC", safeLimit.toString()
        )
        cursor.use {
            while (it.moveToNext()) {
                val role = it.getString(1)
                val content = sanitizeLegacyNullPrefix(role, it.getString(2))
                result.add(Message(it.getLong(0), role, content, it.getString(3), it.getLong(4)))
            }
        }
        result.reverse()
        return result
    }

    fun delete(id: Long) {
        if (id > 0) helper.writableDatabase.delete("agent_messages", "id=?", arrayOf(id.toString()))
    }

    fun clear() {
        helper.writableDatabase.delete("agent_messages", "thread_id=?", arrayOf(THREAD))
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, "rinne_local_agent.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE agent_messages (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "thread_id TEXT NOT NULL," +
                        "role TEXT NOT NULL," +
                        "content TEXT NOT NULL," +
                        "name TEXT NOT NULL DEFAULT ''," +
                        "created_at INTEGER NOT NULL)"
            )
            db.execSQL("CREATE INDEX idx_agent_messages_thread ON agent_messages(thread_id,id)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    companion object {
        private const val THREAD = "default"
        private const val MAX_MESSAGE_CHARS = 128 * 1024
        private const val MAX_STORED_MESSAGES = 500

        @JvmStatic
        fun sanitizeLegacyNullPrefix(role: String?, content: String?): String {
            val value = content ?: ""
            if (role != "assistant") return value
            var offset = 0
            var count = 0
            while (value.startsWith("null", offset)) { offset += 4; count++ }
            return if (count >= 2) value.substring(offset) else value
        }
    }
}
