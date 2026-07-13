package com.yuki.yukihub;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

import com.apps.account.LauncherSessionExpiredNotifier;
import com.yuki.yukihub.util.UiScaleUtil;

public class YukiHubApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        LauncherSessionExpiredNotifier.install(this);
    }

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(UiScaleUtil.wrap(base));
    }
}
