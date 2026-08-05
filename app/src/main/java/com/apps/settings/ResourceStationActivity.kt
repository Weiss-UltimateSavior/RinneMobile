package com.apps.settings

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.apps.LauncherActivity
import com.apps.theme.LauncherMotion
import com.core.R

/**
 * 资源站薄宿主（重构计划 9.9 W-3，阶段 125）。
 *
 * 全部逻辑抽取至 [ResourceStationFragment]（WebView/顶栏/导航拦截/硬件返回），
 * 本类仅承载竖屏独立启动路径（HD 由 HdHomeFragment 以子 Fragment 承载），
 * 保留沉浸式状态栏窗口配置（与 Fragment 内顶栏 statusBarHeight padding 耦合）。
 */
class ResourceStationActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        LauncherActivity.applySavedToneMode(this)
        super.onCreate(savedInstanceState)
        configureImmersiveStatusBar()
        setContentView(R.layout.activity_launcher_settings_host)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(
                    R.id.settingsHostContainer,
                    ResourceStationFragment.newInstance(
                        url = getIntent().getStringExtra(ResourceStationFragment.EXTRA_URL),
                        title = getIntent().getStringExtra(ResourceStationFragment.EXTRA_TITLE),
                        hdEmbedded = false,
                    ),
                )
                .commit()
        }
    }

    /** 资源站关闭入口（顶栏返回/硬件返回落点；WebView goBack 语义由 Fragment OnBackPressedCallback 覆盖）。 */
    fun finishResourceStation() {
        LauncherMotion.finish(this)
    }

    // ResourceStation WebView 沉浸式状态栏：透明状态栏 + 底栏色导航栏，LIGHT 标志固定
    // （页面顶栏恒为卡片色、无深色分支）。与 LauncherEdgeToEdgeHelper 的明暗自适应语义
    // 不同，故不走 helper（豁免，见 agent.md §8 grep 监控与重构计划 4.7 项 2）。
    private fun configureImmersiveStatusBar() {
        val window = window
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = ContextCompat.getColor(this, R.color.launcher_bottom_bar_color)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase))
    }
}
