package com.core.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID

/**
 * 游玩会话与游玩时长数据访问层（play_sessions 表）。
 * 从 GameRepository 按职责拆分（重构计划 3.5，§8:323 Repository 按职责切片），
 * 与 GameRepository 共享同一 [YukiDatabaseHelper] 实例以保证跨 games/play_sessions
 * 的多语句事务原子性（会话结算与累计时长均在同一数据库连接上执行）。
 */
class PlaySessionRepository(private val helper: YukiDatabaseHelper) {

    fun startPlaySession(gameId: Long, start: Long, launchType: String?): Long {
        val db = helper.writableDatabase
        val session = ContentValues()
        session.put("game_id", gameId)
        session.put("start_time", start)
        session.putNull("end_time")
        session.put("duration", 0L)
        session.put("launch_type", if (launchType == null) "external" else launchType)
        session.put("session_uuid", UUID.randomUUID().toString())
        session.put("device_id", "local")
        session.put("created_at", start)
        session.put("updated_at", start)
        session.put("dirty", 1)
        session.put("deleted", 0)
        return db.insert("play_sessions", null, session)
    }

    fun cancelPlaySession(sessionId: Long) {
        if (sessionId <= 0) return
        val db = helper.writableDatabase
        db.delete("play_sessions", "id=? AND (end_time IS NULL OR duration=0)", arrayOf(sessionId.toString()))
    }

    /**
     * 软删除指定的游玩会话记录。仅标记 deleted=1，不实际删除行，
     * 保证已同步到服务端的记录在后续导出/同步时仍可识别。
     *
     * @return 受影响行数；0 表示会话不存在或已删除。
     */
    fun deletePlaySession(sessionId: Long): Int {
        if (sessionId <= 0) return 0
        val db = helper.writableDatabase
        val values = ContentValues()
        values.put("deleted", 1)
        values.put("updated_at", System.currentTimeMillis())
        return db.update("play_sessions", values, "id=?", arrayOf(sessionId.toString()))
    }

    fun finishPlaySession(sessionId: Long, end: Long, minDuration: Long, maxDuration: Long) {
        if (sessionId <= 0) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            var gameId = 0L
            var start = 0L
            val c = db.rawQuery("SELECT game_id,start_time FROM play_sessions WHERE id=? AND end_time IS NULL LIMIT 1", arrayOf(sessionId.toString()))
            c.use {
                if (!it.moveToFirst()) return
                gameId = it.getLong(0)
                start = it.getLong(1)
            }
            val rawDuration = Math.max(0L, end - start)
            if (rawDuration < minDuration) {
                if (db.delete("play_sessions", "id=?", arrayOf(sessionId.toString())) != 1) {
                    throw IllegalStateException("删除无效游玩会话失败: $sessionId")
                }
                db.setTransactionSuccessful()
                return
            }
            val duration = Math.min(rawDuration, maxDuration)
            val values = ContentValues()
            values.put("end_time", end)
            values.put("duration", duration)
            values.put("updated_at", end)
            values.put("dirty", 1)
            if (db.update("play_sessions", values, "id=?", arrayOf(sessionId.toString())) != 1) {
                throw IllegalStateException("结算游玩会话失败: $sessionId")
            }
            // Keep the increment in SQL so a stale Game object cannot overwrite it.
            db.execSQL("UPDATE games SET total_play_time = total_play_time + ?, last_played_at = MAX(IFNULL(last_played_at,0), ?), updated_at = ? WHERE id = ?",
                arrayOf<Any>(duration, end, end, gameId))
            ensureSingleChangedRow(db, "结算游玩会话时找不到游戏: $gameId")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun finishUnfinishedPlaySessions(end: Long, minDuration: Long, maxDuration: Long) {
        finishUnfinishedPlaySessions(end, minDuration, maxDuration, -1L)
    }

    fun finishUnfinishedPlaySessions(end: Long, minDuration: Long, maxDuration: Long, exceptSessionId: Long) {
        val db = helper.writableDatabase
        val ids = ArrayList<Long>()
        val c = db.rawQuery("SELECT id FROM play_sessions WHERE end_time IS NULL ORDER BY start_time ASC", null)
        c.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                if (id != exceptSessionId) ids.add(id)
            }
        }
        for (id in ids) finishPlaySession(id, end, minDuration, maxDuration)
    }

    fun findLatestOpenPlaySession(): PlayActivity? {
        val db = helper.readableDatabase
        val c = db.rawQuery(
            "SELECT ps.id,ps.session_uuid,ps.game_id,g.title,ps.start_time,ps.end_time,ps.duration,ps.launch_type " +
                "FROM play_sessions ps JOIN games g ON g.id=ps.game_id " +
                "WHERE ps.end_time IS NULL AND IFNULL(ps.deleted,0)=0 " +
                "ORDER BY ps.start_time DESC LIMIT 1", null)
        return c.use {
            if (!it.moveToFirst()) return null
            val a = PlayActivity()
            a.sessionId = it.getLong(0)
            a.sessionUuid = it.getString(1)
            a.gameId = it.getLong(2)
            a.gameTitle = it.getString(3)
            a.startTime = it.getLong(4)
            a.endTime = 0L
            a.duration = it.getLong(6)
            a.launchType = it.getString(7)
            val title = a.gameTitle
            if (title == null || title.trim().isEmpty()) {
                a.gameTitle = "未命名游戏"
            }
            a
        }
    }

    fun deleteOpenPlaySessions(): Int {
        val db = helper.writableDatabase
        return db.delete("play_sessions", "end_time IS NULL", null)
    }

    fun deleteOpenPlaySession(sessionId: Long): Int {
        if (sessionId <= 0) return 0
        val db = helper.writableDatabase
        return db.delete("play_sessions", "id=? AND end_time IS NULL", arrayOf(sessionId.toString()))
    }

    fun addPlayTime(gameId: Long, start: Long, end: Long, duration: Long) {
        var dur = duration
        if (dur <= 0) dur = Math.max(0L, end - start)
        addManualPlayTime(gameId, dur, if (end <= 0) System.currentTimeMillis() else end)
    }

    fun addManualPlayTime(gameId: Long, duration: Long) {
        addManualPlayTime(gameId, duration, System.currentTimeMillis())
    }

    fun addManualPlayTime(gameId: Long, duration: Long, end: Long) {
        if (gameId <= 0 || duration <= 0) return
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        val safeEnd = if (end <= 0) now else end
        val start = Math.max(0L, safeEnd - duration)
        val session = ContentValues()
        session.put("game_id", gameId)
        session.put("start_time", start)
        session.put("end_time", safeEnd)
        session.put("duration", duration)
        session.put("launch_type", "manual")
        session.put("session_uuid", UUID.randomUUID().toString())
        session.put("device_id", "local")
        session.put("created_at", now)
        session.put("updated_at", now)
        session.put("dirty", 1)
        session.put("deleted", 0)
        db.beginTransaction()
        try {
            if (db.insert("play_sessions", null, session) <= 0) {
                throw IllegalStateException("写入手动游玩记录失败: $gameId")
            }
            db.execSQL("UPDATE games SET total_play_time = total_play_time + ?, last_played_at = MAX(IFNULL(last_played_at,0), ?), updated_at = ? WHERE id = ?",
                arrayOf<Any>(duration, safeEnd, now, gameId))
            ensureSingleChangedRow(db, "累计手动游玩时长时找不到游戏: $gameId")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun setManualPlayTimeForGame(gameId: Long, totalDuration: Long) {
        if (gameId <= 0) return
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        val safeDuration = Math.max(0L, totalDuration)
        db.beginTransaction()
        try {
            db.delete("play_sessions", "game_id=?", arrayOf(gameId.toString()))
            var lastPlayed = 0L
            if (safeDuration > 0) {
                lastPlayed = now
                val start = Math.max(0L, now - safeDuration)
                val session = ContentValues()
                session.put("game_id", gameId)
                session.put("start_time", start)
                session.put("end_time", now)
                session.put("duration", safeDuration)
                session.put("launch_type", "manual")
                session.put("session_uuid", UUID.randomUUID().toString())
                session.put("device_id", "local")
                session.put("created_at", now)
                session.put("updated_at", now)
                session.put("dirty", 1)
                session.put("deleted", 0)
                if (db.insert("play_sessions", null, session) <= 0) {
                    throw IllegalStateException("写入手动总时长记录失败: $gameId")
                }
            }
            val v = ContentValues()
            v.put("total_play_time", safeDuration)
            v.put("last_played_at", lastPlayed)
            v.put("playtime_reset_at", now)
            v.put("updated_at", now)
            if (db.update("games", v, "id=?", arrayOf(gameId.toString())) != 1) {
                throw IllegalStateException("设置手动总时长时找不到游戏: $gameId")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    data class PlayActivity(
        var sessionId: Long = 0L,
        var sessionUuid: String? = null,
        var gameId: Long = 0L,
        var gameTitle: String? = null,
        var startTime: Long = 0L,
        var endTime: Long = 0L,
        var duration: Long = 0L,
        var launchType: String? = null,
        var playStatus: String? = null
    )

    fun getPlayDurationsBetween(startInclusive: Long, endExclusive: Long): Map<String, Long> {
        val result = LinkedHashMap<String, Long>()
        val db = helper.readableDatabase
        val c = db.rawQuery(
            "SELECT g.title, SUM(ps.duration) FROM play_sessions ps " +
                "JOIN games g ON g.id=ps.game_id " +
                "WHERE ps.end_time IS NOT NULL AND ps.end_time>=? AND ps.end_time<? AND IFNULL(ps.deleted,0)=0 " +
                "GROUP BY ps.game_id ORDER BY MAX(ps.end_time) DESC",
            arrayOf(startInclusive.toString(), endExclusive.toString()))
        c.use {
            while (it.moveToNext()) {
                val title = it.getString(0)
                val duration = it.getLong(1)
                result.put(if (title == null || title.trim().isEmpty()) "未命名游戏" else title, duration)
            }
        }
        return result
    }

    fun getRecentPlayActivities(limit: Int): List<PlayActivity> {
        val list = ArrayList<PlayActivity>()
        val db = helper.readableDatabase
        val c = db.rawQuery(
            "SELECT ps.id,ps.session_uuid,ps.game_id,g.title,ps.start_time,ps.end_time,ps.duration,ps.launch_type,g.play_status " +
                "FROM play_sessions ps JOIN games g ON g.id=ps.game_id " +
                "WHERE ps.end_time IS NOT NULL AND IFNULL(ps.deleted,0)=0 " +
                "ORDER BY ps.end_time DESC LIMIT ?",
            arrayOf(Math.max(1, limit).toString()))
        c.use {
            while (it.moveToNext()) {
                val a = PlayActivity()
                a.sessionId = it.getLong(0)
                a.sessionUuid = it.getString(1)
                a.gameId = it.getLong(2)
                a.gameTitle = it.getString(3)
                a.startTime = it.getLong(4)
                a.endTime = it.getLong(5)
                a.duration = it.getLong(6)
                a.launchType = it.getString(7)
                a.playStatus = normalizePlayStatus(it.getString(8))
                val title = a.gameTitle
                if (title == null || title.trim().isEmpty()) {
                    a.gameTitle = "未命名游戏"
                }
                list.add(a)
            }
        }
        return list
    }

    fun getPlayActivitiesBetween(startInclusive: Long, endExclusive: Long, limit: Int): List<PlayActivity> {
        val list = ArrayList<PlayActivity>()
        val db = helper.readableDatabase
        val c = db.rawQuery(
            "SELECT ps.id,ps.session_uuid,ps.game_id,g.title,ps.start_time,ps.end_time,ps.duration,ps.launch_type,g.play_status " +
                "FROM play_sessions ps JOIN games g ON g.id=ps.game_id " +
                "WHERE ps.end_time IS NOT NULL AND ps.end_time>=? AND ps.end_time<? AND IFNULL(ps.deleted,0)=0 " +
                "ORDER BY ps.end_time DESC LIMIT ?",
            arrayOf(startInclusive.toString(), endExclusive.toString(), Math.max(1, limit).toString()))
        c.use {
            while (it.moveToNext()) {
                val a = PlayActivity()
                a.sessionId = it.getLong(0)
                a.sessionUuid = it.getString(1)
                a.gameId = it.getLong(2)
                a.gameTitle = it.getString(3)
                a.startTime = it.getLong(4)
                a.endTime = it.getLong(5)
                a.duration = it.getLong(6)
                a.launchType = it.getString(7)
                a.playStatus = normalizePlayStatus(it.getString(8))
                val title = a.gameTitle
                if (title == null || title.trim().isEmpty()) {
                    a.gameTitle = "未命名游戏"
                }
                list.add(a)
            }
        }
        return list
    }

    private fun ensureSingleChangedRow(db: SQLiteDatabase, message: String) {
        val changed = db.rawQuery("SELECT changes()", null)
        changed.use {
            if (!it.moveToFirst() || it.getInt(0) != 1) throw IllegalStateException(message)
        }
    }
}

/** 游玩状态归一化：GameRepository CRUD/导入导出与 PlaySessionRepository 共用单源（原 GameRepository 私有方法迁移）。 */
internal fun normalizePlayStatus(status: String?): String {
    if (status == null) return "unplayed"
    // 注意：迁移至 Kotlin 时由 toLowerCase()（默认 Locale）改为 lowercase(Locale.ROOT)，
    // 修正土耳其 Locale 下 "I" → "ı" 的归一化缺陷（行为变化，等同 bug 修复）。
    val s = status.trim().lowercase(Locale.ROOT)
    if (s == "completed" || s == "played" || s == "done") return "completed"
    if (s == "playing" || s == "current") return "playing"
    return "unplayed"
}
