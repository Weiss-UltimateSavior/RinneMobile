package com.apps.account;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.yuki.yukihub.R;
import com.yuki.yukihub.databinding.ActivityLauncherDisclaimerBinding;
import com.yuki.yukihub.launcherbridge.LauncherDisclaimerBridge;
import com.apps.LauncherActivity;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherTabletPortraitScaler;

public class LauncherDisclaimerActivity extends AppCompatActivity {
    private ActivityLauncherDisclaimerBinding binding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        applySavedToneMode();
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();

        binding = ActivityLauncherDisclaimerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        LauncherTabletPortraitScaler.applyActivityContent(this);
        applySystemBarInsets();
        applyThemeTone();

        binding.disclaimerTitle.setText(LauncherDisclaimerBridge.getTitle());
        binding.disclaimerBody.setText(LauncherDisclaimerBridge.getContent());
        binding.disclaimerClose.setOnClickListener(view -> finish());
    }

    private void applySystemBarInsets() {
        int originalLeft = binding.disclaimerContent.getPaddingLeft();
        int originalTop = binding.disclaimerContent.getPaddingTop();
        int originalRight = binding.disclaimerContent.getPaddingRight();
        int originalBottom = binding.disclaimerContent.getPaddingBottom();

        binding.getRoot().setOnApplyWindowInsetsListener((view, insets) -> {
            binding.disclaimerContent.setPadding(
                    originalLeft,
                    originalTop + insets.getSystemWindowInsetTop(),
                    originalRight,
                    originalBottom + insets.getSystemWindowInsetBottom()
            );
            return insets;
        });
        binding.getRoot().requestApplyInsets();
    }

    private void applyThemeTone() {
        LauncherTheme.applyPrimaryTone(binding.getRoot());
        LauncherTheme.longActionButton(binding.disclaimerClose);
        View iconWrap = binding.disclaimerContent.getChildAt(1);
        if (iconWrap instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) iconWrap;
            for (int i = 0; i < group.getChildCount(); i++) {
                group.getChildAt(i).setBackground(LauncherTheme.circle(this));
            }
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
