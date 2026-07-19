package com.yuki.yukihub.util

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration

/**
 * UI 字体与全局缩放工具：通过 SharedPreferences 持久化用户偏好，
 * 在 Activity attach 时用 [wrap] 创建自定义 Configuration 的 Context。
 */
object UiScaleUtil {
    const val PREFS_NAME = "yukihub_prefs"
    const val KEY_UI_FONT_SCALE = "ui_font_scale"
    const val KEY_UI_SCALE = "ui_scale"
    const val DEFAULT_FONT_SCALE = 1.0f
    const val MIN_FONT_SCALE = 0.85f
    const val MAX_FONT_SCALE = 1.30f
    const val DEFAULT_UI_SCALE = 1.0f
    const val MIN_UI_SCALE = 0.70f
    const val MAX_UI_SCALE = 1.50f

    @JvmStatic
    fun getFontScale(context: Context?): Float {
        if (context == null) return DEFAULT_FONT_SCALE
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return clamp(prefs.getFloat(KEY_UI_FONT_SCALE, DEFAULT_FONT_SCALE))
    }

    @JvmStatic
    fun setFontScale(context: Context?, scale: Float) {
        if (context == null) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_UI_FONT_SCALE, clamp(scale))
            .apply()
    }

    @JvmStatic
    fun resetFontScale(context: Context?) {
        if (context == null) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_UI_FONT_SCALE)
            .apply()
    }

    @JvmStatic
    fun getUiScale(context: Context?): Float {
        if (context == null) return DEFAULT_UI_SCALE
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return clampUiScale(prefs.getFloat(KEY_UI_SCALE, DEFAULT_UI_SCALE))
    }

    @JvmStatic
    fun setUiScale(context: Context?, scale: Float) {
        if (context == null) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_UI_SCALE, clampUiScale(scale))
            .apply()
    }

    @JvmStatic
    fun clampUiScale(value: Float): Float {
        if (value.isNaN() || value.isInfinite()) return DEFAULT_UI_SCALE
        return value.coerceIn(MIN_UI_SCALE, MAX_UI_SCALE)
    }

    @JvmStatic
    fun uiScalePercent(scale: Float): Int {
        // 严格等价原 Java Math.round(float): half-up 舍入
        // 不使用 Kotlin roundToInt() —— 后者用 Math.rint() 即 half-to-even(银行家舍入)，
        // 仅当乘积精确落在 .5 时不同；保留原语义以避免边界差异
        return Math.round(clampUiScale(scale) * 100f)
    }

    @JvmStatic
    fun wrap(base: Context?): Context? {
        if (base == null) return null
        val fontScale = getFontScale(base)
        val uiScale = getUiScale(base)
        val config = Configuration(base.resources.configuration)
        config.fontScale = fontScale
        // 通过修改 densityDpi 实现全局 UI 缩放
        if (uiScale != 1.0f) {
            config.densityDpi = (config.densityDpi * uiScale).toInt()
        }
        return base.createConfigurationContext(config)
    }

    @JvmStatic
    fun clamp(value: Float): Float {
        if (value.isNaN() || value.isInfinite()) return DEFAULT_FONT_SCALE
        return value.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
    }

    @JvmStatic
    fun percent(scale: Float): Int {
        // 严格等价原 Java Math.round(float): half-up 舍入
        return Math.round(clamp(scale) * 100f)
    }
}
