package com.core.launcherbridge

import android.content.Context
import android.net.Uri
import com.core.launcher.EnginePackages
import com.core.launcher.ExternalGodotPluginStrategy
import com.core.launcher.ExternalRenPyPluginStrategy
import com.core.launcher.ExternalRpgMakerPluginStrategy
import com.core.nativeplugin.NativePluginImportResult
import com.core.nativeplugin.NativePluginInstallState
import com.core.nativeplugin.NativePluginInstaller
import com.core.nativeplugin.NativePluginManager
import java.util.Locale

/**
 * Launcher ↔ Core 模块兼容层的桥接器。
 *
 * 封装两类信息：
 * - 安装探测：委托给 [ExternalRpgMakerPluginStrategy] /
 *   [ExternalRenPyPluginStrategy] / [ExternalGodotPluginStrategy]
 *   的 PackageManager 检查。
 * - 启用状态：保存在 `yukihub_prefs` 中，默认不启用（用户需在「模块兼容」页面手动开启）。
 *   com.apps 通过本桥读写；[LauncherGameLaunchBridge] 在 validate 阶段拦截。
 *
 * com.apps 不得直接 import core.launcher 包，所有调用都走本桥。
 */
object LauncherModuleBridge {

    private const val KEY_RPGM_ENABLED = "module.rpgm.enabled"
    private const val KEY_RENPY_ENABLED = "module.renpy.enabled"
    private const val KEY_GODOT_ENABLED = "module.godot.enabled"

    // ----- 安装探测 -----

    @JvmStatic
    fun isRpgMakerModuleInstalled(context: Context): Boolean =
        ExternalRpgMakerPluginStrategy.isRpgMakerPluginInstalled(context)

    @JvmStatic
    fun isRenPyModuleInstalled(context: Context): Boolean =
        ExternalRenPyPluginStrategy.isRenPyPluginInstalled(context)

    @JvmStatic
    fun isGodotModuleInstalled(context: Context): Boolean =
        // 用户可能安装 Godot 3 或 Godot 4 插件中的任意一个
        ExternalGodotPluginStrategy.isPluginInstalled(context, ExternalGodotPluginStrategy.PLUGIN_PACKAGE_GODOT3)
                || ExternalGodotPluginStrategy.isPluginInstalled(context, ExternalGodotPluginStrategy.PLUGIN_PACKAGE_GODOT4)

    // ----- 启用状态 -----
    // 默认不启用：用户需在「模块兼容」页面手动开启，才能使用外置 JoiPlay 插件启动游戏。

    @JvmStatic
    fun isRpgMakerModuleEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_RPGM_ENABLED, false)

    @JvmStatic
    fun isRenPyModuleEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_RENPY_ENABLED, false)

    @JvmStatic
    fun isGodotModuleEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_GODOT_ENABLED, false)

    @JvmStatic
    fun setRpgMakerModuleEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_RPGM_ENABLED, enabled).apply()
    }

    @JvmStatic
    fun setRenPyModuleEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_RENPY_ENABLED, enabled).apply()
    }

    @JvmStatic
    fun setGodotModuleEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_GODOT_ENABLED, enabled).apply()
    }

    // ----- 内置 native zip 插件：Kirikiroid2 -----

    @JvmStatic
    fun kirikiroid2ModuleState(context: Context): NativePluginInstallState =
        NativePluginManager.kirikiroid2InstallState(context)

    @JvmStatic
    fun kirikiroid2ModuleStateCode(context: Context): String =
        when (NativePluginManager.kirikiroid2InstallState(context)) {
            NativePluginInstallState.NOT_INSTALLED -> "not_installed"
            NativePluginInstallState.INSTALLED_ENABLED -> "installed_enabled"
            NativePluginInstallState.INSTALLED_DISABLED -> "installed_disabled"
            NativePluginInstallState.INVALID -> "invalid"
        }

    @JvmStatic
    fun isKirikiroid2ModuleInstalled(context: Context): Boolean =
        NativePluginManager.isKirikiroid2Installed(context)

    @JvmStatic
    fun isKirikiroid2ModuleEnabled(context: Context): Boolean =
        NativePluginManager.isKirikiroid2Enabled(context)

    @JvmStatic
    fun setKirikiroid2ModuleEnabled(context: Context, enabled: Boolean) {
        NativePluginManager.setKirikiroid2Enabled(context, enabled)
    }

    @JvmStatic
    fun deleteKirikiroid2Module(context: Context): Boolean =
        NativePluginManager.deleteKirikiroid2(context)

    @JvmStatic
    fun importKirikiroid2Module(context: Context, uri: Uri?): NativePluginImportResult =
        NativePluginInstaller.importKirikiroid2(context, uri)

    @JvmStatic
    fun kirikiroid2ReadyCode(context: Context): String =
        NativePluginManager.requireKirikiroid2Ready(context).code

    // ----- 包名匹配 -----

    /**
     * 判断给定包名是否属于 RPGM 外部插件。
     * 涵盖真实包名 `cyou.joiplay.runtime.rpgmaker` 及所有 YukiHub 内部别名：
     * internal.rpgmaker / internal.rpgmxp / internal.rpgmvx / internal.rpgmvxace /
     * internal.mkxp-z / internal.mkxpz。
     */
    @JvmStatic
    fun isRpgMakerPluginPackage(pkg: String?): Boolean {
        if (pkg == null) return false
        val p = pkg.trim().lowercase(Locale.ROOT)
        if (p.isEmpty()) return false
        if (ExternalRpgMakerPluginStrategy.PLUGIN_PACKAGE.equals(p, ignoreCase = true)) return true
        return p.startsWith("internal.rpg") || p.startsWith("internal.mkxp")
    }

    /**
     * 判断给定包名是否属于 RenPy 外部插件。
     * 涵盖真实包名 `cyou.joiplay.runtime.renpy.v8d4d1` 及别名：
     * [com.core.launcher.EnginePackages.INTERNAL_RENPY] / [com.core.launcher.EnginePackages.INTERNAL_RENPY8]。
     */
    @JvmStatic
    fun isRenPyPluginPackage(pkg: String?): Boolean {
        if (pkg == null) return false
        val p = pkg.trim().lowercase(Locale.ROOT)
        if (p.isEmpty()) return false
        if (ExternalRenPyPluginStrategy.PLUGIN_PACKAGE.equals(p, ignoreCase = true)) return true
        return p.startsWith(EnginePackages.INTERNAL_RENPY)
    }

    /**
     * 判断给定包名是否属于 Godot 外部插件。
     * 涵盖真实包名 `cyou.joiplay.runtime.godot3` /
     * `cyou.joiplay.runtime.godot4` 及别名：
     * internal.godot / internal.godot3 / internal.godot4。
     */
    @JvmStatic
    fun isGodotPluginPackage(pkg: String?): Boolean {
        if (pkg == null) return false
        val p = pkg.trim().lowercase(Locale.ROOT)
        if (p.isEmpty()) return false
        if (ExternalGodotPluginStrategy.PLUGIN_PACKAGE_GODOT3.equals(p, ignoreCase = true)) return true
        if (ExternalGodotPluginStrategy.PLUGIN_PACKAGE_GODOT4.equals(p, ignoreCase = true)) return true
        return p == EnginePackages.INTERNAL_GODOT || p == "internal.godot3" || p == "internal.godot4"
    }

    private fun getPrefs(context: Context) = context.yukiPrefs()
}
