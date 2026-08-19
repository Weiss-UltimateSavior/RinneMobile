package com.core.scanner

import android.content.Context
import android.util.Base64
import com.core.data.GameRepository
import com.core.model.EngineType
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * Result cache used as an incremental hint. Cached entries are only reused after their URI is
 * observed in the current directory listing, so deleted files/directories are never resurrected.
 */
internal object ScanCacheStore {
    // v2→v3：新增 .pkg / PS3 目录签名识别与 engineCandidates 双候选后，schema 语义已变化。
    // 若不递增键名，重扫同目录会命中旧缓存而看不到 PS3 识别结果；bump 命名即强制整体失效。
    private const val PREFS = "game_scan_result_cache_v3"
    /** 上一个缓存 schema 的 prefs 名，仅用于升级迁移清理。 */
    private const val LEGACY_PREFS = "game_scan_result_cache_v2"

    /** v2 旧缓存清理仅需一次；用内存标志避免每次 load 都重新 clear+写盘。 */
    private var legacyPrefsCleared = false

    fun load(context: Context, rootUri: String, depth: Int): Map<String, ScanResult> {
        cleanupLegacyPrefs(context)
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(rootUri, depth), null) ?: return emptyMap()
        return runCatching {
            val array = JSONArray(raw)
            buildMap {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val result = decode(item) ?: continue
                    val normalized = GameRepository.normalizeRootUriKey(result.uri)
                    if (normalized.isNotEmpty()) put(normalized, result)
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun save(context: Context, rootUri: String, depth: Int, results: List<ScanResult>) {
        val array = JSONArray()
        for (result in results) {
            if (result.uri.isNullOrBlank() || result.engine == null) continue
            array.put(encode(result))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key(rootUri, depth), array.toString())
            .apply()
    }

    private fun encode(result: ScanResult): JSONObject = JSONObject()
        .put("title", result.title ?: "")
        .put("uri", result.uri ?: "")
        .put("engine", result.engine?.name ?: EngineType.UNKNOWN.name)
        .put("confidence", result.confidence)
        .put("launchTarget", result.launchTarget ?: "")
        .put("coverUri", result.coverUri ?: "")
        .put("xp3Candidates", JSONArray(result.xp3Candidates ?: emptyList<String>()))
        .put("engineCandidates", JSONArray(
            (result.engineCandidates ?: emptyList()).map { it.name }))
        .put("rpgMakerSubtype", result.rpgMakerSubtype ?: "")
        .put("renpySubtype", result.renpySubtype ?: "")
        .put("godotSubtype", result.godotSubtype ?: "")

    private fun decode(item: JSONObject): ScanResult? {
        val uri = item.optString("uri", "")
        if (uri.isBlank()) return null
        val candidates = ArrayList<String>()
        val array = item.optJSONArray("xp3Candidates")
        if (array != null) {
            for (index in 0 until array.length()) {
                array.optString(index, "").takeIf { it.isNotBlank() }?.let(candidates::add)
            }
        }
        val engineCandidates = optEngineCandidates(item)
        return ScanResult(
            title = item.optString("title", ""),
            uri = uri,
            engine = runCatching {
                EngineType.valueOf(item.optString("engine", EngineType.UNKNOWN.name))
            }.getOrDefault(EngineType.UNKNOWN),
            confidence = item.optInt("confidence", 0),
            launchTarget = item.optString("launchTarget", ""),
            coverUri = item.optString("coverUri", ""),
            xp3Candidates = candidates,
            engineCandidates = engineCandidates,
            rpgMakerSubtype = item.optString("rpgMakerSubtype", ""),
            renpySubtype = item.optString("renpySubtype", ""),
            godotSubtype = item.optString("godotSubtype", "")
        )
    }

    /** 读取 engineCandidates：仅接受 JSONArray 新格式字段。 */
    private fun optEngineCandidates(item: JSONObject): List<EngineType> {
        val names = ArrayList<String>()
        val array = item.optJSONArray("engineCandidates")
        if (array != null) {
            for (index in 0 until array.length()) {
                array.optString(index, "").takeIf { it.isNotBlank() }?.let(names::add)
            }
        }
        return names.mapNotNull { name ->
            runCatching { EngineType.valueOf(name) }.getOrNull()
        }
    }

    private fun key(rootUri: String, depth: Int): String {
        val normalized = GameRepository.normalizeRootUriKey(rootUri)
        val encoded = Base64.encodeToString(
            normalized.toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP or Base64.URL_SAFE
        )
        return "${depth}_$encoded"
    }

    /** 迁移清理：清除 v2 旧命名缓存，避免升级后残留无版本字段的旧结果。幂等 + 内存标志，进程内仅执行一次。 */
    private fun cleanupLegacyPrefs(context: Context) {
        if (legacyPrefsCleared) return
        legacyPrefsCleared = true
        context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
