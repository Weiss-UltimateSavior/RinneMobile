package com.apps.game;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.apps.LauncherActivity;
import com.core.R;

/**
 * 添加游戏页薄宿主（重构计划 9.9 阶段 110）。
 *
 * 全部逻辑抽取至 {@link LauncherAddGameFragment}，本类仅承载竖屏独立启动路径
 * （HD 由 HdManageFragment 以子 Fragment 承载）；保存成功时转发 setResult + finish。
 */
public class LauncherAddGameActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherActivity.applySavedToneMode(this);
        super.onCreate(savedInstanceState);
        com.apps.LauncherEdgeToEdgeHelper.apply(this);
        setContentView(R.layout.activity_launcher_manage_host);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.manageHostContainer, new LauncherAddGameFragment())
                    .commit();
        }
    }

    /** 添加游戏保存成功后关闭入口：成功时回传 RESULT_OK（原 Activity setResult 语义）。 */
    void finishAddGame(boolean resultOk) {
        if (resultOk) setResult(RESULT_OK);
        finish();
    }

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase));
    }
}
