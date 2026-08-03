package com.apps.home

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.apps.LauncherActivity
import com.apps.LauncherPreferences
import com.apps.LauncherThemeStyle
import com.apps.navigationOverlayBottomPadding
import com.apps.refreshNavigationOverlayInsets
import com.apps.account.LauncherDisclaimerActivity
import com.apps.agent.LocalAgentActivity
import com.apps.data.LauncherRepository
import com.apps.data.LauncherViewModel
import com.apps.game.GameSessionController
import com.apps.game.LauncherSaveCategoryActivity
import com.apps.settings.LauncherToolboxActivity
import com.apps.settings.LauncherAppSettingsActivity
import com.apps.settings.ResourceStationActivity
import com.apps.theme.LauncherDialogFactory
import com.apps.theme.LauncherMotion
import com.apps.theme.LauncherTheme
import com.apps.theme.LauncherThemeMenuActivity
import com.apps.util.LauncherUrlOpener
import com.apps.widget.AvatarCropActivity
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.databinding.FragmentLauncherHomeBinding
import com.core.launcherbridge.LauncherAuthBridge
import com.core.launcherbridge.LauncherRepositoryBridge
import com.core.launcherbridge.LauncherUpdateBridge
import com.core.model.Game
import com.core.util.AppExecutors
import com.core.util.RxMainScheduler
import com.core.util.SafeImageLoader
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

open class LauncherHomeFragment : Fragment() {

    protected var binding: FragmentLauncherHomeBinding? = null
    private lateinit var viewModel: LauncherViewModel
    private var sessionController: GameSessionController? = null

    private val avatarPickerLauncher: ActivityResultLauncher<PickVisualMediaRequest> =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) return@registerForActivityResult
            startCrop(uri)
        }

    private val cropLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
                val outputUri = result.data?.getStringExtra(AvatarCropActivity.EXTRA_OUTPUT_URI)
                if (!outputUri.isNullOrEmpty()) {
                    copyAvatarToInternal(Uri.parse(outputUri))
                }
            }
        }

    private fun startCrop(sourceUri: Uri) {
        val intent = Intent(requireContext(), AvatarCropActivity::class.java)
        intent.putExtra(AvatarCropActivity.EXTRA_INPUT_URI, sourceUri.toString())
        cropLauncher.launch(intent)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentLauncherHomeBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val currentBinding = requireNotNull(binding)
        if (usePortraitTabletScaler()) {
            LauncherTabletPortraitScaler.apply(currentBinding.root)
        }
        viewModel = ViewModelProvider(requireActivity()).get(LauncherViewModel::class.java)
        sessionController = GameSessionController(
            requireContext(),
            com.core.util.RxMainQueue(),
            object : GameSessionController.Listener {
                override fun reloadGame(gameId: Long) {
                    refreshPlayStats()
                }

                override fun reloadAllGames() {
                    refreshPlayStats()
                }
            }
        )

        if (applyHomeSystemBarInsets()) {
            applySystemBarInsets()
        }
        setupRecentList()
        currentBinding.launcherAvatarContainer.clipToOutline = true
        renderAvatar()
        applyThemeStyle()
        LauncherTheme.applyPrimaryTone(currentBinding.root)
        applyIconTone()
        bindActions()
        onHomeLayoutReady()
        observeState()
    }

    override fun onResume() {
        super.onResume()
        val currentBinding = binding ?: return
        refreshNavigationOverlayInsets()
        applyThemeStyle()
        LauncherTheme.applyPrimaryTone(currentBinding.root)
        applyIconTone()
        renderAvatar()
        if (sessionController?.hasActiveSession() == true) {
            sessionController?.finishDirectPlaySessionIfNeeded(this)
        } else {
            viewModel.refreshRecentItems(includeFavorites = includeFavoriteItems())
        }
    }

    override fun onDestroyView() {
        sessionController?.cleanup()
        binding?.root?.setOnApplyWindowInsetsListener(null)
        super.onDestroyView()
        binding = null
    }

    private fun applySystemBarInsets() {
        val currentBinding = binding ?: return
        val originalLeft = currentBinding.contentScroll.paddingLeft
        val originalTop = currentBinding.contentScroll.paddingTop
        val originalRight = currentBinding.contentScroll.paddingRight
        val originalBottom = currentBinding.contentScroll.paddingBottom

        currentBinding.root.setOnApplyWindowInsetsListener { _, insets ->
            currentBinding.contentScroll.setPadding(
                originalLeft,
                originalTop + insets.systemWindowInsetTop,
                originalRight,
                navigationOverlayBottomPadding(originalBottom)
            )
            insets
        }
        currentBinding.root.requestApplyInsets()
    }

    private fun bindActions() {
        val currentBinding = binding ?: return
        parentFragmentManager.setFragmentResultListener(
            LauncherHomeAccountBottomSheet.REQUEST_KEY,
            viewLifecycleOwner
        ) { _, result ->
            when (result.getString(LauncherHomeAccountBottomSheet.RESULT_ACTION)) {
                LauncherHomeAccountBottomSheet.ACTION_APP_SETTINGS -> {
                    startLauncherActivity(Intent(requireContext(), LauncherAppSettingsActivity::class.java))
                }
                LauncherHomeAccountBottomSheet.ACTION_THEME -> {
                    startLauncherActivity(Intent(requireContext(), LauncherThemeMenuActivity::class.java))
                }
                LauncherHomeAccountBottomSheet.ACTION_TONE -> confirmToggleTone()
                LauncherHomeAccountBottomSheet.ACTION_UPDATE -> checkUpdate()
                LauncherHomeAccountBottomSheet.ACTION_FEEDBACK -> showFeedbackOptions()
                LauncherHomeAccountBottomSheet.ACTION_DISCLAIMER -> openDisclaimer()
            }
        }
        currentBinding.launcherAvatarContainer.setOnClickListener { showChangeAvatarDialog() }
        currentBinding.actionProfileMenu.setOnClickListener {
            LauncherHomeAccountBottomSheet.show(parentFragmentManager)
        }
        currentBinding.actionSaveSlot.setOnClickListener {
            startLauncherActivity(Intent(requireContext(), LauncherSaveCategoryActivity::class.java))
        }
        currentBinding.actionResourceStation.setOnClickListener { showResourceStationDialog() }
        currentBinding.actionToolbox.setOnClickListener {
            startLauncherActivity(Intent(requireContext(), LauncherToolboxActivity::class.java))
        }
        currentBinding.actionAgent.setOnClickListener {
            startLauncherActivity(Intent(requireContext(), LocalAgentActivity::class.java))
        }
        currentBinding.recentRefresh.setOnRefreshListener {
            viewModel.refreshStats()
            viewModel.refreshRecentItems(
                showRefreshing = true,
                includeFavorites = includeFavoriteItems(),
            )
        }
    }

    protected open fun onHomeLayoutReady() = Unit

    /** HD 首页沿用业务逻辑，但不使用竖屏平板缩放器。 */
    protected open fun usePortraitTabletScaler(): Boolean = true

    /** 嵌入横屏 HD 容器时由外层 Activity 统一处理安全区。 */
    protected open fun applyHomeSystemBarInsets(): Boolean = true

    protected open fun applyIconTone() {
        val currentBinding = binding ?: return
        val darkMode = LauncherActivity.isLauncherDarkMode(requireContext())
        val white = android.graphics.Color.WHITE
        LauncherTheme.applyCardCircleIcon(currentBinding.actionProfileMenu, requireContext())
        applyIconTint(currentBinding.actionSaveSlotIcon, darkMode, white)
        applyIconTint(currentBinding.actionResourceStationIcon, darkMode, white)
        applyIconTint(currentBinding.actionToolboxIcon, darkMode, white)
        applyIconTint(currentBinding.actionAgentIcon, darkMode, white)
    }

    private fun applyIconTint(imageView: ImageView?, tint: Boolean, color: Int) {
        if (imageView == null) return
        if (tint) {
            imageView.setColorFilter(color)
        } else {
            imageView.clearColorFilter()
        }
    }

    private fun applyThemeStyle() {
        val currentBinding = binding ?: return
        currentBinding.homeStatsImage.setImageResource(LauncherThemeStyle.homeStatsImageRes(requireContext()))
        // default 主题 scrim 用 launcher_home_stats_scrim 资源,其他主题用 statsScrim。
        val isDefault = !LauncherActivity.isRinneTheme(requireContext())
            && !LauncherActivity.isAnriTheme(requireContext())
            && !LauncherActivity.isXinhaitianTheme(requireContext())
            && !LauncherActivity.isNatsumeTheme(requireContext())
        if (isDefault) {
            currentBinding.homeStatsScrim.setBackgroundResource(com.core.R.drawable.launcher_home_stats_scrim)
        } else {
            currentBinding.homeStatsScrim.background = LauncherTheme.statsScrim(requireContext())
        }
    }

    protected fun showResourceStationDialog() {
        val resourceOptions = arrayOf(
            getString(com.core.R.string.home_resource_aggregated_search) to "https://searchgal.top",
            getString(com.core.R.string.home_resource_kungal) to "https://www.kungal.com",
            getString(com.core.R.string.home_resource_shinnku) to "https://www.shinnku.com/",
            getString(com.core.R.string.home_resource_touch_gal) to "https://www.touchgal.ink/",
        )
        LauncherDialogFactory.showStandardActionChoices(
            requireContext(),
            getString(com.core.R.string.home_resource_station),
            Array<CharSequence>(resourceOptions.size) { resourceOptions[it].first },
        ) { index ->
            val resource = resourceOptions.getOrNull(index) ?: return@showStandardActionChoices
            val intent = Intent(requireContext(), ResourceStationActivity::class.java)
            intent.putExtra("resource_url", resource.second)
            intent.putExtra("resource_title", resource.first)
            startLauncherActivity(intent)
        }
    }

    private fun showFeedbackOptions() {
        val feedbackOptions = arrayOf(
            getString(com.core.R.string.home_github_repository) to
                "https://github.com/Weiss-UltimateSavior/RinneMobile",
            getString(com.core.R.string.home_qq_group) to
                "https://qun.qq.com/universal-share/share?ac=1&authKey=nZMa0s3mxxG1A0f%2BY0nAWmBYpul7FWTEDI6UWrzqb2IgKC4aDkUhvkV2AekAkW%2F1&busi_data=eyJncm91cENvZGUiOiIxNjM2MDM2MzUiLCJ0b2tlbiI6Im93eFRyY0tqNDdxK3FGQXlVZ0lhMEZGbWZWemphZnpYYW1kWWpPN1ViL3A0SkRUd1dEclMwZkM1bWI0UEYxME4iLCJ1aW4iOiIzMDg2Njc4NzU1In0%3D&data=bwoLG7XAPzqsvtfneNCQUUlu-HpX1yCn-6dkgd8ubDeBJKEPgd7wKYa6ym-EbW07Vapc3xm_o-iy0GbFHhZk5Q&svctype=4&tempid=h5_group_info",
        )
        LauncherDialogFactory.showStandardActionChoices(
            requireContext(),
            getString(com.core.R.string.home_feedback),
            Array<CharSequence>(feedbackOptions.size) { feedbackOptions[it].first },
        ) { index ->
            val url = feedbackOptions.getOrNull(index)?.second ?: return@showStandardActionChoices
            openExternalUrl(url)
        }
    }

    private fun confirmToggleTone() {
        if (LauncherActivity.isFollowingSystemTone(requireContext())) return
        val darkMode = LauncherActivity.isLauncherDarkMode(requireContext())
        val nextTone = getString(
            if (darkMode) com.core.R.string.home_light_mode else com.core.R.string.home_dark_mode
        )
        LauncherDialogFactory.showConfirm(
            requireContext(),
            getString(com.core.R.string.home_switch_tone),
            getString(com.core.R.string.home_switch_tone_message, nextTone),
            getString(com.core.R.string.core_confirm)
        ) {
            LauncherMotion.recreateWithToneOverlay(requireActivity()) {
                LauncherActivity.setLauncherDarkMode(requireContext(), !darkMode)
            }
        }
    }

    private fun setupRecentList() {
        val currentBinding = binding ?: return
        currentBinding.recentRefresh.setOnChildScrollUpCallback { _, _ ->
            binding?.contentScroll?.canScrollVertically(-1) == true
        }
    }

    private fun observeState() {
        viewModel.getLauncherState().observe(viewLifecycleOwner) { state ->
            val currentBinding = binding ?: return@observe
            currentBinding.recentRefresh.isRefreshing = state.isRecentRefreshing
            currentBinding.tvAccountMode.text = state.accountMode
            currentBinding.tvStateTitle.text = state.accountName
            currentBinding.tvGameCount.text = state.gameCount.toString()
            currentBinding.tvTotalPlayTime.text = state.totalPlayTime
            currentBinding.tvTodayPlayTime.text = state.todayPlayTime
            renderHomeLists(state)
        }
    }

    protected open fun renderHomeLists(state: LauncherViewModel.LauncherState) {
        renderRecentItems(state.recentItems)
    }

    protected open fun recentItemLayoutRes(): Int = com.core.R.layout.item_launcher_recent

    /** 默认首页保持五条单列动态；HD 首页可覆盖为多列海报网格。 */
    protected open fun recentDisplayLimit(): Int = 5

    protected open fun recentGridColumns(): Int = 1

    /** Base home does not load favorites; variants opt in with HomeStyle.X.needsFavorites. */
    protected open fun includeFavoriteItems(): Boolean = false

    protected open fun bindRecentItem(itemView: View, item: LauncherRepository.RecentItem) {
        val icon: TextView = itemView.findViewById(com.core.R.id.recentIcon)
        val title: TextView = itemView.findViewById(com.core.R.id.recentTitle)
        val meta: TextView = itemView.findViewById(com.core.R.id.recentMeta)
        val status: TextView = itemView.findViewById(com.core.R.id.recentStatus)
        icon.text = item.iconText
        title.text = item.title
        meta.text = item.timeAndDuration
        status.text = LauncherRepository.launchTypeLabel(requireContext(), item.launchType)
            .ifEmpty { getString(com.core.R.string.repo_played) }
    }

    private fun renderRecentItems(items: List<LauncherRepository.RecentItem>?) {
        val currentBinding = binding ?: return
        if (items.isNullOrEmpty()) {
            currentBinding.recentEmpty.visibility = View.VISIBLE
            currentBinding.recentList.visibility = View.GONE
            currentBinding.recentList.removeAllViews()
            return
        }
        currentBinding.recentEmpty.visibility = View.GONE
        currentBinding.recentList.visibility = View.VISIBLE
        currentBinding.recentList.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        val columns = recentGridColumns().coerceAtLeast(1)
        val visibleItems = items.take(recentDisplayLimit().coerceAtLeast(0))
        var currentRow: LinearLayout? = null
        for ((index, item) in visibleItems.withIndex()) {
            if (columns > 1 && index % columns == 0) {
                currentRow = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                }
                currentBinding.recentList.addView(currentRow)
            }
            val itemView = inflater.inflate(
                recentItemLayoutRes(),
                currentBinding.recentList,
                false
            )
            if (usePortraitTabletScaler()) {
                LauncherTabletPortraitScaler.apply(itemView)
            }
            bindRecentItem(itemView, item)
            LauncherTheme.applyPrimaryTone(itemView)
            itemView.setOnClickListener { confirmLaunchRecentGame(item) }
            itemView.setOnLongClickListener {
                confirmDeleteRecentItem(item)
                true
            }
            if (columns == 1) {
                currentBinding.recentList.addView(itemView)
            } else {
                itemView.layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ).apply {
                    setMargins(LauncherTheme.dp(requireContext(), 5), LauncherTheme.dp(requireContext(), 2), LauncherTheme.dp(requireContext(), 5), LauncherTheme.dp(requireContext(), 3))
                }
                currentRow?.addView(itemView)
            }
        }
        if (columns > 1 && visibleItems.isNotEmpty()) {
            val missing = (columns - visibleItems.size % columns) % columns
            repeat(missing) {
                currentRow?.addView(
                    View(requireContext()),
                    LinearLayout.LayoutParams(0, 0, 1f),
                )
            }
        }
    }

    protected fun confirmLaunchRecentGame(item: LauncherRepository.RecentItem) {
        confirmLaunchGame(item.gameId, item.title)
    }

    protected fun confirmLaunchGame(gameId: Long, title: String?) {
        if (!isAdded || binding == null) return
        val displayTitle = if (title == null || title.trim { it <= ' ' }.isEmpty()) {
            getString(com.core.R.string.home_this_game)
        } else {
            title
        }
        LauncherDialogFactory.showConfirm(
            requireContext(),
            getString(com.core.R.string.home_open_game),
            getString(com.core.R.string.home_open_game_message, displayTitle),
            getString(com.core.R.string.home_open_game)
        ) { launchRecentGame(gameId) }
    }

    private fun launchRecentGame(gameId: Long) {
        if (gameId <= 0) {
            Toast.makeText(requireContext(), com.core.R.string.home_game_info_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val app = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val game = try {
                withContext(Dispatchers.IO) {
                    LauncherRepositoryBridge.findGameById(app, gameId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("LauncherHomeFragment", "Failed to load recent game", e)
                Toast.makeText(app, com.core.R.string.home_game_read_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (game == null) {
                Toast.makeText(app, com.core.R.string.home_game_missing, Toast.LENGTH_SHORT).show()
                return@launch
            }
            sessionController?.launchGameDirectly(this@LauncherHomeFragment, game)
        }
    }

    protected fun confirmDeleteRecentItem(item: LauncherRepository.RecentItem) {
        if (!isAdded || binding == null) return
        val displayTitle = if (item.title == null || item.title.trim { it <= ' ' }.isEmpty()) {
            getString(com.core.R.string.home_this_activity)
        } else {
            item.title
        }
        LauncherDialogFactory.showConfirm(
            requireContext(),
            getString(com.core.R.string.home_delete_activity),
            getString(com.core.R.string.home_delete_activity_message, displayTitle),
            getString(com.core.R.string.home_delete)
        ) { viewModel.deleteRecentItem(item.sessionId) }
    }

    private fun refreshPlayStats() {
        if (!::viewModel.isInitialized) return
        viewModel.refreshStats()
        viewModel.refreshRecentItems(includeFavorites = includeFavoriteItems())
    }

    private fun showChangeAvatarDialog() {
        LauncherDialogFactory.showStandardConfirm(
            requireContext(),
            getString(com.core.R.string.home_change_avatar),
            getString(com.core.R.string.home_change_avatar_message),
            getString(com.core.R.string.core_confirm)
        ) {
            avatarPickerLauncher.launch(
                PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    .build()
            )
        }
    }

    private fun copyAvatarToInternal(sourceUri: Uri) {
        val app = requireContext().applicationContext
        // 用户确认后的文件与偏好持久化由应用级任务承载，不随 Home View 销毁而取消。
        AppExecutors.runOnSingle {
            val outFile = File(app.filesDir, "launcher_avatar.jpg")
            val savedUri = Uri.fromFile(outFile).toString()
            var tempFile: File? = null
            val success = try {
                val pendingFile =
                    File.createTempFile("launcher_avatar_", ".tmp", app.filesDir)
                tempFile = pendingFile
                val input = app.contentResolver.openInputStream(sourceUri)
                    ?: throw IllegalStateException("Unable to open avatar source")
                input.use {
                    FileOutputStream(pendingFile).use { out ->
                        val buffer = ByteArray(8192)
                        var n = it.read(buffer)
                        while (n > 0) {
                            out.write(buffer, 0, n)
                            n = it.read(buffer)
                        }
                        out.flush()
                        out.fd.sync()
                    }
                }
                // 临时文件与目标位于同一目录；原子替换失败时旧头像保持不变。
                Files.move(
                    pendingFile.toPath(),
                    outFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
                val homeCommitted = app.getSharedPreferences(LauncherPreferences.APP_PREFS, 0)
                    .edit().putString(KEY_PROFILE_AVATAR, savedUri).commit()
                if (homeCommitted) {
                    val profileCommitted = app.getSharedPreferences(LauncherPreferences.PROFILE_PREFS, 0)
                        .edit().putString("custom_avatar_uri", savedUri).commit()
                    SafeImageLoader.invalidateUri(savedUri)
                    if (!profileCommitted) {
                        Log.w("LauncherHomeFragment", "Failed to mirror avatar preference")
                    }
                }
                homeCommitted
            } catch (e: Exception) {
                Log.w("LauncherHomeFragment", "Failed to persist avatar", e)
                false
            } finally {
                tempFile?.let { pending ->
                    if (pending.exists() && !pending.delete()) {
                        Log.w("LauncherHomeFragment", "Failed to delete temporary avatar")
                    }
                }
            }
            RxMainScheduler.post {
                if (!isAdded || binding == null) return@post
                if (!success) {
                    Toast.makeText(app, com.core.R.string.home_avatar_save_failed, Toast.LENGTH_SHORT).show()
                    return@post
                }
                renderAvatar()
                Toast.makeText(app, com.core.R.string.home_avatar_updated, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderAvatar() {
        val currentBinding = binding ?: return
        // 优先使用主页头像，再检查个人页头像
        var avatar = prefs().getString(KEY_PROFILE_AVATAR, "")
        if (avatar == null || avatar.trim { it <= ' ' }.isEmpty()) {
            val profileAvatar = requireContext().getSharedPreferences(LauncherPreferences.PROFILE_PREFS, 0)
                .getString("custom_avatar_uri", "")
            if (profileAvatar != null && profileAvatar.trim { it <= ' ' }.isNotEmpty()) {
                avatar = profileAvatar
            }
        }
        // 更新首字母
        val nickname = if (LauncherAuthBridge.isLoggedIn(requireContext())) {
            LauncherAuthBridge.getNickname(requireContext())
        } else {
            ""
        }
        val initial = if (nickname.trim { it <= ' ' }.isNotEmpty()) {
            nickname.trim { it <= ' ' }.substring(0, 1).uppercase()
        } else {
            getString(com.core.R.string.launcher_avatar_fallback_initial)
        }
        currentBinding.launcherAvatarInitial.text = initial

        if (avatar == null || avatar.trim { it <= ' ' }.isEmpty()) {
            currentBinding.launcherAvatarImage.setImageDrawable(null)
            currentBinding.launcherAvatarImage.visibility = View.GONE
            currentBinding.launcherAvatarInitial.visibility = View.VISIBLE
            return
        }
        try {
            currentBinding.launcherAvatarImage.clipToOutline = true
            // 先显示回退态；缓存命中时 SafeImageLoader 会同步回填并立即覆盖此状态。
            currentBinding.launcherAvatarImage.visibility = View.GONE
            currentBinding.launcherAvatarInitial.visibility = View.VISIBLE
            if (!SafeImageLoader.loadUri(
                    currentBinding.launcherAvatarImage,
                    avatar,
                    SafeImageLoader.Callback { success ->
                        val cb = binding ?: return@Callback
                        if (success) {
                            cb.launcherAvatarImage.visibility = View.VISIBLE
                            cb.launcherAvatarInitial.visibility = View.GONE
                        } else {
                            showDefaultAvatar()
                        }
                    }
                )
            ) {
                showDefaultAvatar()
                return
            }
        } catch (error: RuntimeException) {
            // 头像加载兜底：SafeImageLoader 已内部返回 false，此处仅防运行时异常
            showDefaultAvatar()
        }
    }

    private fun showDefaultAvatar() {
        val currentBinding = binding ?: return
        val nickname = if (LauncherAuthBridge.isLoggedIn(requireContext())) {
            LauncherAuthBridge.getNickname(requireContext())
        } else {
            ""
        }
        val initial = if (nickname.trim { it <= ' ' }.isNotEmpty()) {
            nickname.trim { it <= ' ' }.substring(0, 1).uppercase()
        } else {
            getString(com.core.R.string.launcher_avatar_fallback_initial)
        }
        currentBinding.launcherAvatarInitial.text = initial
        currentBinding.launcherAvatarImage.setImageDrawable(null)
        currentBinding.launcherAvatarImage.visibility = View.GONE
        currentBinding.launcherAvatarInitial.visibility = View.VISIBLE
    }

    private fun prefs(): SharedPreferences =
        requireContext().applicationContext.getSharedPreferences(LauncherPreferences.APP_PREFS, android.content.Context.MODE_PRIVATE)

    private fun checkUpdate() {
        Toast.makeText(requireContext(), com.core.R.string.home_checking_update, Toast.LENGTH_SHORT).show()
        LauncherUpdateBridge.checkUpdate(requireContext(), object : LauncherUpdateBridge.Callback {
            override fun onResult(info: LauncherUpdateBridge.UpdateInfo?, currentVersion: String, hasUpdate: Boolean) {
                if (!isAdded) return
                showUpdateResultDialog(info, currentVersion, hasUpdate, null)
            }

            override fun onError(message: String) {
                if (!isAdded) return
                showUpdateResultDialog(null, "", false, message)
            }
        })
    }

    private fun showUpdateResultDialog(
        info: LauncherUpdateBridge.UpdateInfo?,
        currentVersion: String?,
        hasUpdate: Boolean,
        error: String?
    ) {
        LauncherTheme.showUpdateResultDialog(requireContext(), info, currentVersion, hasUpdate, error)
    }

    private fun openDisclaimer() {
        startLauncherActivity(Intent(requireContext(), LauncherDisclaimerActivity::class.java))
    }

    protected open fun startLauncherActivity(intent: Intent) {
        startActivity(intent)
        LauncherMotion.applyActivityOpen(requireActivity())
    }

    private fun openExternalUrl(url: String?) {
        // 统一走 LauncherUrlOpener：scheme 白名单校验 + ActivityNotFoundException 捕获
        if (!LauncherUrlOpener.open(requireContext(), url)) {
            Toast.makeText(requireContext(), com.core.R.string.home_cannot_open_link, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val KEY_PROFILE_AVATAR = "profile_avatar"
    }
}
