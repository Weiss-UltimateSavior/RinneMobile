package com.yuki.yukihub;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.apps.LauncherActivity;

public class LaunchEntryActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Class<?> target = AppLaunchMode.shouldRouteToLauncher(this)
                ? LauncherActivity.class
                : MainActivity.class;
        Intent intent = new Intent(this, target);
        if (getIntent() != null && getIntent().getExtras() != null) {
            intent.putExtras(getIntent().getExtras());
        }
        startActivity(intent);
        finish();
    }
}
