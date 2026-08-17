package com.apps

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import com.apps.home.HomeStyle

/**
 * Launcher 偏好设置集中托管对象。
 *
 * 该 object 从 LauncherActivity 的 companion object 中抽取而来，负责管理 Launcher
 * 相关的 SharedPreferences 读写与夜间模式同步逻辑。所有方法签名保持稳定，
 * 由 LauncherActivity 的 companion object 委托调用，行为与原实现完全一致。
 */
object LauncherPreferences {

    const val APP_PREFS = "yukihub_prefs"
    const val ACCOUNT_SETTINGS_PREFS = "launcher_account_settings"
    const val PROFILE_PREFS = "launcher_profile_prefs"
    /** 本地玩家昵称（SyncManager/首页/Pad 头部共用，单源常量）。 */
    const val KEY_PROFILE_NAME = "profile_name"
    /** 本地玩家昵称默认值（未登录/退出登录后显示）。 */
    const val DEFAULT_PROFILE_NAME = "Rinne"
    /** 旧默认昵称迁移标志位（值迁移变体：旧键==新键，用标志位避免误伤用户主动改名）。 */
    private const val KEY_LEGACY_NAME_MIGRATED = "legacy_name_migrated_v1"
    const val KEY_LAUNCHER_DARK_MODE = "launcher_dark_mode"
    private const val KEY_FOLLOW_SYSTEM_TONE = "launcher_follow_system_tone"
    private const val KEY_START_LANDSCAPE_PAGE = "launcher_start_landscape_page"
    private const val KEY_HD_MODE_STARTUP = "launcher_hd_mode_startup"
    private const val KEY_HOME_STYLE = "launcher_home_style"
    private const val LEGACY_KEY_FEATURED_HOME_STYLE = "launcher_featured_home_style"
    const val KEY_LAUNCHER_PARTICLES_ENABLED = "launcher_particles_enabled"
    const val KEY_LAUNCHER_PARTICLE_STYLE = "launcher_particle_style"
    const val KEY_STORAGE_PERMISSION_ASKED = "launcher_storage_permission_asked"
    const val KEY_PAD_GAME_SHOWCASE_IDS = "pad_game_showcase_ids"
    const val LEGACY_PAD_GAME_SHOWCASE_PREFS = "pad_game_showcase"
    const val LEGACY_KEY_PAD_GAME_SHOWCASE_IDS = "game_ids"
    const val PARTICLE_STYLE_FLOATING = "floating"
    const val PARTICLE_STYLE_RAIN = "rain"
    const val PARTICLE_STYLE_STAR = "star"
    const val PARTICLE_STYLE_SAKURA = "sakura"
    const val PARTICLE_STYLE_FIREFLIES = "fireflies"
    const val PARTICLE_STYLE_CONSTELLATION = "constellation"
    const val PARTICLE_STYLE_RIPPLES = "ripples"

    @JvmStatic
    fun setDarkMode(context: Context, darkMode: Boolean) {
        if (isFollowingSystemTone(context)) return
        context.applicationContext
            .getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LAUNCHER_DARK_MODE, darkMode)
            .apply()
        // 同步更新进程级 night mode 默认值，确保后续未被 recreate 的 AppCompat 组件
        // （如残留的 Dialog / Fragment）也能立刻命中新色调，而非等到下次冷启动。
        AppCompatDelegate.setDefaultNightMode(
            if (darkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    @JvmStatic
    fun isDarkMode(context: Context): Boolean {
        if (isFollowingSystemTone(context)) {
            return (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
        return context.applicationContext
            .getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LAUNCHER_DARK_MODE, false)
    }

    /** 是否由系统夜间模式自动决定 Launcher 的深浅色。 */
    @JvmStatic
    fun isFollowingSystemTone(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_FOLLOW_SYSTEM_TONE, false)

    /**
     * 设置 Launcher 色调来源。开启后全局及后续 Activity 均跟随系统；关闭后恢复已保存的手动色调。
     */
    @JvmStatic
    fun setFollowingSystemTone(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
        preferences.edit().putBoolean(KEY_FOLLOW_SYSTEM_TONE, enabled).apply()
        val manualDarkMode = preferences.getBoolean(KEY_LAUNCHER_DARK_MODE, false)
        AppCompatDelegate.setDefaultNightMode(
            if (enabled) AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else if (manualDarkMode) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    @JvmStatic
    fun isLandscapeStartupPage(context: Context): Boolean =
        context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_START_LANDSCAPE_PAGE, false)

    @JvmStatic
    fun setLandscapeStartupPage(context: Context, enabled: Boolean) {
        context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_START_LANDSCAPE_PAGE, enabled)
            .apply()
    }

    /** 是否在应用启动时直接进入大屏横屏模式。 */
    @JvmStatic
    fun isHdModeStartupEnabled(context: Context): Boolean =
        context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HD_MODE_STARTUP, false)

    @JvmStatic
    fun setHdModeStartupEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HD_MODE_STARTUP, enabled)
            .apply()
    }

    @JvmStatic
    fun getHomeStyle(context: Context): HomeStyle {
        val preferences = context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
        if (preferences.contains(KEY_HOME_STYLE)) {
            return HomeStyle.fromStorage(preferences.getString(KEY_HOME_STYLE, null))
        }

        // Migrate the former Boolean preference without changing existing users' selection.
        val migratedStyle = if (preferences.getBoolean(LEGACY_KEY_FEATURED_HOME_STYLE, false)) {
            HomeStyle.FEATURED
        } else {
            HomeStyle.DEFAULT
        }
        preferences.edit()
            .putString(KEY_HOME_STYLE, migratedStyle.storageValue)
            .remove(LEGACY_KEY_FEATURED_HOME_STYLE)
            .apply()
        return migratedStyle
    }

    @JvmStatic
    fun setHomeStyle(context: Context, style: HomeStyle) {
        context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HOME_STYLE, style.storageValue)
            .remove(LEGACY_KEY_FEATURED_HOME_STYLE)
            .apply()
    }

    @JvmStatic
    fun setParticlesEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LAUNCHER_PARTICLES_ENABLED, enabled)
            .apply()
    }

    @JvmStatic
    fun isParticlesEnabled(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LAUNCHER_PARTICLES_ENABLED, true)
    }

    @JvmStatic
    fun setParticleStyle(context: Context, style: String?) {
        val safeStyle = when (style) {
            PARTICLE_STYLE_RAIN -> PARTICLE_STYLE_RAIN
            PARTICLE_STYLE_STAR -> PARTICLE_STYLE_STAR
            PARTICLE_STYLE_SAKURA -> PARTICLE_STYLE_SAKURA
            PARTICLE_STYLE_FIREFLIES -> PARTICLE_STYLE_FIREFLIES
            PARTICLE_STYLE_CONSTELLATION -> PARTICLE_STYLE_CONSTELLATION
            PARTICLE_STYLE_RIPPLES -> PARTICLE_STYLE_RIPPLES
            else -> PARTICLE_STYLE_FLOATING
        }
        context.applicationContext
            .getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAUNCHER_PARTICLE_STYLE, safeStyle)
            .apply()
    }

    @JvmStatic
    fun getParticleStyle(context: Context): String {
        val style = context.applicationContext
            .getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAUNCHER_PARTICLE_STYLE, PARTICLE_STYLE_FLOATING)
        return when (style) {
            PARTICLE_STYLE_RAIN -> PARTICLE_STYLE_RAIN
            PARTICLE_STYLE_STAR -> PARTICLE_STYLE_STAR
            PARTICLE_STYLE_SAKURA -> PARTICLE_STYLE_SAKURA
            PARTICLE_STYLE_FIREFLIES -> PARTICLE_STYLE_FIREFLIES
            PARTICLE_STYLE_CONSTELLATION -> PARTICLE_STYLE_CONSTELLATION
            PARTICLE_STYLE_RIPPLES -> PARTICLE_STYLE_RIPPLES
            else -> PARTICLE_STYLE_FLOATING
        }
    }

    /**
     * 存量迁移：旧版本默认昵称写入过 "Yuki"，未登录/退出登录后应显示 Rinne。
     *
     * 值迁移变体：旧键与新键同为 [KEY_PROFILE_NAME]，无法用 contains() 区分"旧默认值"与
     * "用户主动改名"，故用 [KEY_LEGACY_NAME_MIGRATED] 标志位保证只迁移一次，
     * 避免误伤用户主动改名为 "Yuki" 的情况（迁移后用户再改名不受影响）。
     */
    @JvmStatic
    fun migrateLegacyProfileName(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_LEGACY_NAME_MIGRATED, false)) return
        val editor = prefs.edit()
        if (prefs.getString(KEY_PROFILE_NAME, "") == "Yuki") {
            editor.putString(KEY_PROFILE_NAME, DEFAULT_PROFILE_NAME)
        }
        editor.putBoolean(KEY_LEGACY_NAME_MIGRATED, true).apply()
    }
}
