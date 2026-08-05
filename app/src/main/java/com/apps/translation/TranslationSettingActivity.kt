package com.apps.translation

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.apps.LauncherActivity
import com.apps.LauncherEdgeToEdgeHelper
import com.core.R

/**
 * 智能翻译配置页薄宿主（重构计划 9.9 阶段 111）。
 *
 * 全部逻辑抽取至 [TranslationSettingFragment]，本类仅承载竖屏独立启动路径
 * （HD 由 HdProfileFragment 以子 Fragment 承载）。
 */
class TranslationSettingActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        LauncherActivity.applySavedToneMode(this)
        super.onCreate(savedInstanceState)
        LauncherEdgeToEdgeHelper.apply(this)
        setContentView(R.layout.activity_launcher_profile_host)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.profileHostContainer, TranslationSettingFragment())
                .commit()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase))
    }
}
