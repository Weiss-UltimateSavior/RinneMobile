package com.core

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.apps.LauncherActivity
import com.apps.account.LauncherSessionExpiredNotifier
import com.core.util.UiScaleUtil

class CoreApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 以保存的色调偏好初始化进程级 night mode 默认值，确保冷启动期间所有 AppCompat
        // 组件（含尚未调用 setLocalNightMode 的 Activity / Dialog）都能命中正确色调。
        // 自动模式必须保留 FOLLOW_SYSTEM，不能在启动时解析成一次性的 YES/NO；否则系统
        // 随后切换深浅色时不会向各个 AppCompat Activity 分发新的 night mode。
        AppCompatDelegate.setDefaultNightMode(
            if (LauncherActivity.isFollowingSystemTone(this)) {
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            } else if (LauncherActivity.isLauncherDarkMode(this)) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        )
        LauncherSessionExpiredNotifier.install(this)
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(UiScaleUtil.wrap(base) ?: base)
    }
}
