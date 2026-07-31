package com.apps

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.InsetDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.apps.PadUi.PadGameModeActivity
import com.apps.HDModel.HdModeActivity
import com.apps.account.LauncherAccountFragment
import com.apps.data.LauncherViewModel
import com.apps.game.GameSessionController
import com.apps.game.LauncherLibraryFragment
import com.apps.game.LauncherManageFragment
import com.apps.game.PinnedGameShortcut
import com.apps.home.LauncherHomeFragment
import com.apps.home.LauncherFeaturedHomeFragment
import com.apps.home.LauncherPlaceholderFragment
import com.apps.theme.LauncherDialogFactory
import com.apps.theme.LauncherMotion
import com.apps.theme.LauncherTheme
import com.core.R
import com.core.databinding.ActivityLauncherBinding
import com.core.launcherbridge.LauncherGameLaunchBridge
import com.core.launcherbridge.LauncherUpdateBridge
import com.core.util.Disposable
import com.core.util.RxMainQueue
import java.io.File

class LauncherActivity : AppCompatActivity() {

    private var binding: ActivityLauncherBinding? = null
    private var viewModel: LauncherViewModel? = null
    private var currentNavItem: LauncherViewModel.NavItem? = null
    private var navIndicatorReady = false
    private var splashDelay: Disposable? = null
    private var pinnedGameSessionController: GameSessionController? = null
    private val liquidGlassSelectedIndex = mutableIntStateOf(0)
    private val liquidGlassPrimaryColor = mutableIntStateOf(Color.TRANSPARENT)
    private val liquidGlassDarkMode = mutableStateOf(false)
    private var appliedNavigationStyle = LauncherNavigationMetrics.Style.DEFAULT

    /**
     * Splash 首帧绘制完成后再保留的最低展示时长，用于品牌曝光与状态栏图标色阶过渡。
     * 计时从首帧绘制完成后开始，保证用户选择的启动图能完整停留两秒。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_YukiHub_Launcher)
        applySavedToneMode()
        super.onCreate(savedInstanceState)
        pinnedGameSessionController = GameSessionController(this, RxMainQueue(), object : GameSessionController.Listener {
            override fun reloadGame(gameId: Long) { viewModel?.refresh() }
            override fun reloadAllGames() { viewModel?.refresh() }
        })
        configureEdgeToEdgeWindow()

        if (savedInstanceState != null || launcherSplashShownInProcess || !isSplashImageEnabled(this)) {
            showLauncherContent()
            return
        }
        launcherSplashShownInProcess = true
        // Android 12+ replaces a legacy window background with the system icon splash.
        // Draw the wallpaper as real Activity content so it is also visible on Honor/MagicOS.
        setContentView(R.layout.activity_launcher_splash)
        applyCustomSplashImage(this, findViewById(R.id.launcherSplashImage))
        scheduleLauncherContent()
    }

    /**
     * 在 splash 首帧绘制完成后再启动 [SPLASH_MIN_DISPLAY_MS] 倒计时，
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
                splashDelay = com.core.util.RxMainScheduler.postDelayed(
                    Runnable { showLauncherContent() }, SPLASH_MIN_DISPLAY_MS
                )
                return true
            }
        })
    }

    private fun showLauncherContent() {
        if (isFinishing || isDestroyed) return
        val forcePortraitHome = intent?.getBooleanExtra(EXTRA_FORCE_PORTRAIT_HOME, false) == true
        if (isHdModeStartupEnabled(this) && !forcePortraitHome) {
            startActivity(Intent(this, HdModeActivity::class.java))
            finish()
            return
        }
        if (isLandscapeStartupPage(this) && !forcePortraitHome) {
            startActivity(Intent(this, PadGameModeActivity::class.java))
            finish()
            return
        }

        binding = ActivityLauncherBinding.inflate(layoutInflater)
        val b = binding!!
        appliedNavigationStyle = LauncherNavigationMetrics.currentStyle(this)
        if (appliedNavigationStyle == LauncherNavigationMetrics.Style.LIQUID_GLASS) {
            refreshLiquidGlassThemeState()
            hideXmlNavigation(b)
            val composeRoot = ComposeView(this).apply {
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
                )
                setContent {
                    LauncherLiquidGlassHost(
                        launcherRoot = b.root,
                        selectedIndex = liquidGlassSelectedIndex.intValue,
                        darkMode = liquidGlassDarkMode.value,
                        primaryColor = liquidGlassPrimaryColor.intValue,
                        onItemClick = ::onLiquidGlassNavigationItemClick,
                    )
                }
            }
            val host = FrameLayout(this).apply {
                addView(
                    b.root,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
                addView(
                    composeRoot,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
            setContentView(host)
        } else {
            setContentView(b.root)
        }

        viewModel = ViewModelProvider(this).get(LauncherViewModel::class.java)

        renderParticles()
        requestStoragePermissionIfNeeded()
        bindActions()
        observeState()
        // onResume may already have run while the splash screen was visible. Load the
        // complete state here so a process/activity recreation cannot leave the home
        // stats card displaying LauncherState's default zero values until pull-to-refresh.
        viewModel?.refresh()
        scheduleAutoUpdateCheck()
        openAccountLoginIfRequested(intent)
        launchPinnedGameIfRequested(intent)
    }

    override fun onDestroy() {
        splashDelay?.let { if (!it.isDisposed()) it.dispose() }
        pinnedGameSessionController?.cleanup()
        super.onDestroy()
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
            renderSelectedNav(currentNavItem)
            renderParticles()
            if (currentNavItem == LauncherViewModel.NavItem.HOME) {
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
     *
     */
    private fun synchronizeToneModeFromPreferences() {
        val desiredMode = if (isFollowingSystemTone(this)) {
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        } else if (isLauncherDarkMode(this)) {
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
        if (intent == null || !intent.getBooleanExtra(EXTRA_OPEN_ACCOUNT_LOGIN, false)) return
        val vm = viewModel ?: return
        intent.removeExtra(EXTRA_OPEN_ACCOUNT_LOGIN)
        vm.selectNavItem(LauncherViewModel.NavItem.ACCOUNT)
    }

    private fun launchPinnedGameIfRequested(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (ACTION_LAUNCH_PINNED_GAME != action
            && LEGACY_ACTION_LAUNCH_PINNED_GAME != action) return
        val gameId = intent.getLongExtra(EXTRA_PINNED_GAME_ID, -1L)
        intent.removeExtra(EXTRA_PINNED_GAME_ID)
        intent.action = null
        PinnedGameShortcut.launchPinnedGame(this, gameId, pinnedGameSessionController, object : PinnedGameShortcut.LaunchCallback {
            override fun onResult(result: LauncherGameLaunchBridge.LaunchResult) {
                if (!result.success && result.message.trim { it <= ' ' }.isNotEmpty()) {
                    if (result.activeGameConflict) {
                        LauncherGameLaunchBridge.showActiveGameDialog(this@LauncherActivity, result.activeGameTitle)
                    } else {
                        Toast.makeText(this@LauncherActivity, result.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    private fun configureEdgeToEdgeWindow() {
        val darkMode = isLauncherDarkMode(this)
        val window = window
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = ContextCompat.getColor(this, R.color.launcher_bottom_bar_color)
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        if (!darkMode) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = flags
    }

    private fun scheduleAutoUpdateCheck() {
        com.core.util.RxMainScheduler.postDelayed(Runnable {
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
        if (getSharedPreferences(APP_PREFS, MODE_PRIVATE).getBoolean(KEY_STORAGE_PERMISSION_ASKED, false)) return
        getSharedPreferences(APP_PREFS, MODE_PRIVATE).edit().putBoolean(KEY_STORAGE_PERMISSION_ASKED, true).apply()

        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                val dialog = AlertDialog.Builder(this).create()
                dialog.show()
                val window: Window? = dialog.window
                if (window == null) return
                window.setBackgroundDrawableResource(android.R.color.transparent)

                val root = LinearLayout(this)
                root.orientation = LinearLayout.VERTICAL
                root.setPadding(dp(22), dp(20), dp(22), dp(16))
                root.setBackgroundResource(R.drawable.launcher_dialog_bg)

                val title = TextView(this)
                title.setText(R.string.core_file_access_title)
                title.gravity = android.view.Gravity.CENTER
                title.setSingleLine(true)
                title.ellipsize = android.text.TextUtils.TruncateAt.END
                title.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_color))
                title.textSize = 16f
                title.setTypeface(null, android.graphics.Typeface.BOLD)
                root.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

                val info = TextView(this)
                info.setText(R.string.core_file_access_message)
                info.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_muted_color))
                info.textSize = 12f
                info.setLineSpacing(dp(4).toFloat(), 1f)
                val infoLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                infoLp.setMargins(0, dp(13), 0, 0)
                root.addView(info, infoLp)

                val goBtn = TextView(this)
                goBtn.setText(R.string.core_go)
                goBtn.gravity = android.view.Gravity.CENTER
                goBtn.textSize = 13f
                goBtn.setTypeface(null, android.graphics.Typeface.BOLD)
                LauncherTheme.primaryButton(goBtn)
                goBtn.setOnClickListener {
                    dialog.dismiss()
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")))
                    } catch (t: Throwable) {
                        try { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (ignored: Throwable) { }
                    }
                }
                val goLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38))
                goLp.setMargins(0, dp(9), 0, 0)
                root.addView(goBtn, goLp)

                val cancelBtn = TextView(this)
                cancelBtn.setText(R.string.core_cancel)
                cancelBtn.gravity = android.view.Gravity.CENTER
                cancelBtn.setTextColor(LauncherTheme.primary(this))
                cancelBtn.textSize = 13f
                cancelBtn.setTypeface(null, android.graphics.Typeface.BOLD)
                LauncherTheme.menuItem(cancelBtn)
                cancelBtn.setOnClickListener { dialog.dismiss() }
                val cancelLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38))
                cancelLp.setMargins(0, dp(9), 0, 0)
                root.addView(cancelBtn, cancelLp)

                window.setContentView(root)
                window.setLayout(dp(288), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1001)
        }
    }

    private fun bindActions() {
        val b = binding ?: return
        b.navHome.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            viewModel?.selectNavItem(LauncherViewModel.NavItem.HOME)
        }
        b.navSavings.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            viewModel?.selectNavItem(LauncherViewModel.NavItem.LIBRARY)
        }
        b.navCards.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            viewModel?.selectNavItem(LauncherViewModel.NavItem.MANAGE)
        }
        b.navAccount.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            viewModel?.selectNavItem(LauncherViewModel.NavItem.ACCOUNT)
        }
        b.navLaunchCenter.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            LauncherMotion.runAfterPulse(b.navLaunchCenterCircle, Runnable { confirmOpenPadGameModeActivity() })
        }
        b.navPillHome.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            viewModel?.selectNavItem(LauncherViewModel.NavItem.HOME)
        }
        b.navPillLibrary.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            viewModel?.selectNavItem(LauncherViewModel.NavItem.LIBRARY)
        }
        b.navPillManage.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            viewModel?.selectNavItem(LauncherViewModel.NavItem.MANAGE)
        }
        b.navPillAccount.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            viewModel?.selectNavItem(LauncherViewModel.NavItem.ACCOUNT)
        }
        b.navPillLaunchCenter.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            LauncherMotion.runAfterPulse(b.navPillLaunchCenter, Runnable { confirmOpenPadGameModeActivity() })
        }
        b.navCardHome.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            viewModel?.selectNavItem(LauncherViewModel.NavItem.HOME)
        }
        b.navCardLibrary.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            viewModel?.selectNavItem(LauncherViewModel.NavItem.LIBRARY)
        }
        b.navCardManage.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            viewModel?.selectNavItem(LauncherViewModel.NavItem.MANAGE)
        }
        b.navCardAccount.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            viewModel?.selectNavItem(LauncherViewModel.NavItem.ACCOUNT)
        }
    }

    private fun onLiquidGlassNavigationItemClick(index: Int) {
        binding?.root?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        when (index) {
            0 -> viewModel?.selectNavItem(LauncherViewModel.NavItem.HOME)
            1 -> viewModel?.selectNavItem(LauncherViewModel.NavItem.LIBRARY)
            2 -> viewModel?.selectNavItem(LauncherViewModel.NavItem.MANAGE)
            3 -> viewModel?.selectNavItem(LauncherViewModel.NavItem.ACCOUNT)
        }
    }

    private fun observeState() {
        val vm = viewModel ?: return
        vm.getLauncherState().observe(this) { state ->
            val selectedItem = state.selectedItem
            renderSelectedNav(selectedItem)
            showFragment(selectedItem)
        }
    }

    private fun showFragment(selectedItem: LauncherViewModel.NavItem?) {
        binding ?: return
        val navItem = selectedItem ?: LauncherViewModel.NavItem.HOME
        val currentFragment = supportFragmentManager.findFragmentById(R.id.launcherFragmentContainer)
        val expectedHomeFragment = if (isFeaturedHomeStyle(this)) {
            LauncherFeaturedHomeFragment::class.java
        } else {
            LauncherHomeFragment::class.java
        }
        if (currentNavItem == navItem && currentFragment != null &&
            (navItem != LauncherViewModel.NavItem.HOME || currentFragment.javaClass == expectedHomeFragment)) {
            return
        }

        // 根据底部导航顺序判断左右方向：切到右侧 tab 时新页从右进、旧页往左出；反之亦然。
        val fromIndex = currentNavItem?.ordinal ?: 0
        val toRight = navItem.ordinal >= fromIndex
        currentNavItem = navItem
        val fragment: Fragment
        if (navItem == LauncherViewModel.NavItem.HOME) {
            fragment = if (isFeaturedHomeStyle(this)) LauncherFeaturedHomeFragment() else LauncherHomeFragment()
        } else if (navItem == LauncherViewModel.NavItem.LIBRARY) {
            fragment = LauncherLibraryFragment()
        } else if (navItem == LauncherViewModel.NavItem.MANAGE) {
            fragment = LauncherManageFragment()
        } else if (navItem == LauncherViewModel.NavItem.ACCOUNT) {
            fragment = LauncherAccountFragment()
        } else {
            fragment = LauncherPlaceholderFragment.newInstance(placeholderTitle(navItem))
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

    private fun renderSelectedNav(selectedItem: LauncherViewModel.NavItem?) {
        val b = binding ?: return
        val navItem = selectedItem ?: LauncherViewModel.NavItem.HOME
        applyLauncherThemeTone()
        if (isLiquidGlassNavigationStyle(this)) {
            hideXmlNavigation(b)
            liquidGlassSelectedIndex.intValue = when (navItem) {
                LauncherViewModel.NavItem.HOME -> 0
                LauncherViewModel.NavItem.LIBRARY -> 1
                LauncherViewModel.NavItem.MANAGE -> 2
                LauncherViewModel.NavItem.ACCOUNT -> 3
            }
            return
        }
        if (isCardNavigationStyle(this)) {
            b.bottomNav.visibility = View.GONE
            b.bottomNavShadow.visibility = View.GONE
            b.bottomNavPill.visibility = View.GONE
            b.bottomNavCardShadow.visibility = View.VISIBLE
            b.bottomNavCard.visibility = View.VISIBLE
            renderCardNav(navItem)
            return
        }
        if (isPillNavigationStyle(this)) {
            b.bottomNav.visibility = View.GONE
            b.bottomNavShadow.visibility = View.GONE
            b.bottomNavPill.visibility = View.VISIBLE
            b.bottomNavCardShadow.visibility = View.GONE
            b.bottomNavCard.visibility = View.GONE
            renderPillNav(navItem)
            return
        }
        b.bottomNav.visibility = View.VISIBLE
        b.bottomNavShadow.visibility = View.VISIBLE
        b.bottomNavPill.visibility = View.GONE
        b.bottomNavCardShadow.visibility = View.GONE
        b.bottomNavCard.visibility = View.GONE
        setNavSelected(
            b.navHome,
            b.navHomeIcon,
            b.navHomeLabel,
            navItem == LauncherViewModel.NavItem.HOME
        )
        setNavSelected(
            b.navSavings,
            b.navSavingsIcon,
            b.navSavingsLabel,
            navItem == LauncherViewModel.NavItem.LIBRARY
        )
        setNavSelected(
            b.navCards,
            b.navCardsIcon,
            b.navCardsLabel,
            navItem == LauncherViewModel.NavItem.MANAGE
        )
        setNavSelected(
            b.navAccount,
            b.navAccountIcon,
            b.navAccountLabel,
            navItem == LauncherViewModel.NavItem.ACCOUNT
        )
        moveNavIndicator(navItem)
    }

    private fun hideXmlNavigation(b: ActivityLauncherBinding) {
        b.bottomNav.visibility = View.GONE
        b.bottomNavShadow.visibility = View.GONE
        b.bottomNavPill.visibility = View.GONE
        b.bottomNavCard.visibility = View.GONE
        b.bottomNavCardShadow.visibility = View.GONE
    }

    private fun renderPillNav(navItem: LauncherViewModel.NavItem) {
        val b = binding ?: return
        setPillNavSelected(b.navPillHome, b.navPillHomeIcon, b.navPillHomeLabel,
            navItem == LauncherViewModel.NavItem.HOME)
        setPillNavSelected(b.navPillLibrary, b.navPillLibraryIcon, b.navPillLibraryLabel,
            navItem == LauncherViewModel.NavItem.LIBRARY)
        setPillNavSelected(b.navPillManage, b.navPillManageIcon, b.navPillManageLabel,
            navItem == LauncherViewModel.NavItem.MANAGE)
        setPillNavSelected(b.navPillAccount, b.navPillAccountIcon, b.navPillAccountLabel,
            navItem == LauncherViewModel.NavItem.ACCOUNT)
        b.navPillLaunchCenterIcon.setColorFilter(LauncherTheme.primary(this))
    }

    private fun renderCardNav(navItem: LauncherViewModel.NavItem) {
        val b = binding ?: return
        setCardNavSelected(b.navCardHomeIcon, b.navCardHomeLabel,
            navItem == LauncherViewModel.NavItem.HOME)
        setCardNavSelected(b.navCardLibraryIcon, b.navCardLibraryLabel,
            navItem == LauncherViewModel.NavItem.LIBRARY)
        setCardNavSelected(b.navCardManageIcon, b.navCardManageLabel,
            navItem == LauncherViewModel.NavItem.MANAGE)
        setCardNavSelected(b.navCardAccountIcon, b.navCardAccountLabel,
            navItem == LauncherViewModel.NavItem.ACCOUNT)
        moveCardNavIndicator(navItem)
    }

    private fun setNavSelected(container: LinearLayout, icon: ImageView, label: TextView, selected: Boolean) {
        container.setBackgroundResource(R.drawable.launcher_nav_unselected)
        // 选中项始终使用当前主题主色；未选中项在浅色、深色模式下统一使用灰色。
        val color = if (selected) launcherPrimaryColor(this) else Color.GRAY
        icon.setColorFilter(color)
        label.setTextColor(color)
        label.setTypeface(null, android.graphics.Typeface.BOLD)
    }

    private fun setPillNavSelected(
        container: LinearLayout,
        icon: ImageView,
        label: TextView,
        selected: Boolean
    ) {
        (container.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.weight = if (selected) 1.55f else 0.8625f
            params.height = dp(if (selected) 44 else 45)
            params.marginStart = 0
            params.marginEnd = 0
            container.layoutParams = params
        }
        container.background = if (selected) {
            InsetDrawable(LauncherTheme.selectedChip(this), dp(4), 0, dp(6), 0)
        } else {
            null
        }
        val color = if (selected) LauncherTheme.onPrimary(this) else LauncherTheme.textMuted(this)
        icon.setColorFilter(color)
        label.setTextColor(color)
        label.visibility = if (selected) View.VISIBLE else View.GONE
    }

    private fun setCardNavSelected(
        icon: ImageView,
        label: TextView,
        selected: Boolean
    ) {
        val color = if (selected) LauncherTheme.primary(this) else LauncherTheme.textMuted(this)
        icon.setColorFilter(color)
        label.setTextColor(color)
        label.visibility = if (selected) View.VISIBLE else View.GONE
    }

    private fun moveCardNavIndicator(navItem: LauncherViewModel.NavItem) {
        val b = binding ?: return
        val target = cardNavTarget(navItem) ?: return
        if (!b.cardNavItems.isLaidOut || target.width <= 0) {
            b.cardNavItems.post { moveCardNavIndicator(navItem) }
            return
        }
        val indicatorWidth = (target.width * 0.72f).toInt()
        val left = target.left + (target.width - indicatorWidth) / 2
        val params = b.cardNavSelectionIndicator.layoutParams as FrameLayout.LayoutParams
        if (params.width != indicatorWidth) {
            params.width = indicatorWidth
            b.cardNavSelectionIndicator.layoutParams = params
        }
        b.cardNavSelectionIndicator.background = LauncherTheme.solidPrimary(this, 2f)
        b.cardNavSelectionIndicator.animate().cancel()
        b.cardNavSelectionIndicator.animate()
            .translationX(left.toFloat())
            .setDuration(220L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withLayer()
            .start()
    }

    private fun moveNavIndicator(navItem: LauncherViewModel.NavItem) {
        val b = binding ?: return
        val target = navTarget(navItem) ?: return
        if (!b.bottomNav.isLaidOut || target.width <= 0) {
            b.bottomNav.post { moveNavIndicator(navItem) }
            return
        }

        // 指示器与 bottomNavItems 都是 bottomNav 的子 View，且默认水平 gravity 均为 start，
        // 二者 left 都等于 bottomNav 的 paddingLeft，所以只需用 target 在 bottomNavItems
        // 内部的 left 作为 translationX，避免重复叠加 paddingLeft 导致指示器整体右移。
        val left = target.left
        val params = b.navSelectionIndicator.layoutParams as FrameLayout.LayoutParams
        if (params.width != target.width) {
            params.width = target.width
            b.navSelectionIndicator.layoutParams = params
        }
        b.navSelectionIndicator.setBackgroundResource(R.drawable.launcher_nav_selected)
        if (!navIndicatorReady) {
            b.navSelectionIndicator.translationX = left.toFloat()
            navIndicatorReady = true
            return
        }
        b.navSelectionIndicator.animate().cancel()
        b.navSelectionIndicator.animate()
            .translationX(left.toFloat())
            .setDuration(220L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withLayer()
            .start()
    }

    private fun navTarget(navItem: LauncherViewModel.NavItem): View? {
        val b = binding ?: return null
        if (navItem == LauncherViewModel.NavItem.LIBRARY) return b.navSavings
        if (navItem == LauncherViewModel.NavItem.MANAGE) return b.navCards
        if (navItem == LauncherViewModel.NavItem.ACCOUNT) return b.navAccount
        return b.navHome
    }

    private fun cardNavTarget(navItem: LauncherViewModel.NavItem): View? {
        val b = binding ?: return null
        if (navItem == LauncherViewModel.NavItem.LIBRARY) return b.navCardLibrary
        if (navItem == LauncherViewModel.NavItem.MANAGE) return b.navCardManage
        if (navItem == LauncherViewModel.NavItem.ACCOUNT) return b.navCardAccount
        return b.navCardHome
    }

    private fun applyLauncherThemeTone() {
        val b = binding ?: return
        refreshLiquidGlassThemeState()
        b.navLaunchCenterCircle.background = LauncherTheme.circleWithSoftShadow(this)
        val rinneTheme = isRinneTheme(this)
        val anriTheme = isAnriTheme(this)
        val xinhaitianTheme = isXinhaitianTheme(this)
        val natsumeTheme = isNatsumeTheme(this)
        val themedIcon = rinneTheme || anriTheme || xinhaitianTheme || natsumeTheme
        b.navLaunchCenterImage.visibility = if (themedIcon) View.GONE else View.VISIBLE
        b.navLaunchCenterText.visibility = if (themedIcon) View.VISIBLE else View.GONE
        if (rinneTheme) {
            b.navLaunchCenterText.setImageResource(R.drawable.launcher_theme_rinne_def)
            b.navLaunchCenterImage.clearColorFilter()
            b.navLaunchCenterText.setColorFilter(Color.WHITE)
        } else if (anriTheme) {
            b.navLaunchCenterText.setImageResource(R.drawable.launcher_theme_anri_def)
            b.navLaunchCenterImage.clearColorFilter()
            b.navLaunchCenterText.setColorFilter(Color.WHITE)
        } else if (xinhaitianTheme) {
            b.navLaunchCenterText.setImageResource(R.drawable.launcher_theme_xinhaitian_def)
            b.navLaunchCenterImage.clearColorFilter()
            b.navLaunchCenterText.setColorFilter(Color.WHITE)
        } else if (natsumeTheme) {
            b.navLaunchCenterText.setImageResource(R.drawable.launcher_theme_natsume_def)
            b.navLaunchCenterImage.clearColorFilter()
            b.navLaunchCenterText.setColorFilter(Color.WHITE)
        } else {
            b.navLaunchCenterImage.setColorFilter(Color.WHITE)
        }
        applyCenterLogoScale(b.navLaunchCenterText, rinneTheme, anriTheme, xinhaitianTheme, natsumeTheme)
        b.navPillLaunchCenterIcon.apply {
            when {
                rinneTheme -> setImageResource(R.drawable.launcher_theme_rinne_def)
                anriTheme -> setImageResource(R.drawable.launcher_theme_anri_def)
                xinhaitianTheme -> setImageResource(R.drawable.launcher_theme_xinhaitian_def)
                natsumeTheme -> setImageResource(R.drawable.launcher_theme_natsume_def)
                else -> setImageResource(R.drawable.launcher_game_center_default)
            }
            setColorFilter(LauncherTheme.primary(this@LauncherActivity))
        }
        applyCenterLogoScale(b.navPillLaunchCenterIcon, rinneTheme, anriTheme, xinhaitianTheme, natsumeTheme)
    }

    private fun refreshLiquidGlassThemeState() {
        liquidGlassPrimaryColor.intValue = LauncherTheme.primary(this)
        liquidGlassDarkMode.value = isLauncherDarkMode()
    }

    /**
     * 主题 Logo 的 PNG 透明边距并不一致；按默认游戏中心 Logo 的可视范围校正缩放。
     * 每个主题只使用一个缩放比例，避免为补偿画布留白而拉伸图案本身。
     */
    private fun applyCenterLogoScale(
        logo: ImageView,
        rinneTheme: Boolean,
        anriTheme: Boolean,
        xinhaitianTheme: Boolean,
        natsumeTheme: Boolean
    ) {
        val scale = when {
            rinneTheme -> 1.09f
            anriTheme -> 1.29f
            xinhaitianTheme -> 1.14f
            natsumeTheme -> 1.02f
            else -> 1f
        }
        logo.scaleX = scale
        logo.scaleY = scale
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
        val enabled = isLauncherParticlesEnabled(this)
        b.launcherParticleView.visibility = if (enabled) View.VISIBLE else View.GONE
        b.launcherParticleView.setParticleStyle(getLauncherParticleStyle(this))
        b.launcherParticleView.setParticlesEnabled(enabled)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun isLauncherDarkMode(): Boolean {
        return isLauncherDarkMode(this)
    }

    private fun applySavedToneMode() {
        applySavedToneMode(this)
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(wrapLauncherUiMode(newBase) ?: newBase)
    }

    companion object {
        @Volatile
        private var launcherSplashShownInProcess = false

        private const val SPLASH_MIN_DISPLAY_MS = 2_000L
        const val EXTRA_OPEN_ACCOUNT_LOGIN = "open_account_login"
        const val EXTRA_PINNED_GAME_ID = "pinned_game_id"
        const val EXTRA_FORCE_PORTRAIT_HOME = "force_portrait_home"
        const val ACTION_LAUNCH_PINNED_GAME = "com.core.action.LAUNCH_PINNED_GAME"
        // Keep shortcuts pinned before the package refactor working after an app update.
        private const val LEGACY_ACTION_LAUNCH_PINNED_GAME = "com.yuki.yukihub.action.LAUNCH_PINNED_GAME"
        const val APP_PREFS = "yukihub_prefs"
        private const val KEY_START_LANDSCAPE_PAGE = "launcher_start_landscape_page"
        private const val KEY_HD_MODE_STARTUP = "launcher_hd_mode_startup"
        private const val KEY_FEATURED_HOME_STYLE = "launcher_featured_home_style"
        private const val KEY_SPLASH_ENABLED = "launcher_splash_enabled"
        private const val KEY_FOLLOW_SYSTEM_TONE = "launcher_follow_system_tone"
        private const val CUSTOM_SPLASH_IMAGE_FILE = "launcher_splash_image"
        private const val KEY_STORAGE_PERMISSION_ASKED = "launcher_storage_permission_asked"
        const val KEY_LAUNCHER_DARK_MODE = "launcher_dark_mode"
        const val KEY_LAUNCHER_THEME_STYLE = "launcher_theme_style"
        const val KEY_LAUNCHER_PARTICLES_ENABLED = "launcher_particles_enabled"
        const val KEY_LAUNCHER_PARTICLE_STYLE = "launcher_particle_style"
        const val PARTICLE_STYLE_FLOATING = "floating"
        const val PARTICLE_STYLE_RAIN = "rain"
        const val PARTICLE_STYLE_STAR = "star"
        const val PARTICLE_STYLE_SAKURA = "sakura"
        const val PARTICLE_STYLE_FIREFLIES = "fireflies"
        const val PARTICLE_STYLE_CONSTELLATION = "constellation"
        const val PARTICLE_STYLE_RIPPLES = "ripples"
        const val THEME_STYLE_DEFAULT = "default"
        const val THEME_STYLE_RINNE = "rinne"
        const val THEME_STYLE_ANRI = "anri"
        const val THEME_STYLE_XINHAITIAN = "xinhaitian"
        const val THEME_STYLE_NATSUME = "natsume"

        @JvmField
        val RINNE_PRIMARY_COLOR: Int = Color.rgb(216, 169, 201)
        @JvmField
        val ANRI_PRIMARY_COLOR: Int = Color.rgb(77, 53, 89)
        @JvmField
        val XINHAITIAN_PRIMARY_COLOR: Int = Color.rgb(122, 131, 203)
        @JvmField
        val XINHAITIAN_ACCENT_COLOR: Int = Color.rgb(237, 173, 201)
        @JvmField
        val NATSUME_PRIMARY_COLOR: Int = Color.rgb(197, 57, 58)

        @JvmStatic
        fun setLauncherDarkMode(context: android.content.Context, darkMode: Boolean) {
            if (isFollowingSystemTone(context)) return
            context.applicationContext
                .getSharedPreferences(APP_PREFS, android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_LAUNCHER_DARK_MODE, darkMode)
                .apply()
            // 同步更新进程级 night mode 默认值，确保后续未被 recreate 的 AppCompat 组件
            // （如残留的 Dialog / Fragment）也能立刻命中新色调，而非等到下次冷启动。
            AppCompatDelegate.setDefaultNightMode(
                if (darkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
        }

        @JvmStatic
        fun isLauncherDarkMode(context: android.content.Context): Boolean {
            if (isFollowingSystemTone(context)) {
                return (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
            }
            return context.applicationContext
                .getSharedPreferences(APP_PREFS, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_LAUNCHER_DARK_MODE, false)
        }

        /** 是否由系统夜间模式自动决定 Launcher 的深浅色。 */
        @JvmStatic
        fun isFollowingSystemTone(context: android.content.Context): Boolean =
            context.applicationContext
                .getSharedPreferences(APP_PREFS, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_FOLLOW_SYSTEM_TONE, false)

        /**
         * 设置 Launcher 色调来源。开启后全局及后续 Activity 均跟随系统；关闭后恢复已保存的手动色调。
         */
        @JvmStatic
        fun setFollowingSystemTone(context: android.content.Context, enabled: Boolean) {
            val appContext = context.applicationContext
            val preferences = appContext.getSharedPreferences(APP_PREFS, android.content.Context.MODE_PRIVATE)
            preferences.edit().putBoolean(KEY_FOLLOW_SYSTEM_TONE, enabled).apply()
            val manualDarkMode = preferences.getBoolean(KEY_LAUNCHER_DARK_MODE, false)
            AppCompatDelegate.setDefaultNightMode(
                if (enabled) AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                else if (manualDarkMode) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
            )
        }

        @JvmStatic
        fun setLauncherThemeStyle(context: android.content.Context, style: String?) {
            val value = if (THEME_STYLE_RINNE == style
                || THEME_STYLE_ANRI == style
                || THEME_STYLE_XINHAITIAN == style
                || THEME_STYLE_NATSUME == style
            ) style else THEME_STYLE_DEFAULT
            context.applicationContext
                .getSharedPreferences(APP_PREFS, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAUNCHER_THEME_STYLE, value)
                .apply()
        }

        @JvmStatic
        fun getLauncherThemeStyle(context: android.content.Context): String {
            return context.applicationContext
                .getSharedPreferences(APP_PREFS, android.content.Context.MODE_PRIVATE)
                .getString(KEY_LAUNCHER_THEME_STYLE, THEME_STYLE_DEFAULT) ?: THEME_STYLE_DEFAULT
        }

        @JvmStatic
        fun isRinneTheme(context: android.content.Context): Boolean {
            return THEME_STYLE_RINNE == getLauncherThemeStyle(context)
        }

        @JvmStatic
        fun isAnriTheme(context: android.content.Context): Boolean {
            return THEME_STYLE_ANRI == getLauncherThemeStyle(context)
        }

        @JvmStatic
        fun isXinhaitianTheme(context: android.content.Context): Boolean {
            return THEME_STYLE_XINHAITIAN == getLauncherThemeStyle(context)
        }

        @JvmStatic
        fun isNatsumeTheme(context: android.content.Context): Boolean {
            return THEME_STYLE_NATSUME == getLauncherThemeStyle(context)
        }

        @JvmStatic
        fun setLauncherParticlesEnabled(context: android.content.Context, enabled: Boolean) {
            context.applicationContext
                .getSharedPreferences(APP_PREFS, android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_LAUNCHER_PARTICLES_ENABLED, enabled)
                .apply()
        }

        @JvmStatic
        fun isLauncherParticlesEnabled(context: android.content.Context): Boolean {
            return context.applicationContext
                .getSharedPreferences(APP_PREFS, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_LAUNCHER_PARTICLES_ENABLED, true)
        }

        @JvmStatic
        fun setLauncherParticleStyle(context: android.content.Context, style: String?) {
            val safeStyle = when (style) {
                PARTICLE_STYLE_RAIN -> PARTICLE_STYLE_RAIN
                PARTICLE_STYLE_STAR -> PARTICLE_STYLE_STAR
                PARTICLE_STYLE_SAKURA -> PARTICLE_STYLE_SAKURA
                PARTICLE_STYLE_FIREFLIES -> PARTICLE_STYLE_FIREFLIES
                PARTICLE_STYLE_CONSTELLATION -> PARTICLE_STYLE_CONSTELLATION
                PARTICLE_STYLE_RIPPLES -> PARTICLE_STYLE_RIPPLES
                else -> PARTICLE_STYLE_FLOATING
            }
            context.applicationContext
                .getSharedPreferences(APP_PREFS, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAUNCHER_PARTICLE_STYLE, safeStyle)
                .apply()
        }

        @JvmStatic
        fun getLauncherParticleStyle(context: android.content.Context): String {
            val style = context.applicationContext
                .getSharedPreferences(APP_PREFS, android.content.Context.MODE_PRIVATE)
                .getString(KEY_LAUNCHER_PARTICLE_STYLE, PARTICLE_STYLE_FLOATING)
            return when (style) {
                PARTICLE_STYLE_RAIN -> PARTICLE_STYLE_RAIN
                PARTICLE_STYLE_STAR -> PARTICLE_STYLE_STAR
                PARTICLE_STYLE_SAKURA -> PARTICLE_STYLE_SAKURA
                PARTICLE_STYLE_FIREFLIES -> PARTICLE_STYLE_FIREFLIES
                PARTICLE_STYLE_CONSTELLATION -> PARTICLE_STYLE_CONSTELLATION
                PARTICLE_STYLE_RIPPLES -> PARTICLE_STYLE_RIPPLES
                else -> PARTICLE_STYLE_FLOATING
            }
        }

        @JvmStatic
        fun launcherPrimaryColor(context: android.content.Context): Int {
            if (isRinneTheme(context)) return RINNE_PRIMARY_COLOR
            if (isAnriTheme(context)) return ANRI_PRIMARY_COLOR
            if (isXinhaitianTheme(context)) return XINHAITIAN_PRIMARY_COLOR
            if (isNatsumeTheme(context)) return NATSUME_PRIMARY_COLOR
            return ContextCompat.getColor(wrapLauncherUiMode(context)!!, R.color.launcher_primary_color)
        }

        @JvmStatic
        fun applySavedToneMode(activity: AppCompatActivity?) {
            if (activity == null) return
            activity.delegate.setLocalNightMode(
                if (isFollowingSystemTone(activity)) AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                else if (isLauncherDarkMode(activity)) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
            )
        }

        @JvmStatic
        fun wrapLauncherUiMode(base: android.content.Context?): android.content.Context? {
            if (base == null) return null
            if (isFollowingSystemTone(base)) return base
            val configuration = Configuration(base.resources.configuration)
            val targetNightMode = if (isLauncherDarkMode(base))
                Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or targetNightMode
            return base.createConfigurationContext(configuration)
        }

        /** The image is kept in private storage so it remains available after URI grants expire. */
        @JvmStatic
        fun customSplashImageFile(context: android.content.Context): File =
            File(context.applicationContext.filesDir, CUSTOM_SPLASH_IMAGE_FILE)

        @JvmStatic
        fun hasCustomSplashImage(context: android.content.Context): Boolean =
            customSplashImageFile(context).isFile

        /** 是否在应用启动时显示启动图片。 */
        @JvmStatic
        fun isSplashImageEnabled(context: android.content.Context): Boolean =
            context.getSharedPreferences(APP_PREFS, MODE_PRIVATE)
                .getBoolean(KEY_SPLASH_ENABLED, true)

        @JvmStatic
        fun setSplashImageEnabled(context: android.content.Context, enabled: Boolean) {
            context.getSharedPreferences(APP_PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SPLASH_ENABLED, enabled)
                .apply()
        }

        @JvmStatic
        fun isLandscapeStartupPage(context: android.content.Context): Boolean =
            context.getSharedPreferences(APP_PREFS, MODE_PRIVATE)
                .getBoolean(KEY_START_LANDSCAPE_PAGE, false)

        @JvmStatic
        fun setLandscapeStartupPage(context: android.content.Context, enabled: Boolean) {
            context.getSharedPreferences(APP_PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_START_LANDSCAPE_PAGE, enabled)
                .apply()
        }

        /** 是否在应用启动时直接进入大屏横屏模式。 */
        @JvmStatic
        fun isHdModeStartupEnabled(context: android.content.Context): Boolean =
            context.getSharedPreferences(APP_PREFS, MODE_PRIVATE)
                .getBoolean(KEY_HD_MODE_STARTUP, false)

        @JvmStatic
        fun setHdModeStartupEnabled(context: android.content.Context, enabled: Boolean) {
            context.getSharedPreferences(APP_PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_HD_MODE_STARTUP, enabled)
                .apply()
        }

        @JvmStatic
        fun isFeaturedHomeStyle(context: android.content.Context): Boolean =
            context.getSharedPreferences(APP_PREFS, MODE_PRIVATE)
                .getBoolean(KEY_FEATURED_HOME_STYLE, false)

        @JvmStatic
        fun setFeaturedHomeStyle(context: android.content.Context, enabled: Boolean) {
            context.getSharedPreferences(APP_PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_FEATURED_HOME_STYLE, enabled)
                .apply()
        }

        @JvmStatic
        fun isPillNavigationStyle(context: android.content.Context): Boolean =
            LauncherNavigationMetrics.isPillStyle(context)

        @JvmStatic
        fun setPillNavigationStyle(context: android.content.Context, enabled: Boolean) {
            LauncherNavigationMetrics.setPillStyle(context, enabled)
        }

        @JvmStatic
        fun isCardNavigationStyle(context: android.content.Context): Boolean =
            LauncherNavigationMetrics.isCardStyle(context)

        @JvmStatic
        fun setCardNavigationStyle(context: android.content.Context, enabled: Boolean) {
            LauncherNavigationMetrics.setCardStyle(context, enabled)
        }

        @JvmStatic
        fun isLiquidGlassNavigationStyle(context: android.content.Context): Boolean =
            LauncherNavigationMetrics.isLiquidGlassStyle(context)

        @JvmStatic
        fun setLiquidGlassNavigationStyle(context: android.content.Context, enabled: Boolean) {
            LauncherNavigationMetrics.setLiquidGlassStyle(context, enabled)
        }

        /** Returns the bottom clearance required by the active phone navigation variant. */
        @JvmStatic
        fun getNavigationOverlayBottomPadding(context: android.content.Context): Int {
            return LauncherNavigationMetrics.overlayBottomPadding(context)
        }

        @JvmStatic
        fun applyCustomSplashImage(context: android.content.Context, imageView: ImageView?) {
            if (imageView == null) return
            val imageFile = customSplashImageFile(context)
            if (!imageFile.isFile) return
            // 隐藏 ImageView 避免 setContentView 后先显示 XML 默认启动图，
            // 待自定义图片解码成功后再设为 VISIBLE，消除"默认图→自定义图"的视觉切换。
            imageView.visibility = View.INVISIBLE
            try {
                imageView.setImageURI(Uri.fromFile(imageFile))
                if (imageView.drawable != null) {
                    imageView.visibility = View.VISIBLE
                } else {
                    // 异步解码：等待 drawable 就绪后再显示，500ms 超时后回退默认图
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    val preDraw = object : android.view.ViewTreeObserver.OnPreDrawListener {
                        override fun onPreDraw(): Boolean {
                            if (imageView.drawable != null) {
                                imageView.viewTreeObserver.removeOnPreDrawListener(this)
                                handler.removeCallbacksAndMessages(null)
                                imageView.visibility = View.VISIBLE
                                return true
                            }
                            return false
                        }
                    }
                    imageView.viewTreeObserver.addOnPreDrawListener(preDraw)
                    handler.postDelayed({
                        if (imageView.visibility != View.VISIBLE) {
                            imageView.viewTreeObserver.removeOnPreDrawListener(preDraw)
                            imageView.visibility = View.VISIBLE
                        }
                    }, 500)
                }
            } catch (_: Throwable) {
                // 解码失败时恢复显示默认启动图
                imageView.visibility = View.VISIBLE
            }
        }
    }
}
