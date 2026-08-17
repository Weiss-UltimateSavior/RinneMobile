package com.apps

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.apps.theme.LauncherTheme
import com.core.R

/** Shared edge-to-edge launcher Activity window setup. */
object LauncherEdgeToEdgeHelper {
    @JvmStatic
    fun apply(window: Window, context: Context) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = ContextCompat.getColor(context, R.color.launcher_bg_color)
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        val darkMode = LauncherPreferences.isDarkMode(context)
        if (!darkMode) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = flags
    }

    @JvmStatic
    fun apply(activity: Activity) {
        apply(activity, false)
    }

    @JvmStatic
    fun apply(activity: Activity, adjustResize: Boolean) {
        apply(activity, adjustResize, false)
    }

    @JvmStatic
    fun apply(activity: Activity, adjustResize: Boolean, usePrimaryLuminanceForStatusBar: Boolean) {
        apply(activity, adjustResize, usePrimaryLuminanceForStatusBar, R.color.launcher_bg_color)
    }

    @JvmStatic
    fun apply(
        activity: Activity,
        adjustResize: Boolean,
        usePrimaryLuminanceForStatusBar: Boolean,
        @ColorRes navigationBarColorRes: Int,
    ) {
        val window = activity.window
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        if (adjustResize) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = ContextCompat.getColor(activity, navigationBarColorRes)
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        val darkMode = LauncherPreferences.isDarkMode(activity)
        if (usePrimaryLuminanceForStatusBar) {
            if (ColorUtils.calculateLuminance(LauncherTheme.primary(activity)) > 0.5) {
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        } else if (!darkMode) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        if (!darkMode) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = flags
    }
}
