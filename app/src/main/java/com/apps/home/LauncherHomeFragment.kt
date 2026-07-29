package com.apps.home

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.apps.LauncherActivity
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
        LauncherTabletPortraitScaler.apply(currentBinding.root)
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

        applySystemBarInsets()
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
        applyThemeStyle()
        LauncherTheme.applyPrimaryTone(currentBinding.root)
        applyIconTone()
        renderAvatar()
        if (sessionController?.hasActiveSession() == true) {
            sessionController?.finishDirectPlaySessionIfNeeded(this)
        } else {
            viewModel.refreshRecentItems()
        }
    }

    override fun onPause() {
        super.onPause()
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
                originalBottom
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
            viewModel.refreshRecentItems(true)
        }
    }

    protected open fun onHomeLayoutReady() = Unit

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
        if (LauncherActivity.isRinneTheme(requireContext())) {
            currentBinding.homeStatsImage.setImageResource(com.core.R.drawable.launcher_home_stats_rinne_bg)
            currentBinding.homeStatsScrim.background = LauncherTheme.statsScrim(requireContext())
        } else if (LauncherActivity.isAnriTheme(requireContext())) {
            currentBinding.homeStatsImage.setImageResource(com.core.R.drawable.launcher_home_stats_bg_anri)
            currentBinding.homeStatsScrim.background = LauncherTheme.statsScrim(requireContext())
        } else if (LauncherActivity.isXinhaitianTheme(requireContext())) {
            currentBinding.homeStatsImage.setImageResource(com.core.R.drawable.launcher_home_stats_xinhaitian_bg)
            currentBinding.homeStatsScrim.background = LauncherTheme.statsScrim(requireContext())
        } else if (LauncherActivity.isNatsumeTheme(requireContext())) {
            currentBinding.homeStatsImage.setImageResource(com.core.R.drawable.launcher_home_stats_natsume_bg)
            currentBinding.homeStatsScrim.background = LauncherTheme.statsScrim(requireContext())
        } else {
            currentBinding.homeStatsImage.setImageResource(com.core.R.drawable.launcher_home_stats_bg)
            currentBinding.homeStatsScrim.setBackgroundResource(com.core.R.drawable.launcher_home_stats_scrim)
        }
    }

    private fun showResourceStationDialog() {
        val dialog = AlertDialog.Builder(requireContext()).create()
        dialog.show()
        LauncherMotion.applyDialogMotion(dialog)

        val window: Window = dialog.window ?: return
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.setLayout(dp(252), WindowManager.LayoutParams.WRAP_CONTENT)

        val root = LinearLayout(requireContext())
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(22), dp(20), dp(22), dp(16))
        root.setBackgroundResource(com.core.R.drawable.launcher_dialog_bg)

        val title = TextView(requireContext())
        title.setText(com.core.R.string.home_resource_station)
        title.gravity = android.view.Gravity.CENTER
        title.setTextColor(ContextCompat.getColor(requireContext(), com.core.R.color.launcher_text_color))
        title.textSize = 16f
        title.setTypeface(null, android.graphics.Typeface.BOLD)
        root.addView(
            title,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        addResourceOption(root, getString(com.core.R.string.home_resource_aggregated_search), "https://searchgal.top", dialog)
        addResourceOption(root, getString(com.core.R.string.home_resource_kungal), "https://www.kungal.com", dialog)
        addResourceOption(root, getString(com.core.R.string.home_resource_shinnku), "https://www.shinnku.com/", dialog)
        addResourceOption(root, "Touch Gal", "https://www.touchgal.ink/", dialog)

        val cancel = TextView(requireContext())
        cancel.setText(com.core.R.string.core_cancel)
        cancel.gravity = android.view.Gravity.CENTER
        cancel.setTextColor(LauncherTheme.primary(requireContext()))
        cancel.textSize = 13f
        cancel.setTypeface(null, android.graphics.Typeface.BOLD)
        cancel.background = LauncherTheme.cancelChip(requireContext())
        cancel.setOnClickListener { dialog.dismiss() }
        val cancelLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36))
        cancelLp.setMargins(0, dp(9), 0, 0)
        root.addView(cancel, cancelLp)

        window.setContentView(root)
    }

    private fun addResourceOption(root: LinearLayout, label: String?, url: String?, dialog: AlertDialog) {
        val option = TextView(requireContext())
        option.text = label
        option.gravity = android.view.Gravity.CENTER
        option.isSingleLine = true
        option.textSize = 13f
        option.setTypeface(null, android.graphics.Typeface.BOLD)
        LauncherTheme.menuItem(option)
        option.setOnClickListener {
            dialog.dismiss()
            val intent = Intent(requireContext(), ResourceStationActivity::class.java)
            intent.putExtra("resource_url", url)
            intent.putExtra("resource_title", label)
            startLauncherActivity(intent)
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36))
        lp.setMargins(0, dp(11), 0, 0)
        root.addView(option, lp)
    }

    private fun showFeedbackOptions() {
        val dialog = AlertDialog.Builder(requireContext()).create()
        dialog.show()
        LauncherMotion.applyDialogMotion(dialog)

        val window: Window = dialog.window ?: return
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.setLayout(dp(252), WindowManager.LayoutParams.WRAP_CONTENT)

        val root = LinearLayout(requireContext())
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(22), dp(20), dp(22), dp(16))
        root.setBackgroundResource(com.core.R.drawable.launcher_dialog_bg)

        val title = TextView(requireContext())
        title.setText(com.core.R.string.home_feedback)
        title.gravity = Gravity.CENTER
        title.setTextColor(ContextCompat.getColor(requireContext(), com.core.R.color.launcher_text_color))
        title.textSize = 16f
        title.setTypeface(null, android.graphics.Typeface.BOLD)
        root.addView(
            title,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        addFeedbackOption(root, getString(com.core.R.string.home_github_repository), dialog) {
            openExternalUrl("https://github.com/Weiss-UltimateSavior/RinneMobile")
        }
        addFeedbackOption(root, getString(com.core.R.string.home_qq_group), dialog) {
            openExternalUrl("https://qun.qq.com/universal-share/share?ac=1&authKey=nZMa0s3mxxG1A0f%2BY0nAWmBYpul7FWTEDI6UWrzqb2IgKC4aDkUhvkV2AekAkW%2F1&busi_data=eyJncm91cENvZGUiOiIxNjM2MDM2MzUiLCJ0b2tlbiI6Im93eFRyY0tqNDdxK3FGQXlVZ0lhMEZGbWZWemphZnpYYW1kWWpPN1ViL3A0SkRUd1dEclMwZkM1bWI0UEYxME4iLCJ1aW4iOiIzMDg2Njc4NzU1In0%3D&data=bwoLG7XAPzqsvtfneNCQUUlu-HpX1yCn-6dkgd8ubDeBJKEPgd7wKYa6ym-EbW07Vapc3xm_o-iy0GbFHhZk5Q&svctype=4&tempid=h5_group_info")
        }

        val cancel = TextView(requireContext())
        cancel.setText(com.core.R.string.core_cancel)
        cancel.gravity = Gravity.CENTER
        cancel.setTextColor(LauncherTheme.primary(requireContext()))
        cancel.textSize = 13f
        cancel.setTypeface(null, android.graphics.Typeface.BOLD)
        cancel.background = LauncherTheme.cancelChip(requireContext())
        cancel.setOnClickListener { dialog.dismiss() }
        val cancelLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36))
        cancelLp.setMargins(0, dp(9), 0, 0)
        root.addView(cancel, cancelLp)

        window.setContentView(root)
    }

    private fun addFeedbackOption(root: LinearLayout, label: String?, dialog: AlertDialog, action: Runnable) {
        val option = TextView(requireContext())
        option.text = label
        option.gravity = Gravity.CENTER
        option.isSingleLine = true
        option.textSize = 13f
        option.setTypeface(null, android.graphics.Typeface.BOLD)
        LauncherTheme.menuItem(option)
        option.setOnClickListener {
            dialog.dismiss()
            action.run()
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36))
        lp.setMargins(0, dp(11), 0, 0)
        root.addView(option, lp)
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
            renderRecentItems(state.recentItems)
        }
    }

    protected open fun recentItemLayoutRes(): Int = com.core.R.layout.item_launcher_recent

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
        for (item in items) {
            val itemView = inflater.inflate(
                recentItemLayoutRes(),
                currentBinding.recentList,
                false
            )
            LauncherTabletPortraitScaler.apply(itemView)
            bindRecentItem(itemView, item)
            LauncherTheme.applyPrimaryTone(itemView)
            itemView.setOnClickListener { confirmLaunchRecentGame(item) }
            itemView.setOnLongClickListener {
                confirmDeleteRecentItem(item)
                true
            }
            currentBinding.recentList.addView(itemView)
        }
    }

    private fun confirmLaunchRecentGame(item: LauncherRepository.RecentItem) {
        if (!isAdded || binding == null) return
        val displayTitle = if (item.title == null || item.title.trim { it <= ' ' }.isEmpty()) {
            getString(com.core.R.string.home_this_game)
        } else {
            item.title
        }
        LauncherDialogFactory.showConfirm(
            requireContext(),
            getString(com.core.R.string.home_open_game),
            getString(com.core.R.string.home_open_game_message, displayTitle),
            getString(com.core.R.string.home_open_game)
        ) { launchRecentGame(item.gameId) }
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

    private fun confirmDeleteRecentItem(item: LauncherRepository.RecentItem) {
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
        viewModel.refreshRecentItems()
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
                val homeCommitted = app.getSharedPreferences(APP_PREFS, 0)
                    .edit().putString(KEY_PROFILE_AVATAR, savedUri).commit()
                if (homeCommitted) {
                    val profileCommitted = app.getSharedPreferences("launcher_profile_prefs", 0)
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
                if (!isAdded || view == null) return@post
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
            val profileAvatar = requireContext().getSharedPreferences("launcher_profile_prefs", 0)
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
            "Y"
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
        } catch (throwable: Throwable) {
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
            "Y"
        }
        currentBinding.launcherAvatarInitial.text = initial
        currentBinding.launcherAvatarImage.setImageDrawable(null)
        currentBinding.launcherAvatarImage.visibility = View.GONE
        currentBinding.launcherAvatarInitial.visibility = View.VISIBLE
    }

    private fun prefs(): SharedPreferences =
        requireContext().applicationContext.getSharedPreferences(APP_PREFS, android.content.Context.MODE_PRIVATE)

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
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

    private fun startLauncherActivity(intent: Intent) {
        startActivity(intent)
        LauncherMotion.applyActivityOpen(requireActivity())
    }

    private fun openExternalUrl(url: String?) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (throwable: Throwable) {
            Toast.makeText(requireContext(), com.core.R.string.home_cannot_open_link, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val APP_PREFS = "yukihub_prefs"
        private const val KEY_PROFILE_AVATAR = "profile_avatar"
    }
}
