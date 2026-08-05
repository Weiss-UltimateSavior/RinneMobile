package com.apps.theme;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.apps.LauncherActivity;
import com.core.R;

/**
 * 主题菜单薄宿主（重构计划 9.9 阶段 109）。
 *
 * 全部逻辑抽取至 {@link LauncherThemeMenuFragment}，本类仅承载竖屏独立启动路径
 * （HD 由 HdSettingsFragment 以子 Fragment 承载）。
 */
public class LauncherThemeMenuActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        applySavedToneMode();
        super.onCreate(savedInstanceState);
        com.apps.LauncherEdgeToEdgeHelper.apply(this);
        setContentView(R.layout.activity_launcher_settings_host);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settingsHostContainer, new LauncherThemeMenuFragment())
                    .commit();
        }
    }

    /** 主题应用后关闭入口（原 LauncherMotion.finish 语义）。 */
    void finishThemeMenu() {
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
