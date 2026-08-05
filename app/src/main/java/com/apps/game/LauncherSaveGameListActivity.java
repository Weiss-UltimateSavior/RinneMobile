package com.apps.game;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.apps.LauncherActivity;
import com.core.R;

/**
 * Second-level screen host: lists only games belonging to one emulator type.
 *
 * 重构计划 9.9 阶段 107：全部逻辑抽取至 {@link LauncherSaveGameListFragment}，
 * 本类仅承载竖屏独立启动路径（HD 由 HdSaveManagerFragment 以子 Fragment 承载）。
 */
public class LauncherSaveGameListActivity extends AppCompatActivity {
    public static final String EXTRA_ENGINE = LauncherSaveGameListFragment.EXTRA_ENGINE;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherActivity.applySavedToneMode(this);
        super.onCreate(savedInstanceState);
        com.apps.LauncherEdgeToEdgeHelper.apply(this);
        setContentView(R.layout.activity_launcher_save_game_list_host);
        if (savedInstanceState == null) {
            String engineName = getIntent().getStringExtra(EXTRA_ENGINE);
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.saveGameListHostContainer,
                            LauncherSaveGameListFragment.newInstance(engineName))
                    .commit();
        }
    }

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase));
    }
}
