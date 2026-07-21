package com.yuki.yukihub

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.apps.LauncherActivity
import com.apps.account.LauncherSessionExpiredNotifier
import com.yuki.yukihub.util.UiScaleUtil

class YukiHubApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 以保存的色调偏好初始化进程级 night mode 默认值，确保冷启动期间所有 AppCompat
        // 组件（含尚未调用 setLocalNightMode 的 Activity / Dialog）都能命中正确色调。
        // 原先固定 MODE_NIGHT_NO 会导致深色偏好下首帧按浅色渲染再被纠正，产生闪烁。
        AppCompatDelegate.setDefaultNightMode(
            if (LauncherActivity.isLauncherDarkMode(this))
                AppCompatDelegate.MODE_NIGHT_YES
            else
                AppCompatDelegate.MODE_NIGHT_NO
        )
        LauncherSessionExpiredNotifier.install(this)
    }

    protected override fun attachBaseContext(base: Context) {
        super.attachBaseContext(UiScaleUtil.wrap(base) ?: base)
    }
}
