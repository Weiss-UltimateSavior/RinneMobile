package com.apps

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.apps.account.LauncherSessionExpiredNotifier
import com.apps.theme.LauncherDialogFactory
import com.apps.theme.LauncherTheme
import com.core.CoreApp
import com.core.launcher.LauncherUiBridge
import com.core.util.DevLogger

class LauncherApplication : CoreApp() {
    override fun onCreate() {
        LauncherUiBridge.install(AppLauncherUiDelegate)
        super.onCreate()
        // 初始化开发者日志（设置 logcat 文件路径；若上次开启过则恢复采集）。
        // 之前未调用导致 logcatFile 为 null，诊断界面大小恒显示 0B。
        DevLogger.init(applicationContext)
        // 历史偏好迁移集中在 LauncherPreferences 内完成（规范 §4），Application 只调入口。
        LauncherPreferences.migrateLegacyProfileName(applicationContext)
    }
}

private object AppLauncherUiDelegate : LauncherUiBridge.Delegate {
    override fun isFollowingSystemTone(context: Context): Boolean =
        LauncherPreferences.isFollowingSystemTone(context)

    override fun isLauncherDarkMode(context: Context): Boolean =
        LauncherPreferences.isDarkMode(context)

    override fun primary(context: Context): Int = LauncherTheme.primary(context)

    override fun onPrimary(context: Context): Int = LauncherTheme.onPrimary(context)

    override fun card(context: Context): Int = LauncherTheme.card(context)

    override fun text(context: Context): Int = LauncherTheme.text(context)

    override fun textMuted(context: Context): Int = LauncherTheme.textMuted(context)

    override fun themeLogoRes(context: Context): Int = when {
        LauncherThemeStyle.isRinne(context) -> com.core.R.drawable.launcher_theme_rinne_def
        LauncherThemeStyle.isAnri(context) -> com.core.R.drawable.launcher_theme_anri_def
        LauncherThemeStyle.isXinhaitian(context) -> com.core.R.drawable.launcher_theme_xinhaitian_def
        LauncherThemeStyle.isNatsume(context) -> com.core.R.drawable.launcher_theme_natsume_def
        LauncherThemeStyle.isIzumi(context) -> com.core.R.drawable.launcher_theme_izumi_def
        else -> com.core.R.drawable.launcher_game_center_default
    }

    override fun restartLauncher(activity: Activity): Boolean = try {
        val intent = Intent(activity, LauncherActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
        activity.overridePendingTransition(0, 0)
        activity.finish()
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    override fun showOverlayConfirm(
        context: Context,
        title: CharSequence,
        message: CharSequence,
        positiveText: CharSequence,
        windowType: Int,
        onConfirm: Runnable,
        onDismiss: Runnable
    ): Boolean = try {
        val dialog = LauncherDialogFactory.showOverlayConfirm(
            context,
            title.toString(),
            message.toString(),
            positiveText.toString(),
            { onConfirm.run() },
            windowType
        )
        dialog.setOnDismissListener { onDismiss.run() }
        true
    } catch (error: Exception) {
        DevLogger.w("LauncherApplication", "Failed to show overlay confirmation dialog", error)
        onDismiss.run()
        false
    }

    override fun onApplicationCreate(application: Application) {
        LauncherSessionExpiredNotifier.install(application)
    }
}
