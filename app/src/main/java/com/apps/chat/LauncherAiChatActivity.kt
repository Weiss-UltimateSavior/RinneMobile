package com.apps.chat

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.apps.LauncherActivity
import com.apps.LauncherEdgeToEdgeHelper
import com.apps.theme.LauncherMotion
import com.core.R

/**
 * AI 聊天薄宿主（重构计划 9.9 W-3，阶段 127）。
 *
 * 全部逻辑抽取至 [LauncherAiChatFragment]（聊天交互/insets+IME/更多菜单），
 * 本类仅承载竖屏独立启动路径（HD 由 HdProfileFragment 以子 Fragment 承载，阶段 129 接入）；
 * 保留 edge-to-edge 窗口配置（与 Fragment 内 insets/IME 处理耦合）。
 */
class LauncherAiChatActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        LauncherActivity.applySavedToneMode(this)
        super.onCreate(savedInstanceState)
        LauncherEdgeToEdgeHelper.apply(this, true, true)
        setContentView(R.layout.activity_launcher_settings_host)
        // 进入动效（对齐薄宿主惯例，见 W-1）。
        LauncherMotion.applyActivityOpen(this)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(
                    R.id.settingsHostContainer,
                    LauncherAiChatFragment.newInstance(
                        persona = getIntent().getStringExtra(LauncherAiChatFragment.EXTRA_PERSONA),
                        threadId = getIntent().getStringExtra(LauncherAiChatFragment.EXTRA_THREAD_ID),
                        title = getIntent().getStringExtra(LauncherAiChatFragment.EXTRA_TITLE),
                    ),
                )
                .commit()
        }
    }

    /** AI 聊天关闭入口（原返回按钮 LauncherMotion.finish 语义；Fragment OnBackPressedCallback 落点）。 */
    fun finishAiChat() {
        LauncherMotion.finish(this)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase))
    }
}
