package com.apps.theme

import android.content.Context
import com.apps.LauncherActivity
import com.core.R

/** 主题色值解析：统一经 LauncherThemeParts.color 走 UI mode 包装的资源解析。 */
internal object LauncherThemeColors {

    internal fun primary(context: Context): Int {
        return LauncherActivity.launcherPrimaryColor(context)
    }

    internal fun onPrimary(context: Context): Int = LauncherThemeParts.color(context, R.color.launcher_on_primary_color)

    internal fun card(context: Context): Int = LauncherThemeParts.color(context, R.color.launcher_card_color)

    internal fun bg(context: Context): Int = LauncherThemeParts.color(context, R.color.launcher_bg_color)

    internal fun line(context: Context): Int = LauncherThemeParts.color(context, R.color.launcher_line_color)

    internal fun text(context: Context): Int = LauncherThemeParts.color(context, R.color.launcher_text_color)

    internal fun textMuted(context: Context): Int = LauncherThemeParts.color(context, R.color.launcher_text_muted_color)

    internal fun primaryText(context: Context): Int = LauncherThemeParts.color(context, R.color.launcher_primary_color)

    internal fun danger(context: Context): Int = LauncherThemeParts.color(context, R.color.launcher_danger_color)

    internal fun onDanger(context: Context): Int = LauncherThemeParts.color(context, R.color.launcher_on_danger_color)
}
