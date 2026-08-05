package com.apps.leaderboard

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.apps.LauncherActivity
import com.apps.LauncherEdgeToEdgeHelper
import com.core.R

/**
 * 游玩时长排行榜页薄宿主（重构计划 9.9 阶段 111）。
 *
 * 全部逻辑抽取至 [LauncherLeaderboardFragment]，本类仅承载竖屏独立启动路径
 * （HD 由 HdProfileFragment 以子 Fragment 承载）。
 */
class LauncherLeaderboardActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        LauncherActivity.applySavedToneMode(this)
        super.onCreate(savedInstanceState)
        LauncherEdgeToEdgeHelper.apply(this)
        setContentView(R.layout.activity_launcher_profile_host)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.profileHostContainer, LauncherLeaderboardFragment())
                .commit()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase))
    }
}
