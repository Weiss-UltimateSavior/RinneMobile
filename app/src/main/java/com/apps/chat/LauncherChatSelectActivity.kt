package com.apps.chat

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.apps.LauncherActivity
import com.apps.LauncherEdgeToEdgeHelper
import com.apps.theme.LauncherMotion
import com.core.R

/**
 * 聊天选择薄宿主（重构计划 9.9 W-3，阶段 129）。
 *
 * 全部逻辑抽取至 [LauncherChatSelectFragment]（选择行/继续/路由），
 * 本类仅承载竖屏独立启动路径（HD 由 HdProfileFragment 以子 Fragment 承载，
 * 选择后替换为聊天子 Fragment）；保留 edge-to-edge 窗口配置。
 */
class LauncherChatSelectActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        LauncherActivity.applySavedToneMode(this)
        super.onCreate(savedInstanceState)
        LauncherEdgeToEdgeHelper.apply(this)
        setContentView(R.layout.activity_launcher_settings_host)
        // 进入动效（对齐薄宿主惯例，见 W-1）。
        LauncherMotion.applyActivityOpen(this)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsHostContainer, LauncherChatSelectFragment())
                .commit()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase))
    }
}
