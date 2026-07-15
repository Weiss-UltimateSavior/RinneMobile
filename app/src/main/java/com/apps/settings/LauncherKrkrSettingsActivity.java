package com.apps.settings;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.yuki.yukihub.R;
import com.yuki.yukihub.databinding.ActivityLauncherKrkrSettingsBinding;
import com.yuki.yukihub.launcherbridge.LauncherGameLaunchBridge;
import com.yuki.yukihub.launcherbridge.LauncherKrkrBridge;
import com.yuki.yukihub.ons.OnsSettings;
import com.apps.LauncherActivity;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherTabletPortraitScaler;

public class LauncherKrkrSettingsActivity extends AppCompatActivity {
    private ActivityLauncherKrkrSettingsBinding binding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherActivity.applySavedToneMode(this);
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();

        binding = ActivityLauncherKrkrSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        LauncherTabletPortraitScaler.applyActivityContent(this);
        applySystemBarInsets();
        bindActions();
        applyThemeTone();
        loadConfig();
    }

    private void applySystemBarInsets() {
        int left = binding.krkrScroll.getPaddingLeft();
        int top = binding.krkrScroll.getPaddingTop();
        int right = binding.krkrScroll.getPaddingRight();
        int bottom = binding.krkrScroll.getPaddingBottom();
        binding.krkrScroll.setOnApplyWindowInsetsListener((view, insets) -> {
            binding.krkrScroll.setPadding(left, top + insets.getSystemWindowInsetTop(), right, bottom);
            return insets;
        });
        binding.krkrScroll.requestApplyInsets();
    }

    private void bindActions() {
        binding.btnSave.setOnClickListener(v -> save());
        binding.btnCancel.setOnClickListener(v -> finish());
        binding.btnNativeKrkr.setOnClickListener(v -> enterNativeKrkr());
    }

    private void applyThemeTone() {
        ArrayAdapter<String> adapter = LauncherTheme.spinnerAdapter(this,
                new String[]{"自动", "1.3.9", "1.3.4"});
        binding.engineVersionSpinner.setAdapter(adapter);
        LauncherTheme.styleSpinner(binding.engineVersionSpinner);
        LauncherTheme.styleSwitch(binding.krScopedSwitch);
        LauncherTheme.styleSwitch(binding.artemisScopedSwitch);
        LauncherTheme.styleSwitch(binding.onsScopedSwitch);
        LauncherTheme.styleSwitch(binding.tyranoScopedSwitch);
        LauncherTheme.applyPrimaryTone(binding.getRoot());
        LauncherTheme.longActionButton(binding.btnNativeKrkr);
        LauncherTheme.longActionButton(binding.btnSave);
        LauncherTheme.longActionButton(binding.btnCancel);
    }

    private void loadConfig() {
        String version = LauncherKrkrBridge.getEngineVersion(this);
        int selection = 0;
        if (LauncherKrkrBridge.ENGINE_VERSION_139.equals(version)) selection = 1;
        else if (LauncherKrkrBridge.ENGINE_VERSION_134.equals(version)) selection = 2;
        binding.engineVersionSpinner.setSelection(selection);
        binding.krScopedSwitch.setChecked(LauncherKrkrBridge.isKrScopedSaveDir(this));
        binding.artemisScopedSwitch.setChecked(LauncherKrkrBridge.isArtemisScopedSaveDir(this));
        binding.onsScopedSwitch.setChecked(OnsSettings.load(this).scopedSaveDir);
        binding.tyranoScopedSwitch.setChecked(LauncherKrkrBridge.isTyranoScopedSaveDir(this));
    }

    private void save() {
        int pos = binding.engineVersionSpinner.getSelectedItemPosition();
        String version = LauncherKrkrBridge.ENGINE_VERSION_AUTO;
        if (pos == 1) version = LauncherKrkrBridge.ENGINE_VERSION_139;
        else if (pos == 2) version = LauncherKrkrBridge.ENGINE_VERSION_134;

        LauncherKrkrBridge.setEngineVersion(this, version);
        LauncherKrkrBridge.setKrScopedSaveDir(this, binding.krScopedSwitch.isChecked());
        LauncherKrkrBridge.setArtemisScopedSaveDir(this, binding.artemisScopedSwitch.isChecked());
        OnsSettings onsSettings = OnsSettings.load(this);
        onsSettings.scopedSaveDir = binding.onsScopedSwitch.isChecked();
        onsSettings.save(this);
        LauncherKrkrBridge.setTyranoScopedSaveDir(this, binding.tyranoScopedSwitch.isChecked());

        Toast.makeText(this, "引擎设置已保存：" + LauncherKrkrBridge.engineVersionLabel(version), Toast.LENGTH_SHORT).show();
        finish();
    }

    private void enterNativeKrkr() {
        try {
            startActivity(LauncherGameLaunchBridge.buildInternalKrkrOriginIntent(this));
        } catch (Throwable t) {
            Toast.makeText(this, "无法进入原生 KRKR", Toast.LENGTH_SHORT).show();
        }
    }

    private void configureEdgeToEdgeWindow() {
        boolean darkMode = LauncherActivity.isLauncherDarkMode(this);
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(ContextCompat.getColor(this, R.color.launcher_bg_color));
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        if (!darkMode) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        window.getDecorView().setSystemUiVisibility(flags);
    }

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase));
    }
}
