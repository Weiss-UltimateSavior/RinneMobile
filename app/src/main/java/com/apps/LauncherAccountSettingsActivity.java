package com.apps;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.yuki.yukihub.R;
import com.yuki.yukihub.databinding.ActivityLauncherAccountSettingsBinding;

public class LauncherAccountSettingsActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "launcher_account_settings";

    private ActivityLauncherAccountSettingsBinding binding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        applySavedToneMode();
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();

        binding = ActivityLauncherAccountSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applySystemBarInsets();
        LauncherTheme.applyPrimaryTone(binding.getRoot());

        bindActions();
        renderAllChips();
    }

    private void bindActions() {
        binding.rowSyncConfig.setOnClickListener(v -> toggleAndRender("sync_config", binding.chipSyncConfig));
        binding.rowRealtimePlaytime.setOnClickListener(v -> toggleAndRender("realtime_playtime", binding.chipRealtimePlaytime));
        binding.rowProfileDisplay.setOnClickListener(v -> toggleAndRender("profile_display", binding.chipProfileDisplay));
        binding.rowModelFeature.setOnClickListener(v -> toggleAndRender("model_feature", binding.chipModelFeature));
        binding.rowEmailSubscribe.setOnClickListener(v -> toggleAndRender("email_subscribe", binding.chipEmailSubscribe));
    }

    private void toggleAndRender(String key, android.widget.TextView chip) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, 0);
        boolean enabled = !prefs.getBoolean(key, getDefault(key));
        prefs.edit().putBoolean(key, enabled).apply();
        renderChip(chip, enabled);
    }

    private void renderAllChips() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, 0);
        renderChip(binding.chipSyncConfig, prefs.getBoolean("sync_config", getDefault("sync_config")));
        renderChip(binding.chipRealtimePlaytime, prefs.getBoolean("realtime_playtime", getDefault("realtime_playtime")));
        renderChip(binding.chipProfileDisplay, prefs.getBoolean("profile_display", getDefault("profile_display")));
        renderChip(binding.chipModelFeature, prefs.getBoolean("model_feature", getDefault("model_feature")));
        renderChip(binding.chipEmailSubscribe, prefs.getBoolean("email_subscribe", getDefault("email_subscribe")));
    }

    private void renderChip(android.widget.TextView chip, boolean enabled) {
        chip.setText(enabled ? "关闭" : "开启");
        LauncherTheme.chip(chip, enabled);
    }

    private boolean getDefault(String key) {
        switch (key) {
            case "realtime_playtime":
            case "profile_display":
                return true;
            default:
                return false;
        }
    }

    private void applySystemBarInsets() {
        int originalLeft = binding.accountSettingsScroll.getPaddingLeft();
        int originalTop = binding.accountSettingsScroll.getPaddingTop();
        int originalRight = binding.accountSettingsScroll.getPaddingRight();
        int originalBottom = binding.accountSettingsScroll.getPaddingBottom();

        binding.getRoot().setOnApplyWindowInsetsListener((view, insets) -> {
            binding.accountSettingsScroll.setPadding(
                    originalLeft,
                    originalTop + insets.getSystemWindowInsetTop(),
                    originalRight,
                    originalBottom
            );
            return insets;
        });
        binding.getRoot().requestApplyInsets();
    }

    private void configureEdgeToEdgeWindow() {
        boolean darkMode = LauncherActivity.isLauncherDarkMode(this);
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(ContextCompat.getColor(this, R.color.launcher_bg_color));
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        if (!darkMode) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private void applySavedToneMode() {
        LauncherActivity.applySavedToneMode(this);
    }

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase));
    }
}
