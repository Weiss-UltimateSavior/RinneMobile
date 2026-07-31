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
    private const val PREFS = "game_scan_result_cache_v2"

    fun load(context: Context, rootUri: String, depth: Int): Map<String, ScanResult> {
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
            rpgMakerSubtype = item.optString("rpgMakerSubtype", ""),
            renpySubtype = item.optString("renpySubtype", ""),
            godotSubtype = item.optString("godotSubtype", "")
        )
    }

    private fun key(rootUri: String, depth: Int): String {
        val normalized = GameRepository.normalizeRootUriKey(rootUri)
        val encoded = Base64.encodeToString(
            normalized.toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP or Base64.URL_SAFE
        )
        return "${depth}_$encoded"
    }
}
