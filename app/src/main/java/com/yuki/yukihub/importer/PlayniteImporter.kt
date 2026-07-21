package com.yuki.yukihub.importer

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import java.nio.charset.StandardCharsets

/**
 * Playnite JSON 导入器。
 *
 * Playnite 导出的是 JSON 数组，每个元素包含：
 * name, cover_url, company, summary, rating, release_date,
 * path, save_path, source_type, source_id, created_at
 *
 * 注意：Playnite 的 path 是 Windows 路径，Android 上不适用。
 * 导入时 path 留空，以"无路径游戏"方式存入（和 YukiHub 收藏但没设目录的游戏一样）。
 */
object PlayniteImporter {

    /** Playnite JSON 文件最大 50MB（足够容纳大型游戏库导出） */
    private const val MAX_FILE_BYTES = ImporterIO.MAX_ENTRY_BYTES

    /**
     * 从 SAF Uri 读取 Playnite JSON 并解析成候选项列表。
     * 在后台线程调用。
     */
    @JvmStatic
    @Throws(Exception::class)
    fun parse(context: Context, uri: Uri): List<ImportGameData> {
        var buf = readAllBytes(context, uri)

        // 去掉 UTF-8 BOM
        if (buf.size >= 3 &&
            buf[0] == 0xEF.toByte() && buf[1] == 0xBB.toByte() && buf[2] == 0xBF.toByte()
        ) {
            buf = buf.copyOfRange(3, buf.size)
        }

        val json = String(buf, StandardCharsets.UTF_8)
        val arr = JSONArray(json)

        val result = ArrayList<ImportGameData>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val g = ImportGameData()
            g.name = o.optString("name", "").trim()
            if (g.name.isEmpty()) continue
            g.originalName = g.name
            g.developer = o.optString("company", "").trim()
            g.description = o.optString("summary", "").trim()
            g.coverUrl = o.optString("cover_url", "").trim()
            g.releaseDate = o.optString("release_date", "").trim()
            g.rating = o.optDouble("rating", 0.0)
            g.path = o.optString("path", "").trim()
            g.savePath = o.optString("save_path", "").trim()
            g.sourceType = mapSourceType(o.optString("source_type", ""))
            g.sourceId = o.optString("source_id", "").trim()
            val createdAt = o.optLong("created_at", 0)
            g.createdAt = if (createdAt > 0) createdAt * 1000L else System.currentTimeMillis()
            result.add(g)
        }
        return result
    }

    private fun mapSourceType(raw: String?): String =
        when (raw?.trim()?.lowercase()) {
            "bangumi" -> "bangumi"
            "vndb" -> "vndb"
            "ymgal" -> "ymgal"
            "steam" -> "steam"
            else -> "local"
        }

    @Throws(Exception::class)
    private fun readAllBytes(context: Context, uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw Exception("无法打开文件")
        // 单文件场景，acc=null 表示不跟踪累计
        return input.use { ImporterIO.readBytes(it, MAX_FILE_BYTES, null) }
    }
}
