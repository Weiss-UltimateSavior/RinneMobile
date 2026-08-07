package com.apps.game;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.apps.LauncherActivity;
import com.core.R;

/**
 * 编辑游戏页薄宿主（重构计划 9.9 阶段 110 同构，镜像 LauncherKrkrSettingsActivity）。
 *
 * 全部逻辑抽取至 {@link LauncherGameEditFragment}，本类仅承载竖屏独立启动路径
 * （含 Library 长按「编辑游戏」的 EXTRA_GAME_ID 入口；HD 由 HdGameLibraryFragment
 * 压入主容器回退栈承载）。
 */
public class LauncherGameEditActivity extends AppCompatActivity {
    public static final String EXTRA_GAME_ID = LauncherGameEditFragment.EXTRA_GAME_ID;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherActivity.applySavedToneMode(this);
        super.onCreate(savedInstanceState);
        com.apps.LauncherEdgeToEdgeHelper.apply(this);
        setContentView(R.layout.activity_launcher_manage_host);
        if (savedInstanceState == null) {
            long gameId = getIntent().getLongExtra(EXTRA_GAME_ID, -1L);
            LauncherGameEditFragment fragment = LauncherGameEditFragment.newInstance(gameId);
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.manageHostContainer, fragment)
                    .commit();
        }
    }

    /** 编辑游戏保存/取消后关闭入口（原 finish() 语义）。 */
    void finishGameEdit() {
        finish();
    }

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase));
    }
}
