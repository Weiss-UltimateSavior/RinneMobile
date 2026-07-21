package com.yuki.yukihub.importer

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream

/**
 * PotatoVN ZIP 导入器。
 *
 * PotatoVN 导出的 ZIP 包含：
 * - data.galgames.json：游戏列表（核心数据）
 * - 封面图片文件（路径由 ImagePath 字段指定）
 *
 * 每条 Galgame 记录包含：名字、路径、开发商、简介、评分、标签、
 * 游玩记录（PlayedTime: {"2024/1/1": 120} 表示这天玩了 120 分钟）、
 * 数据源类型（RssType: 0=VNDB, 1=Bangumi, 5=ymgal, 7=steam）和数据源 ID（Ids 数组）。
 *
 * Android 上 path（Windows 路径）无效，导入时不设 rootUri，
 * 以"无路径游戏"方式存入。
 */
object PotatoVnImporter {

    private const val TAG = "PotatoVnImporter"

    /** PotatoVN 默认图标路径（不导入） */
    private const val DEFAULT_IMAGE_PATH = "ms-appx:///Assets/WindowIcon.ico"

    /**
     * 从 SAF Uri 读取 PotatoVN ZIP 并解析成候选项列表。
     * 封面图片解压到应用缓存目录，路径写入 coverLocalPath。
     * 在后台线程调用。
     */
    @JvmStatic
    @Throws(Exception::class)
    fun parse(context: Context, uri: Uri): List<ImportGameData> {
        val tempDir = File(context.cacheDir, "potatovn_import_${System.currentTimeMillis()}")
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            throw Exception("无法创建缓存目录")
        }
        ImporterIO.registerTempDir(tempDir)

        val raw = context.contentResolver.openInputStream(uri)
            ?: throw Exception("无法打开 ZIP 文件")
        val galgamesJson = raw.use { extractZip(it, tempDir) }
            ?: throw Exception("ZIP 中未找到 data.galgames.json")

        val arr = JSONArray(galgamesJson)
        val result = ArrayList<ImportGameData>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val g = convertFromPotatoVn(o, tempDir) ?: continue
            if (g.name.trim().isNotEmpty()) {
                g.name = g.name.trim()
                result.add(g)
            }
        }

        // 临时目录由 ImporterIO 统一注册，待 ImporterService 写库完成后清理。
        // 不再使用 deleteOnExit() —— Android 进程几乎不退出，shutdown hook 不会执行。
        return result
    }

    @Throws(Exception::class)
    private fun extractZip(raw: InputStream, tempDir: File): String? {
        var galgamesJson: String? = null
        // 累计字节计数器：单条 entry 检查 MAX_ENTRY_BYTES，累计检查 MAX_TOTAL_BYTES
        val acc = ImporterIO.ReadAccumulator(ImporterIO.MAX_TOTAL_BYTES)
        var entryCount = 0
        ZipInputStream(raw).use { zis ->
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
                when {
                    name == "data.galgames.json" ->
                        galgamesJson = ImporterIO.readString(
                            zis, ImporterIO.MAX_ENTRY_BYTES, StandardCharsets.UTF_8, acc
                        )
                    isImageFile(name) -> extractImage(zis, tempDir, name, acc)
                    // 未知 entry，分块跳过以维持流进度，不分配大缓冲区
                    else -> ImporterIO.skipFully(zis, ImporterIO.MAX_ENTRY_BYTES)
                }
                zis.closeEntry()
            }
        }
        return galgamesJson
    }

    private fun convertFromPotatoVn(o: JSONObject, tempDir: File): ImportGameData? {
        val g = ImportGameData()
        g.name = pickDisplayName(o)
        if (g.name.trim().isEmpty()) return null

        g.originalName = optLockableString(o, "OriginalName")
        g.developer = optLockableString(o, "Developer")
        g.description = optLockableString(o, "Description")
        g.releaseDate = optLockableDate(o)
        g.rating = optLockableDouble(o, "Rating")
        g.tags = optLockableStringList(o, "Tags")

        // 封面：imagePath 来自 ZIP 内 JSON，攻击者可控，必须做 canonical path 校验
        val imagePath = optLockableString(o, "ImagePath")
        if (imagePath.isNotEmpty() && imagePath != DEFAULT_IMAGE_PATH) {
            if (imagePath.startsWith("http://") || imagePath.startsWith("https://")) {
                g.coverUrl = imagePath
            } else {
                val coverFile = resolveSafeChild(tempDir, imagePath)
                if (coverFile != null && coverFile.exists()) {
                    g.coverLocalPath = coverFile.absolutePath
                }
            }
        }

        // 游戏路径（Windows 路径，Android 上无效）
        val exePath = o.optString("ExePath", "")
        if (exePath.isNotEmpty() && exePath != "null") {
            g.path = exePath.trim()
        }
        val savePath = o.optString("SavePath", "")
        if (savePath.isNotEmpty() && savePath != "null") {
            g.savePath = savePath.trim()
        }

        // 数据源
        val rssType = o.optInt("RssType", 3)
        g.sourceType = mapRssType(rssType)
        g.sourceId = pickSourceId(o, rssType)

        // 创建时间
        g.createdAt = parseFlexTime(o.optString("AddTime", ""))

        // 游玩记录：PlayedTime {"2024/1/1": 120}
        val playedTime = o.optJSONObject("PlayedTime")
        if (playedTime != null && playedTime.length() > 0) {
            val map = LinkedHashMap<String, Int>()
            val it = playedTime.keys()
            while (it.hasNext()) {
                val dateStr = it.next()
                val minutes = playedTime.optInt(dateStr, 0)
                if (minutes > 0) map[dateStr] = minutes
            }
            g.playedTimeMap = map
        }

        // PotatoVN 存秒
        g.totalPlayTime = o.optLong("TotalPlayTime", 0)

        return g
    }

    // ==================== PotatoVN 字段提取辅助 ====================

    /** 优先中文名 > 原始名 */
    private fun pickDisplayName(o: JSONObject): String {
        val cn = optLockableString(o, "ChineseName")
        if (cn.trim().isNotEmpty()) return cn.trim()
        val name = optLockableString(o, "Name")
        if (name.trim().isNotEmpty()) return name.trim()
        return ""
    }

    /**
     * PotatoVN 的可锁定属性格式：{"Value": "xxx", "IsLock": false}
     */
    private fun optLockableString(o: JSONObject, key: String): String {
        val prop = o.optJSONObject(key) ?: return ""
        return prop.optString("Value", "")
    }

    private fun optLockableDouble(o: JSONObject, key: String): Double {
        val prop = o.optJSONObject(key) ?: return 0.0
        return prop.optDouble("Value", 0.0)
    }

    private fun optLockableStringList(o: JSONObject, key: String): List<String>? {
        val prop = o.optJSONObject(key) ?: return null
        val arr = prop.optJSONArray("Value") ?: return null
        val list = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "")
            if (s.trim().isNotEmpty()) list.add(s.trim())
        }
        return if (list.isEmpty()) null else list
    }

    private fun optLockableDate(o: JSONObject): String {
        val prop = o.optJSONObject("ReleaseDate") ?: return ""
        val raw = prop.optString("Value", "")
        if (raw.isEmpty() || raw == "null") return ""
        for (fmt in listOf("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd")) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                val d = sdf.parse(raw)
                if (d != null) {
                    return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d)
                }
            } catch (ignored: Exception) {
            }
        }
        return raw
    }

    /** 从 Ids 数组按 RssType 位置取 SourceID */
    private fun pickSourceId(o: JSONObject, rssType: Int): String {
        val ids = o.optJSONArray("Ids") ?: return ""
        if (ids.length() == 0) return ""
        if (rssType in 0 until ids.length()) {
            val id = ids.optString(rssType, "")
            if (id.isNotEmpty()) return id
        }
        // 兜底取第一个非空
        for (i in 0 until ids.length()) {
            val id = ids.optString(i, "")
            if (id.isNotEmpty()) return id
        }
        return ""
    }

    private fun mapRssType(rssType: Int): String = when (rssType) {
        0 -> "vndb"
        1 -> "bangumi"
        5 -> "ymgal"
        7 -> "steam"
        else -> "local"
    }

    private fun parseFlexTime(raw: String?): Long {
        if (raw.isNullOrEmpty() || raw == "null") {
            return System.currentTimeMillis()
        }
        for (fmt in listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd"
        )) {
            try {
                val d = SimpleDateFormat(fmt, Locale.US).parse(raw)
                if (d != null) return d.time
            } catch (ignored: Exception) {
            }
        }
        Log.w(TAG, "无法解析时间，fallback 当前时间: $raw")
        return System.currentTimeMillis()
    }

    // ==================== ZIP 辅助 ====================

    private fun isImageFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
            lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp")
    }

    /**
     * 解析 ZIP entry name（或 JSON 中的 imagePath）为 tempDir 内的安全子路径。
     * 通过 canonical path 校验防止 zip slip 路径穿越。
     * 返回 null 表示路径非法或解析失败。
     */
    private fun resolveSafeChild(tempDir: File, rawPath: String?): File? {
        if (rawPath.isNullOrEmpty()) return null
        val normalized = rawPath.replace("\\", "/").replaceFirst(Regex("^/+"), "")
        val outFile = File(tempDir, normalized)
        return try {
            ImporterIO.ensurePathInside(outFile, tempDir)
            outFile
        } catch (e: IOException) {
            Log.w(TAG, "拒绝跨目录路径: $rawPath", e)
            null
        }
    }

    @Throws(Exception::class)
    private fun extractImage(
        zis: ZipInputStream,
        tempDir: File,
        entryName: String,
        acc: ImporterIO.ReadAccumulator
    ) {
        val outFile = resolveSafeChild(tempDir, entryName)
        if (outFile == null) {
            // N5: 路径非法时不再 readBytes 分配 50MB byte[]，改为 skipFully 分块跳过
            ImporterIO.skipFully(zis, ImporterIO.MAX_ENTRY_BYTES)
            return
        }
        val parent = outFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw Exception("无法创建目录: ${parent.absolutePath}")
        }
        FileOutputStream(outFile).use { fos ->
            val data = ImporterIO.readBytes(zis, ImporterIO.MAX_ENTRY_BYTES, acc)
            fos.write(data)
        }
    }
}
