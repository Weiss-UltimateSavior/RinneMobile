package com.yuki.yukihub;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.apps.LauncherActivity;

public class LaunchEntryActivity extends Activity {
    private static final long SPLASH_DELAY_MS = 1500L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (isFinishing()) return;
            Class<?> target = AppLaunchMode.shouldRouteToLauncher(this)
                    ? LauncherActivity.class
                    : MainActivity.class;
            Intent intent = new Intent(this, target);
            if (getIntent() != null && getIntent().getExtras() != null) {
                intent.putExtras(getIntent().getExtras());
            }
            startActivity(intent);
            overridePendingTransition(R.anim.launcher_activity_pop_enter, R.anim.launcher_activity_pop_exit);
            finish();
        }, SPLASH_DELAY_MS);
    }
}
