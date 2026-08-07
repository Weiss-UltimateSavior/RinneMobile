package com.core.launcher

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.core.R
import com.core.util.DevLogger

/**
 * Core-facing UI boundary. com.core code can request launcher UI values or events through this
 * bridge, while com.apps owns the actual Activity, theme and dialog implementations.
 */
object LauncherUiBridge {
    const val EXTRA_PRIMARY_COLOR = "primaryColor"
    const val EXTRA_DARK_MODE = "darkMode"
    const val EXTRA_THEME_COLOR_PRIMARY = "themeColorPrimary"
    const val EXTRA_THEME_COLOR_ON_PRIMARY = "themeColorOnPrimary"
    const val EXTRA_THEME_COLOR_CARD = "themeColorCard"
    const val EXTRA_THEME_COLOR_TEXT = "themeColorText"
    const val EXTRA_THEME_COLOR_TEXT_MUTED = "themeColorTextMuted"
    const val EXTRA_UI_LANGUAGE_TAG = "uiLanguageTag"

    interface Delegate {
        fun isFollowingSystemTone(context: Context): Boolean
        fun isLauncherDarkMode(context: Context): Boolean
        fun primary(context: Context): Int
        fun onPrimary(context: Context): Int
        fun card(context: Context): Int
        fun text(context: Context): Int
        fun textMuted(context: Context): Int
        fun themeLogoRes(context: Context): Int = R.drawable.launcher_game_center_default
        fun restartLauncher(activity: Activity): Boolean

        /**
         * Shows an overlay-safe confirmation surface.
         *
         * Implementations own dialog rendering. A confirm action may run onConfirm and then still
         * run onDismiss as the dialog closes; callers should treat onDismiss as final cleanup. If
         * showing fails, return false and invoke onDismiss so callers can continue cleanup.
         */
        fun showOverlayConfirm(
            context: Context,
            title: CharSequence,
            message: CharSequence,
            positiveText: CharSequence,
            windowType: Int,
            onConfirm: Runnable,
            onDismiss: Runnable
        ): Boolean

        /**
         * Application-level launcher setup hook. The default only leaves core night-mode fallback
         * active; apps delegates should install process-wide UI notifiers here when needed.
         */
        fun onApplicationCreate(application: Application) = Unit
    }

    @Volatile
    private var delegate: Delegate? = null

    fun install(delegate: Delegate) {
        this.delegate = delegate
    }

    fun onApplicationCreate(application: Application) {
        applyDefaultNightMode(application)
        delegate?.onApplicationCreate(application)
    }

    internal fun applyDefaultNightMode(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            if (isFollowingSystemTone(context)) {
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            } else if (isLauncherDarkMode(context)) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        )
    }

    fun appendEngineThemeExtras(intent: Intent, context: Context) {
        val primary = primary(context)
        intent.putExtra(EXTRA_PRIMARY_COLOR, primary)
        intent.putExtra(EXTRA_DARK_MODE, isLauncherDarkMode(context))
        intent.putExtra(EXTRA_THEME_COLOR_PRIMARY, primary)
        intent.putExtra(EXTRA_THEME_COLOR_ON_PRIMARY, onPrimary(context))
        intent.putExtra(EXTRA_THEME_COLOR_CARD, card(context))
        intent.putExtra(EXTRA_THEME_COLOR_TEXT, text(context))
        intent.putExtra(EXTRA_THEME_COLOR_TEXT_MUTED, textMuted(context))
        intent.putExtra(EXTRA_UI_LANGUAGE_TAG, languageTag(context))
    }

    /**
     * [appendEngineThemeExtras] 的安全封装：各引擎启动器（Krkr/Artemis/ScriptEngine/Krkrsdl3）统一走此入口，
     * 异常经 DevLogger 记录，避免每个启动器重复实现 try/catch。
     */
    @JvmStatic
    fun appendEngineThemeExtrasSafely(intent: Intent, context: Context) {
        try {
            appendEngineThemeExtras(intent, context)
        } catch (error: Exception) {
            DevLogger.w("LauncherUiBridge", "appendEngineThemeExtras failed", error)
        }
    }

    fun restartLauncher(activity: Activity): Boolean =
        delegate?.restartLauncher(activity) == true

    fun primaryColor(context: Context): Int = primary(context)

    fun themeLogoRes(context: Context): Int =
        delegate?.themeLogoRes(context) ?: R.drawable.launcher_game_center_default

    fun showOverlayConfirm(
        context: Context,
        title: CharSequence,
        message: CharSequence,
        positiveText: CharSequence,
        windowType: Int,
        onConfirm: Runnable,
        onDismiss: Runnable
    ): Boolean = delegate?.showOverlayConfirm(
        context,
        title,
        message,
        positiveText,
        windowType,
        onConfirm,
        onDismiss
    ) == true

    private fun isFollowingSystemTone(context: Context): Boolean =
        delegate?.isFollowingSystemTone(context) ?: false

    private fun isLauncherDarkMode(context: Context): Boolean =
        delegate?.isLauncherDarkMode(context) ?: false

    private fun primary(context: Context): Int =
        delegate?.primary(context) ?: ContextCompat.getColor(context, R.color.launcher_primary_color)

    private fun onPrimary(context: Context): Int =
        delegate?.onPrimary(context) ?: ContextCompat.getColor(context, R.color.launcher_on_primary_color)

    private fun card(context: Context): Int =
        delegate?.card(context) ?: ContextCompat.getColor(context, R.color.launcher_card_color)

    private fun text(context: Context): Int =
        delegate?.text(context) ?: ContextCompat.getColor(context, R.color.launcher_text_color)

    private fun textMuted(context: Context): Int =
        delegate?.textMuted(context) ?: ContextCompat.getColor(context, R.color.launcher_text_muted_color)

    private fun languageTag(context: Context): String {
        val appLocales = AppCompatDelegate.getApplicationLocales()
        return if (!appLocales.isEmpty) {
            appLocales[0]?.toLanguageTag()
        } else {
            context.resources.configuration.locales[0].toLanguageTag()
        } ?: ""
    }
}
