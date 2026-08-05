package com.apps.agent

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import com.apps.LauncherActivity
import com.apps.theme.LauncherMotion
import com.apps.theme.LauncherTheme
import com.core.R

/**
 * 本地智能体薄宿主（重构计划 9.9 阶段 114）。
 *
 * 全部逻辑抽取至 [LocalAgentFragment]，本类仅承载竖屏独立启动路径
 * （HD 由 HdHomeFragment 以子 Fragment 承载）；原 Activity 的独立
 * edge-to-edge 窗口配置保留在此（WindowCompat insets/cutout/对比度
 * 与 Fragment 内 bindInsets/IME 处理耦合，见 com_apps_refactor_plan.md §8.1）。
 */
class LocalAgentActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        LauncherActivity.applySavedToneMode(this)
        super.onCreate(savedInstanceState)
        configureEdgeToEdgeWindow()
        setContentView(R.layout.activity_launcher_agent_host)
        // 进入动效（原 Java LocalAgentActivity#onCreate 保留；薄宿主迁移时补回，见 W-1）。
        LauncherMotion.applyActivityOpen(this)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.agentHostContainer, LocalAgentFragment())
                .commit()
        }
    }

    /** 本地智能体关闭入口（原返回按钮 LauncherMotion.finish 语义；runtime.cancel 由 Fragment onDestroyView 覆盖）。 */
    fun finishLocalAgent() {
        LauncherMotion.finish(this)
    }

    override fun onBackPressed() {
        finishLocalAgent()
    }

    private fun configureEdgeToEdgeWindow() {
        val darkMode = LauncherActivity.isLauncherDarkMode(this)
        val window = window
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        // LocalAgent uses a custom edge-to-edge profile: WindowCompat insets,
        // cutout support and contrast flags are coupled with bindInsets()/IME handling.
        // edge-to-edge 模式下 setSoftInputMode(SOFT_INPUT_ADJUST_RESIZE) 已失效
        //（Android 11+ 弃用），IME inset 改由 Fragment 内 bindInsets() 手动处理。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = LauncherTheme.bg(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val attributes = window.attributes
            attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attributes
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        if (ColorUtils.calculateLuminance(LauncherTheme.primary(this)) > 0.5) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        if (!darkMode) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.decorView.systemUiVisibility = flags
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase))
    }
}
