package com.apps.game

import android.content.Context
import android.util.Log
import androidx.fragment.app.Fragment
import com.core.launcherbridge.LauncherRepositoryBridge
import com.core.model.Game
import com.core.util.AppExecutors
import com.core.util.RxMainQueue
import java.text.Collator
import java.util.ArrayList
import java.util.Collections
import java.util.Locale

/**
 * 持有 allGames / filteredGames / visibleGames 三个列表，统一管理 Library 与 Pad 两个 Fragment
 * 的加载、过滤、分页、单卡刷新逻辑。
 *
 * <p>Fragment 仅负责 ViewBinding、RecyclerView 初始化以及通过 [Listener] 回调
 * 把 Adapter 与 Controller 的事件对接。所有列表状态由本类独占。</p>
 */
class GameListController(
    private val mainQueue: RxMainQueue,
    private val listener: Listener
) {

    /** 宿主 Fragment 注入的钩子。 */
    interface Listener {
        /** 应用级 Context，用于后台 IO。 */
        fun getAppContext(): Context

        /** Fragment 是否已销毁视图（binding == null）。 */
        fun isBindingNull(): Boolean

        /** 当前搜索关键字（Fragment 持有，用于工具栏 UI）。 */
        fun getSearchQuery(): String

        /** 当前选中的分类（Fragment 持有，用于工具栏 UI）。 */
        fun getSelectedCategory(): String

        /** 当前开发商映射（Fragment 持有，由 [onDataLoaded] 更新）。 */
        fun getGameDevelopers(): Map<Long, List<String>>

        /** 分页大小。 */
        fun getPageSize(): Int

        /** 是否启用横向分页布局。 */
        fun usesHorizontalPaging(): Boolean

        /**
         * 数据加载完成后回调，Fragment 应更新自身的 categories/gameDevelopers 集合并调用
         * renderCategories()。
         */
        fun onDataLoaded(categories: List<CategoryOption>, developers: Map<Long, List<String>>)

        /**
         * 可见列表变化时回调，Fragment 应调用
         * `adapter.submit(new ArrayList<>(controller.getVisibleGames()), forceFullRefresh)`。
         */
        fun onVisibleGamesChanged(forceFullRefresh: Boolean)

        /**
         * 视图状态需要刷新时回调（可见性、空态文案、卡片高度、视口填充检测）。
         */
        fun onRenderStateRequested()
    }

    private companion object {
        const val TAG = "GameListController"
    }

    private val libraryState = GameLibraryState()
    private val allGames = ArrayList<Game>()

    // volatile：在 IO 线程的 loadGames/reloadSingleGame 任务中检查，确保 cleanup() 后能立即观察到
    @Volatile
    private var disposed = false
    private var loading = false
    private var fullyLoaded = false
    private var dataLoaded = false
    private var viewportFillCheckPending = false

    /**
     * Fragment 视图销毁时调用，标记 Controller 已废弃。后续 IO 回调将不再更新状态或 UI，
     * 避免无意义的 DB 查询和 mainQueue.post 浪费。
     *
     * <p>注意：与 [GameSessionController.cleanup()] 和 [GameSyncController.cleanup()]
     * 保持一致的生命周期契约，由 Fragment 在 onDestroyView 中调用。</p>
     */
    fun cleanup() {
        disposed = true
    }

    fun getAllGames(): List<Game> = Collections.unmodifiableList(allGames)
    fun getFilteredGames(): List<Game> = libraryState.getFiltered()
    fun getVisibleGames(): List<Game> = libraryState.getVisible()
    fun isLoading(): Boolean = loading
    fun isFullyLoaded(): Boolean = fullyLoaded
    fun isDataLoaded(): Boolean = dataLoaded
    fun isViewportFillCheckPending(): Boolean = viewportFillCheckPending

    fun setLoading(value: Boolean) { loading = value }
    fun setViewportFillCheckPending(value: Boolean) { viewportFillCheckPending = value }
    fun setDataLoaded(value: Boolean) { dataLoaded = value }

    /** 整体替换 allGames（同步 libraryState）。供同步逻辑使用。 */
    fun replaceAllGames(games: List<Game>?) {
        allGames.clear()
        if (games != null) allGames.addAll(games)
        libraryState.replaceAll(games)
    }

    /** 清空 allGames（同步 libraryState）。 */
    fun clearAllGames() {
        allGames.clear()
        libraryState.replaceAll(null)
    }

    /** 加载全部游戏并构建分类。 */
    fun loadGames() {
        setLoading(true)
        val appContext = listener.getAppContext()
        AppExecutors.runOnSingle {
            if (disposed) return@runOnSingle
            var games: List<Game> = emptyList()
            var developers: Map<Long, List<String>> = emptyMap()
            var builtCategories: List<CategoryOption> = emptyList()
            try {
                games = LauncherRepositoryBridge.getAllGames(appContext)
            } catch (error: Error) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Failed to load games", error)
                games = emptyList()
            }
            if (disposed) return@runOnSingle
            // 在后台线程构建分类（含元数据查询），避免主线程卡顿
            try {
                val result = GameCategoryBuilder.build(appContext, games)
                developers = result.developers
                builtCategories = result.categories
            } catch (error: Error) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Failed to build game categories", error)
                developers = emptyMap()
                builtCategories = emptyList()
            }
            val loadedGames = games
            val loadedDevelopers = developers
            val loadedCategories = builtCategories
            mainQueue.post {
                if (disposed || listener.isBindingNull()) {return@post
                }
                allGames.clear()
                allGames.addAll(loadedGames)
                libraryState.replaceAll(loadedGames)
                listener.onDataLoaded(loadedCategories, loadedDevelopers)
                setDataLoaded(true)
                // 后台数据已经加载完成，必须先解除 loading 状态。
                // 否则 RecyclerView 的滚动监听和上拉手势都会被 loading 条件拦截。
                setLoading(false)
                applyFilters()
            }
        }
    }

    fun applyFilters() {
        applyFilters(false)
    }

    fun applyFilters(forceFullRefresh: Boolean) {
        libraryState.setQuery(listener.getSearchQuery())
        libraryState.setCategory(listener.getSelectedCategory())
        libraryState.rebuild(::matchGame, ::compareGames,
            listener.getPageSize(), listener.usesHorizontalPaging())
        fullyLoaded = libraryState.isFullyLoaded()
        if (listener.usesHorizontalPaging()) {
            renderPagedGrid(forceFullRefresh)
        } else {
            listener.onVisibleGamesChanged(forceFullRefresh)
        }
        listener.onRenderStateRequested()
    }

    fun renderPagedGrid(forceFullRefresh: Boolean) {
        libraryState.renderPage(listener.getPageSize())
        fullyLoaded = libraryState.isFullyLoaded()
        listener.onVisibleGamesChanged(forceFullRefresh)
    }

    fun showNextPage(): Boolean {
        if (!listener.usesHorizontalPaging() || loading) return false
        if (!libraryState.nextPage(listener.getPageSize())) return false
        fullyLoaded = libraryState.isFullyLoaded()
        listener.onVisibleGamesChanged(false)
        listener.onRenderStateRequested()
        return true
    }

    fun showPreviousPage(): Boolean {
        if (!listener.usesHorizontalPaging() || loading
            || !libraryState.previousPage(listener.getPageSize())) return false
        fullyLoaded = libraryState.isFullyLoaded()
        listener.onVisibleGamesChanged(false)
        listener.onRenderStateRequested()
        return true
    }

    fun loadNextPage() {
        loadNextPage(false)
    }

    fun loadNextPage(forceFullRefresh: Boolean) {
        if (listener.isBindingNull()) return
        if (loading && !libraryState.getVisible().isEmpty()) return
        loading = true
        libraryState.loadNext(listener.getPageSize())
        fullyLoaded = libraryState.isFullyLoaded()
        listener.onVisibleGamesChanged(forceFullRefresh)
        loading = false
        listener.onRenderStateRequested()
    }

    /**
     * 就地刷新单张卡片，避免 loadGames() 重置分页与滑动位置。
     * 用于长按菜单（状态、游玩时长、收藏、封面同步、元数据 rematch）等异步操作。
     */
    fun updateSingleGame(updated: Game?) {
        if (updated == null || listener.isBindingNull()) return
        for (i in allGames.indices) {
            val g = allGames[i]
            if (g.id == updated.id) {
                allGames[i] = updated
                break
            }
        }
        libraryState.updateGame(updated, ::matchGame)
        fullyLoaded = libraryState.isFullyLoaded()
        listener.onVisibleGamesChanged(false)
        listener.onRenderStateRequested()
    }

    /** 按 id 移除单张卡片，保留滑动位置。 */
    fun removeSingleGame(gameId: Long) {
        if (listener.isBindingNull()) return
        for (i in allGames.indices) {
            val g = allGames[i]
            if (g.id == gameId) {
                allGames.removeAt(i)
                break
            }
        }
        libraryState.removeGame(gameId)
        fullyLoaded = libraryState.isFullyLoaded()
        listener.onVisibleGamesChanged(false)
        listener.onRenderStateRequested()
    }

    /**
     * 异步从 DB 重新拉取单张卡片，用于封面/元数据等异步操作完成后回填。
     *
     * <p>使用 [Listener.getAppContext()] 而非 {@code fragment.requireContext()}，
     * 避免在 IO 线程持有 Fragment 引用导致 detach 后 IllegalStateException 被静默吞掉。</p>
     */
    fun reloadSingleGame(fragment: Fragment, gameId: Long) {
        val appContext = listener.getAppContext()
        AppExecutors.io().execute {
            if (disposed) return@execute
            var updated: Game? = null
            try {
                updated = LauncherRepositoryBridge.findGameById(appContext, gameId)
            } catch (e: Exception) {
                // DB 查询失败兜底，避免单卡刷新影响主流程
                Log.w(TAG, "reloadSingleGame failed", e)
            }
            val result = updated
            mainQueue.post {
                if (disposed || listener.isBindingNull() || result == null) return@post
                updateSingleGame(result)
            }
        }
    }

    private fun matchGame(game: Game, query: String, category: String): Boolean {
        val normalized = query.trim().lowercase(Locale.ROOT)
        return (normalized.isEmpty()
                || GameMetadataFormatter.safeTitle(game).lowercase(Locale.ROOT).contains(normalized)
                || game.originalTitle?.lowercase(Locale.ROOT)?.contains(normalized) == true
                || game.tags?.lowercase(Locale.ROOT)?.contains(normalized) == true)
                && (category.trim().isEmpty()
                || GameCategoryBuilder.matches(game, category, listener.getGameDevelopers()))
    }

    private fun compareGames(left: Game, right: Game): Int {
        return Collator.getInstance(Locale.CHINA)
            .compare(GameMetadataFormatter.safeTitle(left), GameMetadataFormatter.safeTitle(right))
    }
}
