package com.yuki.yukihub.launcherbridge

import android.content.Context
import com.yuki.yukihub.data.GameRepository
import com.yuki.yukihub.data.MetadataRepository
import com.yuki.yukihub.metadata.BangumiClient
import com.yuki.yukihub.metadata.MetadataController
import com.yuki.yukihub.metadata.VnMetadata
import com.yuki.yukihub.metadata.VndbClient
import com.yuki.yukihub.model.Game
import com.yuki.yukihub.util.AppExecutors

/**
 * 元数据桥接：VNDB/Bangumi/月幕Gal 搜索、保存、封面同步。
 */
object LauncherMetadataBridge {


    interface Callback {
        fun onResult(success: Boolean)
    }

    interface CandidatesCallback {
        fun onResult(candidates: List<VnMetadata>, errorMessage: String?)
    }

    // 资料源配置桥接

    @JvmStatic
    fun getMetadataSource(context: Context?): String {
        if (context == null) return MetadataController.SOURCE_VNDB
        val prefs = context.yukiPrefs()
        return prefs.getString(MetadataController.KEY_METADATA_SOURCE, MetadataController.SOURCE_VNDB)
            ?: MetadataController.SOURCE_VNDB
    }

    @JvmStatic
    fun setMetadataSource(context: Context?, source: String?) {
        if (context == null) return
        val prefs = context.yukiPrefs()
        prefs.edit().putString(MetadataController.KEY_METADATA_SOURCE, source).apply()
    }

    @JvmStatic
    fun getBangumiToken(context: Context?): String {
        if (context == null) return ""
        val prefs = context.yukiPrefs()
        return prefs.getString(MetadataController.KEY_BANGUMI_TOKEN, "") ?: ""
    }

    @JvmStatic
    fun setBangumiToken(context: Context?, token: String?) {
        if (context == null) return
        val prefs = context.yukiPrefs()
        prefs.edit().putString(MetadataController.KEY_BANGUMI_TOKEN, token?.trim() ?: "").apply()
    }

    @JvmStatic
    fun sourceLabel(source: String?): String = when (source) {
        MetadataController.SOURCE_BANGUMI -> "Bangumi"
        MetadataController.SOURCE_BANGUMI_MIRROR -> "Bangumi 镜像"
        MetadataController.SOURCE_YMGAL -> "月幕 Gal"
        else -> "VNDB"
    }

    /**
     * 搜索 VNDB 候选并保存最佳匹配的元数据到本地，返回获取到的元数据
     * （无匹配或出错时返回 null）。在调用线程运行；调用方应确保不在主线程。
     */
    @JvmStatic
    fun fetchAndSaveVndbSync(context: Context?, game: Game?): VnMetadata? {
        if (context == null || game == null || game.title.isNullOrBlank()) return null
        val app = context.applicationContext
        return try {
            val candidates = VndbClient.searchCandidates(game.title, 1)
            if (candidates.isNullOrEmpty()) return null
            val meta = candidates[0]
            val metaRepo = MetadataRepository(app)
            metaRepo.saveVndb(game.id, meta)
            meta
        } catch (_: Throwable) {
            null
        }
    }

    @JvmStatic
    fun fetchAndSaveMetadataAsync(context: Context?, game: Game?, callback: Callback?) {
        if (callback == null) return
        if (context == null || game == null || game.title.isNullOrBlank()) {
            callback.onResult(false)
            return
        }
        val app = context.applicationContext
        AppExecutors.io().execute {
            var ok = false
            try {
                val candidates = VndbClient.searchCandidates(game.title, 1)
                if (!candidates.isNullOrEmpty()) {
                    val meta = candidates[0]
                    val metaRepo = MetadataRepository(app)
                    metaRepo.saveVndb(game.id, meta)
                    setPreferredMetadataSource(app, game.id, MetadataController.SOURCE_VNDB)
                    if (!meta.coverUrl.isNullOrBlank()) {
                        val cover = LauncherCoverBridge.downloadCover(app, meta.coverUrl, "meta_cover_${game.id}")
                        if (cover != null) {
                            val repo = GameRepository(app)
                            val latest = repo.findById(game.id)
                            if (latest != null && latest.coverUri.isNullOrBlank()) {
                                latest.coverUri = cover
                                latest.coverPersistUri = cover
                                latest.coverSourceType = 1
                                repo.update(latest)
                            }
                        }
                    }
                    ok = true
                }
            } catch (_: Throwable) {
            }
            val success = ok
            postToMain { callback.onResult(success) }
        }
    }

    /** 按用户输入的关键词搜索多个 VNDB 候选，结果始终回调到主线程。 */
    @JvmStatic
    fun searchVndbCandidatesAsync(context: Context?, keyword: String?, limit: Int, callback: CandidatesCallback?) {
        if (callback == null) return
        val query = keyword?.trim() ?: ""
        if (context == null || query.isEmpty()) {
            callback.onResult(emptyList(), "请输入搜索关键词")
            return
        }
        AppExecutors.io().execute {
            var candidates: List<VnMetadata>? = emptyList()
            var error: String? = null
            try {
                candidates = VndbClient.searchCandidates(query, maxOf(1, minOf(10, limit)))
            } catch (t: Throwable) {
                error = if (t.message.isNullOrBlank()) "VNDB 搜索失败" else t.message
            }
            val result = candidates ?: emptyList()
            val finalError = error
            postToMain { callback.onResult(result, finalError) }
        }
    }

    /** 保存用户明确选择的 VNDB 候选；不自动覆盖游戏卡片，仍由"同步封面到卡片"控制。 */
    @JvmStatic
    fun saveSelectedVndbMetadataAsync(context: Context?, game: Game?, metadata: VnMetadata?, callback: Callback?) {
        if (callback == null) return
        if (context == null || game == null || game.id <= 0 || metadata == null) {
            callback.onResult(false)
            return
        }
        val app = context.applicationContext
        AppExecutors.io().execute {
            var success = false
            try {
                MetadataRepository(app).saveVndb(game.id, metadata)
                setPreferredMetadataSource(app, game.id, MetadataController.SOURCE_VNDB)
                success = true
            } catch (_: Throwable) {
            }
            val finalSuccess = success
            postToMain { callback.onResult(finalSuccess) }
        }
    }

    /** 按用户输入的关键词搜索 Bangumi 候选，结果始终回调到主线程。 */
    @JvmStatic
    fun searchBangumiCandidatesAsync(context: Context?, keyword: String?, limit: Int, callback: CandidatesCallback?) {
        if (callback == null) return
        val query = keyword?.trim() ?: ""
        if (context == null || query.isEmpty()) {
            callback.onResult(emptyList(), "请输入搜索关键词")
            return
        }
        val token = getBangumiToken(context)
        if (token.isBlank()) {
            callback.onResult(emptyList(), "请先在设置中配置 Bangumi Token")
            return
        }
        val useMirror = MetadataController.SOURCE_BANGUMI_MIRROR == getMetadataSource(context)
        AppExecutors.io().execute {
            var candidates: List<VnMetadata>? = emptyList()
            var error: String? = null
            try {
                candidates = BangumiClient.searchCandidates(query, token, maxOf(1, minOf(10, limit)), useMirror)
            } catch (t: Throwable) {
                error = if (t.message.isNullOrBlank()) "Bangumi 搜索失败" else t.message
            }
            val result = candidates ?: emptyList()
            val finalError = error
            postToMain { callback.onResult(result, finalError) }
        }
    }

    /** 保存用户明确选择的 Bangumi 候选。 */
    @JvmStatic
    fun saveSelectedBangumiMetadataAsync(context: Context?, game: Game?, metadata: VnMetadata?, callback: Callback?) {
        if (callback == null) return
        if (context == null || game == null || game.id <= 0 || metadata == null) {
            callback.onResult(false)
            return
        }
        val app = context.applicationContext
        AppExecutors.io().execute {
            var success = false
            try {
                MetadataRepository(app).saveBangumi(game.id, metadata)
                setPreferredMetadataSource(app, game.id, MetadataController.SOURCE_BANGUMI)
                success = true
            } catch (_: Throwable) {
            }
            val finalSuccess = success
            postToMain { callback.onResult(finalSuccess) }
        }
    }

    /**
     * 按可用元数据来源顺序（VNDB、Bangumi、Ymgal）返回游戏的开发商字符串。
     * 无元数据或无开发商时返回空串。
     */
    @JvmStatic
    fun getDeveloperOf(context: Context?, gameId: Long): String {
        if (context == null || gameId <= 0) return ""
        val app = context.applicationContext
        val metaRepo = MetadataRepository(app)
        var meta = metaRepo.getVndb(gameId)
        if (meta?.developer.isNullOrBlank()) {
            meta = metaRepo.getBangumi(gameId)
        }
        if (meta?.developer.isNullOrBlank()) {
            meta = metaRepo.getYmgal(gameId)
        }
        return meta?.developer?.trim() ?: ""
    }

    @JvmStatic
    fun syncCoverToGameAsync(context: Context?, game: Game?, callback: Callback?) {
        if (callback == null) return
        if (context == null || game == null) {
            callback.onResult(false)
            return
        }
        val app = context.applicationContext
        AppExecutors.io().execute {
            var ok = false
            try {
                val metaRepo = MetadataRepository(app)
                // 用户明确绑定的来源优先；旧数据没有偏好记录时，以最近更新的缓存为首选。
                // 首选来源没有封面才回退到其他来源，避免旧 VNDB 缓存覆盖新绑定的 Bangumi 封面。
                var preferredSource = getPreferredMetadataSource(app, game.id)
                if (preferredSource.isEmpty()) {
                    preferredSource = metaRepo.getMostRecentlyUpdatedSource(game.id)
                }
                val meta = findCoverMetadata(metaRepo, game.id, preferredSource)
                if (meta != null && !meta.coverUrl.isNullOrBlank()) {
                    val cover = LauncherCoverBridge.downloadCover(app, meta.coverUrl, "sync_cover_${game.id}")
                    if (cover != null) {
                        val repo = GameRepository(app)
                        val latest = repo.findById(game.id)
                        if (latest != null) {
                            latest.coverUri = cover
                            latest.coverPersistUri = cover
                            latest.coverSourceType = 1
                            if (!meta.chineseTitle.isNullOrEmpty())
                                latest.title = meta.chineseTitle
                            else if (!meta.originalTitle.isNullOrEmpty())
                                latest.title = meta.originalTitle
                            repo.update(latest)
                            ok = true
                        }
                    }
                }
            } catch (_: Throwable) {
            }
            val success = ok
            postToMain { callback.onResult(success) }
        }
    }

    private fun setPreferredMetadataSource(context: Context, gameId: Long, source: String) {
        context.yukiPrefs()
            .edit()
            .putString(MetadataController.KEY_VISIBLE_METADATA_SOURCE_PREFIX + gameId, source)
            .apply()
    }

    private fun getPreferredMetadataSource(context: Context, gameId: Long): String {
        val source = context.yukiPrefs()
            .getString(MetadataController.KEY_VISIBLE_METADATA_SOURCE_PREFIX + gameId, "") ?: ""
        return normalizeMetadataSource(source)
    }

    private fun findCoverMetadata(repository: MetadataRepository, gameId: Long, preferredSource: String): VnMetadata? {
        val sources = arrayOf(
            normalizeMetadataSource(preferredSource),
            MetadataController.SOURCE_VNDB,
            MetadataController.SOURCE_BANGUMI,
            MetadataController.SOURCE_YMGAL
        )
        for (i in sources.indices) {
            val source = sources[i]
            if (source.isEmpty() || appearedEarlier(sources, i, source)) continue
            val metadata = getMetadata(repository, gameId, source)
            if (metadata != null && !metadata.coverUrl.isNullOrBlank()) {
                return metadata
            }
        }
        return null
    }

    private fun appearedEarlier(sources: Array<String>, end: Int, source: String): Boolean {
        for (i in 0 until end) {
            if (source == sources[i]) return true
        }
        return false
    }

    private fun getMetadata(repository: MetadataRepository, gameId: Long, source: String): VnMetadata? = when (source) {
        MetadataController.SOURCE_BANGUMI -> repository.getBangumi(gameId)
        MetadataController.SOURCE_YMGAL -> repository.getYmgal(gameId)
        MetadataController.SOURCE_VNDB -> repository.getVndb(gameId)
        else -> null
    }

    private fun normalizeMetadataSource(source: String?): String = when {
        source == MetadataController.SOURCE_BANGUMI || source == MetadataController.SOURCE_BANGUMI_MIRROR ->
            MetadataController.SOURCE_BANGUMI
        source == MetadataController.SOURCE_YMGAL -> MetadataController.SOURCE_YMGAL
        source == MetadataController.SOURCE_VNDB -> MetadataController.SOURCE_VNDB
        else -> ""
    }
}
