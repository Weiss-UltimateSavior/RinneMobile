package com.yuki.yukihub.importer

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * LunaBox ZIP 备份导入器。
 *
 * LunaBox 备份 ZIP 结构：
 * - database/games.csv          游戏列表（CSV，逗号分隔，双引号包裹含逗号的字段）
 * - database/play_sessions.csv  游玩记录（CSV）
 * - database/game_tags.csv      标签（CSV，每游戏多条）
 * - covers/&lt;id&gt;.webp        封面图片（文件名 = 游戏 ID + .webp）
 *
 * games.csv 列：id,name,cover_url,company,summary,rating,release_date,path,save_path,
 *   process_name,wine_runner,wine_args,wine_prefix,launch_mode,status,
 *   source_type,cached_at,source_id,created_at,updated_at,
 *   use_locale_emulator,use_magpie,metadata_locked
 *
 * play_sessions.csv 列：id,game_id,start_time,end_time,duration,updated_at
 *   duration 单位：秒
 *   start_time/end_time 格式：2026-07-16 17:34:23.673844+08（带时区的 PostgreSQL 风格时间戳）
 *
 * game_tags.csv 列：id,game_id,name,source,weight,is_spoiler,created_at,updated_at
 *
 * Android 上 path（Windows 路径）无效，导入时不设 rootUri。
 * 封面图片从 ZIP 中解压到缓存目录，路径写入 coverLocalPath。
 */
object LunaBoxImporter {

    private const val TAG = "LunaBoxImporter"

    /**
     * 匹配小数秒部分，用于把 6 位微秒截断为 3 位毫秒。
     * SimpleDateFormat 的 S 是毫秒，6 位数字会被当作 673844ms（≈11 分钟）。
     */
    private val FRACTIONAL_SECONDS = Regex("(\\.\\d{3})\\d+")

    /**
     * 从 SAF Uri 读取 LunaBox ZIP 备份并解析成候选项列表。
     * 封面图片解压到应用缓存目录。
     * 在后台线程调用。
     */
    @JvmStatic
    @Throws(Exception::class)
    fun parse(context: Context, uri: Uri): List<ImportGameData> {
        val tempDir = File(context.cacheDir, "lunabox_import_${System.currentTimeMillis()}")
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            throw Exception("无法创建缓存目录")
        }
        ImporterIO.registerTempDir(tempDir)

        var gamesCsv: String? = null
        var sessionsCsv: String? = null
        var tagsCsv: String? = null
        // 文件名(不含路径) -> File
        val coverFiles = HashMap<String, File>()
        // 累计字节计数器：单条 entry 检查 MAX_ENTRY_BYTES，累计检查 MAX_TOTAL_BYTES
        val acc = ImporterIO.ReadAccumulator(ImporterIO.MAX_TOTAL_BYTES)
        var entryCount = 0

        val raw = context.contentResolver.openInputStream(uri)
            ?: throw Exception("无法打开 ZIP 文件")
        raw.use { input ->
            ZipInputStream(input).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    // N6: ZIP entry 数量上限，防止恶意 ZIP 用海量空 entry 耗尽资源
                    if (++entryCount > ImporterIO.MAX_ENTRY_COUNT) {
                        throw IOException("ZIP entry 数量超过上限 ${ImporterIO.MAX_ENTRY_COUNT}")
                    }
                    if (entry.isDirectory) {
                        zis.closeEntry()
                        continue
                    }
                    val name = entry.name
                    val data = ImporterIO.readBytes(zis, ImporterIO.MAX_ENTRY_BYTES, acc)

                    when {
                        name == "database/games.csv" || name.endsWith("/games.csv") ->
                            gamesCsv = String(data, StandardCharsets.UTF_8)
                        name == "database/play_sessions.csv" || name.endsWith("/play_sessions.csv") ->
                            sessionsCsv = String(data, StandardCharsets.UTF_8)
                        name == "database/game_tags.csv" || name.endsWith("/game_tags.csv") ->
                            tagsCsv = String(data, StandardCharsets.UTF_8)
                        isImageFile(name) -> {
                            // baseName 已通过 File(name).name 剥离路径，
                            // 再做 canonical path 校验以防恶意 entry name 逃逸
                            val baseName = File(name).name
                            val outFile = File(tempDir, baseName)
                            ImporterIO.ensurePathInside(outFile, tempDir)
                            FileOutputStream(outFile).use { fos -> fos.write(data) }
                            coverFiles[baseName] = outFile
                        }
                    }
                    zis.closeEntry()
                }
            }
        }

        val games = gamesCsv ?: throw Exception("ZIP 中未找到 database/games.csv")

        // 解析 tags CSV -> game_id -> list of tag names
        val tagsByGameId = parseTagsCsv(tagsCsv)

        // 解析 play_sessions CSV -> game_id -> list of LunaBoxSession
        val sessionsByGameId = parseSessionsCsv(sessionsCsv)

        // 解析 games CSV
        val gameRows = parseCsv(games)
        val result = ArrayList<ImportGameData>()
        if (gameRows.isEmpty()) return result

        // 第一行是表头
        val headers = gameRows[0]
        val colMap = LinkedHashMap<String, Int>()
        for (i in headers.indices) {
            colMap[headers[i].trim().lowercase()] = i
        }

        for (i in 1 until gameRows.size) {
            val row = gameRows[i]
            if (row.isEmpty()) continue

            val g = convertFromLunaBox(row, colMap, tagsByGameId, sessionsByGameId, coverFiles)
            if (g != null) result.add(g)
        }

        // 临时目录由 ImporterIO 统一注册，待 ImporterService 写库完成后清理。
        // 不再使用 deleteOnExit() —— Android 进程几乎不退出，shutdown hook 不会执行。
        return result
    }

    private fun convertFromLunaBox(
        row: Array<String>,
        colMap: Map<String, Int>,
        tagsByGameId: Map<String, List<String>>,
        sessionsByGameId: Map<String, List<ImportGameData.LunaBoxSession>>,
        coverFiles: Map<String, File>
    ): ImportGameData? {
        val g = ImportGameData()
        val gameId = getCol(row, colMap, "id")
        g.name = getCol(row, colMap, "name")
        if (g.name.trim().isEmpty()) return null
        g.name = g.name.trim()

        g.developer = getCol(row, colMap, "company")
        g.description = getCol(row, colMap, "summary")
        g.releaseDate = getCol(row, colMap, "release_date")
        g.rating = parseDoubleSafe(getCol(row, colMap, "rating"), 0.0)
        g.path = getCol(row, colMap, "path")
        g.savePath = getCol(row, colMap, "save_path")
        g.sourceType = mapSourceType(getCol(row, colMap, "source_type"))
        g.sourceId = getCol(row, colMap, "source_id")

        // 游玩状态映射：LunaBox 5态 → YukiHub 3态
        g.playStatus = mapPlayStatus(getCol(row, colMap, "status"))

        // 封面
        val coverUrl = getCol(row, colMap, "cover_url")
        if (coverUrl.isNotEmpty()) {
            if (coverUrl.startsWith("http://") || coverUrl.startsWith("https://")) {
                g.coverUrl = coverUrl
            } else if (coverUrl.startsWith("/local/covers/")) {
                // LunaBox 本地封面格式：/local/covers/<uuid>.webp
                val fileName = coverUrl.substring("/local/covers/".length)
                val coverFile = coverFiles[fileName]
                if (coverFile != null && coverFile.exists()) {
                    g.coverLocalPath = coverFile.absolutePath
                }
            }
        }

        // 标签
        if (gameId.isNotEmpty()) {
            val tags = tagsByGameId[gameId]
            if (!tags.isNullOrEmpty()) {
                val uniqueTags = ArrayList<String>()
                for (t in tags) {
                    val trimmed = t.trim()
                    if (trimmed.isNotEmpty() && !uniqueTags.contains(trimmed)) {
                        uniqueTags.add(trimmed)
                        if (uniqueTags.size >= 20) break
                    }
                }
                if (uniqueTags.isNotEmpty()) g.tags = uniqueTags
            }
        }

        // 创建时间
        g.createdAt = parseLunaBoxTime(getCol(row, colMap, "created_at"))

        // 游玩记录
        if (gameId.isNotEmpty()) {
            val sessions = sessionsByGameId[gameId]
            if (!sessions.isNullOrEmpty()) {
                g.lunaBoxSessions = sessions
                var totalSec = 0L
                for (s in sessions) {
                    totalSec += s.durationSeconds
                }
                if (totalSec > 0) g.totalPlayTime = totalSec
            }
        }

        return g
    }

    // ==================== CSV 解析 ====================

    /**
     * 简易 CSV 解析器，支持双引号包裹的字段（含逗号、换行、转义双引号）。
     * 返回 List<Array<String>>，每行一个数组。
     */
    private fun parseCsv(csv: String?): List<Array<String>> {
        val rows = ArrayList<Array<String>>()
        if (csv.isNullOrEmpty()) return rows

        val currentRow = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        val len = csv.length

        while (i < len) {
            val c = csv[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < len && csv[i + 1] == '"') {
                        // 转义的双引号 -> 一个双引号
                        field.append('"')
                        i += 2
                    } else {
                        inQuotes = false
                        i++
                    }
                } else {
                    field.append(c)
                    i++
                }
            } else {
                when (c) {
                    '"' -> {
                        inQuotes = true
                        i++
                    }
                    ',' -> {
                        currentRow.add(field.toString())
                        field.setLength(0)
                        i++
                    }
                    '\n' -> {
                        currentRow.add(field.toString())
                        field.setLength(0)
                        rows.add(currentRow.toTypedArray())
                        currentRow.clear()
                        i++
                    }
                    // 跳过 \r，\n 会处理换行
                    '\r' -> i++
                    else -> {
                        field.append(c)
                        i++
                    }
                }
            }
        }
        // 处理最后一行最后一个字段
        if (field.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(field.toString())
            rows.add(currentRow.toTypedArray())
        }

        return rows
    }

    private fun parseTagsCsv(tagsCsv: String?): Map<String, List<String>> {
        val tagsByGameId = HashMap<String, MutableList<String>>()
        if (tagsCsv == null) return tagsByGameId
        val tagRows = parseCsv(tagsCsv)
        if (tagRows.isEmpty()) return tagsByGameId
        // 跳过表头
        for (i in 1 until tagRows.size) {
            val row = tagRows[i]
            if (row.size < 3) continue
            val gameId = row[1].trim()
            val tagName = row[2].trim()
            if (gameId.isEmpty() || tagName.isEmpty()) continue
            tagsByGameId.getOrPut(gameId) { ArrayList() }.add(tagName)
        }
        return tagsByGameId
    }

    private fun parseSessionsCsv(
        sessionsCsv: String?
    ): Map<String, List<ImportGameData.LunaBoxSession>> {
        val sessionsByGameId = HashMap<String, MutableList<ImportGameData.LunaBoxSession>>()
        if (sessionsCsv == null) return sessionsByGameId
        val sessionRows = parseCsv(sessionsCsv)
        if (sessionRows.isEmpty()) return sessionsByGameId
        for (i in 1 until sessionRows.size) {
            val row = sessionRows[i]
            if (row.size < 5) continue
            val gameId = row[1].trim()
            val startTime = row[2].trim()
            val endTime = row[3].trim()
            val durationStr = row[4].trim()
            if (gameId.isEmpty()) continue
            val session = ImportGameData.LunaBoxSession(
                start = startTime,
                end = endTime,
                durationSeconds = parseIntSafe(durationStr, 0)
            )
            sessionsByGameId.getOrPut(gameId) { ArrayList() }.add(session)
        }
        return sessionsByGameId
    }

    private fun getCol(row: Array<String>, colMap: Map<String, Int>, colName: String): String {
        val idx = colMap[colName.lowercase()] ?: return ""
        if (idx >= row.size) return ""
        return row[idx]
    }

    // ==================== 时间解析 ====================

    /**
     * 解析 LunaBox 的时间戳格式：2026-07-16 11:55:03.747711+08
     * PostgreSQL 风格，带时区偏移。
     *
     * 注意：SimpleDateFormat 的 S 是毫秒，6 位微秒 673844 会被当作 673844ms（≈11 分钟）。
     * 因此解析前先用正则把 6 位微秒截断为 3 位毫秒。
     */
    private fun parseLunaBoxTime(raw: String?): Long {
        if (raw.isNullOrEmpty() || raw == "null") {
            return System.currentTimeMillis()
        }
        var cleaned = raw.trim()
        // 截断微秒为毫秒：.747711 → .747
        if (FRACTIONAL_SECONDS.containsMatchIn(cleaned)) {
            cleaned = FRACTIONAL_SECONDS.replaceFirst(cleaned, "$1")
        }
        val formats = listOf(
            "yyyy-MM-dd HH:mm:ss.SSSX",  // 带毫秒和时区
            "yyyy-MM-dd HH:mm:ss.SSS",
            "yyyy-MM-dd HH:mm:ssX",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd"
        )
        for (fmt in formats) {
            try {
                val d = SimpleDateFormat(fmt, Locale.US).parse(cleaned)
                if (d != null) return d.time
            } catch (ignored: Exception) {
            }
        }
        // 兜底：截取 yyyy-MM-dd HH:mm:ss 部分
        try {
            if (cleaned.length >= 19) {
                val simplified = cleaned.substring(0, 19)
                val d = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(simplified)
                if (d != null) return d.time
            }
        } catch (ignored: Exception) {
        }
        Log.w(TAG, "无法解析时间，fallback 当前时间: $raw")
        return System.currentTimeMillis()
    }

    /**
     * 解析 LunaBox 的 start_time/end_time，用于 play_session 的时间戳。
     */
    @JvmStatic
    fun parseLunaBoxTimestamp(raw: String?): Long = parseLunaBoxTime(raw)

    // ==================== 状态映射 ====================

    /**
     * LunaBox 5 态 → YukiHub 3 态映射：
     * not_started → unplayed  (未开始 → 未玩)
     * playing     → playing   (游玩中 → 在玩)
     * completed   → completed (已通关 → 玩过)
     * want_to_play → unplayed (想玩   → 未玩)
     * on_hold     → unplayed  (搁置   → 未玩)
     */
    private fun mapPlayStatus(lunaStatus: String?): String {
        if (lunaStatus.isNullOrEmpty()) return "unplayed"
        return when (lunaStatus.trim().lowercase()) {
            "playing" -> "playing"
            "completed" -> "completed"
            else -> "unplayed"
        }
    }

    private fun mapSourceType(raw: String?): String {
        if (raw.isNullOrEmpty()) return "local"
        val lower = raw.trim().lowercase()
        return when (lower) {
            "vndb", "bangumi", "ymgal", "steam", "hikarinagi" -> lower
            else -> "local"
        }
    }

    // ==================== 辅助方法 ====================

    private fun parseDoubleSafe(s: String?, def: Double): Double {
        if (s.isNullOrEmpty()) return def
        return try {
            s.trim().toDouble()
        } catch (e: Exception) {
            def
        }
    }

    private fun parseIntSafe(s: String?, def: Int): Int {
        if (s.isNullOrEmpty()) return def
        return try {
            s.trim().toInt()
        } catch (e: Exception) {
            def
        }
    }

    private fun isImageFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
            lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp")
    }
}
