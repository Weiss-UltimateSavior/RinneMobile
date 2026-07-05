package com.yuki.yukihub.launcherbridge;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * KRKR 引擎设置桥接：负责读取/保存主项目 yukihub_prefs 中的 KRKR 引擎相关配置。
 * 涉及键值与 MainActivity / SyncManager 完全一致，保证 Launcher 修改后主项目立即可见。
 */
public final class LauncherKrkrBridge {
    private static final String APP_PREFS = "yukihub_prefs";
    private static final String KEY_KR_ENGINE_VERSION = "kr_engine_version";
    private static final String KEY_KR_COMPAT_MODE = "kr_compat_mode";
    private static final String KEY_KR_SCOPED_SAVE_DIR = "kr_scoped_save_dir";
    private static final String KEY_ARTEMIS_SCOPED_SAVE_DIR = "artemis_scoped_save_dir";

    public static final String ENGINE_VERSION_AUTO = "auto";
    public static final String ENGINE_VERSION_139 = "1.3.9";
    public static final String ENGINE_VERSION_134 = "1.3.4";

    private LauncherKrkrBridge() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE);
    }

    public static String getEngineVersion(Context context) {
        if (context == null) return ENGINE_VERSION_AUTO;
        String v = prefs(context).getString(KEY_KR_ENGINE_VERSION, ENGINE_VERSION_AUTO);
        return normalizeEngineVersion(v);
    }

    public static void setEngineVersion(Context context, String version) {
        if (context == null) return;
        prefs(context).edit()
                .putString(KEY_KR_ENGINE_VERSION, normalizeEngineVersion(version))
                .apply();
    }

    public static boolean isCompatMode(Context context) {
        if (context == null) return false;
        return prefs(context).getBoolean(KEY_KR_COMPAT_MODE, false);
    }

    public static void setCompatMode(Context context, boolean enabled) {
        if (context == null) return;
        prefs(context).edit().putBoolean(KEY_KR_COMPAT_MODE, enabled).apply();
    }

    public static boolean isKrScopedSaveDir(Context context) {
        if (context == null) return false;
        return prefs(context).getBoolean(KEY_KR_SCOPED_SAVE_DIR, false);
    }

    public static void setKrScopedSaveDir(Context context, boolean enabled) {
        if (context == null) return;
        prefs(context).edit().putBoolean(KEY_KR_SCOPED_SAVE_DIR, enabled).apply();
    }

    public static boolean isArtemisScopedSaveDir(Context context) {
        if (context == null) return false;
        return prefs(context).getBoolean(KEY_ARTEMIS_SCOPED_SAVE_DIR, false);
    }

    public static void setArtemisScopedSaveDir(Context context, boolean enabled) {
        if (context == null) return;
        prefs(context).edit().putBoolean(KEY_ARTEMIS_SCOPED_SAVE_DIR, enabled).apply();
    }

    public static String normalizeEngineVersion(String value) {
        String v = value == null ? ENGINE_VERSION_AUTO : value.trim().toLowerCase();
        if (ENGINE_VERSION_139.equals(v)) return ENGINE_VERSION_139;
        if (ENGINE_VERSION_134.equals(v)) return ENGINE_VERSION_134;
        return ENGINE_VERSION_AUTO;
    }

    public static String engineVersionLabel(String value) {
        String v = normalizeEngineVersion(value);
        if (ENGINE_VERSION_139.equals(v)) return "1.3.9";
        if (ENGINE_VERSION_134.equals(v)) return "1.3.4";
        return "自动";
    }
}
