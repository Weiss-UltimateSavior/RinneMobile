package com.yuki.yukihub.launcherbridge

import android.content.Context

/**
 * KRKR 引擎设置桥接：负责读取/保存主项目 yukihub_prefs 中的 KRKR 引擎相关配置。
 * 涉及键值与 MainActivity / SyncManager 完全一致，保证 Launcher 修改后主项目立即可见。
 */
object LauncherKrkrBridge {

    private const val KEY_KR_ENGINE_VERSION = "kr_engine_version"
    private const val KEY_KR_SCOPED_SAVE_DIR = "kr_scoped_save_dir"
    private const val KEY_ARTEMIS_SCOPED_SAVE_DIR = "artemis_scoped_save_dir"
    private const val KEY_TYRANO_SCOPED_SAVE_DIR = "tyrano_scoped_save_dir"

    const val ENGINE_VERSION_AUTO = "auto"
    const val ENGINE_VERSION_139 = "1.3.9"
    const val ENGINE_VERSION_134 = "1.3.4"
    const val ENGINE_VERSION_126 = "1.2.6"

    private fun prefs(context: Context) = context.yukiPrefs()

    @JvmStatic
    fun getEngineVersion(context: Context?): String {
        if (context == null) return ENGINE_VERSION_AUTO
        val v = prefs(context).getString(KEY_KR_ENGINE_VERSION, ENGINE_VERSION_AUTO)
        return normalizeEngineVersion(v)
    }

    @JvmStatic
    fun setEngineVersion(context: Context?, version: String?) {
        if (context == null) return
        prefs(context).edit()
            .putString(KEY_KR_ENGINE_VERSION, normalizeEngineVersion(version))
            .apply()
    }

    @JvmStatic
    fun isKrScopedSaveDir(context: Context?): Boolean {
        if (context == null) return true
        // Keep the new app-scoped mode as the default, while allowing a game
        // with stricter filesystem assumptions to use its original directory.
        return prefs(context).getBoolean(KEY_KR_SCOPED_SAVE_DIR, true)
    }

    @JvmStatic
    fun setKrScopedSaveDir(context: Context?, enabled: Boolean) {
        if (context == null) return
        prefs(context).edit().putBoolean(KEY_KR_SCOPED_SAVE_DIR, enabled).apply()
    }

    @JvmStatic
    fun isArtemisScopedSaveDir(context: Context?): Boolean {
        if (context == null) return true
        // Keep the current safe default for new installs, but honour an
        // explicit user choice to run Artemis against its original directory.
        return prefs(context).getBoolean(KEY_ARTEMIS_SCOPED_SAVE_DIR, true)
    }

    @JvmStatic
    fun setArtemisScopedSaveDir(context: Context?, enabled: Boolean) {
        if (context == null) return
        prefs(context).edit().putBoolean(KEY_ARTEMIS_SCOPED_SAVE_DIR, enabled).apply()
    }

    @JvmStatic
    fun isTyranoScopedSaveDir(context: Context?): Boolean {
        if (context == null) return true
        return prefs(context).getBoolean(KEY_TYRANO_SCOPED_SAVE_DIR, true)
    }

    @JvmStatic
    fun setTyranoScopedSaveDir(context: Context?, enabled: Boolean) {
        if (context == null) return
        prefs(context).edit().putBoolean(KEY_TYRANO_SCOPED_SAVE_DIR, enabled).apply()
    }

    @JvmStatic
    fun normalizeEngineVersion(value: String?): String {
        val v = value?.trim()?.lowercase() ?: ENGINE_VERSION_AUTO
        return when (v) {
            ENGINE_VERSION_139 -> ENGINE_VERSION_139
            ENGINE_VERSION_134 -> ENGINE_VERSION_134
            ENGINE_VERSION_126 -> ENGINE_VERSION_126
            else -> ENGINE_VERSION_AUTO
        }
    }

    @JvmStatic
    fun engineVersionLabel(value: String?): String {
        return when (normalizeEngineVersion(value)) {
            ENGINE_VERSION_139 -> "1.3.9"
            ENGINE_VERSION_134 -> "1.3.4"
            ENGINE_VERSION_126 -> "1.2.6"
            else -> "自动"
        }
    }
}
