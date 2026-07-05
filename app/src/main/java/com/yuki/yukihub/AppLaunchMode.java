package com.yuki.yukihub;

import android.content.Context;
import android.content.SharedPreferences;

public final class AppLaunchMode {
    public static final String PREFS_NAME = "yukihub_prefs";
    public static final String KEY_YUKI_MOBILE_UI_ENABLED = "yuki_mobile_ui_enabled";

    private AppLaunchMode() {
    }

    public static boolean isYukiMobileUiEnabled(Context context) {
        if (context == null) return false;
        return prefs(context).getBoolean(KEY_YUKI_MOBILE_UI_ENABLED, false);
    }

    public static void setYukiMobileUiEnabled(Context context, boolean enabled) {
        if (context == null) return;
        prefs(context).edit().putBoolean(KEY_YUKI_MOBILE_UI_ENABLED, enabled).apply();
    }

    public static boolean shouldRouteToLauncher(Context context) {
        return isYukiMobileUiEnabled(context);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
