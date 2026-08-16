package com.apps.PadUi

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import com.apps.LauncherActivity
import com.apps.theme.LauncherTheme

/**
 * Pad/HD 横屏全出血窗口配置共享 helper。
 *
 * 与竖屏 [com.apps.LauncherEdgeToEdgeHelper]（透明状态栏 + 明暗自适应）语义不同，属豁免实现
 * （agent.md §6）。将原散落在各横屏 Activity 的 `configureLandscapeWindow()` 收敛于此，
 * 新横屏 Activity 直接调用 [configure]，避免重复。
 */
object PadLandscapeWindow {

    /** 系统栏着色为页面背景色 + 刘海短边裁切 + 关闭对比度增强。 */
    fun configure(activity: Activity) {
        val window: Window = activity.window
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        val background = LauncherTheme.bg(activity)
        window.setStatusBarColor(background)
        window.setNavigationBarColor(background)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val attributes = window.attributes
            attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attributes
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false)
            window.setNavigationBarContrastEnforced(false)
        }
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        if (!LauncherActivity.isLauncherDarkMode(activity)) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = flags
    }
}
