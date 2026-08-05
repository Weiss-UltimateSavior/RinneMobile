package com.apps.profile;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.apps.LauncherActivity;
import com.apps.theme.LauncherMotion;
import com.core.R;

/**
 * 个人资料编辑页薄宿主（重构计划 9.9 阶段 111）。
 *
 * 全部逻辑抽取至 {@link LauncherProfileEditFragment}，本类仅承载竖屏独立启动路径
 * （HD 由 HdProfileFragment 以子 Fragment 承载）；密码修改成功时转发 setResult + finish。
 */
public class LauncherProfileEditActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        applySavedToneMode();
        super.onCreate(savedInstanceState);
        com.apps.LauncherEdgeToEdgeHelper.apply(this);
        setContentView(R.layout.activity_launcher_profile_host);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.profileHostContainer, new LauncherProfileEditFragment())
                    .commit();
        }
    }

    /** 资料编辑关闭入口：密码修改成功时回传 RESULT_OK（原 Activity setResult 语义）；统一带退出动画。 */
    void finishProfileEdit(boolean resultOk) {
        if (resultOk) setResult(RESULT_OK);
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
