package com.yuki.yukihub.launcherbridge

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.yuki.yukihub.data.GameRepository
import com.yuki.yukihub.data.YukiDatabaseHelper
import com.yuki.yukihub.model.Game
import java.util.Locale

/**
 * 数据仓库桥接：供 com.apps 访问 GameRepository，无需直接导入
 * com.yuki.yukihub.data.GameRepository，保持 com.apps 与核心数据层解耦。
 *
 * 注意：Game 和 EngineType 模型类作为纯数据持有者仍在各包间共享。
 */
object LauncherRepositoryBridge {

    /** 返回所有非隐藏游戏，按最近游玩时间降序排列。 */
    @JvmStatic
    fun getAllGames(context: Context?): List<Game> {
        if (context == null) return emptyList()
        return GameRepository(context.applicationContext).getAll()
    }

    /** 按主键查找游戏，未找到返回 null。 */
    @JvmStatic
    fun findGameById(context: Context?, id: Long): Game? {
        if (context == null || id <= 0) return null
        return GameRepository(context.applicationContext).findById(id)
    }

    /** 若不存在相同 rootUri 的记录则插入游戏。返回新 id 或 -1。 */
    @JvmStatic
    fun insertGameIfNotExists(context: Context?, game: Game?): Long {
        if (context == null || game == null) return -1L
        return GameRepository(context.applicationContext).insertIfNotExists(game)
    }

    /** 更新已有游戏行。返回受影响行数。 */
    @JvmStatic
    fun updateGame(context: Context?, game: Game?): Int {
        if (context == null || game == null) return 0
        return GameRepository(context.applicationContext).update(game)
    }

    /** 按 id 删除游戏。返回删除行数。 */
    @JvmStatic
    fun deleteGame(context: Context?, id: Long): Int {
        if (context == null || id <= 0) return 0
        val app = context.applicationContext
        val deleted = GameRepository(app).delete(id)
        if (deleted > 0) LauncherOnsGameSettingsBridge.clearOverride(app, id)
        return deleted
    }

    /**
     * 返回游戏标题 -> 总游玩时长的映射，
     * 统计 end_time 落在 [startInclusive, endExclusive) 区间内的会话。
     */
    @JvmStatic
    fun getPlayDurationsBetween(context: Context?, startInclusive: Long, endExclusive: Long): Map<String, Long> {
        if (context == null) return emptyMap()
        return GameRepository(context.applicationContext)
            .getPlayDurationsBetween(startInclusive, endExclusive)
    }

    /**
     * 用给定时长替换某游戏的总游玩时间。清除该游戏所有已有 play_sessions，
     * 当 duration > 0 时插入一条合成手动会话。
     */
    @JvmStatic
    fun setManualPlayTimeForGame(context: Context?, gameId: Long, totalDurationMs: Long) {
        if (context == null || gameId <= 0) return
        GameRepository(context.applicationContext)
            .setManualPlayTimeForGame(gameId, totalDurationMs)
    }

    /**
     * 返回最近完成的游玩会话，按时间降序。
     * 使用 [RecentActivity] 字段进行展示，而非 GameRepository.PlayActivity。
     */
    @JvmStatic
    fun getRecentPlayActivities(context: Context?, limit: Int): List<RecentActivity> {
        if (context == null) return emptyList()
        val source = GameRepository(context.applicationContext)
            .getRecentPlayActivities(limit)
        if (source.isNullOrEmpty()) return emptyList()
        return source.mapNotNull { a ->
            if (a == null) null
            else RecentActivity(
                a.sessionId, a.sessionUuid ?: "", a.gameId, a.gameTitle ?: "",
                a.startTime, a.endTime, a.duration, a.launchType ?: "", a.playStatus ?: ""
            )
        }
    }

    /**
     * 软删除指定的游玩会话记录（标记 deleted=1）。
     * 供主页动态列表长按删除使用。
     *
     * @return 受影响行数；0 表示会话不存在或已删除。
     */
    @JvmStatic
    fun deletePlaySession(context: Context?, sessionId: Long): Int {
        if (context == null || sessionId <= 0) return 0
        return GameRepository(context.applicationContext).deletePlaySession(sessionId)
    }

    /**
     * 等价于 GameRepository.PlayActivity 的值类，
     * 使 com.apps 无需导入核心数据层。
     */
    class RecentActivity(
        @JvmField val sessionId: Long,
        @JvmField val sessionUuid: String,
        @JvmField val gameId: Long,
        @JvmField val gameTitle: String,
        @JvmField val startTime: Long,
        @JvmField val endTime: Long,
        @JvmField val duration: Long,
        @JvmField val launchType: String,
        @JvmField val playStatus: String
    )

    // ══════════════════════════════════════════════════
    //  游玩记录 SQL 导出/导入
    // ══════════════════════════════════════════════════

    /**
     * 导出游玩记录为 SQL INSERT 语句字符串。涵盖 games、play_sessions、metadata_cache、settings 四张表。
     * 输出格式兼容旧版 LauncherUserData，可在 [importPlaySql] 中完整恢复。
     */
    @JvmStatic
    fun exportPlaySql(context: Context?): String {
        if (context == null) return ""
        val sb = StringBuilder()
        sb.append("-- Launcher Play Data Export\n")
        sb.append("-- Export time: ").append(System.currentTimeMillis()).append("\n\n")

        val helper = YukiDatabaseHelper(context.applicationContext)
        val db: SQLiteDatabase = helper.readableDatabase

        try {
            sb.append("-- games\n")
            db.query("games", null, null, null, null, null, "id ASC").use { gc ->
                sb.append(tableToInsertSql(gc, "games"))
            }

            sb.append("\n-- play_sessions\n")
            db.query("play_sessions", null, null, null, null, null, "id ASC").use { pc ->
                sb.append(tableToInsertSql(pc, "play_sessions"))
            }

            sb.append("\n-- metadata_cache\n")
            db.query("metadata_cache", null, null, null, null, null, "game_id ASC, source ASC").use { mc ->
                sb.append(tableToInsertSql(mc, "metadata_cache"))
            }

            sb.append("\n-- settings\n")
            db.query("settings", null, null, null, null, null, "\"key\" ASC").use { sc ->
                sb.append(tableToInsertSql(sc, "settings"))
            }
        } finally {
            helper.close()
        }

        return sb.toString()
    }

    /**
     * 导入旧版游玩记录 SQL。旧格式携带数据库本地主键，只能作为完整快照恢复：先校验全部语句，
     * 再在同一事务中清空相关表并导入；任一语句失败都会整体回滚。
     *
     * @return 完整恢复成功返回 true；校验或执行失败返回 false，数据库保持恢复前状态
     */
    @JvmStatic
    fun importPlaySql(context: Context?, sql: String?): Boolean {
        if (context == null || sql.isNullOrBlank()) return false

        val helper = YukiDatabaseHelper(context.applicationContext)
        val db: SQLiteDatabase = helper.writableDatabase
        var transactionStarted = false
        var imported = false

        try {
            val statements = splitSqlStatements(sql)
            val validated = ArrayList<String>()
            for (stmt in statements) {
                val trimmed = removeLeadingSqlComments(stmt).trim()
                if (trimmed.isEmpty()) continue
                if (!isAllowedLegacyInsert(trimmed)) return false
                validated.add(trimmed)
            }
            if (validated.isEmpty()) return false

            db.beginTransaction()
            transactionStarted = true
            db.delete("play_sessions", null, null)
            db.delete("metadata_cache", null, null)
            db.delete("settings", null, null)
            db.delete("games", null, null)
            for (stmt in validated) db.execSQL(stmt)
            db.setTransactionSuccessful()
            imported = true
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                if (transactionStarted) db.endTransaction()
            } catch (_: Exception) {
                imported = false
            }
            helper.close()
        }
        if (imported) {
            // SQL 快照携带本地主键，但不携带独立 prefs；旧覆盖不能安全映射到新 games 表。
            LauncherOnsGameSettingsBridge.clearAllOverrides(context.applicationContext)
        }
        return imported
    }

    private fun tableToInsertSql(cursor: Cursor, tableName: String): String {
        val sb = StringBuilder()
        val columns = cursor.columnNames
        val colCount = columns.size

        while (cursor.moveToNext()) {
            sb.append("INSERT OR REPLACE INTO ").append(tableName).append(" (")
            for (i in 0 until colCount) {
                if (i > 0) sb.append(", ")
                appendIdentifier(sb, columns[i])
            }
            sb.append(") VALUES (")
            for (i in 0 until colCount) {
                if (i > 0) sb.append(", ")
                if (cursor.isNull(i)) {
                    sb.append("NULL")
                } else {
                    when (cursor.getType(i)) {
                        Cursor.FIELD_TYPE_INTEGER -> sb.append(cursor.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT -> sb.append(cursor.getDouble(i))
                        else -> appendEscapedString(sb, cursor.getString(i))
                    }
                }
            }
            sb.append(");\n")
        }
        return sb.toString()
    }

    private fun appendIdentifier(sb: StringBuilder, id: String) {
        sb.append('"').append(id.replace("\"", "\"\"")).append('"')
    }

    private fun appendEscapedString(sb: StringBuilder, value: String) {
        sb.append('\'')
        for (c in value) {
            if (c == '\'') sb.append("''")
            else sb.append(c)
        }
        sb.append('\'')
    }

    private fun isAllowedLegacyInsert(sql: String): Boolean {
        val normalized = sql.replace(Regex("\\s+"), " ").trim().lowercase(Locale.ROOT)
        if (!normalized.startsWith("insert or replace into ")) return false
        var rest = normalized.substring("insert or replace into ".length).trim()
        if (rest.startsWith("\"")) rest = rest.substring(1)
        return rest.startsWith("games\"") || rest.startsWith("games ") || rest.startsWith("games(")
                || rest.startsWith("play_sessions\"") || rest.startsWith("play_sessions ") || rest.startsWith("play_sessions(")
                || rest.startsWith("metadata_cache\"") || rest.startsWith("metadata_cache ") || rest.startsWith("metadata_cache(")
                || rest.startsWith("settings\"") || rest.startsWith("settings ") || rest.startsWith("settings(")
    }

    private fun removeLeadingSqlComments(stmt: String): String {
        val lines = stmt.split(Regex("\\r?\\n"))
        val sb = StringBuilder()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("--")) continue
            sb.append(line).append('\n')
        }
        return sb.toString()
    }

    /**
     * 正确拆分 SQL 语句，跳过字符串字面量中的分号。
     * 例如: INSERT INTO t VALUES ('a;b'); INSERT INTO t VALUES('c');
     * → ["INSERT INTO t VALUES ('a;b')", "INSERT INTO t VALUES('c')"]
     */
    private fun splitSqlStatements(sql: String): List<String> {
        val result = ArrayList<String>()
        val sb = StringBuilder()
        var inSingleQuote = false

        var i = 0
        while (i < sql.length) {
            val c = sql[i]

            if (c == '\'') {
                if (inSingleQuote && i + 1 < sql.length && sql[i + 1] == '\'') {
                    sb.append("''")
                    i++
                    i++
                    continue
                }
                inSingleQuote = !inSingleQuote
                sb.append(c)
                i++
                continue
            }

            if (c == ';' && !inSingleQuote) {
                val stmt = sb.toString().trim()
                if (stmt.isNotEmpty()) {
                    result.add(stmt)
                }
                sb.setLength(0)
            } else {
                sb.append(c)
            }
            i++
        }

        val remaining = sb.toString().trim()
        if (remaining.isNotEmpty()) {
            result.add(remaining)
        }

        return result
    }
}
