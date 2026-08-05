package com.apps.HDModel

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider
import com.apps.LauncherActivity
import com.apps.LauncherNavRenderer
import com.apps.LauncherPreferences
import com.apps.LauncherThemeStyle
import com.apps.PadUi.PadDialogFactory
import com.apps.data.LauncherViewModel
import com.apps.theme.LauncherTheme
import com.core.R
import com.core.databinding.ActivityHdModeBinding
import com.core.launcherbridge.LauncherUpdateBridge
import com.core.util.Disposable
import com.core.util.RxMainScheduler

/** 大屏横屏模式的基础外壳；侧边导航功能将在后续逐项接入。 */
class HdModeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHdModeBinding
    private lateinit var launcherViewModel: LauncherViewModel
    private var selectedNavItem = HdNavItem.HOME
    private var appliedThemeStyle = LauncherThemeStyle.THEME_STYLE_DEFAULT
    private var autoUpdateDelay: Disposable? = null
    private val launcherPreferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                LauncherThemeStyle.KEY_LAUNCHER_THEME_STYLE ->
                    binding.root.post { recreateIfThemeStyleChanged() }
                LauncherPreferences.KEY_LAUNCHER_PARTICLES_ENABLED,
                LauncherPreferences.KEY_LAUNCHER_PARTICLE_STYLE ->
                    binding.root.post { renderParticles() }
            }
        }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        LauncherActivity.applySavedToneMode(this)
        super.onCreate(savedInstanceState)
        configureLandscapeWindow()
        binding = ActivityHdModeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        launcherViewModel = ViewModelProvider(this)[LauncherViewModel::class.java]
        selectedNavItem = savedInstanceState?.getString(STATE_SELECTED_NAV)
            ?.let { value -> HdNavItem.entries.firstOrNull { it.name == value } }
            ?: HdNavItem.HOME
        appliedThemeStyle = LauncherActivity.getLauncherThemeStyle(this)
        applySystemBarInsets()
        applyTheme()
        renderParticles()
        binding.hdNavA.setOnClickListener { showHomeFragment() }
        binding.hdNavB.setOnClickListener { showGameLibraryFragment() }
        binding.hdNavC.setOnClickListener { showManageFragment() }
        binding.hdNavD.setOnClickListener { showAccountFragment() }
        binding.hdNavE.setOnClickListener { showSettingsFragment() }
        if (savedInstanceState == null) {
            launcherViewModel.refresh()
            showHomeFragment()
        }
        scheduleAutoUpdateCheck()
    }

    /**
     * 与 LauncherActivity.scheduleAutoUpdateCheck 等价：启动后延迟静默检查更新，
     * 仅在有新版本时弹窗提示，失败静默处理。
     */
    private fun scheduleAutoUpdateCheck() {
        autoUpdateDelay = RxMainScheduler.postDelayed(Runnable {
            if (!isFinishing && !isDestroyed) {
                LauncherUpdateBridge.checkUpdate(this, object : LauncherUpdateBridge.Callback {
                    override fun onResult(
                        info: LauncherUpdateBridge.UpdateInfo?,
                        currentVersion: String,
                        hasUpdate: Boolean,
                    ) {
                        if (isFinishing || isDestroyed) return
                        if (hasUpdate) {
                            PadDialogFactory.showUpdateResult(
                                this@HdModeActivity,
                                info,
                                currentVersion,
                                true,
                                null,
                            )
                        }
                    }

                    override fun onError(message: String) {
                        // 静默失败，不打扰用户
                    }
                })
            }
        }, 2000)
    }

    override fun onResume() {
        super.onResume()
        if (recreateIfThemeStyleChanged()) return
        renderParticles()
        if (::launcherViewModel.isInitialized) launcherViewModel.refreshStats()
    }

    override fun onStart() {
        super.onStart()
        getSharedPreferences(LauncherPreferences.APP_PREFS, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(launcherPreferenceListener)
    }

    override fun onStop() {
        getSharedPreferences(LauncherPreferences.APP_PREFS, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(launcherPreferenceListener)
        super.onStop()
    }

    override fun onDestroy() {
        autoUpdateDelay?.let { if (!it.isDisposed()) it.dispose() }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SELECTED_NAV, selectedNavItem.name)
        super.onSaveInstanceState(outState)
    }

    // HD 横屏全出血窗口：系统栏着色为页面背景色（LauncherTheme.bg）+ 刘海短边裁切 +
    // 关闭对比度增强。与 LauncherEdgeToEdgeHelper（透明状态栏 + 明暗自适应）语义不同，
    // 故不走 helper（豁免，见 agent.md §8 grep 监控与重构计划 4.7 项 2）。
    private fun configureLandscapeWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        val background = LauncherTheme.bg(this)
        window.statusBarColor = background
        window.navigationBarColor = background
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            if (!LauncherActivity.isLauncherDarkMode(this)) {
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else {
                0
            }
    }

    private fun applySystemBarInsets() {
        val content = binding.hdModeContent
        val left = content.paddingLeft
        val top = content.paddingTop
        val right = content.paddingRight
        val bottom = content.paddingBottom
        binding.root.setOnApplyWindowInsetsListener { _, insets ->
            content.setPadding(
                left + insets.systemWindowInsetLeft,
                top + insets.systemWindowInsetTop,
                right + insets.systemWindowInsetRight,
                bottom + insets.systemWindowInsetBottom,
            )
            insets
        }
        binding.root.requestApplyInsets()
    }

    private fun applyTheme() {
        LauncherTheme.applyPrimaryTone(binding.root)
        styleNavigationIcon(binding.hdNavA, selectedNavItem == HdNavItem.HOME)
        styleNavigationIcon(binding.hdNavB, selectedNavItem == HdNavItem.LIBRARY)
        styleNavigationIcon(binding.hdNavC, selectedNavItem == HdNavItem.MANAGE)
        styleNavigationIcon(binding.hdNavD, selectedNavItem == HdNavItem.ACCOUNT)
        styleNavigationIcon(binding.hdNavE, selectedNavItem == HdNavItem.SETTINGS)
    }

    private fun styleNavigationIcon(icon: ImageView, selected: Boolean) {
        icon.background = null
        // 选中/未选中取色统一走 LauncherNavRenderer.navTone 封装（primary/textMuted）。
        LauncherNavRenderer.applyNavTone(icon, selected, this)
    }

    private fun showHomeFragment() {
        // 仅在真正切换到首页栏目时刷新统计，避免重复点击首页导航按钮触发动态列表刷新。
        if (selectedNavItem != HdNavItem.HOME && ::launcherViewModel.isInitialized) {
            launcherViewModel.refreshStats()
        }
        showRootFragment(HdNavItem.HOME, HdHomeFragment(), "hd_home")
    }

    private fun showGameLibraryFragment() {
        showRootFragment(HdNavItem.LIBRARY, HdGameLibraryFragment(), "hd_game_library")
    }

    private fun showManageFragment() {
        showRootFragment(HdNavItem.MANAGE, HdManageFragment(), "hd_manage")
    }

    private fun showAccountFragment() {
        showRootFragment(HdNavItem.ACCOUNT, HdAccountFragment(), "hd_account")
    }

    private fun showSettingsFragment() {
        showRootFragment(HdNavItem.SETTINGS, HdSettingsFragment(), "hd_settings")
    }

    internal fun showSaveManagerFragment() {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.launcher_fragment_enter,
                R.anim.launcher_fragment_exit,
                R.anim.launcher_fragment_enter_back,
                R.anim.launcher_fragment_exit_back,
            )
            .replace(R.id.hdFragmentContainer, HdSaveManagerFragment(), "hd_save_manager")
            .addToBackStack(null)
            .commit()
    }

    private fun showRootFragment(item: HdNavItem, fragment: Fragment, tag: String) {
        val current = supportFragmentManager.findFragmentById(R.id.hdFragmentContainer)
        // 重复点击已选中栏目时不重建页面，避免视觉闪屏。
        // 注意 current?.tag 可能不等于 tag：账户栏目会在 onResume 内部通过
        // navigateToProfile() 将自身替换为 HdProfileFragment（tag = "launcher_ACCOUNT_PROFILE"），
        // 首页也可能堆叠 HdSaveManagerFragment 等详情页，因此只要 selectedNavItem 相同就提前返回。
        // 但首次启动时容器中还没有任何 fragment（current == null），必须正常创建。
        if (selectedNavItem == item && current != null) {
            // 若有详情页堆叠（如 HdSaveManagerFragment），弹出回到根 Fragment。
            if (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStackImmediate(
                    null, FragmentManager.POP_BACK_STACK_INCLUSIVE,
                )
            }
            applyTheme()
            return
        }
        // 切换主栏目时清空详情页回退栈（例如 HdSaveManagerFragment），避免回退时进入残留的详情页。
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStackImmediate(
                null, FragmentManager.POP_BACK_STACK_INCLUSIVE,
            )
        }
        val toRight = item.ordinal >= selectedNavItem.ordinal
        selectNavigation(item)
        val enter = if (toRight) {
            R.anim.launcher_fragment_enter
        } else {
            R.anim.launcher_fragment_enter_back
        }
        val exit = if (toRight) {
            R.anim.launcher_fragment_exit
        } else {
            R.anim.launcher_fragment_exit_back
        }
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(enter, exit, enter, exit)
            .replace(R.id.hdFragmentContainer, fragment, tag)
            .commit()
    }

    internal fun showProfileDetail(id: String, intent: android.content.Intent): Boolean {
        val profile = supportFragmentManager
            .findFragmentById(R.id.hdFragmentContainer) as? HdProfileFragment
            ?: return false
        profile.showEmbeddedActivity(id, intent)
        return true
    }

    override fun finishFromChild(child: Activity) {
        val owner = currentEmbeddedOwner()
        if (owner != null) {
            owner.closeEmbeddedActivity(child)
            binding.root.post { recreateIfThemeStyleChanged() }
            return
        }
        super.finishFromChild(child)
    }

    // 9.9 ③ 代理路径清理（阶段 115）：原 launchSplashImagePicker/launchTranslationProjection/
    // requestTranslationNotificationPermission 转发方法已随 9.9 ② 各目标迁子 Fragment 而无调用方，
    // 一并删除（Fragment 自有 ActivityResultRegistry 直接接管）。
    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        if (currentEmbeddedOwner()?.closeEmbeddedActivity() == true) return
        super.onBackPressed()
    }

    private fun currentEmbeddedOwner(): HdEmbeddedActivityOwner? =
        supportFragmentManager.findFragmentById(R.id.hdFragmentContainer)
            as? HdEmbeddedActivityOwner

    private fun selectNavigation(item: HdNavItem) {
        selectedNavItem = item
        applyTheme()
    }

    private fun recreateIfThemeStyleChanged(): Boolean {
        val currentStyle = LauncherActivity.getLauncherThemeStyle(this)
        if (currentStyle == appliedThemeStyle) return false
        appliedThemeStyle = currentStyle
        recreate()
        return true
    }

    private fun renderParticles() {
        val enabled = LauncherActivity.isLauncherParticlesEnabled(this)
        binding.hdLauncherParticleView.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.hdLauncherParticleView.setParticleStyle(
            LauncherActivity.getLauncherParticleStyle(this),
        )
        binding.hdLauncherParticleView.setParticlesEnabled(enabled)
    }

    internal fun refreshNavigationChrome() {
        binding.hdNavigationRail.translationZ = 0f
        binding.hdNavigationRail.visibility = View.VISIBLE
        binding.hdNavigationRail.alpha = 1f
        listOf(
            binding.hdNavA,
            binding.hdNavB,
            binding.hdNavC,
            binding.hdNavD,
            binding.hdNavE,
        ).forEach {
            it.visibility = View.VISIBLE
            it.alpha = 1f
        }
        applyTheme()
        binding.hdNavigationRail.invalidate()
    }

    private enum class HdNavItem {
        HOME,
        LIBRARY,
        MANAGE,
        ACCOUNT,
        SETTINGS,
    }

    companion object {
        private const val STATE_SELECTED_NAV = "hd_selected_nav"
    }
}
