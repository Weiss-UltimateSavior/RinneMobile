package com.yuki.yukihub.importer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Vnite 数据库导入器。
 *
 * Vnite 导出的是一个目录，包含：
 * - gameDocs.json：游戏文档（元数据、标签、计时器等）
 * - gameLocalDocs.json：本地文档（路径、启动器配置）
 * - covers/<id>.jpg：封面图片（可选）
 *
 * 注意：Vnite 的路径是 Windows 路径，Android 上不适用。
 */
object VniteImporter {

    private const val TAG = "VniteImporter"

    /**
     * 从 SAF 选中的目录 Uri 读取 Vnite 数据并解析。
     *
     * Vnite 的文档 ID 是 string 类型（UUID），需要合并 GameDocs 和 GameLocalDocs。
     * 在后台线程调用。
     */
    @JvmStatic
    @Throws(Exception::class)
    fun parse(context: Context, dirUri: Uri): List<ImportGameData> {
        // SAF 列目录（递归，covers 子目录可达），找到 JSON 文件
        val fileUris = listDirectoryFilesRecursive(context, dirUri, "", 3)
        val jsonFileNames = ArrayList<String>()
        for (name in fileUris.keys) {
            if (name.endsWith(".json")) {
                jsonFileNames.add(name)
            }
        }

        // 读取 JSON 文件内容
        var gameDocsJson: String? = null
        var gameLocalDocsJson: String? = null
        for (name in jsonFileNames) {
            val content = readUriToString(context, fileUris.getValue(name))
            // 优先匹配文件名精确包含 gameDocs / gameLocalDocs
            val lower = name.lowercase()
            if (lower.contains("gamedoc") && !lower.contains("gamelocal")) {
                gameDocsJson = content
            } else if (lower.contains("gamelocaldoc")) {
                gameLocalDocsJson = content
            }
        }

        // 也可能是单个 JSON 文件包含所有数据
        if (gameDocsJson == null && jsonFileNames.size == 1) {
            gameDocsJson = readUriToString(context, fileUris.getValue(jsonFileNames[0]))
        }

        if (gameDocsJson == null) {
            throw Exception("未找到 Vnite 游戏数据文件（gameDocs.json）")
        }

        // 解析 GameDocs（Vnite 格式可能是数组或 map）
        val gameDocsMap = parseJsonObjectOrArray(gameDocsJson)

        // 解析 GameLocalDocs
        val localDocsMap = if (gameLocalDocsJson != null) {
            parseJsonObjectOrArray(gameLocalDocsJson)
        } else {
            HashMap()
        }

        // 合并并生成 ImportGameData 列表
        val result = ArrayList<ImportGameData>()
        for (id in gameDocsMap.keys) {
            val gameDoc = gameDocsMap[id] ?: continue
            val localDoc = localDocsMap[id]
            val g = convertFromVnite(gameDoc, localDoc) ?: continue
            if (g.name.trim().isNotEmpty()) {
                g.name = g.name.trim()
                result.add(g)
            }
        }
        return result
    }

    private fun convertFromVnite(gameDoc: JSONObject, localDoc: JSONObject?): ImportGameData? {
        val g = ImportGameData()

        // 名称
        val meta = gameDoc.optJSONObject("metadata")
        if (meta != null) {
            g.name = meta.optString("name", meta.optString("originalName", ""))
            g.originalName = meta.optString("originalName", "")
            g.developer = pickFirst(meta, "developers", "publishers")
            g.description = meta.optString("description", "")
            g.releaseDate = meta.optString("releaseDate", "")
            g.sourceId = pickSourceId(meta)
            g.sourceType = pickSourceType(meta)

            val tagArr = meta.optJSONArray("tags")
            if (tagArr != null && tagArr.length() > 0) {
                val tags = ArrayList<String>()
                for (i in 0 until tagArr.length()) {
                    val t = tagArr.optString(i, "")
                    if (t.trim().isNotEmpty()) tags.add(t.trim())
                }
                g.tags = if (tags.isEmpty()) null else tags
            }
        }

        if (g.name.isEmpty()) {
            g.name = gameDoc.optString("name", gameDoc.optString("title", ""))
        }
        if (g.name.isEmpty()) return null

        // 计时器
        val record = gameDoc.optJSONObject("record")
        if (record != null) {
            val timers = record.optJSONArray("timers")
            if (timers != null && timers.length() > 0) {
                val vniteTimers = ArrayList<ImportGameData.VniteTimer>()
                for (i in 0 until timers.length()) {
                    val t = timers.optJSONObject(i) ?: continue
                    val start = t.optString("start", "")
                    val end = t.optString("end", "")
                    if (start.isNotEmpty() && end.isNotEmpty()) {
                        vniteTimers.add(ImportGameData.VniteTimer(start = start, end = end))
                    }
                }
                g.vniteTimers = vniteTimers
            }
            g.createdAt = parseTime(record.optString("addDate", ""))
        }

        // 本地路径
        if (localDoc != null) {
            val launcher = localDoc.optJSONObject("launcher")
            if (launcher != null) {
                val fileConfig = launcher.optJSONObject("fileConfig")
                if (fileConfig != null) {
                    g.path = fileConfig.optString("path", "")
                }
            }
            val path = localDoc.optJSONObject("path")
            if (path != null) {
                if (g.path.isNullOrEmpty()) {
                    g.path = path.optString("gamePath", "")
                }
                val savePaths = path.optJSONArray("savePaths")
                if (savePaths != null && savePaths.length() > 0) {
                    g.savePath = savePaths.optString(0, "")
                }
            }
        }

        return g
    }

    // ==================== Vnite 辅助方法 ====================

    private fun pickFirst(meta: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val arr = meta.optJSONArray(key)
            if (arr != null && arr.length() > 0) {
                val v = arr.optString(0, "")
                if (v.trim().isNotEmpty()) return v.trim()
            }
        }
        return ""
    }

    private fun pickSourceId(meta: JSONObject): String {
        for (key in listOf("vndbId", "ymgalId", "bangumiId", "steamId")) {
            val v = meta.optString(key, "")
            if (v.trim().isNotEmpty()) return v.trim()
        }
        return ""
    }

    private fun pickSourceType(meta: JSONObject): String {
        val keys = listOf("vndbId", "ymgalId", "bangumiId", "steamId")
        val types = listOf("vndb", "ymgal", "bangumi", "steam")
        for (i in keys.indices) {
            val v = meta.optString(keys[i], "")
            if (v.trim().isNotEmpty()) return types[i]
        }
        return "local"
    }

    private fun parseTime(raw: String?): Long {
        if (raw.isNullOrEmpty()) return System.currentTimeMillis()
        val layouts = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd"
        )
        for (fmt in layouts) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.US)
                if (fmt.endsWith("'Z'")) sdf.timeZone = TimeZone.getTimeZone("UTC")
                val d = sdf.parse(raw)
                if (d != null) return d.time
            } catch (ignored: Exception) {
            }
        }
        Log.w(TAG, "无法解析时间，fallback 当前时间: $raw")
        return System.currentTimeMillis()
    }

    /**
     * JSON 同时兼容数组和对象两种形式，统一返回 Map<id, JSONObject>。
     * 数组模式下缺 id 时使用 "__index_N" 作为稳定 key，确保 GameDocs 与 GameLocalDocs
     * 在相同缺 id 情况下也能正确合并（Vnite 数组模式下两者索引顺序通常一致）。
     */
    @Throws(Exception::class)
    private fun parseJsonObjectOrArray(json: String): Map<String, JSONObject> {
        val map = LinkedHashMap<String, JSONObject>()
        when (val parsed = JSONTokener(json).nextValue()) {
            is JSONArray -> {
                for (i in 0 until parsed.length()) {
                    val o = parsed.optJSONObject(i) ?: continue
                    var id = o.optString("id", o.optString("_id", ""))
                    if (id.isEmpty()) id = "__index_$i"
                    map[id] = o
                }
            }
            is JSONObject -> {
                val keys = parsed.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val o = parsed.optJSONObject(key)
                    if (o != null) map[key] = o
                }
            }
        }
        return map
    }

    /**
     * 递归列举 SAF 目录下的所有文件（含子目录），返回 path -> Uri 映射。
     * path 为相对根目录的路径（如 "covers/abc.jpg"），用于区分同名文件。
     */
    private fun listDirectoryFilesRecursive(
        context: Context,
        treeUri: Uri,
        relativePrefix: String,
        maxDepth: Int
    ): Map<String, Uri> {
        val result = HashMap<String, Uri>()
        if (maxDepth < 0) return result
        try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )
            val cursor = context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            ) ?: return result
            cursor.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    val relPath = if (relativePrefix.isEmpty()) name else "$relativePrefix/$name"
                    if (DocumentsContract.Document.MIME_TYPE_DIR == mime) {
                        // 递归进入子目录
                        result.putAll(
                            listDirectoryFilesRecursive(context, childUri, relPath, maxDepth - 1)
                        )
                    } else {
                        result[relPath] = childUri
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "列举目录失败: $relativePrefix", e)
        }
        return result
    }

    @Throws(Exception::class)
    private fun readUriToString(context: Context, uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw Exception("无法读取文件: $uri")
        return input.use { ImporterIO.readString(it, ImporterIO.MAX_ENTRY_BYTES, StandardCharsets.UTF_8) }
    }
}
