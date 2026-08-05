package com.core.launcher

import android.content.Context
import android.content.Intent

/**
 * 通用包名启动器（重构计划 4.1 W-2，阶段 124）：从 [ExternalGameLaunchers] 提取的
 * 纯「按包名启动」工具，打破 WinlatorLauncher ↔ ExternalGameLaunchers 同包循环依赖
 * （§8:330 object 间禁止循环依赖）。
 *
 * 只依赖 Context，不引用任何同包启动策略 object；[ExternalGameLaunchers.launchPackage]
 * 保留 @JvmStatic 兼容签名并委托本类（单源在 [PackageLauncher]）。
 */
internal object PackageLauncher {

    @JvmStatic
    fun launchPackage(context: Context?, packageName: String?): Boolean {
        if (context == null || packageName.isNullOrBlank()) return false
        val pkg = packageName.trim()
        context.packageManager.getLaunchIntentForPackage(pkg)?.let { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return true
        }
        arrayOf(
            "$pkg.MainActivity", "$pkg.AppActivity", "$pkg.TyranoActivity",
            "$pkg.PlayerActivity", "$pkg.activity.MainActivity",
        ).forEach { className ->
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(pkg, className)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return true
            } catch (_: Exception) {
                // Try the next common launcher class.
            }
        }
        return false
    }
}
