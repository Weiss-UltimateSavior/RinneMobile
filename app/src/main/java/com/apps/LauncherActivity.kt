package com.apps

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
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
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.apps.PadUi.PadGameModeActivity
import com.apps.account.LauncherAccountFragment
import com.apps.data.LauncherViewModel
import com.apps.game.GameSessionController
import com.apps.game.LauncherLibraryFragment
import com.apps.game.LauncherManageFragment
import com.apps.game.PinnedGameShortcut
import com.apps.home.LauncherHomeFragment
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

        if (savedInstanceState != null || launcherSplashShownInProcess) {
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
        if (isLandscapeStartupPage(this) && !forcePortraitHome) {
            startActivity(Intent(this, PadGameModeActivity::class.java))
            finish()
            return
        }

        binding = ActivityLauncherBinding.inflate(layoutInflater)
        val b = binding!!
        setContentView(b.root)

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
        val controller = pinnedGameSessionController
        if (controller != null && controller.hasActiveSession()) {
            controller.finishDirectPlaySessionIfNeeded(this)
        }
        if (binding != null) {
            renderSelectedNav(currentNavItem)
            renderParticles()
        }
        viewModel?.refreshStats()
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
        if (currentNavItem == navItem && supportFragmentManager.findFragmentById(R.id.launcherFragmentContainer) != null) {
            return
        }

        // 根据底部导航顺序判断左右方向：切到右侧 tab 时新页从右进、旧页往左出；反之亦然。
        val fromIndex = currentNavItem?.ordinal ?: 0
        val toRight = navItem.ordinal >= fromIndex
        currentNavItem = navItem
        val fragment: Fragment
        if (navItem == LauncherViewModel.NavItem.HOME) {
            fragment = LauncherHomeFragment()
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

    private fun setNavSelected(container: LinearLayout, icon: ImageView, label: TextView, selected: Boolean) {
        container.setBackgroundResource(R.drawable.launcher_nav_unselected)
        // 选中项始终使用当前主题主色；未选中项在浅色、深色模式下统一使用灰色。
        val color = if (selected) launcherPrimaryColor(this) else Color.GRAY
        icon.setColorFilter(color)
        label.setTextColor(color)
        label.setTypeface(null, android.graphics.Typeface.BOLD)
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

    private fun applyLauncherThemeTone() {
        val b = binding ?: return
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
            return context.applicationContext
                .getSharedPreferences(APP_PREFS, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_LAUNCHER_DARK_MODE, false)
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
                if (isLauncherDarkMode(activity)) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
        }

        @JvmStatic
        fun wrapLauncherUiMode(base: android.content.Context?): android.content.Context? {
            if (base == null) return null
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

        @JvmStatic
        fun applyCustomSplashImage(context: android.content.Context, imageView: ImageView?) {
            if (imageView == null) return
            val imageFile = customSplashImageFile(context)
            if (!imageFile.isFile) return
            try {
                imageView.setImageURI(Uri.fromFile(imageFile))
            } catch (_: Throwable) {
                // Keep the XML default splash image when a custom file cannot be decoded.
            }
        }
    }
}
