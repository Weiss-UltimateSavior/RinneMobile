package com.apps.game

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.core.R
import com.apps.navigationOverlayBottomPadding
import com.apps.refreshNavigationOverlayInsets
import com.apps.settings.LauncherCustomVndbSearchDialog
import com.apps.settings.LauncherKrkrSettingsActivity
import com.apps.HDModel.LauncherDialogRouter
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.databinding.FragmentLauncherLibraryBinding
import com.core.launcherbridge.LauncherRepositoryBridge
import com.core.model.EngineType
import com.core.model.Game
import com.core.util.AppExecutors
import com.core.util.DevLogger
import com.core.util.RxMainQueue

open class LauncherLibraryFragment : Fragment(),
    GameListController.Listener,
    GameActionMenuFactory.ActionMenuCallbacks,
    GameSyncController.Listener {

    private var _binding: FragmentLauncherLibraryBinding? = null
    private val binding get() = _binding!!

    private val mainQueue = RxMainQueue()

    /** Library 弹窗统一经 LauncherDialogRouter（HD 路由 Pad、竖屏委托 Launcher 工厂）。 */
    private val subDialogFactory =
        GameActionMenuFactory.SubDialogFactory { ctx, title, labels, checked, onChoice ->
            LauncherDialogRouter.showSingleChoice(ctx, title, labels, checked) { index ->
                onChoice.accept(index)
            }
        }

    /** Library 同步对话框工厂：自绘对话框，使用 launcher_dialog_bg 背景，252dp 宽度。 */
    private val syncDialogFactory = object : GameSyncController.DialogFactory {
        override fun showSyncConfirmDialog(onConfirm: Runnable) {
            LauncherDialogRouter.showStandardConfirm(
                requireContext(), getString(R.string.game_library_sync_title),
                getString(R.string.game_library_sync_message),
                getString(R.string.game_library_sync_confirm), onConfirm
            )
        }

        override fun createSyncLoadingDialog(title: String?, hint: String?): AlertDialog {
            return LauncherDialogRouter.showProgressLoading(
                requireContext(),
                title,
                getString(R.string.game_sync_progress, 0, 0),
                hint,
                "sync_progress",
            )
        }

        override fun showSyncResultDialog(synced: Int, failed: Int) {
            val message = getString(R.string.game_library_sync_complete_count, synced) +
                if (failed > 0) "\n" + getString(R.string.game_library_sync_failed_count, failed) else ""
            LauncherDialogRouter.showInfo(requireContext(), getString(R.string.game_sync_complete), message)
        }
    }

    private var sessionController: GameSessionController? = null
    private lateinit var listController: GameListController
    private lateinit var syncController: GameSyncController
    private val categories = mutableListOf<CategoryOption>()
    private val gameDevelopers = mutableMapOf<Long, List<String>>()
    private var adapter: LauncherGameAdapter? = null
    private var gridLayoutManager: GridLayoutManager? = null
    private var selectedCategory: String = ""
    private var searchQuery: String = ""
    // 编辑卡片后回退时，仅就地刷新被编辑的那张卡片，避免 loadGames() 重置分页与滑动位置。
    // protected：HD 子类覆写 startEditGameActivity 时也需设置；经 onSaveInstanceState 保存以
    // 支持 Activity 重建时恢复待刷新卡片（replace + addToBackStack 后根 Fragment 仅 view 重建，
    // Fragment 实例不重建，此标记在重建后仍保留）。
    protected var pendingEditGameId: Long = -1L
    private var posterGridStyle: Boolean = false
    private lateinit var pagingHelper: LibraryPagingHelper
    private lateinit var swipeGesture: LibrarySwipeGesture
    private lateinit var toolbarUi: LibraryToolbarUi

    /** LibraryPagingHelper 等协调类访问器（§8 持有 Fragment 的协调类模式）。 */
    internal val libraryBinding: FragmentLauncherLibraryBinding? get() = _binding
    internal val libraryListController: GameListController get() = listController
    internal val libraryAdapter: LauncherGameAdapter? get() = adapter
    internal var libraryPosterGridStyle: Boolean
        get() = posterGridStyle
        set(value) { posterGridStyle = value }
    internal fun libraryUsesTabletPortraitCardSizing(): Boolean = usesTabletPortraitCardSizing()
    internal fun libraryFixedGridRows(): Int = getFixedGridRows()
    internal fun libraryGridColumns(): Int = getGridColumns()
    internal val libraryPagingHelper: LibraryPagingHelper get() = pagingHelper
    internal val libraryCategories: MutableList<CategoryOption> get() = categories
    internal var librarySelectedCategory: String
        get() = selectedCategory
        set(value) { selectedCategory = value }
    internal fun libraryRenderCategories() = toolbarUi.renderCategories()
    internal fun libraryApplyFilters() = applyFilters()

    /** LibraryToolbarUi 访问器（§8 持有 Fragment 的协调类模式）。 */
    internal val librarySyncController: GameSyncController get() = syncController
    internal val libraryGridManager: GridLayoutManager? get() = gridLayoutManager
    internal val libraryMainQueue: RxMainQueue get() = mainQueue
    internal var librarySearchQuery: String
        get() = searchQuery
        set(value) { searchQuery = value }
    internal fun libraryActiveGridColumns(): Int = getActiveGridColumns()
    internal fun libraryGetPosterStylePreferenceKey(): String = getPosterStylePreferenceKey()
    internal fun libraryUsePortraitLibraryScaler(): Boolean = usePortraitLibraryScaler()
    internal fun libraryAreCategoriesCollapsedByDefault(): Boolean = areCategoriesCollapsedByDefault()
    internal fun libraryConfirmClearList() = confirmClearList()

    companion object {
        private const val TAG = "LauncherLibrary"
        internal const val LIBRARY_PREFS = "launcher_library_preferences"
        private const val KEY_POSTER_GRID_STYLE = "poster_grid_style"
        private const val STATE_PENDING_EDIT_GAME_ID = "pending_edit_game_id"
    }

    /**
     * Configuration hooks used by the landscape game repository. Keeping the shared library
     * implementation here means search, categories, sync and game actions stay identical.
     */
    protected open fun getGridColumns(): Int {
        return LauncherTabletPortraitScaler.libraryGridColumns(resources)
    }

    protected open fun getPosterGridColumns(): Int = 3

    private fun getActiveGridColumns(): Int {
        // 参考样式以三列海报为核心；原横向卡片继续沿用设备自适应列数。
        return if (posterGridStyle) {
            Math.max(1, getPosterGridColumns())
        } else {
            Math.max(1, getGridColumns())
        }
    }

    override fun getPageSize(): Int {
        return LauncherTabletPortraitScaler.libraryPageSize(resources)
    }

    private fun usesTabletPortraitCardSizing(): Boolean {
        return LauncherTabletPortraitScaler.isTabletPortrait(resources)
    }

    protected open fun getFixedGridRows(): Int {
        return 0
    }

    override fun usesHorizontalPaging(): Boolean {
        return false
    }

    protected open fun getLibraryTitle(): String {
        return getString(R.string.game_library_title)
    }

    protected open fun usePortraitLibraryScaler(): Boolean = true

    protected open fun applyLibrarySystemBarInsets(): Boolean = true

    protected open fun createLibraryAdapter(): LauncherGameAdapter = LauncherGameAdapter()

    protected open fun getPosterStylePreferenceKey(): String = KEY_POSTER_GRID_STYLE

    protected open fun areCategoriesCollapsedByDefault(): Boolean = true

    protected fun bindLibraryRoot(root: View) {
        _binding = FragmentLauncherLibraryBinding.bind(root)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentLauncherLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 主容器回退栈重建后恢复待刷新卡片（HD 编辑游戏 replace+popBackStack 场景）。
        pendingEditGameId = savedInstanceState?.getLong(STATE_PENDING_EDIT_GAME_ID, -1L) ?: -1L
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_PENDING_EDIT_GAME_ID, pendingEditGameId)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (usePortraitLibraryScaler()) {
            LauncherTabletPortraitScaler.apply(binding.root)
        }
        if (applyLibrarySystemBarInsets()) {
            applySystemBarInsets()
        }
        LauncherTheme.applyPrimaryTone(binding.root)
        binding.libraryTitle.text = getLibraryTitle()
        posterGridStyle = requireContext().applicationContext
            .getSharedPreferences(LIBRARY_PREFS, Context.MODE_PRIVATE)
            .getBoolean(getPosterStylePreferenceKey(), false)
        toolbarUi = LibraryToolbarUi(this)
        toolbarUi.setup()
        sessionController = GameSessionController(requireContext(), mainQueue, object : GameSessionController.Listener {
            override fun reloadGame(gameId: Long) { reloadSingleGame(gameId) }
            override fun reloadAllGames() { loadGames() }
        })
        syncController = GameSyncController(mainQueue, this, syncDialogFactory)
        listController = GameListController(mainQueue, this)
        pagingHelper = LibraryPagingHelper(this)
        setupRecycler()
        loadGames()
        swipeGesture = LibrarySwipeGesture(this)
        swipeGesture.setup()
    }

    override fun onResume() {
        super.onResume()
        refreshNavigationOverlayInsets()
        checkStoragePermission()
        if (sessionController?.hasActiveSession() == true) {
            sessionController?.finishDirectPlaySessionIfNeeded(this)
        } else if (pendingEditGameId > 0L) {
            // 编辑页返回时仅就地刷新该卡片，保留当前滑动位置与已加载分页。
            val id = pendingEditGameId
            pendingEditGameId = -1L
            reloadSingleGame(id)
        } else if (!listController.isDataLoaded()) {
            loadGames()
        }
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                LauncherDialogRouter.showStandardConfirm(
                    requireContext(),
                    getString(R.string.game_library_permission_title),
                    getString(R.string.game_library_permission_message),
                    getString(R.string.game_library_go)
                ) {
                    openAllFilesAccessSettings()
                }
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            if (requireActivity().checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1001)
            }
        }
    }

    private fun openAllFilesAccessSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + requireContext().packageName)
                )
            )
        } catch (first: Exception) {
            DevLogger.w(TAG, "app-specific all files settings unavailable; falling back", first)
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (second: Exception) {
                DevLogger.w(TAG, "all files settings unavailable", second)
            }
        }
    }

    override fun onDestroyView() {
        sessionController?.cleanup()
        if (::syncController.isInitialized) syncController.cleanup()
        if (::listController.isInitialized) listController.cleanup()
        if (::toolbarUi.isInitialized) toolbarUi.cleanup()
        if (::swipeGesture.isInitialized) swipeGesture.cleanup()
        if (_binding != null) {
            binding.root.setOnApplyWindowInsetsListener(null)
            binding.libraryRecycler.adapter = null
        }
        super.onDestroyView()
        _binding = null
        adapter = null
    }

    private fun applySystemBarInsets() {
        val currentBinding = _binding ?: return
        val originalLeft = currentBinding.libraryContent.paddingLeft
        val originalTop = currentBinding.libraryContent.paddingTop
        val originalRight = currentBinding.libraryContent.paddingRight
        val originalBottom = currentBinding.libraryContent.paddingBottom

        currentBinding.root.setOnApplyWindowInsetsListener { _, insets ->
            currentBinding.libraryContent.setPadding(
                originalLeft,
                originalTop + insets.systemWindowInsetTop,
                originalRight,
                originalBottom
            )
            applyNavigationOverlayPadding()
            insets
        }
        currentBinding.root.requestApplyInsets()
    }

    private fun setupRecycler() {
        val gameAdapter = createLibraryAdapter()
        gameAdapter.setPosterStyle(posterGridStyle)
        gameAdapter.setOnGameCardListener(object : LauncherGameAdapter.OnGameCardListener {
            override fun onGameClick(game: Game?) {
                if (swipeGesture.consumeSwipe()) return
                if (game != null) {
                    gameAdapter.setSelectedGameId(game.id)
                    confirmLaunchGame(game)
                }
            }

            override fun onGameLongClick(game: Game?) {
                if (swipeGesture.consumeSwipe()) return
                if (game != null) showGameActionMenu(game)
            }
        })
        adapter = gameAdapter

        val layoutManager = GridLayoutManager(requireContext(), getActiveGridColumns())
        gridLayoutManager = layoutManager
        binding.libraryRecycler.layoutManager = layoutManager
        binding.libraryRecycler.adapter = gameAdapter
        binding.libraryRecycler.setHasFixedSize(true)
        applyNavigationOverlayPadding()
        binding.libraryRecycler.setItemViewCacheSize(20)
        val pool = RecyclerView.RecycledViewPool()
        pool.setMaxRecycledViews(0, 30)
        binding.libraryRecycler.setRecycledViewPool(pool)
        if (usesHorizontalPaging()) {
            // The floating landscape navigation occupies the bottom of the Fragment. Reserve its
            // height so the fourth card row is never obscured, then size all four rows from the
            // actual remaining viewport (rather than assuming a particular screen density).
            binding.libraryRecycler.setPadding(
                binding.libraryRecycler.paddingLeft,
                binding.libraryRecycler.paddingTop,
                binding.libraryRecycler.paddingRight,
                LauncherTheme.dp(requireContext(), 72)
            )
            binding.libraryRecycler.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) pagingHelper.updateFixedGridCardHeight()
            }
            binding.libraryRecycler.post { pagingHelper.updateFixedGridCardHeight() }
        } else if (usesTabletPortraitCardSizing()) {
            // 平板竖屏增加列数后，根据每列实际宽度重新计算 5:3 卡片比例。
            // 这样不会继续沿用手机写死高度，也不会影响手机竖屏。
            binding.libraryRecycler.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left != oldRight - oldLeft) pagingHelper.updateTabletPortraitCardHeight()
            }
            binding.libraryRecycler.post { pagingHelper.updateTabletPortraitCardHeight() }
        }
        binding.libraryRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (usesHorizontalPaging() || dy <= 0 || listController.isLoading() || listController.isFullyLoaded()) return
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                if (lastVisible >= Math.max(0, listController.getVisibleGames().size - getActiveGridColumns())) {
                    pagingHelper.loadNextPage()
                }
            }
        })

        // 当分类收起后，第一页可能铺不满屏幕，RecyclerView 没有滚动距离，onScrolled 不会触发。
        // 这里单独监听“向上拉”的手势，每次手势最多加载一页，避免一次性加载全部。
        binding.libraryRecycler.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                pagingHelper.handleLoadMoreDragWhenNotScrollable(rv, e)
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                pagingHelper.handleLoadMoreDragWhenNotScrollable(rv, e)
            }

            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) { }
        })
    }

    private fun applyNavigationOverlayPadding() {
        if (usesHorizontalPaging()) return
        val currentBinding = _binding ?: return
        val fallback = resources.getDimensionPixelSize(com.core.R.dimen.launcher_library_recycler_bottom_padding)
        val bottomPadding = navigationOverlayBottomPadding(fallback)
        currentBinding.libraryRecycler.setPadding(
            currentBinding.libraryRecycler.paddingLeft,
            currentBinding.libraryRecycler.paddingTop,
            currentBinding.libraryRecycler.paddingRight,
            bottomPadding,
        )
    }

    private fun loadGames() {
        listController.loadGames()
    }

    private fun applyFilters() {
        listController.applyFilters()
    }

    private fun applyFilters(forceFullRefresh: Boolean) {
        listController.applyFilters(forceFullRefresh)
    }

    /**
     * Updates a single game in-place without reloading the entire list, preserving scroll position.
     * Used by long-press dialog actions (status, play time, favorite, cover sync, metadata rematch).
     * DiffUtil detects only the changed card and dispatches a single notifyItemChanged.
     */
    private fun updateSingleGame(updated: Game) {
        listController.updateSingleGame(updated)
    }

    /** Removes a single game by id without reloading the entire list, preserving scroll position. */
    private fun removeSingleGame(gameId: Long) {
        listController.removeSingleGame(gameId)
    }

    /** Re-fetches a single game from DB and updates it in-place, for async metadata operations. */
    override fun reloadSingleGame(gameId: Long) {
        listController.reloadSingleGame(this, gameId)
    }

    private fun confirmLaunchGame(game: Game?) {
        if (game == null) return
        LauncherDialogRouter.showConfirm(
            requireContext(),
            getString(R.string.game_launch_title),
            getString(R.string.game_launch_message, GameMetadataFormatter.safeTitle(requireContext(), game)),
            getString(R.string.core_confirm),
        ) {
            GamePasswordLock.interceptLaunch(this@LauncherLibraryFragment, game) {
                sessionController?.launchGameDirectly(this@LauncherLibraryFragment, game)
            }
        }
    }

    private fun showGameActionMenu(game: Game?) {
        if (game == null) return
        val config = GameActionMenuFactory.ActionMenuConfig()
        // Library 默认包含编辑/收藏/密码，宽度 252dp（与原实现一致）
        GameActionMenuFactory.showGameActionMenu(this, game, config, this)
    }

    // ===== GameActionMenuFactory.ActionMenuCallbacks =====

    override fun onShowGameDetail(game: Game) {
        GameActionMenuFactory.showGameDetailDialog(this, game)
    }

    override fun onEditGame(game: Game) {
        startEditGameActivity(game)
    }

    override fun onShowPlayStatus(game: Game) {
        GameActionMenuFactory.showPlayStatusDialog(this, game, subDialogFactory) { updateSingleGame(it) }
    }

    override fun onToggleFavorite(game: Game) {
        toggleFavorite(game)
    }

    override fun onTogglePassword(game: Game) {
        if (GamePasswordLock.hasPassword(game)) {
            GamePasswordLock.clearPassword(this, game, null)
        } else {
            GamePasswordLock.setPassword(this, game, null)
        }
    }

    override fun onShowMoreOptions(game: Game) {
        showMoreOptionsDialog(game)
    }

    private fun showMoreOptionsDialog(game: Game?) {
        if (game == null) return
        val options = mutableListOf<Array<String>>()
        options.add(arrayOf(getString(R.string.game_action_pin_shortcut), "pin_shortcut"))
        options.add(arrayOf(getString(R.string.game_action_rematch_vndb), "rematch"))
        options.add(arrayOf(getString(R.string.game_action_custom_vndb), "custom_vndb"))
        options.add(arrayOf(getString(R.string.game_action_sync_cover), "sync"))
        // ONS/KRKR/Artemis 引擎游戏支持单独配置引擎参数（版本/独立存档/编码等）
        if (game.engine == EngineType.ONS || game.engine == EngineType.KIRIKIRI || game.engine == EngineType.ARTEMIS) {
            options.add(arrayOf(getString(R.string.game_action_engine_settings), "engine_settings"))
        }
        options.add(arrayOf(getString(R.string.game_action_delete), "delete"))
        val deleteIndex = options.indexOfFirst { it[1] == "delete" }
        LauncherDialogRouter.showStandardActionChoices(
            requireContext(),
            getString(R.string.game_action_more),
            options.map { it[0] as CharSequence }.toTypedArray(),
            deleteIndex
        ) { index ->
            when (options[index][1]) {
                "pin_shortcut" -> PinnedGameShortcut.requestPinShortcut(requireContext(), game)
                "rematch" -> syncController.rematchMetadata(game)
                "custom_vndb" -> LauncherCustomVndbSearchDialog.show(this, game) { reloadSingleGame(game.id) }
                "sync" -> syncController.syncMetadataToCard(game)
                "engine_settings" -> openEngineSettings(game)
                "delete" -> confirmDeleteGame(game)
            }
        }
    }

    protected open fun openEngineSettings(game: Game) {
        try {
            val intent = Intent(requireContext(), LauncherKrkrSettingsActivity::class.java)
            intent.putExtra(LauncherKrkrSettingsActivity.EXTRA_GAME_ID, game.id)
            startActivity(intent)
        } catch (error: Exception) {
            DevLogger.w(TAG, "Failed to open ONS game settings", error)
            Toast.makeText(requireContext(), R.string.game_action_engine_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleFavorite(game: Game) {
        val app = requireContext().applicationContext
        AppExecutors.runOnSingle {
            var updated: Game? = null
            try {
                val latest = LauncherRepositoryBridge.findGameById(app, game.id)
                if (latest != null) {
                    latest.favorite = !latest.favorite
                    if (LauncherRepositoryBridge.updateGame(app, latest) > 0) {
                        updated = latest
                    }
                }
            } catch (e: Exception) {
                DevLogger.w(TAG, "Failed to toggle favorite", e)
            }
            val result = updated
            mainQueue.post {
                if (!isAdded || _binding == null) return@post
                if (result != null) updateSingleGame(result)
            }
        }
    }

    private fun confirmDeleteGame(game: Game) {
        LauncherDialogRouter.showDangerConfirm(
            requireContext(),
            getString(R.string.game_action_delete),
            getString(R.string.game_delete_message,
                GameMetadataFormatter.safeTitle(requireContext(), game)),
            getString(R.string.game_common_remove)
        ) { deleteGame(game) }
    }

    private fun deleteGame(game: Game) {
        // 在主线程捕获 ApplicationContext，避免 IO 线程内调用 fragment.requireContext()
        val app = requireContext().applicationContext
        AppExecutors.runOnSingle {
            val deleted = try {
                LauncherRepositoryBridge.deleteGame(app, game.id) > 0
            } catch (e: Exception) {
                DevLogger.w(TAG, "Failed to delete game", e)
                false
            }
            mainQueue.post {
                if (!isAdded || _binding == null) return@post
                if (!deleted) {
                    Toast.makeText(app, R.string.game_library_delete_retry, Toast.LENGTH_SHORT).show()
                    return@post
                }
                removeSingleGame(game.id)
                Toast.makeText(app, R.string.game_deleted, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmClearList() {
        LauncherDialogRouter.showDangerConfirm(
            requireContext(),
            getString(R.string.game_library_clear),
            getString(R.string.game_library_clear_message),
            getString(R.string.game_library_clear_action)
        ) { clearList() }
    }

    private fun clearList() {
        // 在主线程捕获 ApplicationContext，避免 IO 线程内调用 fragment.requireContext()
        val app = requireContext().applicationContext
        AppExecutors.runOnSingle {
            val deleted = try {
                LauncherRepositoryBridge.deleteAllGames(app)
            } catch (e: Exception) {
                DevLogger.w(TAG, "Failed to clear game list", e)
                -1
            }
            mainQueue.post {
                if (!isAdded || _binding == null) return@post
                if (deleted < 0) {
                    Toast.makeText(app, R.string.game_library_clear_failed, Toast.LENGTH_SHORT).show()
                    return@post
                }
                // 清空内存中的列表、分类、开发商缓存，并重置 controller 状态。
                listController.clearAllGames()
                listController.setDataLoaded(true)
                categories.clear()
                gameDevelopers.clear()
                selectedCategory = ""
                toolbarUi.renderCategories()
                applyFilters(true)
                Toast.makeText(app, R.string.game_library_cleared, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ===== GameSyncController.Listener =====

    override fun onBatchSyncComplete(loadedGames: List<Game>, categoryResult: CategoryBuildResult) {
        if (!isAdded || _binding == null) return
        listController.replaceAllGames(loadedGames)

        gameDevelopers.clear()
        gameDevelopers.putAll(categoryResult.developers)

        categories.clear()
        categories.addAll(categoryResult.categories)

        if (selectedCategory.isNotEmpty()
            && !GameCategoryBuilder.containsCategoryValue(categories, selectedCategory)
        ) {
            selectedCategory = ""
        }

        toolbarUi.renderCategories()
        listController.setDataLoaded(true)

        // controller 已持有最新数据，applyFilters(true) 会强制全量刷新卡片
        applyFilters(true)
    }

    protected open fun startEditGameActivity(game: Game) {
        pendingEditGameId = game.id
        val intent = Intent(requireContext(), LauncherGameEditActivity::class.java)
        intent.putExtra(LauncherGameEditActivity.EXTRA_GAME_ID, game.id)
        startActivity(intent)
    }

    // ===== GameListController.Listener =====
    override fun getAppContext(): Context {
        return requireContext().applicationContext
    }

    override fun isBindingNull(): Boolean {
        return _binding == null
    }

    override fun getSearchQuery(): String {
        return searchQuery
    }

    override fun getSelectedCategory(): String {
        return selectedCategory
    }

    override fun getGameDevelopers(): Map<Long, List<String>> {
        return gameDevelopers
    }

    override fun onDataLoaded(
        categories: List<CategoryOption>,
        developers: Map<Long, List<String>>
    ) {
        gameDevelopers.clear()
        gameDevelopers.putAll(developers)
        this.categories.clear()
        this.categories.addAll(categories)
        if (selectedCategory.isNotEmpty()
            && !GameCategoryBuilder.containsCategoryValue(this.categories, selectedCategory)
        ) {
            selectedCategory = ""
        }
        toolbarUi.renderCategories()
    }

    override fun onVisibleGamesChanged(forceFullRefresh: Boolean) {
        adapter?.submit(ArrayList(listController.getVisibleGames()), forceFullRefresh)
    }

    override fun onRenderStateRequested() {
        pagingHelper.renderState()
    }
}
