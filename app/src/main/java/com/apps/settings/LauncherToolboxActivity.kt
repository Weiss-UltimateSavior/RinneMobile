package com.apps.settings

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.apps.LauncherActivity
import com.apps.LauncherEdgeToEdgeHelper
import com.apps.theme.LauncherMotion
import com.core.R

/**
 * 工具箱薄宿主（重构计划 9.9 阶段 113）。
 *
 * 全部逻辑抽取至 [LauncherToolboxFragment]，本类仅承载竖屏独立启动路径
 * （HD 由 HdHomeFragment 以子 Fragment 承载）。
 */
class LauncherToolboxActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        LauncherActivity.applySavedToneMode(this)
        super.onCreate(savedInstanceState)
        LauncherEdgeToEdgeHelper.apply(this)
        setContentView(R.layout.activity_launcher_settings_host)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsHostContainer, LauncherToolboxFragment())
                .commit()
        }
    }

    /** 工具箱关闭入口（原 Activity 返回按钮 LauncherMotion.finish 语义）。 */
    fun finishToolbox() {
        LauncherMotion.finish(this)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase))
    }
}
