package com.apps

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.apps.HDModel.HdModeActivity
import com.apps.PadUi.PadGameModeActivity
import com.apps.account.LauncherAccountFragment
import com.apps.data.LauncherViewModel
import com.apps.game.GameActionMenuFactory
import com.apps.game.GameSessionController
import com.apps.game.LauncherLibraryFragment
import com.apps.game.LauncherManageFragment
import com.apps.game.PinnedGameShortcut
import com.apps.home.HomeStyle
import com.apps.home.LauncherHomeFragmentFactory
import com.apps.home.LauncherPlaceholderFragment
import com.apps.theme.LauncherDialogFactory
import com.apps.theme.LauncherMotion
import com.apps.theme.LauncherTheme
import com.core.util.DevLogger
import com.core.R
import com.core.databinding.ActivityLauncherBinding
import com.core.launcherbridge.LauncherGameLaunchBridge
import com.core.launcherbridge.LauncherUpdateBridge
import com.core.util.Disposable
import com.core.util.RxMainQueue
import com.core.util.RxMainScheduler

class LauncherActivity : AppCompatActivity() {

    private var binding: ActivityLauncherBinding? = null
    private var viewModel: LauncherViewModel? = null
    private var splashDelay: Disposable? = null
    private var pinnedGameSessionController: GameSessionController? = null
    private lateinit var navRenderer: LauncherNavRenderer
    private var appliedNavigationStyle = LauncherNavigationMetrics.Style.DEFAULT

    /** 暴露给 [LauncherNavRenderer] 使用的视图绑定。 */
    internal val launcherBinding: ActivityLauncherBinding? get() = binding

    /**
     * Splash 首帧绘制完成后再保留的最低展示时长，用于品牌曝光与状态栏图标色阶过渡。
     * 计时从首帧绘制完成后开始，保证用户选择的启动图能完整停留两秒。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_YukiHub_Launcher)
        LauncherUiMode.applySavedToneMode(this)
        super.onCreate(savedInstanceState)
        pinnedGameSessionController = GameSessionController(this, RxMainQueue(), object : GameSessionController.Listener {
            override fun reloadGame(gameId: Long) { refreshLauncherSnapshot() }
            override fun reloadAllGames() { refreshLauncherSnapshot() }
        })
        LauncherEdgeToEdgeHelper.apply(
            this,
            adjustResize = false,
            usePrimaryLuminanceForStatusBar = false,
            navigationBarColorRes = R.color.launcher_bottom_bar_color,
        )

        if (savedInstanceState != null || launcherSplashShownInProcess || !LauncherSplash.isSplashImageEnabled(this)) {
            showLauncherContent()
            return
        }
        launcherSplashShownInProcess = true
        // Android 12+ replaces a legacy window background with the system icon splash.
        // Draw the wallpaper as real Activity content so it is also visible on Honor/MagicOS.
        setContentView(R.layout.activity_launcher_splash)
        LauncherSplash.applyCustomSplashImage(this, findViewById(R.id.launcherSplashImage))
        scheduleLauncherContent()
    }

    /**
     * 在 splash 首帧绘制完成后再启动 [LauncherSplash.SPLASH_MIN_DISPLAY_MS] 倒计时，
     * 取代原先自 onCreate 起算的固定 1500ms 延时。
     *
     * 触发顺序：`setContentView` → 首次 measure/layout → OnPreDrawListener 回调 →
     * 两秒品牌曝光 → [showLauncherContent]。
     */
    private fun scheduleLauncherContent() {
        val content = findViewById<View>(android.R.id.content)
        val vto = content.viewTreeObserver
        vto.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                content.viewTreeObserver.removeOnPreDrawListener(this)
                splashDelay = RxMainScheduler.postDelayed(
                    Runnable { showLauncherContent() }, LauncherSplash.SPLASH_MIN_DISPLAY_MS
                )
                return true
            }
        })
    }

    private fun showLauncherContent() {
        if (isFinishing || isDestroyed) return
        val forcePortraitHome = intent?.getBooleanExtra(LauncherIntents.EXTRA_FORCE_PORTRAIT_HOME, false) == true
        if (LauncherPreferences.isHdModeStartupEnabled(this) && !forcePortraitHome) {
            startActivity(Intent(this, HdModeActivity::class.java))
            finish()
            return
        }
        if (LauncherPreferences.isLandscapeStartupPage(this) && !forcePortraitHome) {
            startActivity(Intent(this, PadGameModeActivity::class.java))
            finish()
            return
        }

        binding = ActivityLauncherBinding.inflate(layoutInflater)
        navRenderer = LauncherNavRenderer(this)
        val b = binding!!
        appliedNavigationStyle = LauncherNavigationMetrics.currentStyle(this)
        if (appliedNavigationStyle == LauncherNavigationMetrics.Style.LIQUID_GLASS) {
            navRenderer.refreshLiquidGlassThemeState()
            b.bottomNav.visibility = View.GONE
            b.bottomNavShadow.visibility = View.GONE
            b.bottomNavPill.visibility = View.GONE
            b.bottomNavCard.visibility = View.GONE
            b.bottomNavCardShadow.visibility = View.GONE
            val composeBackground = ComposeView(this).apply {
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
                )
                setContent {
                    LauncherComposeBackground(navRenderer.liquidGlassBackgroundColor.intValue)
                }
            }
            val xmlRoot = b.root as ViewGroup
            xmlRoot.removeView(b.launcherPortraitBackgroundImage)
            xmlRoot.removeView(b.launcherParticleView)
            xmlRoot.removeView(b.launcherFragmentContainer)
            val backdropHost = FrameLayout(this).apply {
                addView(composeBackground, matchParentLayoutParams())
                addView(b.launcherPortraitBackgroundImage, matchParentLayoutParams())
                addView(b.launcherParticleView, matchParentLayoutParams())
                addView(b.launcherFragmentContainer, matchParentLayoutParams())
            }
            val composeNavigation = ComposeView(this).apply {
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
                )
                setContent {
                    LauncherLiquidGlassHost(
                        launcherRoot = backdropHost,
                        selectedIndex = navRenderer.liquidGlassSelectedIndex.intValue,
                        darkMode = navRenderer.liquidGlassDarkMode.value,
                        primaryColor = navRenderer.liquidGlassPrimaryColor.intValue,
                        landscapeIcon = navRenderer.liquidGlassLandscapeIcon.intValue,
                        onItemClick = ::onLiquidGlassNavigationItemClick,
                        onLandscapeClick = ::onLiquidGlassLandscapeClick,
                    )
                }
            }
            val host = FrameLayout(this).apply {
                addView(backdropHost, matchParentLayoutParams())
                addView(composeNavigation, matchParentLayoutParams())
            }
            setContentView(host)
        } else {
            setContentView(b.root)
        }

        viewModel = ViewModelProvider(this).get(LauncherViewModel::class.java)

        renderPortraitBackground()
        renderParticles()
        requestStoragePermissionIfNeeded()
        bindActions()
        observeState()
        // onResume may already have run while the splash screen was visible. Load the
        // complete state here so a process/activity recreation cannot leave the home
        // stats card displaying LauncherState's default zero values until pull-to-refresh.
        refreshLauncherSnapshot()
        scheduleAutoUpdateCheck()
        openAccountLoginIfRequested(intent)
        launchPinnedGameIfRequested(intent)
    }

    override fun onDestroy() {
        splashDelay?.let { if (!it.isDisposed()) it.dispose() }
        pinnedGameSessionController?.cleanup()
        super.onDestroy()
    }

    /** Only the portrait SquareGrid home consumes favorite cards. */
    private fun refreshLauncherSnapshot() {
        viewModel?.refresh(
            includeFavorites = LauncherPreferences.getHomeStyle(this).needsFavorites,
        )
    }

    override fun onResume() {
        super.onResume()
        synchronizeToneModeFromPreferences()
        if (recreateIfLiquidGlassModeChanged()) return
        val controller = pinnedGameSessionController
        if (controller != null && controller.hasActiveSession()) {
            controller.finishDirectPlaySessionIfNeeded(this)
        }
        if (binding != null) {
            navRenderer.renderSelectedNav(navRenderer.currentNavItem)
            renderPortraitBackground()
            renderParticles()
            if (navRenderer.currentNavItem == LauncherViewModel.NavItem.HOME) {
                showFragment(LauncherViewModel.NavItem.HOME)
            }
        }
        viewModel?.refreshStats()
    }

    private fun recreateIfLiquidGlassModeChanged(): Boolean {
        if (binding == null) return false
        val currentStyle = LauncherNavigationMetrics.currentStyle(this)
        val hostChanged =
            (currentStyle == LauncherNavigationMetrics.Style.LIQUID_GLASS) !=
                (appliedNavigationStyle == LauncherNavigationMetrics.Style.LIQUID_GLASS)
        appliedNavigationStyle = currentStyle
        if (!hostChanged) return false
        recreate()
        return true
    }

    /**
     * 设置页返回时同步本 Activity 的局部 night mode。
     * 局部模式优先级高于 AppCompat 的全局默认值，因此自动色调切换后必须在此处刷新。
     */
    private fun synchronizeToneModeFromPreferences() {
        val desiredMode = if (LauncherPreferences.isFollowingSystemTone(this)) {
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        } else if (LauncherPreferences.isDarkMode(this)) {
            AppCompatDelegate.MODE_NIGHT_YES
        } else {
            AppCompatDelegate.MODE_NIGHT_NO
        }
        if (delegate.localNightMode == desiredMode) return
        delegate.setLocalNightMode(desiredMode)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openAccountLoginIfRequested(intent)
        launchPinnedGameIfRequested(intent)
    }

    private fun openAccountLoginIfRequested(intent: Intent?) {
        if (intent == null || !intent.getBooleanExtra(LauncherIntents.EXTRA_OPEN_ACCOUNT_LOGIN, false)) return
        val vm = viewModel ?: return
        intent.removeExtra(LauncherIntents.EXTRA_OPEN_ACCOUNT_LOGIN)
        vm.selectNavItem(LauncherViewModel.NavItem.ACCOUNT)
    }

    private fun launchPinnedGameIfRequested(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (LauncherIntents.ACTION_LAUNCH_PINNED_GAME != action
            && LauncherIntents.LEGACY_ACTION_LAUNCH_PINNED_GAME != action) return
        val gameId = intent.getLongExtra(LauncherIntents.EXTRA_PINNED_GAME_ID, -1L)
        intent.removeExtra(LauncherIntents.EXTRA_PINNED_GAME_ID)
        intent.action = null
        PinnedGameShortcut.launchPinnedGame(this, gameId, pinnedGameSessionController, object : PinnedGameShortcut.LaunchCallback {
            override fun onResult(result: LauncherGameLaunchBridge.LaunchResult) {
                if (isFinishing || isDestroyed) return
                if (!result.success && result.message.trim { it <= ' ' }.isNotEmpty()) {
                    if (result.activeGameConflict) {
                        GameActionMenuFactory.showActiveGameInfo(this@LauncherActivity, result.activeGameTitle)
                    } else {
                        Toast.makeText(this@LauncherActivity, result.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    private fun scheduleAutoUpdateCheck() {
        RxMainScheduler.postDelayed(Runnable {
            if (!isFinishing && !isDestroyed) {
                LauncherUpdateBridge.checkUpdate(this, object : LauncherUpdateBridge.Callback {
                    override fun onResult(info: LauncherUpdateBridge.UpdateInfo?, currentVersion: String, hasUpdate: Boolean) {
                        if (isFinishing || isDestroyed) return
                        if (hasUpdate) showAutoUpdateResult(info, currentVersion)
                    }

                    override fun onError(message: String) {
                        // 静默失败，不打扰用户
                    }
                })
            }
        }, 2000)
    }

    private fun showAutoUpdateResult(info: LauncherUpdateBridge.UpdateInfo?, currentVersion: String) {
        LauncherTheme.showUpdateResultDialog(this, info, currentVersion, true, null)
    }

    private fun requestStoragePermissionIfNeeded() {
        val prefs = getSharedPreferences(LauncherPreferences.APP_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(LauncherPreferences.KEY_STORAGE_PERMISSION_ASKED, false)) return
        prefs.edit().putBoolean(LauncherPreferences.KEY_STORAGE_PERMISSION_ASKED, true).apply()

        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                LauncherDialogFactory.showStoragePermissionRequest(
                    this,
                    Runnable {
                        try {
                            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:$packageName")))
                        } catch (error: ActivityNotFoundException) {
                            DevLogger.w("LauncherActivity", "App-specific storage settings unavailable; falling back", error)
                            try {
                                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                            } catch (fallbackError: ActivityNotFoundException) {
                                DevLogger.w("LauncherActivity", "All files access settings unavailable", fallbackError)
                            }
                        }
                    },
                    Runnable { }
                )
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1001)
        }
    }

    private fun bindActions() {
        val b = binding ?: return
        bindNavSelect(b.navHome, LauncherViewModel.NavItem.HOME)
        bindNavSelect(b.navSavings, LauncherViewModel.NavItem.LIBRARY)
        bindNavSelect(b.navCards, LauncherViewModel.NavItem.MANAGE)
        bindNavSelect(b.navAccount, LauncherViewModel.NavItem.ACCOUNT)
        bindNavSelect(b.navPillHome, LauncherViewModel.NavItem.HOME)
        bindNavSelect(b.navPillLibrary, LauncherViewModel.NavItem.LIBRARY)
        bindNavSelect(b.navPillManage, LauncherViewModel.NavItem.MANAGE)
        bindNavSelect(b.navPillAccount, LauncherViewModel.NavItem.ACCOUNT)
        bindNavSelect(b.navCardHome, LauncherViewModel.NavItem.HOME)
        bindNavSelect(b.navCardLibrary, LauncherViewModel.NavItem.LIBRARY)
        bindNavSelect(b.navCardManage, LauncherViewModel.NavItem.MANAGE)
        bindNavSelect(b.navCardAccount, LauncherViewModel.NavItem.ACCOUNT)
        b.navLaunchCenter.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            LauncherMotion.runAfterPulse(b.navLaunchCenterCircle, Runnable { confirmOpenPadGameModeActivity() })
        }
        b.navPillLaunchCenter.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            LauncherMotion.runAfterPulse(b.navPillLaunchCenter, Runnable { confirmOpenPadGameModeActivity() })
        }
    }

    private fun bindNavSelect(view: View, item: LauncherViewModel.NavItem) {
        view.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            viewModel?.selectNavItem(item)
        }
    }

    private fun onLiquidGlassNavigationItemClick(index: Int) {
        binding?.launcherFragmentContainer
            ?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        val item = when (index) {
            0 -> LauncherViewModel.NavItem.HOME
            1 -> LauncherViewModel.NavItem.LIBRARY
            2 -> LauncherViewModel.NavItem.MANAGE
            3 -> LauncherViewModel.NavItem.ACCOUNT
            else -> return
        }
        viewModel?.selectNavItem(item)
    }

    private fun onLiquidGlassLandscapeClick() {
        binding?.launcherFragmentContainer
            ?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        confirmOpenPadGameModeActivity()
    }

    private fun observeState() {
        val vm = viewModel ?: return
        vm.getLauncherState().observe(this) { state ->
            val selectedItem = state.selectedItem
            navRenderer.renderSelectedNav(selectedItem)
            showFragment(selectedItem)
        }
    }

    private fun showFragment(selectedItem: LauncherViewModel.NavItem?) {
        if (binding == null) return
        val navItem = selectedItem ?: LauncherViewModel.NavItem.HOME
        val currentFragment = supportFragmentManager.findFragmentById(R.id.launcherFragmentContainer)
        val homeStyle = LauncherPreferences.getHomeStyle(this)
        if (navRenderer.currentNavItem == navItem && currentFragment != null &&
            (navItem != LauncherViewModel.NavItem.HOME ||
                LauncherHomeFragmentFactory.matches(currentFragment, homeStyle))) {
            return
        }

        // 根据底部导航顺序判断左右方向：切到右侧 tab 时新页从右进、旧页往左出；反之亦然。
        val fromIndex = navRenderer.currentNavItem?.ordinal ?: 0
        val toRight = navItem.ordinal >= fromIndex
        navRenderer.currentNavItem = navItem
        val fragment: Fragment = when (navItem) {
            LauncherViewModel.NavItem.HOME -> LauncherHomeFragmentFactory.create(homeStyle)
            LauncherViewModel.NavItem.LIBRARY -> LauncherLibraryFragment()
            LauncherViewModel.NavItem.MANAGE -> LauncherManageFragment()
            LauncherViewModel.NavItem.ACCOUNT -> LauncherAccountFragment()
            else -> LauncherPlaceholderFragment.newInstance(placeholderTitle(navItem))
        }

        val enterAnim = if (toRight) R.anim.launcher_fragment_enter else R.anim.launcher_fragment_enter_back
        val exitAnim = if (toRight) R.anim.launcher_fragment_exit else R.anim.launcher_fragment_exit_back
        supportFragmentManager
            .beginTransaction()
            .setCustomAnimations(enterAnim, exitAnim, enterAnim, exitAnim)
            .replace(R.id.launcherFragmentContainer, fragment, "launcher_" + navItem.name)
            .commit()
    }

    private fun placeholderTitle(navItem: LauncherViewModel.NavItem): String {
        if (navItem == LauncherViewModel.NavItem.LIBRARY) return getString(R.string.core_game_library)
        if (navItem == LauncherViewModel.NavItem.MANAGE) return getString(R.string.core_manage)
        if (navItem == LauncherViewModel.NavItem.ACCOUNT) return getString(R.string.core_account_placeholder)
        return getString(R.string.core_home)
    }

    private fun openPadGameModeActivity() {
        val intent = Intent(this, PadGameModeActivity::class.java)
        startActivity(intent)
        LauncherMotion.applyActivityOpen(this)
    }

    private fun confirmOpenPadGameModeActivity() {
        LauncherDialogFactory.showConfirm(
            this,
            getString(R.string.core_landscape_mode_title),
            getString(R.string.core_landscape_mode_message),
            getString(R.string.core_confirm),
            Runnable { openPadGameModeActivity() }
        )
    }

    private fun renderParticles() {
        val b = binding ?: return
        val enabled = LauncherPreferences.isParticlesEnabled(this)
        b.launcherParticleView.visibility = if (enabled) View.VISIBLE else View.GONE
        b.launcherParticleView.setParticleStyle(LauncherPreferences.getParticleStyle(this))
        b.launcherParticleView.setParticlesEnabled(enabled)
    }

    private fun renderPortraitBackground() {
        LauncherPortraitBackground.apply(this, binding?.launcherPortraitBackgroundImage)
    }

    private fun matchParentLayoutParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
    )

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LauncherUiMode.wrap(newBase) ?: newBase)
    }

    companion object {
        private var launcherSplashShownInProcess = false

        // ===== 委托方法：实现已迁移至各 object，此处仅保留签名以兼容现有调用方 =====

        @JvmStatic
        fun setLauncherDarkMode(context: android.content.Context, darkMode: Boolean) =
            LauncherPreferences.setDarkMode(context, darkMode)

        @JvmStatic
        fun isLauncherDarkMode(context: android.content.Context): Boolean =
            LauncherPreferences.isDarkMode(context)

        @JvmStatic
        fun isFollowingSystemTone(context: android.content.Context): Boolean =
            LauncherPreferences.isFollowingSystemTone(context)

        @JvmStatic
        fun setFollowingSystemTone(context: android.content.Context, enabled: Boolean) =
            LauncherPreferences.setFollowingSystemTone(context, enabled)

        @JvmStatic
        fun setLauncherThemeStyle(context: android.content.Context, style: String?) =
            LauncherThemeStyle.setThemeStyle(context, style)

        @JvmStatic
        fun getLauncherThemeStyle(context: android.content.Context): String =
            LauncherThemeStyle.getThemeStyle(context)

        @JvmStatic
        fun isRinneTheme(context: android.content.Context): Boolean =
            LauncherThemeStyle.isRinne(context)

        @JvmStatic
        fun isAnriTheme(context: android.content.Context): Boolean =
            LauncherThemeStyle.isAnri(context)

        @JvmStatic
        fun isXinhaitianTheme(context: android.content.Context): Boolean =
            LauncherThemeStyle.isXinhaitian(context)

        @JvmStatic
        fun isNatsumeTheme(context: android.content.Context): Boolean =
            LauncherThemeStyle.isNatsume(context)

        @JvmStatic
        fun launcherPrimaryColor(context: android.content.Context): Int =
            LauncherThemeStyle.primaryColor(context)

        @JvmStatic
        fun setLauncherParticlesEnabled(context: android.content.Context, enabled: Boolean) =
            LauncherPreferences.setParticlesEnabled(context, enabled)

        @JvmStatic
        fun isLauncherParticlesEnabled(context: android.content.Context): Boolean =
            LauncherPreferences.isParticlesEnabled(context)

        @JvmStatic
        fun setLauncherParticleStyle(context: android.content.Context, style: String?) =
            LauncherPreferences.setParticleStyle(context, style)

        @JvmStatic
        fun getLauncherParticleStyle(context: android.content.Context): String =
            LauncherPreferences.getParticleStyle(context)

        @JvmStatic
        fun isLandscapeStartupPage(context: android.content.Context): Boolean =
            LauncherPreferences.isLandscapeStartupPage(context)

        @JvmStatic
        fun setLandscapeStartupPage(context: android.content.Context, enabled: Boolean) =
            LauncherPreferences.setLandscapeStartupPage(context, enabled)

        @JvmStatic
        fun isHdModeStartupEnabled(context: android.content.Context): Boolean =
            LauncherPreferences.isHdModeStartupEnabled(context)

        @JvmStatic
        fun setHdModeStartupEnabled(context: android.content.Context, enabled: Boolean) =
            LauncherPreferences.setHdModeStartupEnabled(context, enabled)

        @JvmStatic
        fun getHomeStyle(context: android.content.Context): HomeStyle =
            LauncherPreferences.getHomeStyle(context)

        @JvmStatic
        fun setHomeStyle(context: android.content.Context, style: HomeStyle) =
            LauncherPreferences.setHomeStyle(context, style)

        @JvmStatic
        fun applySavedToneMode(activity: androidx.appcompat.app.AppCompatActivity?) =
            LauncherUiMode.applySavedToneMode(activity)

        @JvmStatic
        fun wrapLauncherUiMode(base: android.content.Context?): android.content.Context? =
            LauncherUiMode.wrap(base)

        @JvmStatic
        fun customSplashImageFile(context: android.content.Context): java.io.File =
            LauncherSplash.customSplashImageFile(context)

        @JvmStatic
        fun hasCustomSplashImage(context: android.content.Context): Boolean =
            LauncherSplash.hasCustomSplashImage(context)

        @JvmStatic
        fun isSplashImageEnabled(context: android.content.Context): Boolean =
            LauncherSplash.isSplashImageEnabled(context)

        @JvmStatic
        fun setSplashImageEnabled(context: android.content.Context, enabled: Boolean) =
            LauncherSplash.setSplashImageEnabled(context, enabled)

        @JvmStatic
        fun applyCustomSplashImage(context: android.content.Context, imageView: android.widget.ImageView?) =
            LauncherSplash.applyCustomSplashImage(context, imageView)

        @JvmStatic
        fun customPortraitBackgroundFile(context: android.content.Context): java.io.File =
            LauncherPortraitBackground.customImageFile(context)

        @JvmStatic
        fun hasCustomPortraitBackground(context: android.content.Context): Boolean =
            LauncherPortraitBackground.hasCustomImage(context)

        @JvmStatic
        fun invalidateCustomPortraitBackground(context: android.content.Context) =
            LauncherPortraitBackground.invalidate(context)

        @JvmStatic
        fun isPillNavigationStyle(context: android.content.Context): Boolean =
            LauncherNavigationMetrics.isPillStyle(context)

        @JvmStatic
        fun setPillNavigationStyle(context: android.content.Context, enabled: Boolean) =
            LauncherNavigationMetrics.setPillStyle(context, enabled)

        @JvmStatic
        fun isCardNavigationStyle(context: android.content.Context): Boolean =
            LauncherNavigationMetrics.isCardStyle(context)

        @JvmStatic
        fun setCardNavigationStyle(context: android.content.Context, enabled: Boolean) =
            LauncherNavigationMetrics.setCardStyle(context, enabled)

        @JvmStatic
        fun isLiquidGlassNavigationStyle(context: android.content.Context): Boolean =
            LauncherNavigationMetrics.isLiquidGlassStyle(context)

        @JvmStatic
        fun setLiquidGlassNavigationStyle(context: android.content.Context, enabled: Boolean) =
            LauncherNavigationMetrics.setLiquidGlassStyle(context, enabled)

        @JvmStatic
        fun getNavigationOverlayBottomPadding(context: android.content.Context): Int =
            LauncherNavigationMetrics.overlayBottomPadding(context)
    }
}
