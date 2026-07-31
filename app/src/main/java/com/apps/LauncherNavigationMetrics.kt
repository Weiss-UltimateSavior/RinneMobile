package com.apps

import android.content.Context
import androidx.fragment.app.Fragment
import com.core.R

/** Navigation style preferences and the content clearance required by each variant. */
object LauncherNavigationMetrics {
    private const val KEY_PILL_NAVIGATION_STYLE = "launcher_pill_navigation_style"
    private const val KEY_CARD_NAVIGATION_STYLE = "launcher_card_navigation_style"

    @JvmStatic
    fun isPillStyle(context: Context): Boolean =
        context.getSharedPreferences(LauncherActivity.APP_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PILL_NAVIGATION_STYLE, false)

    @JvmStatic
    fun setPillStyle(context: Context, enabled: Boolean) {
        context.getSharedPreferences(LauncherActivity.APP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PILL_NAVIGATION_STYLE, enabled)
            .apply()
    }

    @JvmStatic
    fun isCardStyle(context: Context): Boolean =
        context.getSharedPreferences(LauncherActivity.APP_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CARD_NAVIGATION_STYLE, false)

    @JvmStatic
    fun setCardStyle(context: Context, enabled: Boolean) {
        context.getSharedPreferences(LauncherActivity.APP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CARD_NAVIGATION_STYLE, enabled)
            .apply()
    }

    @JvmStatic
    fun overlayBottomPadding(context: Context): Int {
        val navigationHeightRes = when {
            isCardStyle(context) -> R.dimen.launcher_card_nav_height
            isPillStyle(context) -> R.dimen.launcher_bottom_nav_height
            else -> R.dimen.launcher_default_nav_height
        }
        return context.resources.getDimensionPixelSize(navigationHeightRes) +
            context.resources.getDimensionPixelSize(R.dimen.launcher_navigation_content_gap)
    }
}

fun Fragment.navigationOverlayBottomPadding(fallback: Int): Int =
    if (activity is LauncherActivity) {
        LauncherNavigationMetrics.overlayBottomPadding(requireContext())
    } else {
        fallback
    }

fun Fragment.refreshNavigationOverlayInsets() {
    view?.requestApplyInsets()
}
