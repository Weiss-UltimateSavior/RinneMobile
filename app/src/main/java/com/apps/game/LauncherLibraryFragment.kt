package com.apps.game

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.core.R
import com.apps.navigationOverlayBottomPadding
import com.apps.refreshNavigationOverlayInsets
import com.apps.settings.LauncherCustomVndbSearchDialog
import com.apps.settings.LauncherKrkrSettingsActivity
import com.apps.theme.LauncherDialogFactory
import com.apps.theme.LauncherMotion
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.databinding.FragmentLauncherLibraryBinding
import com.core.launcherbridge.LauncherRepositoryBridge
import com.core.model.EngineType
import com.core.model.Game
import com.core.util.AppExecutors
import com.core.util.RxMainQueue
import kotlin.math.abs

open class LauncherLibraryFragment : Fragment(),
    GameListController.Listener,
    GameActionMenuFactory.ActionMenuCallbacks,
    GameSyncController.Listener {

    private var _binding: FragmentLauncherLibraryBinding? = null
    private val binding get() = _binding!!

    private val mainQueue = RxMainQueue()

    /** Library 偏好使用 LauncherDialogFactory 的单选实现。 */
    private val subDialogFactory =
        GameActionMenuFactory.SubDialogFactory { ctx, title, labels, checked, onChoice ->
            LauncherDialogFactory.showSingleChoice(ctx, title, labels, checked) { index ->
                onChoice.accept(index)
            }
        }

    /** Library 同步对话框工厂：自绘对话框，使用 launcher_dialog_bg 背景，252dp 宽度。 */
    private val syncDialogFactory = object : GameSyncController.DialogFactory {
        override fun showSyncConfirmDialog(onConfirm: Runnable) {
            LauncherDialogFactory.showStandardConfirm(
                requireContext(), getString(R.string.game_library_sync_title),
                getString(R.string.game_library_sync_message),
                getString(R.string.game_library_sync_confirm), onConfirm
            )
        }

        override fun createSyncLoadingDialog(title: String?, hint: String?): AlertDialog {
            return LauncherDialogFactory.showProgressLoading(
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
            LauncherDialogFactory.showInfo(requireContext(), getString(R.string.game_sync_complete), message)
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
    private var categoriesCollapsed: Boolean = true
    // 编辑卡片后回退时，仅就地刷新被编辑的那张卡片，避免 loadGames() 重置分页与滑动位置。
    private var pendingEditGameId: Long = -1L
    private var searchDebounce: Runnable? = null
    private var swipeGestureDetector: GestureDetector? = null
    private var swipeConsumed: Boolean = false
    private var loadMoreDragStartY: Float = 0f
    private var loadMoreDragCandidate: Boolean = false
    private var posterGridStyle: Boolean = false

    companion object {
        private const val TAG = "LauncherLibrary"
        private const val LIBRARY_PREFS = "launcher_library_preferences"
        private const val KEY_POSTER_GRID_STYLE = "poster_grid_style"
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
        categoriesCollapsed = areCategoriesCollapsedByDefault()
        binding.libraryCategoryScroll.visibility =
            if (categoriesCollapsed) View.GONE else View.VISIBLE
        setupSearchAndCategories()
        sessionController = GameSessionController(requireContext(), mainQueue, object : GameSessionController.Listener {
            override fun reloadGame(gameId: Long) { reloadSingleGame(gameId) }
            override fun reloadAllGames() { loadGames() }
        })
        syncController = GameSyncController(mainQueue, this, syncDialogFactory)
        listController = GameListController(mainQueue, this)
        setupRecycler()
        loadGames()
        setupSwipeGesture()
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
        } else if (!listController.isDataLoaded) {
            loadGames()
        }
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                LauncherDialogFactory.showStandardConfirm(
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
            Log.w(TAG, "app-specific all files settings unavailable; falling back", first)
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (second: Exception) {
                Log.w(TAG, "all files settings unavailable", second)
            }
        }
    }

    override fun onDestroyView() {
        sessionController?.cleanup()
        if (::syncController.isInitialized) syncController.cleanup()
        if (::listController.isInitialized) listController.cleanup()
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
                if (swipeConsumed) {
                    swipeConsumed = false
                    return
                }
                if (game != null) {
                    gameAdapter.setSelectedGameId(game.id)
                    confirmLaunchGame(game)
                }
            }

            override fun onGameLongClick(game: Game?) {
                if (swipeConsumed) {
                    swipeConsumed = false
                    return
                }
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
                if (bottom - top != oldBottom - oldTop) updateFixedGridCardHeight()
            }
            binding.libraryRecycler.post { updateFixedGridCardHeight() }
        } else if (usesTabletPortraitCardSizing()) {
            // 平板竖屏增加列数后，根据每列实际宽度重新计算 5:3 卡片比例。
            // 这样不会继续沿用手机写死高度，也不会影响手机竖屏。
            binding.libraryRecycler.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left != oldRight - oldLeft) updateTabletPortraitCardHeight()
            }
            binding.libraryRecycler.post { updateTabletPortraitCardHeight() }
        }
        binding.libraryRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (usesHorizontalPaging() || dy <= 0 || listController.isLoading || listController.isFullyLoaded) return
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                if (lastVisible >= Math.max(0, listController.getVisibleGames().size - getActiveGridColumns())) {
                    loadNextPage()
                }
            }
        })

        // 当分类收起后，第一页可能铺不满屏幕，RecyclerView 没有滚动距离，onScrolled 不会触发。
        // 这里单独监听“向上拉”的手势，每次手势最多加载一页，避免一次性加载全部。
        binding.libraryRecycler.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                handleLoadMoreDragWhenNotScrollable(rv, e)
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                handleLoadMoreDragWhenNotScrollable(rv, e)
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

    private fun updateFixedGridCardHeight() {
        if (posterGridStyle) return
        val currentBinding = _binding ?: return
        val currentAdapter = adapter ?: return
        val rows = getFixedGridRows()
        val height = currentBinding.libraryRecycler.height
        if (rows <= 0 || height <= 0) return
        val usableHeight = height
            - currentBinding.libraryRecycler.paddingTop
            - currentBinding.libraryRecycler.paddingBottom
        // item_launcher_game_card contributes 5dp top + 5dp bottom margins per row.
        currentAdapter.setFixedCardHeight(Math.max(LauncherTheme.dp(requireContext(), 34), usableHeight / rows - LauncherTheme.dp(requireContext(), 10)))
    }

    /**
     * 平板竖屏卡片按列宽保持原来的高:宽 = 5:3。
     * item_launcher_game_card 每张卡片左右各有约 5dp margin。
     */
    private fun updateTabletPortraitCardHeight() {
        if (posterGridStyle || !usesTabletPortraitCardSizing()) return
        val currentBinding = _binding ?: return
        val currentAdapter = adapter ?: return

        val recyclerView = currentBinding.libraryRecycler
        val recyclerWidth = recyclerView.width
        val columns = Math.max(1, getGridColumns())
        if (recyclerWidth <= 0) return

        val usableWidth = recyclerWidth
            - recyclerView.paddingLeft
            - recyclerView.paddingRight
        val totalHorizontalMargins = LauncherTheme.dp(requireContext(), 10) * columns
        val cardWidth = Math.max(1, (usableWidth - totalHorizontalMargins) / columns)
        val cardHeight = Math.round(cardWidth * 5f / 3f)
        currentAdapter.setFixedCardHeight(Math.max(LauncherTheme.dp(requireContext(), 34), cardHeight))
    }

    private fun setupSwipeGesture() {
        swipeGestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            private val swipeThreshold = 80
            private val swipeVelocity = 200

            override fun onDown(event: MotionEvent): Boolean {
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                if (abs(diffX) > abs(diffY) && abs(diffX) > swipeThreshold && abs(velocityX) > swipeVelocity) {
                    val handled = if (diffX < 0) handleSwipeLeft() else handleSwipeRight()
                    if (handled) swipeConsumed = true
                    return handled
                }
                return false
            }
        })

        // RecyclerView 区域：通过 OnItemTouchListener 获取触摸事件
        binding.libraryRecycler.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                swipeGestureDetector!!.onTouchEvent(e)
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                swipeGestureDetector!!.onTouchEvent(e)
            }

            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })

        // 非列表区域（背景、分类栏、空提示等）
        binding.root.setOnTouchListener { _, event ->
            swipeGestureDetector!!.onTouchEvent(event)
            false
        }
        binding.libraryContent.setOnTouchListener { _, event ->
            swipeGestureDetector!!.onTouchEvent(event)
            false
        }
        binding.libraryEmpty.setOnTouchListener { _, event ->
            swipeGestureDetector!!.onTouchEvent(event)
            false
        }
    }

    private fun handleSwipeLeft(): Boolean {
        if (usesHorizontalPaging()) return showNextPage()
        return switchToNextCategory()
    }

    private fun handleSwipeRight(): Boolean {
        if (usesHorizontalPaging()) return showPreviousPage()
        return switchToPreviousCategory()
    }

    private fun getFlatCategories(): List<CategoryOption> {
        val flat = mutableListOf<CategoryOption>()
        flat.add(CategoryOption(getString(R.string.game_common_all), ""))
        flat.addAll(categories)
        return flat
    }

    private fun getCurrentCategoryIndex(): Int {
        val flat = getFlatCategories()
        for (i in flat.indices) {
            if (flat[i].value == selectedCategory) return i
        }
        return 0
    }

    private fun switchToNextCategory(): Boolean {
        val flat = getFlatCategories()
        val idx = getCurrentCategoryIndex()
        if (idx < flat.size - 1) {
            selectedCategory = flat[idx + 1].value
            renderCategories()
            applyFilters()
            animateCategorySwitch()
            return true
        }
        return false
    }

    private fun switchToPreviousCategory(): Boolean {
        val flat = getFlatCategories()
        val idx = getCurrentCategoryIndex()
        if (idx > 0) {
            selectedCategory = flat[idx - 1].value
            renderCategories()
            applyFilters()
            animateCategorySwitch()
            return true
        }
        return false
    }

    private fun animateCategorySwitch() {
        val currentBinding = _binding ?: return
        // 滚动分类栏到当前选中项
        val categoryScroll: HorizontalScrollView = currentBinding.libraryCategoryScroll
        for (i in 0 until currentBinding.libraryCategoryRow.childCount) {
            val child = currentBinding.libraryCategoryRow.getChildAt(i)
            if (child is TextView) {
                val tag = child.tag
                val catValue = tag?.toString() ?: ""
                if (catValue == selectedCategory) {
                    val scrollX = child.left - categoryScroll.width / 2 + child.width / 2
                    categoryScroll.smoothScrollTo(scrollX, 0)
                    break
                }
            }
        }
        // 列表淡入动画
        currentBinding.libraryRecycler.alpha = 0.7f
        currentBinding.libraryRecycler.animate().alpha(1f).setDuration(250)
            .setInterpolator(AccelerateDecelerateInterpolator()).start()
    }

    private fun setupSearchAndCategories() {
        binding.librarySearchButton.setOnClickListener {
            val show = binding.librarySearchInput.visibility != View.VISIBLE
            binding.librarySearchInput.visibility = if (show) View.VISIBLE else View.GONE
            if (show) {
                binding.librarySearchInput.requestFocus()
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(binding.librarySearchInput, InputMethodManager.SHOW_IMPLICIT)
            } else {
                binding.librarySearchInput.setText("")
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(binding.librarySearchInput.windowToken, 0)
            }
            renderToolbarButtonState()
        }
        binding.librarySyncButton.setOnClickListener { showLibrarySettingsMenu() }
        binding.libraryCollapseButton.setOnClickListener {
            categoriesCollapsed = !categoriesCollapsed
            binding.libraryCategoryScroll.visibility = if (categoriesCollapsed) View.GONE else View.VISIBLE
            renderToolbarButtonState()
        }
        binding.librarySearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = if (s == null) "" else s.toString().trim { it <= ' ' }
                if (searchDebounce != null) mainQueue.removeCallbacks(searchDebounce!!)
                searchDebounce = Runnable {
                    if (!isAdded || _binding == null) return@Runnable
                    applyFilters()
                }
                mainQueue.postDelayed(searchDebounce!!, 300)
            }
            override fun afterTextChanged(s: Editable?) { }
        })
        renderToolbarButtonState()
    }

    private fun showLibrarySettingsMenu() {
        val styleLabel = getString(if (posterGridStyle)
            R.string.game_library_horizontal_cards else R.string.game_library_poster_grid)
        LauncherDialogFactory.showStandardActionChoices(
            requireContext(), getString(R.string.game_library_settings),
            arrayOf(getString(R.string.game_library_sync_all), styleLabel,
                getString(R.string.game_library_clear))
        ) { index ->
            when (index) {
                0 -> syncController.showSyncDataConfirmDialog()
                1 -> togglePosterGridStyle()
                2 -> confirmClearList()
            }
        }
    }

    private fun togglePosterGridStyle() {
        posterGridStyle = !posterGridStyle
        requireContext().applicationContext
            .getSharedPreferences(LIBRARY_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(getPosterStylePreferenceKey(), posterGridStyle).apply()
        val currentBinding = _binding
        if (currentBinding != null) {
            applyPosterStyleWhenRecyclerIsIdle(currentBinding, posterGridStyle)
        }
        Toast.makeText(
            requireContext(),
            getString(if (posterGridStyle)
                R.string.game_library_switched_poster else R.string.game_library_switched_horizontal),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun applyPosterStyleWhenRecyclerIsIdle(
        currentBinding: FragmentLauncherLibraryBinding,
        posterStyle: Boolean,
    ) {
        val recyclerView = currentBinding.libraryRecycler
        recyclerView.post {
            if (_binding !== currentBinding || posterGridStyle != posterStyle) return@post
            if (recyclerView.isComputingLayout) {
                applyPosterStyleWhenRecyclerIsIdle(currentBinding, posterStyle)
                return@post
            }
            recyclerView.stopScroll()
            recyclerView.itemAnimator?.endAnimations()
            gridLayoutManager?.spanCount = getActiveGridColumns()
            adapter?.setPosterStyle(posterStyle)
            recyclerView.recycledViewPool.clear()
            recyclerView.scrollToPosition(0)
            recyclerView.invalidateItemDecorations()
            recyclerView.post {
                if (_binding !== currentBinding || posterGridStyle != posterStyle) return@post
                when {
                    !posterStyle && usesHorizontalPaging() -> updateFixedGridCardHeight()
                    !posterStyle && usesTabletPortraitCardSizing() -> updateTabletPortraitCardHeight()
                }
            }
        }
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

    private fun renderPagedGrid(forceFullRefresh: Boolean) {
        listController.renderPagedGrid(forceFullRefresh)
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

    private fun showNextPage(): Boolean {
        if (!listController.showNextPage()) return false
        animatePageChange(true)
        return true
    }

    private fun showPreviousPage(): Boolean {
        if (!listController.showPreviousPage()) return false
        animatePageChange(false)
        return true
    }

    private fun animatePageChange(forward: Boolean) {
        val currentBinding = _binding ?: return
        val distance = LauncherTheme.dp(requireContext(), 36) * (if (forward) 1f else -1f)
        currentBinding.libraryRecycler.animate().cancel()
        currentBinding.libraryRecycler.translationX = distance
        currentBinding.libraryRecycler.alpha = 0.72f
        currentBinding.libraryRecycler.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(220L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun loadNextPage() {
        listController.loadNextPage()
    }

    private fun loadNextPage(forceFullRefresh: Boolean) {
        listController.loadNextPage(forceFullRefresh)
    }

    private fun renderState() {
        val currentBinding = _binding ?: return
        val hasGames = listController.getVisibleGames().isNotEmpty()
        currentBinding.libraryRecycler.visibility = if (hasGames) View.VISIBLE else View.GONE
        if (hasGames && usesHorizontalPaging()) {
            currentBinding.libraryRecycler.post { updateFixedGridCardHeight() }
        } else if (hasGames && usesTabletPortraitCardSizing()) {
            currentBinding.libraryRecycler.post { updateTabletPortraitCardHeight() }
        }
        currentBinding.libraryEmpty.text =
            getString(if (listController.getAllGames().isEmpty())
                R.string.game_empty else R.string.game_empty_search)
        currentBinding.libraryEmpty.visibility = if (hasGames) View.GONE else View.VISIBLE
        if (hasGames) scheduleLoadUntilViewportFilled()
    }

    /**
     * A short first page can leave no scroll range, which previously required a manual upward
     * drag to reveal more games. Add pages after layout until the list is scrollable or exhausted.
     *
     * 使用 OnPreDrawListener 等待 RecyclerView 完成布局后再检测是否填满容器。
     * 高 dpi 手机首屏尤其需要：page size 默认 8 项（2 列 × 4 行）往往填不满高屏幕，
     * 若用 post() 检测，runnable 可能在 DiffUtil 触发的布局完成前运行，
     * canScrollVertically() 基于旧布局返回 true（误判为已填满），导致下一页无法自动加载。
     */
    private fun scheduleLoadUntilViewportFilled() {
        val currentBinding = _binding ?: return
        if (listController.isViewportFillCheckPending || usesHorizontalPaging()
            || listController.isLoading || listController.isFullyLoaded
            || listController.getVisibleGames().size >= listController.getFilteredGames().size
        ) {
            return
        }
        listController.setViewportFillCheckPending(true)
        val recyclerView = currentBinding.libraryRecycler
        val observer = recyclerView.viewTreeObserver
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val vto = recyclerView.viewTreeObserver
                vto.removeOnPreDrawListener(this)
                listController.setViewportFillCheckPending(false)
                if (_binding == null || listController.isLoading || listController.isFullyLoaded
                    || listController.getVisibleGames().size >= listController.getFilteredGames().size
                ) {
                    return true
                }
                // 列表无法向下滚动时，说明内容未填满容器，加载下一页
                if (!recyclerView.canScrollVertically(1)) {
                    listController.loadNextPage()
                }
                return true
            }
        }
        observer.addOnPreDrawListener(listener)
    }

    private fun handleLoadMoreDragWhenNotScrollable(recyclerView: RecyclerView, event: MotionEvent) {
        if (listController.isLoading || listController.isFullyLoaded
            || listController.getFilteredGames().isEmpty()
            || listController.getVisibleGames().size >= listController.getFilteredGames().size
        ) {
            loadMoreDragCandidate = false
            return
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                loadMoreDragStartY = event.y
                loadMoreDragCandidate = !recyclerView.canScrollVertically(1)
            }

            MotionEvent.ACTION_MOVE -> {
                if (loadMoreDragCandidate && loadMoreDragStartY - event.y > LauncherTheme.dp(requireContext(), 48)) {
                    loadMoreDragCandidate = false
                    loadNextPage()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                loadMoreDragCandidate = false
            }
        }
    }

    private fun confirmLaunchGame(game: Game?) {
        if (game == null) return
        LauncherDialogFactory.showConfirm(
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

    override fun onEditPlayTime(game: Game) {
        GameActionMenuFactory.showEditPlayTimeDialog(this, game) { updateSingleGame(it) }
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
        options.add(arrayOf(getString(R.string.game_action_edit_duration), "edit_play_time"))
        options.add(arrayOf(getString(R.string.game_action_pin_shortcut), "pin_shortcut"))
        options.add(arrayOf(getString(R.string.game_action_rematch_vndb), "rematch"))
        options.add(arrayOf(getString(R.string.game_action_custom_vndb), "custom_vndb"))
        options.add(arrayOf(getString(R.string.game_action_sync_cover), "sync"))
        // ONS 引擎游戏支持单独配置 ONS 引擎参数（编码/拉伸/锐化/视频/独立存档目录等）
        if (game.engine == EngineType.ONS) {
            options.add(arrayOf(getString(R.string.game_action_ons_settings), "ons_settings"))
        }
        options.add(arrayOf(getString(R.string.game_action_delete), "delete"))
        val deleteIndex = options.indexOfFirst { it[1] == "delete" }
        LauncherDialogFactory.showStandardActionChoices(
            requireContext(),
            getString(R.string.game_action_more),
            options.map { it[0] as CharSequence }.toTypedArray(),
            deleteIndex
        ) { index ->
            when (options[index][1]) {
                "edit_play_time" -> GameActionMenuFactory.showEditPlayTimeDialog(this, game) { updateSingleGame(it) }
                "pin_shortcut" -> PinnedGameShortcut.requestPinShortcut(requireContext(), game)
                "rematch" -> syncController.rematchMetadata(game)
                "custom_vndb" -> LauncherCustomVndbSearchDialog.show(this, game) { reloadSingleGame(game.id) }
                "sync" -> syncController.syncMetadataToCard(game)
                "ons_settings" -> openOnsGameSettings(game)
                "delete" -> confirmDeleteGame(game)
            }
        }
    }

    private fun openOnsGameSettings(game: Game) {
        try {
            val intent = Intent(requireContext(), LauncherKrkrSettingsActivity::class.java)
            intent.putExtra(LauncherKrkrSettingsActivity.EXTRA_GAME_ID, game.id)
            startActivity(intent)
        } catch (error: Exception) {
            Log.w(TAG, "Failed to open ONS game settings", error)
            Toast.makeText(requireContext(), R.string.game_action_ons_open_failed, Toast.LENGTH_SHORT).show()
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
                Log.w("LauncherLibraryFragment", "Failed to toggle favorite", e)
            }
            val result = updated
            mainQueue.post {
                if (!isAdded || _binding == null) return@post
                if (result != null) updateSingleGame(result)
            }
        }
    }

    private fun confirmDeleteGame(game: Game) {
        LauncherDialogFactory.showDangerConfirm(
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
                Log.w("LauncherLibraryFragment", "Failed to delete game", e)
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
        LauncherDialogFactory.showDangerConfirm(
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
                Log.w("LauncherLibraryFragment", "Failed to clear game list", e)
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
                renderCategories()
                applyFilters(true)
                Toast.makeText(app, R.string.game_library_cleared, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ===== GameSyncController.Listener =====

    override fun onBatchSyncComplete(loadedGames: List<Game>, categoryResult: CategoryBuildResult) {
        if (_binding == null) return
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

        renderCategories()
        listController.setDataLoaded(true)

        // controller 已持有最新数据，applyFilters(true) 会强制全量刷新卡片
        applyFilters(true)
    }

    private fun startEditGameActivity(game: Game) {
        pendingEditGameId = game.id
        val intent = Intent(requireContext(), LauncherGameEditActivity::class.java)
        intent.putExtra(LauncherGameEditActivity.EXTRA_GAME_ID, game.id)
        startActivity(intent)
    }

    private fun renderCategories() {
        val currentBinding = _binding ?: return
        currentBinding.libraryCategoryRow.removeAllViews()
        addCategoryChip(getString(R.string.game_common_all), "")
        for (category in categories) {
            addCategoryChip(category.label, category.value)
        }
    }

    private fun addCategoryChip(label: String?, value: String?) {
        val chip = TextView(requireContext())
        val selected = value == selectedCategory
        chip.text = label
        chip.isSingleLine = true
        chip.gravity = Gravity.CENTER
        chip.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_PX,
            resources.getDimension(com.core.R.dimen.launcher_library_category_text_size)
        )
        chip.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        chip.tag = value
        if (selected) {
            chip.setTextColor(LauncherTheme.onPrimary(requireContext()))
            chip.background = LauncherTheme.selectedChip(requireContext())
        } else {
            LauncherTheme.menuItem(chip)
        }
        val chipHorizontalPadding = resources.getDimensionPixelSize(
            com.core.R.dimen.launcher_library_category_horizontal_padding
        )
        chip.setPadding(chipHorizontalPadding, 0, chipHorizontalPadding, 0)
        chip.setOnClickListener {
            selectedCategory = value ?: ""
            renderCategories()
            applyFilters()
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            resources.getDimensionPixelSize(com.core.R.dimen.launcher_library_category_chip_height)
        )
        lp.setMargins(
            0, 0,
            resources.getDimensionPixelSize(com.core.R.dimen.launcher_library_category_chip_margin_end),
            0
        )
        binding.libraryCategoryRow.addView(chip, lp)
        if (usePortraitLibraryScaler()) {
            LauncherTabletPortraitScaler.apply(chip)
        }
    }

    private fun renderToolbarButtonState() {
        val currentBinding = _binding ?: return
        applyToolbarIconTone(currentBinding.librarySyncButton)
        applyToolbarIconTone(currentBinding.librarySearchButton)
        applyToolbarIconTone(currentBinding.libraryCollapseButton)
    }

    private fun applyToolbarIconTone(view: ImageView) {
        view.imageTintList = ColorStateList.valueOf(LauncherTheme.primary(requireContext()))
        view.background = null
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
        renderCategories()
    }

    override fun onVisibleGamesChanged(forceFullRefresh: Boolean) {
        adapter?.submit(ArrayList(listController.getVisibleGames()), forceFullRefresh)
    }

    override fun onRenderStateRequested() {
        renderState()
    }
}
