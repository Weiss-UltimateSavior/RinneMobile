package com.yuki.yukihub;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

import com.yuki.yukihub.util.UiScaleUtil;

public class YukiHubApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
    }

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(UiScaleUtil.wrap(base));
    }
}
