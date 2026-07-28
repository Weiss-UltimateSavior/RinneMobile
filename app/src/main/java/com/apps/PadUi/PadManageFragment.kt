package com.apps.PadUi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.apps.game.CategoryBuildResult
import com.apps.game.CategoryOption
import com.apps.game.GameActionMenuFactory
import com.apps.game.GameCategoryBuilder
import com.apps.game.GameListController
import com.apps.game.GameMetadataFormatter
import com.apps.game.GamePasswordLock
import com.apps.game.GameSessionController
import com.apps.game.GameSyncController
import com.apps.settings.LauncherCustomVndbSearchDialog
import com.apps.settings.LauncherKrkrSettingsActivity
import com.apps.theme.LauncherMotion
import com.apps.theme.LauncherTheme
import com.core.databinding.FragmentLauncherLibraryBinding
import com.core.launcherbridge.LauncherRepositoryBridge
import com.core.model.EngineType
import com.core.model.Game
import com.core.util.AppExecutors
import com.core.util.RxMainQueue

/**
 * 横屏手机游戏仓库自包含实现：直接继承 [Fragment]，使用 [PadManageGameAdapter]。
 * 卡片高度根据实际列宽自适应，列表纵向连续加载，不再锁定固定行数分页。
 * 不再继承 LauncherLibraryFragment，所有逻辑独立维护。
 */
class PadManageFragment : Fragment(), GameListController.Listener,
    GameActionMenuFactory.ActionMenuCallbacks, GameSyncController.Listener {

    private var _binding: FragmentLauncherLibraryBinding? = null
    private val binding get() = _binding!!

    private val mainQueue = RxMainQueue()

    /** Pad 偏好使用 PadDialogFactory 的单选实现。 */
    private val subDialogFactory = GameActionMenuFactory.SubDialogFactory { ctx, title, labels, checked, onChoice ->
        PadDialogFactory.showSingleChoice(ctx, title, labels, checked) { index -> onChoice.accept(index) }
    }

    /** Pad 同步对话框工厂：复用 PadDialogFactory，使用 secondaryButton 背景，270dp 宽度。 */
    private val syncDialogFactory = object : GameSyncController.DialogFactory {
        override fun showSyncConfirmDialog(onConfirm: Runnable) {
            PadDialogFactory.showStandardConfirm(
                requireContext(), getString(com.core.R.string.pad_sync_data),
                getString(com.core.R.string.pad_sync_all_message),
                getString(com.core.R.string.pad_confirm_sync), onConfirm
            )
        }

        override fun createSyncLoadingDialog(title: String?, hint: String?): AlertDialog =
            createPadSyncLoadingDialog(title, hint)

        override fun showSyncResultDialog(synced: Int, failed: Int) {
            val message = getString(com.core.R.string.pad_sync_complete_count, synced) +
                if (failed > 0) getString(com.core.R.string.pad_sync_failed_count, failed) else ""
            PadDialogFactory.showInfo(requireContext(), getString(com.core.R.string.pad_sync_complete), message)
        }
    }

    private var sessionController: GameSessionController? = null
    private var listController: GameListController? = null
    private var syncController: GameSyncController? = null
    private val categories: MutableList<CategoryOption> = ArrayList()
    private val gameDevelopers: MutableMap<Long, List<String>> = HashMap()
    private var adapter: PadManageGameAdapter? = null
    private var selectedCategory: String = ""
    private var searchQuery: String = ""
    private var categoriesCollapsed: Boolean = true
    private var needsRefresh: Boolean = false
    private var searchDebounce: Runnable? = null
    private var pageSize: Int = GRID_COLUMNS

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
        applyPadContentSpacing()
        applySystemBarInsets()
        LauncherTheme.applyPrimaryTone(binding.root)
        binding.libraryTitle.setText(com.core.R.string.pad_game_repository)
        pageSize = GRID_COLUMNS * (if (isTabletLayout()) 2 else 1)
        setupSearchAndCategories()
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
        syncController = GameSyncController(mainQueue, this, syncDialogFactory)
        listController = GameListController(mainQueue, this)
        setupRecycler()
        loadGames()
    }

    /** 压缩通用游戏库布局为 Launcher 底栏预留的底部空白，仅影响 Pad 管理页。 */
    private fun applyPadContentSpacing() {
        binding.libraryContent.setPadding(
            binding.libraryContent.paddingLeft,
            binding.libraryContent.paddingTop,
            binding.libraryContent.paddingRight,
            dp(6)
        )
        binding.libraryRecycler.setPadding(
            binding.libraryRecycler.paddingLeft,
            binding.libraryRecycler.paddingTop,
            binding.libraryRecycler.paddingRight,
            0
        )
    }

    override fun onResume() {
        super.onResume()
        checkStoragePermission()
        val sc = sessionController
        if (sc != null && sc.hasActiveSession()) {
            sc.finishDirectPlaySessionIfNeeded(this)
        } else if (listController?.isDataLoaded() != true || needsRefresh) {
            loadGames()
        }
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                val dialog = GameActionMenuFactory.createLauncherDialog(requireContext())
                val root = GameActionMenuFactory.createDialogRoot(requireContext())
                root.addView(GameActionMenuFactory.createDialogTitle(requireContext(), getString(com.core.R.string.core_file_access_title)))

                val info = TextView(requireContext())
                info.setText(com.core.R.string.pad_file_access_message)
                info.setTextColor(
                    ContextCompat.getColor(requireContext(), com.core.R.color.launcher_text_muted_color)
                )
                info.textSize = 12f
                info.setLineSpacing(dp(4).toFloat(), 1f)
                val infoLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                infoLp.setMargins(0, dp(13), 0, 0)
                root.addView(info, infoLp)

                root.addView(
                    GameActionMenuFactory.createDialogButton(
                        requireContext(), getString(com.core.R.string.core_go), true,
                        Runnable {
                            try {
                                startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:" + requireContext().packageName)
                                    )
                                )
                            } catch (t: Throwable) {
                                try {
                                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                } catch (ignored: Throwable) {
                                }
                            }
                        },
                        dialog
                    )
                )

                root.addView(GameActionMenuFactory.createDialogCancelButton(requireContext(), dialog))

                GameActionMenuFactory.setDialogContent(dialog, root, 288)
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            if (requireActivity().checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1001)
            }
        }
    }

    override fun onDestroyView() {
        sessionController?.cleanup()
        syncController?.cleanup()
        listController?.cleanup()
        _binding?.let { b ->
            b.root.setOnApplyWindowInsetsListener(null)
            b.libraryRecycler.adapter = null
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
            insets
        }
        currentBinding.root.requestApplyInsets()
    }

    private fun setupRecycler() {
        val newAdapter = PadManageGameAdapter()
        newAdapter.setOnGameCardListener(object : PadManageGameAdapter.OnGameCardListener {
            override fun onGameClick(game: Game?) {
                if (game != null) {
                    newAdapter.setSelectedGameId(game.id)
                    confirmLaunchGame(game)
                }
            }

            override fun onGameLongClick(game: Game?) {
                if (game != null) showGameActionMenu(game)
            }
        })
        adapter = newAdapter

        val gridColumns = GRID_COLUMNS
        val layoutManager = GridLayoutManager(requireContext(), gridColumns)
        binding.libraryRecycler.layoutManager = layoutManager
        binding.libraryRecycler.adapter = newAdapter
        binding.libraryRecycler.setHasFixedSize(true)
        binding.libraryRecycler.setItemViewCacheSize(pageSize)
        val pool = RecyclerView.RecycledViewPool()
        pool.setMaxRecycledViews(0, pageSize * 2)
        binding.libraryRecycler.setRecycledViewPool(pool)
        binding.libraryRecycler.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) updateAdaptiveCardHeight()
        }
        binding.libraryRecycler.post { updateAdaptiveCardHeight() }
        binding.libraryRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val lc = listController ?: return
                if (dy <= 0 || lc.isLoading || lc.isFullyLoaded) return
                if (layoutManager.findLastVisibleItemPosition() >= maxOf(0, lc.visibleGames.size - GRID_COLUMNS)) {
                    loadNextPage()
                }
            }
        })
    }

    /** Keeps compact landscape cards proportional to the actual available column width. */
    private fun updateAdaptiveCardHeight() {
        val b = _binding ?: return
        val currentAdapter = adapter ?: return

        val recyclerView = b.libraryRecycler
        val recyclerWidth = recyclerView.width
        if (recyclerWidth <= 0) return

        val usableWidth = recyclerWidth - recyclerView.paddingLeft - recyclerView.paddingRight
        if (usableWidth <= 0) return

        // item_launcher_game_card 每张卡片左右各约 5dp margin。
        val totalHorizontalMargins = dp(10) * GRID_COLUMNS
        val cardWidth = maxOf(1, (usableWidth - totalHorizontalMargins) / GRID_COLUMNS)

        currentAdapter.setFixedCardHeight(maxOf(dp(34), Math.round(cardWidth * 1.25f)))
    }

    private fun isTabletLayout(): Boolean {
        return resources.configuration.smallestScreenWidthDp >= TABLET_MIN_SMALLEST_WIDTH_DP
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
        binding.librarySyncButton.setOnClickListener { syncController?.showSyncDataConfirmDialog() }
        binding.libraryCollapseButton.setOnClickListener {
            categoriesCollapsed = !categoriesCollapsed
            binding.libraryCategoryScroll.visibility = if (categoriesCollapsed) View.GONE else View.VISIBLE
            renderToolbarButtonState()
        }
        binding.librarySearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim { it <= ' ' } ?: ""
                mainQueue.removeCallbacks(searchDebounce)
                searchDebounce = Runnable { applyFilters() }
                mainQueue.postDelayed(searchDebounce, 300)
            }

            override fun afterTextChanged(s: Editable?) {}
        })
        renderToolbarButtonState()
    }

    private fun loadGames() {
        needsRefresh = false
        listController?.loadGames()
    }

    private fun applyFilters() {
        listController?.applyFilters()
    }

    private fun applyFilters(forceFullRefresh: Boolean) {
        listController?.applyFilters(forceFullRefresh)
    }

    /**
     * Updates a single game in-place without reloading the entire list, preserving scroll position.
     * Used by long-press dialog actions (status, play time, favorite, cover sync, metadata rematch).
     * DiffUtil detects only the changed card and dispatches a single notifyItemChanged.
     */
    private fun updateSingleGame(updated: Game) {
        listController?.updateSingleGame(updated)
    }

    /** Removes a single game by id without reloading the entire list, preserving scroll position. */
    private fun removeSingleGame(gameId: Long) {
        listController?.removeSingleGame(gameId)
    }

    /** Re-fetches a single game from DB and updates it in-place, for async metadata operations. */
    override fun reloadSingleGame(gameId: Long) {
        listController?.reloadSingleGame(this, gameId)
    }

    private fun loadNextPage() {
        listController?.loadNextPage()
    }

    private fun loadNextPage(forceFullRefresh: Boolean) {
        listController?.loadNextPage(forceFullRefresh)
    }

    private fun renderState() {
        val b = _binding ?: return
        val lc = listController ?: return
        val hasGames = lc.visibleGames.isNotEmpty()
        b.libraryRecycler.visibility = if (hasGames) View.VISIBLE else View.GONE
        if (hasGames) {
            b.libraryRecycler.post { updateAdaptiveCardHeight() }
            scheduleLoadUntilViewportFilled()
        }
        b.libraryEmpty.setText(
            if (lc.allGames.isEmpty()) com.core.R.string.pad_no_games
            else com.core.R.string.pad_no_matching_games
        )
        b.libraryEmpty.visibility = if (hasGames) View.GONE else View.VISIBLE
    }

    /**
     * 使用 OnPreDrawListener 等待 RecyclerView 完成布局后再检测是否填满容器。
     * 若用 post() 检测，runnable 可能在 DiffUtil 触发的布局完成前运行，
     * canScrollVertically() 基于旧布局返回 true（误判为已填满），导致下一页无法自动加载。
     */
    private fun scheduleLoadUntilViewportFilled() {
        val b = _binding ?: return
        val lc = listController ?: return
        if (lc.isViewportFillCheckPending || lc.isLoading || lc.isFullyLoaded
            || lc.visibleGames.size >= lc.filteredGames.size
        ) {
            return
        }
        lc.setViewportFillCheckPending(true)
        val recyclerView = b.libraryRecycler
        val observer = recyclerView.viewTreeObserver
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val vto = recyclerView.viewTreeObserver
                vto.removeOnPreDrawListener(this)
                val currentLc = listController
                val currentBinding = _binding
                // 这一轮布局检查已完成；若首屏仍未填满，loadNextPage() 触发的
                // renderState() 才能登记下一轮检查并继续加载。
                currentLc?.setViewportFillCheckPending(false)
                if (currentLc == null || currentBinding == null || currentLc.isLoading
                    || currentLc.isFullyLoaded
                    || currentLc.visibleGames.size >= currentLc.filteredGames.size
                ) {
                    return true
                }
                // 列表无法向下滚动时，说明内容未填满容器，加载下一页
                if (!recyclerView.canScrollVertically(1)) {
                    currentLc.loadNextPage()
                }
                return true
            }
        }
        observer.addOnPreDrawListener(listener)
    }

    private fun confirmLaunchGame(game: Game?) {
        if (game == null) return
        PadDialogFactory.showConfirm(
            requireContext(), getString(com.core.R.string.pad_launch_game),
            getString(com.core.R.string.pad_launch_game_message, GameMetadataFormatter.safeTitle(game)),
            getString(com.core.R.string.core_confirm)
        ) {
            GamePasswordLock.interceptLaunch(this@PadManageFragment, game) {
                sessionController?.launchGameDirectly(this@PadManageFragment, game)
            }
        }
    }

    private fun showGameActionMenu(game: Game?) {
        if (game == null) return
        val config = GameActionMenuFactory.ActionMenuConfig()
        config.includeEditAction = false
        config.includeEditPlayTimeAction = true
        config.includeFavoriteAction = false
        config.includePasswordAction = false
        config.dialogWidthDp = 252
        GameActionMenuFactory.showGameActionMenu(this, game, config, this)
    }

    // ===== GameActionMenuFactory.ActionMenuCallbacks =====

    override fun onShowGameDetail(game: Game) {
        GameActionMenuFactory.showGameDetailDialog(this, game)
    }

    override fun onEditGame(game: Game) {
        // Pad 不支持从动作菜单编辑游戏，保留空实现以满足接口契约
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
        val dialog = GameActionMenuFactory.createLauncherDialog(requireContext())
        val root = GameActionMenuFactory.createDialogRoot(requireContext())
        root.addView(GameActionMenuFactory.createDialogTitle(requireContext(), getString(com.core.R.string.pad_more_options)))

        val favoriteLabel = getString(
            if (game.favorite) com.core.R.string.pad_remove_favorite else com.core.R.string.pad_add_favorite
        )
        val options: MutableList<Array<String>> = ArrayList()
        options.add(arrayOf(favoriteLabel, "favorite"))
        options.add(arrayOf(getString(com.core.R.string.pad_rematch_vndb), "rematch"))
        options.add(arrayOf(getString(com.core.R.string.pad_custom_search_vndb), "custom_vndb"))
        options.add(arrayOf(getString(com.core.R.string.pad_sync_cover), "sync"))
        // ONS 引擎游戏支持单独配置 ONS 引擎参数（编码/拉伸/锐化/视频/独立存档目录等）
        val isOns = game.engine == EngineType.ONS
        if (isOns) {
            options.add(arrayOf(getString(com.core.R.string.pad_ons_settings), "ons_settings"))
        }
        val hasPassword = GamePasswordLock.hasPassword(game)
        options.add(arrayOf(getString(if (hasPassword) com.core.R.string.pad_remove_password else com.core.R.string.pad_password_lock), "password"))
        options.add(arrayOf(getString(com.core.R.string.pad_delete_game), "delete"))

        for (opt in options) {
            val option = TextView(requireContext())
            option.text = opt[0]
            option.gravity = Gravity.CENTER
            option.textSize = 13f
            option.setTypeface(null, Typeface.BOLD)
            if (opt[1] == "delete") {
                LauncherTheme.dangerMenuItem(option)
            } else {
                LauncherTheme.menuItem(option)
            }
            val action = opt[1]
            option.setOnClickListener {
                dialog.dismiss()
                when (action) {
                    "favorite" -> toggleFavorite(game)
                    "rematch" -> syncController?.rematchMetadata(game)
                    "custom_vndb" -> LauncherCustomVndbSearchDialog.show(this, game) { reloadSingleGame(game.id) }
                    "sync" -> syncController?.syncMetadataToCard(game)
                    "ons_settings" -> openOnsGameSettings(game)
                    "password" -> {
                        if (hasPassword) GamePasswordLock.clearPassword(this, game, null)
                        else GamePasswordLock.setPassword(this, game, null)
                    }
                    "delete" -> confirmDeleteGame(game)
                }
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38))
            lp.setMargins(0, dp(11), 0, 0)
            root.addView(option, lp)
        }
        root.addView(GameActionMenuFactory.createDialogCancelButton(requireContext(), dialog))
        GameActionMenuFactory.setDialogContent(dialog, root, 252)
    }

    private fun openOnsGameSettings(game: Game) {
        try {
            val intent = Intent(requireContext(), LauncherKrkrSettingsActivity::class.java)
            intent.putExtra(LauncherKrkrSettingsActivity.EXTRA_GAME_ID, game.id)
            startActivity(intent)
        } catch (ignored: Throwable) {
            Toast.makeText(requireContext(), com.core.R.string.pad_cannot_open_ons_settings, Toast.LENGTH_SHORT).show()
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
                Log.w("PadManageFragment", "Failed to toggle favorite", e)
            }
            val result = updated
            mainQueue.post {
                if (!isAdded || view == null) return@post
                if (result != null) updateSingleGame(result)
            }
        }
    }

    private fun confirmDeleteGame(game: Game?) {
        if (game == null) return
        PadDialogFactory.showDangerConfirm(
            requireContext(), getString(com.core.R.string.pad_delete_game),
            getString(com.core.R.string.pad_delete_game_message, GameMetadataFormatter.safeTitle(game)),
            getString(com.core.R.string.pad_remove)
        ) { deleteGame(game) }
    }

    private fun deleteGame(game: Game) {
        // 在主线程捕获 ApplicationContext，避免 IO 线程内调用 fragment.requireContext()
        val app = requireContext().applicationContext
        AppExecutors.runOnSingle {
            val deleted = try {
                LauncherRepositoryBridge.deleteGame(app, game.id) > 0
            } catch (e: Exception) {
                Log.w("PadManageFragment", "Failed to delete game", e)
                false
            }
            mainQueue.post {
                if (!isAdded || view == null) return@post
                if (!deleted) {
                    Toast.makeText(app, com.core.R.string.pad_delete_failed, Toast.LENGTH_SHORT).show()
                    return@post
                }
                removeSingleGame(game.id)
                Toast.makeText(app, com.core.R.string.pad_deleted, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Pad 风格的同步加载对话框：包含进度文本（tag "sync_progress"），供 DialogFactory 调用。 */
    private fun createPadSyncLoadingDialog(titleText: String?, hintText: String?): AlertDialog {
        val dialog = AlertDialog.Builder(requireContext()).create()
        dialog.setCancelable(false)
        dialog.show()
        LauncherMotion.applyDialogMotion(dialog)

        val window: Window = dialog.window ?: return dialog
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.setLayout(
            PadDialogFactory.dialogWidthPx(requireContext(), PadDialogFactory.WIDTH_COMPACT_DP),
            WindowManager.LayoutParams.WRAP_CONTENT
        )

        val root = LinearLayout(requireContext())
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(22), dp(20), dp(22), dp(16))
        root.background = LauncherTheme.secondaryButton(requireContext(), 20f)

        val title = TextView(requireContext())
        title.text = titleText
        title.gravity = Gravity.CENTER
        title.setTextColor(ContextCompat.getColor(requireContext(), com.core.R.color.launcher_text_color))
        title.textSize = 16f
        title.setTypeface(null, Typeface.BOLD)
        root.addView(
            title,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        val progressBar = ProgressBar(requireContext())
        progressBar.isIndeterminate = true
        progressBar.indeterminateDrawable.setColorFilter(
            LauncherTheme.primary(requireContext()), PorterDuff.Mode.SRC_IN
        )
        val pbLp = LinearLayout.LayoutParams(dp(32), dp(32))
        pbLp.gravity = Gravity.CENTER_HORIZONTAL
        pbLp.setMargins(0, dp(14), 0, 0)
        root.addView(progressBar, pbLp)

        val progressText = TextView(requireContext())
        progressText.tag = "sync_progress"
        progressText.text = getString(com.core.R.string.pad_progress_complete, 0, 0)
        progressText.gravity = Gravity.CENTER
        progressText.setTextColor(
            ContextCompat.getColor(requireContext(), com.core.R.color.launcher_text_muted_color)
        )
        progressText.textSize = 12f
        val ptLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        ptLp.setMargins(0, dp(6), 0, 0)
        root.addView(progressText, ptLp)

        val hint = TextView(requireContext())
        hint.text = hintText
        hint.gravity = Gravity.CENTER
        hint.setTextColor(
            ContextCompat.getColor(requireContext(), com.core.R.color.launcher_text_muted_color)
        )
        hint.textSize = 11f
        val hintLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        hintLp.setMargins(0, dp(10), 0, 0)
        root.addView(hint, hintLp)

        window.setContentView(root)
        return dialog
    }

    // ===== GameSyncController.Listener =====

    override fun onBatchSyncComplete(loadedGames: List<Game>, categoryResult: CategoryBuildResult) {
        if (_binding == null) return
        val lc = listController ?: return
        // 关键：必须同步更新 controller 内部的 all 列表，否则后续 applyFilters()
        // 调用的 controller.rebuild() 仍会遍历旧 Game 对象，导致新封面无法刷新到卡片。
        lc.replaceAllGames(loadedGames)

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
        lc.setDataLoaded(true)

        // controller 已持有最新数据，applyFilters(true) 会强制全量刷新卡片
        applyFilters(true)
    }

    private fun renderCategories() {
        val b = _binding ?: return
        b.libraryCategoryRow.removeAllViews()
        addCategoryChip(getString(com.core.R.string.pad_all), "")
        for (category in categories) {
            addCategoryChip(category.label, category.value)
        }
    }

    private fun addCategoryChip(label: String?, value: String?) {
        val b = _binding ?: return
        val chip = TextView(requireContext())
        val selected = value == selectedCategory
        chip.text = label
        chip.isSingleLine = true
        chip.gravity = Gravity.CENTER
        chip.textSize = 12f
        chip.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        chip.tag = value
        if (selected) {
            chip.setTextColor(LauncherTheme.onPrimary(requireContext()))
            chip.background = LauncherTheme.selectedChip(requireContext())
        } else {
            LauncherTheme.menuItem(chip)
        }
        chip.setPadding(dp(13), 0, dp(13), 0)
        chip.setOnClickListener {
            selectedCategory = value ?: ""
            renderCategories()
            applyFilters()
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(29))
        lp.setMargins(0, 0, dp(7), 0)
        b.libraryCategoryRow.addView(chip, lp)
    }

    private fun renderToolbarButtonState() {
        val b = _binding ?: return
        applyToolbarIconTone(b.librarySyncButton)
        applyToolbarIconTone(b.librarySearchButton)
        applyToolbarIconTone(b.libraryCollapseButton)
    }

    private fun applyToolbarIconTone(view: ImageView) {
        view.imageTintList = ColorStateList.valueOf(LauncherTheme.primary(requireContext()))
        view.background = null
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
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

    override fun getPageSize(): Int {
        return pageSize
    }

    override fun usesHorizontalPaging(): Boolean {
        return false  // Pad 始终不使用横向分页
    }

    override fun onDataLoaded(categories: List<CategoryOption>, developers: Map<Long, List<String>>) {
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
        val lc = listController ?: return
        adapter?.submit(ArrayList(lc.visibleGames), forceFullRefresh)
    }

    override fun onRenderStateRequested() {
        renderState()
    }

    companion object {
        private const val GRID_COLUMNS = 6
        private const val TABLET_MIN_SMALLEST_WIDTH_DP = 600
    }
}
