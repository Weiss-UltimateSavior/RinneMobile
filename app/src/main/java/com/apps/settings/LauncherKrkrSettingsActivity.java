package com.apps.settings;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.apps.LauncherActivity;
import com.core.R;

/**
 * 引擎设置页薄宿主（重构计划 9.9 阶段 110）。
 *
 * 全部逻辑抽取至 {@link LauncherKrkrSettingsFragment}，本类仅承载竖屏独立启动路径
 * （含 Pad/Library 的 per-game EXTRA_GAME_ID 入口；HD 由 HdManageFragment 以子 Fragment 承载）。
 */
public class LauncherKrkrSettingsActivity extends AppCompatActivity {
    public static final String EXTRA_GAME_ID = LauncherKrkrSettingsFragment.EXTRA_GAME_ID;
    public static final String EXTRA_ARTEMIS_ONLY = LauncherKrkrSettingsFragment.EXTRA_ARTEMIS_ONLY;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherActivity.applySavedToneMode(this);
        super.onCreate(savedInstanceState);
        com.apps.LauncherEdgeToEdgeHelper.apply(this);
        setContentView(R.layout.activity_launcher_manage_host);
        if (savedInstanceState == null) {
            long gameId = getIntent().getLongExtra(EXTRA_GAME_ID, 0L);
            boolean artemisOnly = getIntent().getBooleanExtra(EXTRA_ARTEMIS_ONLY, false);
            LauncherKrkrSettingsFragment fragment = artemisOnly
                    ? LauncherKrkrSettingsFragment.newArtemisOnlyInstance()
                    : LauncherKrkrSettingsFragment.newInstance(gameId);
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.manageHostContainer, fragment)
                    .commit();
        }
    }

    /** 引擎设置保存/取消后关闭入口（原 finish() 语义）。 */
    void finishKrkrSettings() {
        finish();
    }

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase));
    }
}
