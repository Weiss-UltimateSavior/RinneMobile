package com.apps

import android.content.Context
import android.graphics.Color
import androidx.core.content.ContextCompat
import com.core.R

object LauncherThemeStyle {

    private const val KEY_LAUNCHER_THEME_STYLE = "launcher_theme_style"
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
    fun setThemeStyle(context: Context, style: String?) {
        val value = if (THEME_STYLE_RINNE == style
            || THEME_STYLE_ANRI == style
            || THEME_STYLE_XINHAITIAN == style
            || THEME_STYLE_NATSUME == style
        ) style else THEME_STYLE_DEFAULT
        context.applicationContext
            .getSharedPreferences(LauncherPreferences.APP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAUNCHER_THEME_STYLE, value)
            .apply()
    }

    @JvmStatic
    fun getThemeStyle(context: Context): String {
        return context.applicationContext
            .getSharedPreferences(LauncherPreferences.APP_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAUNCHER_THEME_STYLE, THEME_STYLE_DEFAULT) ?: THEME_STYLE_DEFAULT
    }

    @JvmStatic
    fun isRinne(context: Context): Boolean {
        return THEME_STYLE_RINNE == getThemeStyle(context)
    }

    @JvmStatic
    fun isAnri(context: Context): Boolean {
        return THEME_STYLE_ANRI == getThemeStyle(context)
    }

    @JvmStatic
    fun isXinhaitian(context: Context): Boolean {
        return THEME_STYLE_XINHAITIAN == getThemeStyle(context)
    }

    @JvmStatic
    fun isNatsume(context: Context): Boolean {
        return THEME_STYLE_NATSUME == getThemeStyle(context)
    }

    @JvmStatic
    fun primaryColor(context: Context): Int {
        if (isRinne(context)) return RINNE_PRIMARY_COLOR
        if (isAnri(context)) return ANRI_PRIMARY_COLOR
        if (isXinhaitian(context)) return XINHAITIAN_PRIMARY_COLOR
        if (isNatsume(context)) return NATSUME_PRIMARY_COLOR
        return ContextCompat.getColor(LauncherUiMode.wrap(context)!!, R.color.launcher_primary_color)
    }
}
