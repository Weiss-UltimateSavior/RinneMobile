package com.apps.settings

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.apps.LauncherActivity
import com.apps.LauncherEdgeToEdgeHelper
import com.core.R

/**
 * 应用设置页薄宿主（重构计划 9.9 阶段 109）。
 *
 * 全部逻辑抽取至 [LauncherAppSettingsFragment]，本类仅承载竖屏独立启动路径
 * （HD 由 HdSettingsFragment 以子 Fragment 承载）。
 */
class LauncherAppSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        LauncherActivity.applySavedToneMode(this)
        super.onCreate(savedInstanceState)
        LauncherEdgeToEdgeHelper.apply(this)
        setContentView(R.layout.activity_launcher_settings_host)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsHostContainer, LauncherAppSettingsFragment())
                .commit()
        }
    }

    /** 设置页关闭入口（原 Activity finish() 语义）。 */
    fun finishAppSettings() {
        finish()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase))
    }
}
