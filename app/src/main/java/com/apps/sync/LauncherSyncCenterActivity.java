package com.apps.sync;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.apps.LauncherActivity;
import com.core.R;

/**
 * 同步中心页薄宿主（重构计划 9.9 阶段 110）。
 *
 * 全部逻辑抽取至 {@link LauncherSyncCenterFragment}，本类仅承载竖屏独立启动路径
 * （HD 由 HdManageFragment 以子 Fragment 承载）。
 */
public class LauncherSyncCenterActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherActivity.applySavedToneMode(this);
        super.onCreate(savedInstanceState);
        com.apps.LauncherEdgeToEdgeHelper.apply(this);
        setContentView(R.layout.activity_launcher_manage_host);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.manageHostContainer, new LauncherSyncCenterFragment())
                    .commit();
        }
    }

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase));
    }
}
