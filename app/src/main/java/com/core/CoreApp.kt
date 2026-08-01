package com.core

import android.app.Application
import android.content.Context
import com.core.launcher.LauncherUiBridge
import com.core.util.UiScaleUtil

open class CoreApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 通过 LauncherUiBridge 交给 apps 层注册的启动协调器完成 Application 级 UI 初始化；
        // core 只保留 night mode 的默认兜底，避免直接依赖展示层对象。
        LauncherUiBridge.onApplicationCreate(this)
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(UiScaleUtil.wrap(base) ?: base)
    }
}
