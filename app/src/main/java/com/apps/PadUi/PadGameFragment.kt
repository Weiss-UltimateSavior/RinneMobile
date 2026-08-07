package com.apps.PadUi

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.apps.game.GameActionMenuFactory
import com.apps.game.GameListController
import com.apps.game.GameSessionController
import com.apps.theme.LauncherTheme
import com.core.R
import com.core.databinding.FragmentPadGameBinding
import com.core.model.Game
import com.core.util.RxMainQueue
import java.util.Locale

/**
 * 横屏游戏库 GAME 页：接入共享 [GameListController] 管线，保留 GAME 页专属头部 UI 与行为。
 * 手机每页 1 行 × 5 列，平板每页 2 行 × 5 列，横向手势切换分页。
 */
class PadGameFragment : Fragment(), GameListController.Listener,
    GameActionMenuFactory.ActionMenuCallbacks {

    companion object {
        private const val TAG = "PadGameFragment"
        private const val GRID_COLUMNS = 5
        private const val PHONE_GRID_ROWS = 1
        private const val TABLET_GRID_ROWS = 2
        private const val TABLET_MIN_SMALLEST_WIDTH_DP = 600
    }

    private var _binding: FragmentPadGameBinding? = null
    private val binding get() = _binding!!

    private val mainQueue = RxMainQueue()
    private var listController: GameListController? = null
    private var sessionController: GameSessionController? = null
    private var adapter: PadGameListAdapter? = null
    private var searchQuery: String = ""
    private var gridRows: Int = PHONE_GRID_ROWS
    private var pageSize: Int = GRID_COLUMNS * PHONE_GRID_ROWS
    private var pageAnimating: Boolean = false
    private var pendingPageAnim: Boolean = false
    private var pageAnimForward: Boolean = true
    private var swipeConsumed = false
    private var needsRefresh: Boolean = false

    private val headerRenderer: PadGameHeaderRenderer by lazy { PadGameHeaderRenderer(this, binding) }
    private val businessHandler: PadGameBusinessHandler by lazy {
        PadGameBusinessHandler(requireContext(), mainQueue, listController, sessionController, this, ::reloadSingleGame)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentPadGameBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        LauncherTheme.applyPrimaryTone(binding.root)
        applyToolbarIconTone()
        binding.padAvatarContainer.clipToOutline = true
        gridRows = if (isTabletLayout()) TABLET_GRID_ROWS else PHONE_GRID_ROWS
        pageSize = GRID_COLUMNS * gridRows
        sessionController = GameSessionController(
            requireContext(), mainQueue,
            object : GameSessionController.Listener {
                override fun reloadGame(gameId: Long) {
                    reloadSingleGame(gameId)
                }

                override fun reloadAllGames() {
                    loadGames()
                }
            }
        )
        listController = GameListController(mainQueue, this)
        headerRenderer.renderAvatar()
        headerRenderer.renderAccountInfo()
        setupRecycler()
        setupSearch()
        setupSettingsButton()
        setupNextPageButton()
        setupPagingGesture()
        needsRefresh = true
        loadGames()
    }

    override fun onResume() {
        super.onResume()
        applyToolbarIconTone()
        headerRenderer.renderAvatar()
        headerRenderer.renderAccountInfo()
        val sc = sessionController
        val lc = listController
        if (sc != null && sc.hasActiveSession()) {
            sc.finishDirectPlaySessionIfNeeded(this)
        } else if (lc?.isDataLoaded() != true || needsRefresh) {
            needsRefresh = false
            loadGames()
        }
    }

    override fun onDestroyView() {
        mainQueue.removeCallbacks(null)
        sessionController?.cleanup()
        listController?.cleanup()
        _binding?.let { b ->
            b.padGameRecycler.adapter = null
            b.root.setOnTouchListener(null)
        }
        super.onDestroyView()
        _binding = null
        adapter = null
    }

    // ===== Toolbar icon tint =====

    private fun applyToolbarIconTone() {
        val primary = LauncherTheme.primary(requireContext())
        binding.padSearchIcon.setColorFilter(primary)
        binding.padGameNextPage.setColorFilter(primary)
        binding.padGameSettingsButton.setColorFilter(primary)
    }

    // ===== Recycler setup =====

    private fun setupRecycler() {
        val newAdapter = PadGameListAdapter()
        newAdapter.setOnGameCardListener(object : PadGameListAdapter.OnGameCardListener {
            override fun onGameClick(game: Game?) {
                if (swipeConsumed) { swipeConsumed = false; return }
                if (game != null) businessHandler.confirmLaunchGame(game)
            }

            override fun onGameLongClick(game: Game?) {
                if (swipeConsumed) { swipeConsumed = false; return }
                if (game != null) businessHandler.showGameActionMenu(game, this@PadGameFragment)
            }
        })
        adapter = newAdapter
        binding.padGameRecycler.layoutManager = GridLayoutManager(requireContext(), GRID_COLUMNS)
        binding.padGameRecycler.adapter = newAdapter
        binding.padGameRecycler.itemAnimator = null
        binding.padGameRecycler.setHasFixedSize(true)
        binding.padGameRecycler.setItemViewCacheSize(pageSize)
        binding.padGameRecycler.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) updateCardHeight()
        }
        binding.padGameRecycler.post {
            if (!isAdded || _binding == null) return@post
            updateCardHeight()
        }
    }

    private fun updateCardHeight() {
        val b = _binding ?: return
        val currentAdapter = adapter ?: return
        val recyclerView = b.padGameRecycler
        val availableWidth = recyclerView.width - recyclerView.paddingLeft - recyclerView.paddingRight
        if (availableWidth <= 0) return
        val parent = recyclerView.parent as? View ?: return
        val availableHeight = parent.height - parent.paddingTop - parent.paddingBottom
        if (availableHeight <= 0) return

        val horizontalMarginTotal = LauncherTheme.dp(requireContext(), 10) * GRID_COLUMNS
        val cardWidth = maxOf(1, (availableWidth - horizontalMarginTotal) / GRID_COLUMNS)
        val heightByRatio = maxOf(1, Math.round(cardWidth * 5f / 3f))
        val heightByRows = maxOf(1, (availableHeight - LauncherTheme.dp(requireContext(), 10) * gridRows) / gridRows)
        val cardHeight = maxOf(LauncherTheme.dp(requireContext(), 34), minOf(heightByRatio, heightByRows))
        currentAdapter.setFixedCardHeight(cardHeight)

        // 垂直居中：计算内容高度并设置顶部 padding
        val contentHeight = cardHeight * gridRows + LauncherTheme.dp(requireContext(), 10) * (gridRows - 1)
        val topPadding = maxOf(0, (availableHeight - contentHeight) / 2)
        recyclerView.setPadding(recyclerView.paddingLeft, topPadding, recyclerView.paddingRight, recyclerView.paddingBottom)
    }

    private fun isTabletLayout(): Boolean {
        return resources.configuration.smallestScreenWidthDp >= TABLET_MIN_SMALLEST_WIDTH_DP
    }

    // ===== Search =====

    private fun setupSearch() {
        binding.padSearchIcon.setOnClickListener { applySearch() }
        binding.padSearchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                applySearch()
                true
            } else false
        }
    }

    private fun applySearch() {
        val b = _binding ?: return
        // Reset any ongoing page animation
        b.padGameRecycler.animate().cancel()
        b.padGameRecycler.translationX = 0f
        pageAnimating = false
        searchQuery = (b.padSearchInput.text?.toString()?.trim()?.lowercase(Locale.ROOT) ?: "")
        listController?.applyFilters()
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(b.padSearchInput.windowToken, 0)
        b.padSearchInput.clearFocus()
    }

    // ===== Paging =====

    private fun setupNextPageButton() {
        binding.padGameNextPage.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            showNextPage()
        }
    }

    private fun showNextPage() {
        if (pageAnimating) return
        pendingPageAnim = true
        pageAnimForward = true
        if (listController?.showNextPage() != true) {
            pendingPageAnim = false
        }
    }

    private fun showPreviousPage() {
        if (pageAnimating) return
        pendingPageAnim = true
        pageAnimForward = false
        if (listController?.showPreviousPage() != true) {
            pendingPageAnim = false
        }
    }

    // ===== Paging gesture =====

    private fun setupPagingGesture() {
        val detector = GestureDetector(requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent): Boolean = true

                override fun onFling(first: MotionEvent?, second: MotionEvent,
                                    velocityX: Float, velocityY: Float): Boolean {
                    if (first == null) return false
                    val deltaX = second.x - first.x
                    val deltaY = second.y - first.y
                    if (Math.abs(deltaX) <= Math.abs(deltaY)
                        || Math.abs(deltaX) < LauncherTheme.dp(requireContext(), 64)
                        || Math.abs(velocityX) < LauncherTheme.dp(requireContext(), 180)) {
                        return false
                    }
                    swipeConsumed = true
                    binding.padGameRecycler.postDelayed({ swipeConsumed = false }, 250L)
                    if (deltaX < 0) showNextPage() else showPreviousPage()
                    return true
                }
            })

        binding.padGameRecycler.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
                detector.onTouchEvent(event)
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, event: MotionEvent) {
                detector.onTouchEvent(event)
            }

            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })
        binding.root.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            true
        }
    }

    // ===== Settings button =====

    private fun setupSettingsButton() {
        binding.padGameSettingsButton.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            startActivity(Intent(requireContext(), PadSettingsActivity::class.java))
        }
    }

    // ===== Load / Filter =====

    private fun loadGames() {
        listController?.loadGames()
    }

    private fun reloadSingleGame(gameId: Long) {
        listController?.reloadSingleGame(this, gameId)
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
        return "" // GAME 页无分类栏
    }

    override fun getGameDevelopers(): Map<Long, List<String>> {
        return emptyMap()
    }

    override fun getPageSize(): Int {
        return pageSize
    }

    override fun usesHorizontalPaging(): Boolean {
        return true
    }

    override fun onDataLoaded(categories: List<com.apps.game.CategoryOption>, developers: Map<Long, List<String>>) {
        // GAME 页不需要分类
    }

    override fun onVisibleGamesChanged(forceFullRefresh: Boolean) {
        val lc = listController ?: return
        val b = _binding ?: return

        if (pendingPageAnim) {
            pendingPageAnim = false
            pageAnimating = true
            val distance = maxOf(b.padGameRecycler.width.toFloat(), b.padGamePanel.width.toFloat()).coerceAtLeast(1f)
            val exitX = if (pageAnimForward) -distance else distance
            b.padGameRecycler.animate().cancel()
            b.padGameRecycler.animate()
                .translationX(exitX)
                .setDuration(180L)
                .withEndAction {
                    if (!isAdded || _binding == null) return@withEndAction
                    adapter?.submit(ArrayList(lc.getVisibleGames()), forceFullRefresh)
                    b.padGameRecycler.translationX = -exitX
                    b.padGameRecycler.animate()
                        .translationX(0f)
                        .setDuration(220L)
                        .withEndAction { pageAnimating = false }
                        .start()
                }
                .start()
        } else {
            adapter?.submit(ArrayList(lc.getVisibleGames()), forceFullRefresh)
        }
    }

    override fun onRenderStateRequested() {
        val b = _binding ?: return
        val lc = listController ?: return
        val hasGames = lc.getVisibleGames().isNotEmpty()
        b.padGameRecycler.visibility = if (hasGames) View.VISIBLE else View.GONE
        b.padGameEmpty.visibility = if (hasGames) View.GONE else View.VISIBLE
        b.padGameEmpty.setText(
            if (lc.getAllGames().isEmpty()) R.string.pad_no_games
            else R.string.pad_no_matching_games
        )
        b.padGameLoading.visibility = if (!hasGames && lc.isLoading()) View.VISIBLE else View.GONE
        b.padGameNextPage.visibility = if (hasGames && !lc.isFullyLoaded()) View.VISIBLE else View.GONE
        // 数据加载后重新计算卡片高度和居中
        if (hasGames) updateCardHeight()
    }

    // ===== GameActionMenuFactory.ActionMenuCallbacks =====

    override fun onShowGameDetail(game: Game) {
        businessHandler.onShowGameDetail(game)
    }

    override fun onEditGame(game: Game) {
        businessHandler.onEditGame(game)
    }

    override fun onShowPlayStatus(game: Game) {
        businessHandler.onShowPlayStatus(game)
    }

    override fun onEditPlayTime(game: Game) {
        businessHandler.onEditPlayTime(game)
    }

    override fun onToggleFavorite(game: Game) {
        businessHandler.onToggleFavorite(game)
    }

    override fun onTogglePassword(game: Game) {
        businessHandler.onTogglePassword(game)
    }

    override fun onShowMoreOptions(game: Game) {
        businessHandler.onShowMoreOptions(game)
    }
}