package com.apps.PadUi

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.apps.LauncherActivity
import com.apps.LauncherThemeStyle
import com.apps.data.LauncherRepository
import com.apps.game.GameActionMenuFactory
import com.apps.game.GameListController
import com.apps.game.GameSessionController
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherCoverLoader
import com.core.R
import com.core.databinding.FragmentPadGameBinding
import com.core.launcherbridge.LauncherRepositoryBridge
import com.core.model.Game
import com.core.util.DevLogger
import com.core.util.RxMainQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 横屏游戏库 GAME 页：复用竖屏首页动态列表样式，纵向滚动时居中条目放大。
 */
class PadGameFragment : Fragment(), GameListController.Listener,
    GameActionMenuFactory.ActionMenuCallbacks {

    companion object {
        private const val TAG = "PadGameFragment"
        // 一次提供完整列表，避免追加分页在视觉缩放列表中形成间距接缝。
        private const val LIST_PAGE_SIZE = 1_000
        private const val NORMAL_ITEM_HEIGHT_DP = 63
    }

    private var _binding: FragmentPadGameBinding? = null
    private val binding get() = _binding!!

    private val mainQueue = RxMainQueue()
    private var listController: GameListController? = null
    private var sessionController: GameSessionController? = null
    private var adapter: PadGameListAdapter? = null
    private lateinit var showcaseStore: PadGameShowcaseStore
    private var showcaseGames: List<Game?> = List(PadGameShowcaseStore.MAX_SHOWCASE_SIZE) { null }
    private var searchQuery: String = ""
    private var needsRefresh: Boolean = false
    private var shouldCenterInitialItem = true
    private val shortcutCardLayoutChangeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        updateShortcutCardSizing()
    }
    private val recyclerLayoutChangeListener = View.OnLayoutChangeListener {
            _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
            updateListViewport()
        }
    }
    private val recyclerScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            updateFocusedItemScale()
            val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return
            if (dy > 0 && manager.findLastVisibleItemPosition() >= (adapter?.itemCount ?: 0) - 3) {
                listController?.loadNextPage()
            }
        }
    }

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
        setupShortcutCards()
        setupRecycler()
        setupSearch()
        applyDetailDividerTone()
        refreshStats()
        needsRefresh = true
        loadGames()
    }

    override fun onResume() {
        super.onResume()
        applyToolbarIconTone()
        applyDetailDividerTone()
        adapter?.refreshThemeTone()
        renderShowcaseCards()
        headerRenderer.renderAvatar()
        headerRenderer.renderAccountInfo()
        refreshStats()
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
            b.padGameShortcutContainer.removeOnLayoutChangeListener(shortcutCardLayoutChangeListener)
            b.padGameRecycler.removeOnLayoutChangeListener(recyclerLayoutChangeListener)
            b.padGameRecycler.removeOnScrollListener(recyclerScrollListener)
            b.padGameRecycler.adapter = null
            listOf(
                b.padGameShowcaseImageOne,
                b.padGameShowcaseImageTwo,
                b.padGameShowcaseImageThree,
                b.padGameShowcaseImageFour,
                b.padGameShowcaseImageFive,
            ).forEach(LauncherCoverLoader::clear)
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
        binding.padGameLoading.setTextColor(primary)
    }

    /** 右容器十字分界线跟随主题线条色。 */
    private fun applyDetailDividerTone() {
        val lineColor = LauncherTheme.line(requireContext())
        binding.padDetailHLine.setBackgroundColor(lineColor)
        binding.padDetailVLine.setBackgroundColor(lineColor)
    }

    private fun setupShortcutCards() {
        showcaseStore = PadGameShowcaseStore(requireContext())
        showcaseCards().forEachIndexed { index, card ->
            card.setOnClickListener {
                showcaseGames.getOrNull(index)?.let { game -> businessHandler.confirmLaunchGame(game) }
            }
        }
        binding.padGameShortcutContainer.addOnLayoutChangeListener(shortcutCardLayoutChangeListener)
        binding.padGameShortcutContainer.post {
            if (!isAdded || _binding == null) return@post
            updateShortcutCardSizing()
        }
        refreshShowcaseCards()
    }

    private fun showcaseCards() = listOf(
        binding.padGameShortcutOne,
        binding.padGameShortcutTwo,
        binding.padGameShortcutThree,
        binding.padGameShortcutFour,
        binding.padGameShortcutFive,
    )

    private fun showcaseImages() = listOf<ImageView>(
        binding.padGameShowcaseImageOne,
        binding.padGameShowcaseImageTwo,
        binding.padGameShowcaseImageThree,
        binding.padGameShowcaseImageFour,
        binding.padGameShowcaseImageFive,
    )

    private fun showcaseAddLabels() = listOf<TextView>(
        binding.padGameShowcaseAddOne,
        binding.padGameShowcaseAddTwo,
        binding.padGameShowcaseAddThree,
        binding.padGameShowcaseAddFour,
        binding.padGameShowcaseAddFive,
    )

    private fun showcaseTitleLabels() = listOf<TextView>(
        binding.padGameShowcaseTitleOne,
        binding.padGameShowcaseTitleTwo,
        binding.padGameShowcaseTitleThree,
        binding.padGameShowcaseTitleFour,
        binding.padGameShowcaseTitleFive,
    )

    private fun showcaseOverlays() = listOf(
        binding.padGameShowcaseOverlayOne,
        binding.padGameShowcaseOverlayTwo,
        binding.padGameShowcaseOverlayThree,
        binding.padGameShowcaseOverlayFour,
        binding.padGameShowcaseOverlayFive,
    )

    private fun refreshShowcaseCards() {
        if (!::showcaseStore.isInitialized) return
        val ids = showcaseStore.gameIds()
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val games = withContext(Dispatchers.IO) {
                ids.mapNotNull { gameId -> LauncherRepositoryBridge.findGameById(appContext, gameId) }.also {
                    showcaseStore.retainExisting(it.map { game -> game.id })
                }
            }
            if (!isAdded || _binding == null) return@launch
            showcaseGames = games + List(PadGameShowcaseStore.MAX_SHOWCASE_SIZE - games.size) { null }
            renderShowcaseCards()
        }
    }

    private fun renderShowcaseCards() {
        val images = showcaseImages()
        val addLabels = showcaseAddLabels()
        val titleLabels = showcaseTitleLabels()
        val overlays = showcaseOverlays()
        images.indices.forEach { index ->
            val game = showcaseGames[index]
            val image = images[index]
            val overlay = overlays[index]
            addLabels[index].setTextColor(LauncherTheme.primary(requireContext()))
            titleLabels[index].setTextColor(LauncherTheme.text(requireContext()))
            LauncherCoverLoader.clear(image)
            if (game == null) {
                image.visibility = View.GONE
                image.background = null
                overlay.visibility = View.GONE
                addLabels[index].visibility = View.VISIBLE
                titleLabels[index].visibility = View.GONE
                return@forEach
            }
            addLabels[index].visibility = View.GONE
            image.visibility = View.VISIBLE
            image.background = LauncherTheme.primaryGradientCard(requireContext(), 8f)
            titleLabels[index].text = com.apps.game.GameMetadataFormatter.safeTitle(game)
            titleLabels[index].visibility = View.VISIBLE
            overlay.visibility = View.VISIBLE
            // 半透明遮罩跟随主题色调：主题色半透明遮罩 + 主题色上的文字色。
            overlay.background = LauncherTheme.primaryTextOverlay(requireContext())
            titleLabels[index].setTextColor(LauncherTheme.onPrimary(requireContext()))
            val persistedCover = game.coverPersistUri?.trim().orEmpty()
            val cover = if (persistedCover.isNotEmpty()) persistedCover else game.coverUri?.trim().orEmpty()
            if (cover.isNotEmpty()) LauncherCoverLoader.loadInto(image, cover, null)
        }
    }

    /**
     * 填充右侧竖屏游戏数据卡片：游戏数、总游玩时长、今日游玩时长，
     * 并应用主题背景图与遮罩，与竖屏首页的统计卡片保持一致。
     */
    private fun refreshStats() {
        val b = _binding ?: return
        b.padStatsImage.setImageResource(LauncherThemeStyle.homeStatsImageRes(requireContext()))
        val isDefault = !LauncherActivity.isRinneTheme(requireContext())
            && !LauncherActivity.isAnriTheme(requireContext())
            && !LauncherActivity.isXinhaitianTheme(requireContext())
            && !LauncherActivity.isNatsumeTheme(requireContext())
            && !LauncherActivity.isIzumiTheme(requireContext())
        if (isDefault) {
            b.padStatsScrim.setBackgroundResource(com.core.R.drawable.launcher_home_stats_scrim)
        } else {
            b.padStatsScrim.background = LauncherTheme.statsScrim(requireContext())
        }
        val app = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val snapshot = LauncherRepository(app).loadStatsSnapshot()
                withContext(Dispatchers.Main) {
                    val bb = _binding ?: return@withContext
                    bb.padTvGameCount.text = snapshot.gameCount.toString()
                    bb.padTvTotalPlayTime.text = snapshot.totalPlayTime
                    bb.padTvTodayPlayTime.text = snapshot.todayPlayTime
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DevLogger.w(TAG, "Failed to load stats snapshot", e)
            }
        }
    }

    /** 与竖屏游戏库的海报模式一致：卡片高度为宽度的 1.42 倍。 */
    private fun updateShortcutCardSizing() {
        val container = _binding?.padGameShortcutContainer ?: return
        if (container.width <= 0) return
        val gap = LauncherTheme.dp(requireContext(), 6)
        val usableWidth = container.width - container.paddingStart - container.paddingEnd
        val cardWidth = ((usableWidth - gap * 4) / 5).coerceAtLeast(1)
        val cardHeight = (cardWidth * 1.42f).toInt()
        val containerHeight = cardHeight + container.paddingTop + container.paddingBottom
        val containerParams = container.layoutParams
        if (containerParams.height != containerHeight) {
            containerParams.height = containerHeight
            container.layoutParams = containerParams
        }
        val cards = listOf(
            binding.padGameShortcutOne,
            binding.padGameShortcutTwo,
            binding.padGameShortcutThree,
            binding.padGameShortcutFour,
            binding.padGameShortcutFive,
        )
        cards.forEach { card ->
            val params = card.layoutParams as LinearLayout.LayoutParams
            if (params.height != cardHeight) {
                params.height = cardHeight
                card.layoutParams = params
            }
        }
        // 详情面板高度走 match-constraints：顶部到五卡片容器、底部到 padDetailBottomGuide。
        // 底部导航图标 view 高 48dp（栏高 40dp + 4dp 下边距，图标向上伸出 4dp），其顶部位于
        // 屏幕底部上方 48dp；guide 上移到该位置之上，保证按钮/数据卡片绝不覆盖底部功能图标。
        val detail = _binding?.padGameDetailPanel ?: return
        val guide = _binding?.padDetailBottomGuide ?: return
        guide.setGuidelineEnd(LauncherTheme.dp(requireContext(), 54))
        val detailParams = detail.layoutParams as ConstraintLayout.LayoutParams
        if (detailParams.height != ConstraintLayout.LayoutParams.MATCH_CONSTRAINT ||
            detailParams.matchConstraintMaxHeight != cardHeight) {
            detailParams.height = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            detailParams.matchConstraintMaxHeight = cardHeight
            detail.layoutParams = detailParams
        }
    }

    // ===== Recycler setup =====

    private fun setupRecycler() {
        val newAdapter = PadGameListAdapter()
        newAdapter.setOnGameCardListener(object : PadGameListAdapter.OnGameCardListener {
            override fun onGameClick(game: Game?) {
                if (game != null) businessHandler.confirmLaunchGame(game)
            }

            override fun onGameLongClick(game: Game?) {
                if (game != null) {
                    val label = getString(
                        if (showcaseStore.contains(game.id)) R.string.game_action_showcase_remove
                        else R.string.game_action_showcase_add
                    )
                    businessHandler.showGameActionMenu(game, this@PadGameFragment, label)
                }
            }
        })
        adapter = newAdapter
        val recyclerView = binding.padGameRecycler
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = newAdapter
        recyclerView.itemAnimator = null
        recyclerView.clipToPadding = false
        recyclerView.setItemViewCacheSize(5)
        recyclerView.addOnLayoutChangeListener(recyclerLayoutChangeListener)
        recyclerView.addOnScrollListener(recyclerScrollListener)
        recyclerView.post {
            if (!isAdded || _binding == null) return@post
            updateListViewport()
        }
    }

    private fun updateListViewport() {
        val b = _binding ?: return
        val recyclerView = b.padGameRecycler
        val parent = recyclerView.parent as? View ?: return
        if (parent.width <= 0 || recyclerView.height <= 0) return
        val params = recyclerView.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        // 原三分之一宽度增加五分之一，即占内容区的 40%。
        val targetWidth = maxOf(1, parent.width * 2 / 5)
        // 顶部搜索区由布局中的 60% Guideline 直接限定在列表左侧。
        val targetEndMargin = 0
        if (params.width != targetWidth || params.marginEnd != targetEndMargin) {
            params.width = targetWidth
            params.marginEnd = targetEndMargin
            recyclerView.layoutParams = params
        }
        val emptyParams = b.padGameEmpty.layoutParams as? FrameLayout.LayoutParams
        if (emptyParams != null && (emptyParams.width != targetWidth ||
                emptyParams.gravity != (Gravity.END or Gravity.CENTER_VERTICAL))) {
            emptyParams.width = targetWidth
            emptyParams.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            b.padGameEmpty.layoutParams = emptyParams
        }
        val horizontalPadding = LauncherTheme.dp(requireContext(), 15)
        // 末项自带 12dp 底边距，顶部补同等留白以保持列表首尾一致。
        val topPadding = LauncherTheme.dp(requireContext(), 12)
        if (recyclerView.paddingStart != horizontalPadding || recyclerView.paddingEnd != 0
            || recyclerView.paddingTop != topPadding || recyclerView.paddingBottom != 0) {
            recyclerView.setPaddingRelative(
                horizontalPadding,
                topPadding,
                0,
                0,
            )
        }
        updateFocusedItemScale()
    }

    private fun updateFocusedItemScale() {
        val recyclerView = _binding?.padGameRecycler ?: return
        if (recyclerView.height <= 0) return
        val normalHeight = LauncherTheme.dp(requireContext(), NORMAL_ITEM_HEIGHT_DP)
        for (index in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(index) ?: continue
            // 所有条目保持完全一致的尺寸，不使用聚焦缩放或其他视觉效果。
            child.scaleX = 1f
            child.scaleY = 1f
            child.alpha = 1f
            child.translationZ = 0f
            val params = child.layoutParams
            if (params.width != ViewGroup.LayoutParams.MATCH_PARENT || params.height != normalHeight) {
                params.width = ViewGroup.LayoutParams.MATCH_PARENT
                params.height = normalHeight
                child.layoutParams = params
            }
        }
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
        searchQuery = (b.padSearchInput.text?.toString()?.trim()?.lowercase(Locale.ROOT) ?: "")
        shouldCenterInitialItem = true
        listController?.applyFilters()
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(b.padSearchInput.windowToken, 0)
        b.padSearchInput.clearFocus()
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
        return LIST_PAGE_SIZE
    }

    override fun usesHorizontalPaging(): Boolean {
        return false
    }

    override fun onDataLoaded(categories: List<com.apps.game.CategoryOption>, developers: Map<Long, List<String>>) {
        // GAME 页不需要分类
    }

    override fun onVisibleGamesChanged(forceFullRefresh: Boolean) {
        val lc = listController ?: return
        val b = _binding ?: return
        adapter?.submit(ArrayList(lc.getVisibleGames()), forceFullRefresh)
        b.padGameRecycler.post {
            if (!isAdded || _binding == null) return@post
            updateListViewport()
            centerInitialItemIfNeeded()
            updateFocusedItemScale()
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
        if (hasGames) updateListViewport()
    }

    private fun centerInitialItemIfNeeded() {
        if (!shouldCenterInitialItem) return
        val recyclerView = _binding?.padGameRecycler ?: return
        val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val itemCount = adapter?.itemCount ?: return
        if (itemCount <= 0 || recyclerView.height <= 0) return
        val itemHeight = LauncherTheme.dp(requireContext(), NORMAL_ITEM_HEIGHT_DP)
        val initialPosition = minOf(2, itemCount - 1)
        manager.scrollToPositionWithOffset(initialPosition, (recyclerView.height - itemHeight) / 2)
        shouldCenterInitialItem = false
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

    override fun onToggleFavorite(game: Game) {
        businessHandler.onToggleFavorite(game)
    }

    override fun onTogglePassword(game: Game) {
        businessHandler.onTogglePassword(game)
    }

    override fun onToggleShowcase(game: Game) {
        if (showcaseStore.contains(game.id)) {
            showcaseStore.remove(game.id)
            refreshShowcaseCards()
            return
        }
        when (showcaseStore.add(game.id)) {
            PadGameShowcaseStore.AddResult.ADDED -> refreshShowcaseCards()
            PadGameShowcaseStore.AddResult.FULL -> Toast.makeText(
                requireContext(), R.string.pad_showcase_full, Toast.LENGTH_SHORT
            ).show()
            PadGameShowcaseStore.AddResult.ALREADY_ADDED -> Unit
        }
    }

    override fun onShowMoreOptions(game: Game) {
        businessHandler.onShowMoreOptions(game)
    }
}
