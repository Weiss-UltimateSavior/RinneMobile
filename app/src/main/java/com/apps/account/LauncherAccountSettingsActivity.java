package com.apps.account;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.apps.LauncherActivity;
import com.core.R;

/**
 * 账号设置页薄宿主（重构计划 9.9 阶段 111）。
 *
 * 全部逻辑抽取至 {@link LauncherAccountSettingsFragment}，本类仅承载竖屏独立启动路径
 * （HD 由 HdProfileFragment 以子 Fragment 承载）。
 */
public class LauncherAccountSettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        applySavedToneMode();
        super.onCreate(savedInstanceState);
        com.apps.LauncherEdgeToEdgeHelper.apply(this);
        setContentView(R.layout.activity_launcher_profile_host);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.profileHostContainer, new LauncherAccountSettingsFragment())
                    .commit();
        }
    }

    private void applySavedToneMode() {
        LauncherActivity.applySavedToneMode(this);
    }

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase));
    }
}
