package com.yuki.yukihub.launcherbridge;

import android.content.Context;
import android.content.SharedPreferences;

import com.yuki.yukihub.launcher.ExternalGodotPluginStrategy;
import com.yuki.yukihub.launcher.ExternalRenPyPluginStrategy;
import com.yuki.yukihub.launcher.ExternalRpgMakerPluginStrategy;

import java.util.Locale;

/**
 * Launcher ↔ Core 模块兼容层的桥接器。
 *
 * <p>封装两类信息：
 * <ul>
 *   <li>安装探测：委托给 {@link ExternalRpgMakerPluginStrategy} /
 *       {@link ExternalRenPyPluginStrategy} / {@link ExternalGodotPluginStrategy}
 *       的 PackageManager 检查。</li>
 *   <li>启用状态：保存在 {@code yukihub_prefs} 中，默认启用。
 *       com.apps 通过本桥读写；{@link LauncherGameLaunchBridge} 在 validate 阶段拦截。</li>
 * </ul>
 *
 * <p>com.apps 不得直接 import core.launcher 包，所有调用都走本桥。
 */
public final class LauncherModuleBridge {
    private static final String APP_PREFS = "yukihub_prefs";
    private static final String KEY_RPGM_ENABLED = "module.rpgm.enabled";
    private static final String KEY_RENPY_ENABLED = "module.renpy.enabled";
    private static final String KEY_GODOT_ENABLED = "module.godot.enabled";

    private LauncherModuleBridge() {
    }

    // ----- 安装探测 -----

    public static boolean isRpgMakerModuleInstalled(Context context) {
        return ExternalRpgMakerPluginStrategy.isRpgMakerPluginInstalled(context);
    }

    public static boolean isRenPyModuleInstalled(Context context) {
        return ExternalRenPyPluginStrategy.isRenPyPluginInstalled(context);
    }

    public static boolean isGodotModuleInstalled(Context context) {
        // 用户可能安装 Godot 3 或 Godot 4 插件中的任意一个
        return ExternalGodotPluginStrategy.isPluginInstalled(context, ExternalGodotPluginStrategy.PLUGIN_PACKAGE_GODOT3)
                || ExternalGodotPluginStrategy.isPluginInstalled(context, ExternalGodotPluginStrategy.PLUGIN_PACKAGE_GODOT4);
    }

    // ----- 启用状态 -----

    public static boolean isRpgMakerModuleEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_RPGM_ENABLED, true);
    }

    public static boolean isRenPyModuleEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_RENPY_ENABLED, true);
    }

    public static boolean isGodotModuleEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_GODOT_ENABLED, true);
    }

    public static void setRpgMakerModuleEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(KEY_RPGM_ENABLED, enabled).apply();
    }

    public static void setRenPyModuleEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(KEY_RENPY_ENABLED, enabled).apply();
    }

    public static void setGodotModuleEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(KEY_GODOT_ENABLED, enabled).apply();
    }

    // ----- 包名匹配 -----

    /**
     * 判断给定包名是否属于 RPGM 外部插件。
     * 涵盖真实包名 {@code cyou.joiplay.runtime.rpgmaker} 及所有 YukiHub 内部别名：
     * internal.rpgmaker / internal.rpgmxp / internal.rpgmvx / internal.rpgmvxace /
     * internal.mkxp-z / internal.mkxpz。
     */
    public static boolean isRpgMakerPluginPackage(String pkg) {
        if (pkg == null) return false;
        String p = pkg.trim().toLowerCase(Locale.ROOT);
        if (p.isEmpty()) return false;
        if (ExternalRpgMakerPluginStrategy.PLUGIN_PACKAGE.equalsIgnoreCase(p)) return true;
        return p.startsWith("internal.rpg") || p.startsWith("internal.mkxp");
    }

    /**
     * 判断给定包名是否属于 RenPy 外部插件。
     * 涵盖真实包名 {@code cyou.joiplay.runtime.renpy.v8d4d1} 及别名：
     * internal.renpy / internal.renpy8。
     */
    public static boolean isRenPyPluginPackage(String pkg) {
        if (pkg == null) return false;
        String p = pkg.trim().toLowerCase(Locale.ROOT);
        if (p.isEmpty()) return false;
        if (ExternalRenPyPluginStrategy.PLUGIN_PACKAGE.equalsIgnoreCase(p)) return true;
        return p.startsWith("internal.renpy");
    }

    /**
     * 判断给定包名是否属于 Godot 外部插件。
     * 涵盖真实包名 {@code cyou.joiplay.runtime.godot3} /
     * {@code cyou.joiplay.runtime.godot4} 及别名：
     * internal.godot / internal.godot3 / internal.godot4。
     */
    public static boolean isGodotPluginPackage(String pkg) {
        if (pkg == null) return false;
        String p = pkg.trim().toLowerCase(Locale.ROOT);
        if (p.isEmpty()) return false;
        if (ExternalGodotPluginStrategy.PLUGIN_PACKAGE_GODOT3.equalsIgnoreCase(p)) return true;
        if (ExternalGodotPluginStrategy.PLUGIN_PACKAGE_GODOT4.equalsIgnoreCase(p)) return true;
        return p.equals("internal.godot") || p.equals("internal.godot3") || p.equals("internal.godot4");
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE);
    }
}
