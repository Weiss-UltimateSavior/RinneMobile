package com.apps;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;

import com.yuki.yukihub.R;
import com.yuki.yukihub.databinding.ActivityLauncherToolboxBinding;

public class LauncherToolboxActivity extends AppCompatActivity {
    private ActivityLauncherToolboxBinding binding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        applySavedToneMode();
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();

        binding = ActivityLauncherToolboxBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applySystemBarInsets();
        bindActions();
        applyThemeTone();
    }

    private void applySystemBarInsets() {
        int originalLeft = binding.toolboxScroll.getPaddingLeft();
        int originalTop = binding.toolboxScroll.getPaddingTop();
        int originalRight = binding.toolboxScroll.getPaddingRight();
        int originalBottom = binding.toolboxScroll.getPaddingBottom();

        binding.getRoot().setOnApplyWindowInsetsListener((view, insets) -> {
            binding.toolboxScroll.setPadding(
                    originalLeft,
                    originalTop + insets.getSystemWindowInsetTop(),
                    originalRight,
                    originalBottom
            );
            return insets;
        });
        binding.getRoot().requestApplyInsets();
    }

    private void bindActions() {
        bindTool(binding.toolTranslate, "文本翻译");
        bindTool(binding.toolArchive, "资源整理");
        bindTool(binding.toolLauncher, "启动助手");
        bindTool(binding.toolRecharge, "记录换算");
        bindTool(binding.toolPayBill, "路径检查");
        bindTool(binding.toolCreditCard, "缓存清理");
        bindTool(binding.toolReport, "运行报告");
    }

    private void bindTool(View view, String name) {
        view.setOnClickListener(v ->
                Toast.makeText(this, name + " 待接入", Toast.LENGTH_SHORT).show());
    }

    private void applyThemeTone() {
        binding.toolboxNoticeCard.setBackground(LauncherTheme.primaryGradientCard(this, 30f));
        binding.toolPayBill.getChildAt(0).setBackground(LauncherTheme.primaryButton(this, 4f));
        LauncherTheme.applyPrimaryTone(binding.getRoot());
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
        AppCompatDelegate.setDefaultNightMode(LauncherActivity.isLauncherDarkMode(this)
                ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_NO);
    }
}
