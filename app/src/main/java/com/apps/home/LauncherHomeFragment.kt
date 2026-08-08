package com.apps.home

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.apps.LauncherActivity
import com.apps.LauncherThemeStyle
import com.apps.navigationOverlayBottomPadding
import com.apps.refreshNavigationOverlayInsets
import com.apps.account.LauncherDisclaimerActivity
import com.apps.agent.LocalAgentActivity
import com.apps.common.LauncherInsetsHelper
import com.apps.data.LauncherRepository
import com.apps.data.LauncherViewModel
import com.apps.game.GameSessionController
import com.apps.game.LauncherSaveCategoryActivity
import com.apps.settings.LauncherToolboxActivity
import com.apps.settings.LauncherAppSettingsActivity
import com.apps.settings.ResourceStationActivity
import com.apps.settings.ResourceStationFragment
import com.apps.HDModel.LauncherDialogRouter
import com.apps.theme.LauncherMotion
import com.apps.theme.LauncherTheme
import com.apps.theme.LauncherThemeMenuActivity
import com.apps.util.LauncherUrlOpener
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.databinding.FragmentLauncherHomeBinding
import com.core.launcherbridge.LauncherRepositoryBridge
import com.core.launcherbridge.LauncherUpdateBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

open class LauncherHomeFragment : Fragment() {

    protected var binding: FragmentLauncherHomeBinding? = null
    private lateinit var viewModel: LauncherViewModel
    private var sessionController: GameSessionController? = null

    private val avatarController = LauncherAvatarController(this) { binding }

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
            LauncherInsetsHelper.applyTopInset(currentBinding.root, currentBinding.contentScroll) { navigationOverlayBottomPadding(it) }
        }
        setupRecentList()
        currentBinding.launcherAvatarContainer.clipToOutline = true
        avatarController.renderAvatar()
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
        avatarController.renderAvatar()
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
        currentBinding.launcherAvatarContainer.setOnClickListener { avatarController.showChangeAvatarDialog() }
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
        // 深色模式图标 tint 用白色（内容色，非页面取色；浅色模式 clearColorFilter 走资源原始色）
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
            && !LauncherActivity.isIzumiTheme(requireContext())
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
        LauncherDialogRouter.showStandardActionChoices(
            requireContext(),
            getString(com.core.R.string.home_resource_station),
            Array<CharSequence>(resourceOptions.size) { resourceOptions[it].first },
        ) { index ->
            val resource = resourceOptions.getOrNull(index) ?: return@showStandardActionChoices
            val intent = Intent(requireContext(), ResourceStationActivity::class.java)
            intent.putExtra(ResourceStationFragment.EXTRA_URL, resource.second)
            intent.putExtra(ResourceStationFragment.EXTRA_TITLE, resource.first)
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
        LauncherDialogRouter.showStandardActionChoices(
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
        LauncherDialogRouter.showConfirm(
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
        val currentBinding = binding ?: return
        LauncherRecentListRenderer.render(
            currentBinding,
            requireContext(),
            state.recentItems,
            recentItemLayoutRes(),
            recentDisplayLimit(),
            recentGridColumns(),
            usePortraitTabletScaler(),
            ::bindRecentItem,
            ::confirmLaunchRecentGame,
            ::confirmDeleteRecentItem,
        )
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
        LauncherDialogRouter.showConfirm(
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
        LauncherDialogRouter.showConfirm(
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
        // 头像键与文件名单源：com.apps.util.LauncherAvatarPersistence
    }
}
