package com.apps.account;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.apps.LauncherActivity;
import com.apps.theme.LauncherMotion;
import com.core.R;

/**
 * 重置密码页薄宿主（重构计划 9.9 阶段 108）。
 *
 * 全部逻辑抽取至 {@link LauncherPasswordResetFragment}，本类仅承载竖屏独立启动路径
 * （HD 由 HdAccountFragment 以子 Fragment 承载）；成功关闭时转发 setResult + finish。
 */
public class LauncherPasswordResetActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        applySavedToneMode();
        super.onCreate(savedInstanceState);
        com.apps.LauncherEdgeToEdgeHelper.apply(this);
        setContentView(R.layout.activity_launcher_auth_host);
        LauncherMotion.applyActivityOpen(this);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.authHostContainer, LauncherPasswordResetFragment.newInstance())
                    .commit();
        }
    }

    /** 重置密码页成功关闭入口：成功时回传 RESULT_OK（原 Activity setResult 语义）。 */
    void finishPasswordReset(boolean resultOk) {
        if (resultOk) setResult(RESULT_OK);
        LauncherMotion.finish(this);
    }

    @Override
    public void onBackPressed() {
        LauncherMotion.finish(this);
    }

    private void applySavedToneMode() {
        LauncherActivity.applySavedToneMode(this);
    }

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase));
    }
}
