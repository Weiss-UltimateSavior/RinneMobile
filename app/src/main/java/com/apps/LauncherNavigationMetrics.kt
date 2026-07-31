package com.apps

import android.content.Context
import androidx.fragment.app.Fragment
import com.core.R

/** Navigation style preferences and the content clearance required by each variant. */
object LauncherNavigationMetrics {
    private const val KEY_PILL_NAVIGATION_STYLE = "launcher_pill_navigation_style"
    private const val KEY_CARD_NAVIGATION_STYLE = "launcher_card_navigation_style"
    private const val KEY_LIQUID_GLASS_NAVIGATION_STYLE = "launcher_liquid_glass_navigation_style"

    enum class Style {
        DEFAULT,
        PILL,
        CARD,
        LIQUID_GLASS,
    }

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
    fun isLiquidGlassStyle(context: Context): Boolean =
        context.getSharedPreferences(LauncherActivity.APP_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LIQUID_GLASS_NAVIGATION_STYLE, false)

    @JvmStatic
    fun setLiquidGlassStyle(context: Context, enabled: Boolean) {
        context.getSharedPreferences(LauncherActivity.APP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LIQUID_GLASS_NAVIGATION_STYLE, enabled)
            .apply()
    }

    @JvmStatic
    fun currentStyle(context: Context): Style = when {
        isLiquidGlassStyle(context) -> Style.LIQUID_GLASS
        isCardStyle(context) -> Style.CARD
        isPillStyle(context) -> Style.PILL
        else -> Style.DEFAULT
    }

    @JvmStatic
    fun overlayBottomPadding(context: Context): Int {
        val navigationHeightRes = when (currentStyle(context)) {
            Style.LIQUID_GLASS -> R.dimen.launcher_liquid_glass_nav_height
            Style.CARD -> R.dimen.launcher_card_nav_height
            Style.PILL -> R.dimen.launcher_bottom_nav_height
            Style.DEFAULT -> R.dimen.launcher_default_nav_height
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
