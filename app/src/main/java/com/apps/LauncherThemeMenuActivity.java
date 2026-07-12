package com.apps;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.yuki.yukihub.R;
import com.yuki.yukihub.databinding.ActivityLauncherThemeMenuBinding;

public class LauncherThemeMenuActivity extends AppCompatActivity {
    private static final String THEME_DEFAULT_LABEL = "清新绿意（默认）";
    private static final String THEME_RINNE_LABEL = "园神凛弥（风格）";
    private static final String THEME_ANRI_LABEL = "鹰仓杏璃（风格）";

    private ActivityLauncherThemeMenuBinding binding;
    private String selectedTheme = THEME_DEFAULT_LABEL;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        applySavedToneMode();
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();

        binding = ActivityLauncherThemeMenuBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        LauncherTabletPortraitScaler.applyActivityContent(this);
        String style = LauncherActivity.getLauncherThemeStyle(this);
        if (LauncherActivity.THEME_STYLE_RINNE.equals(style)) {
            selectedTheme = THEME_RINNE_LABEL;
        } else if (LauncherActivity.THEME_STYLE_ANRI.equals(style)) {
            selectedTheme = THEME_ANRI_LABEL;
        } else {
            selectedTheme = THEME_DEFAULT_LABEL;
        }
        applySystemBarInsets();
        bindActions();
        LauncherTheme.applyPrimaryTone(binding.getRoot());
        applyIconTone();
        renderSelection();
        renderParticleToggle();
    }

    private void applySystemBarInsets() {
        int originalLeft = binding.themeMenuScroll.getPaddingLeft();
        int originalTop = binding.themeMenuScroll.getPaddingTop();
        int originalRight = binding.themeMenuScroll.getPaddingRight();
        int originalBottom = binding.themeMenuScroll.getPaddingBottom();

        binding.getRoot().setOnApplyWindowInsetsListener((view, insets) -> {
            binding.themeMenuScroll.setPadding(
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
        binding.freshThemeRow.setOnClickListener(view -> selectTheme(THEME_DEFAULT_LABEL));
        binding.nightThemeRow.setOnClickListener(view -> selectTheme(THEME_RINNE_LABEL));
        binding.pinkThemeRow.setOnClickListener(view -> selectTheme(THEME_ANRI_LABEL));
        binding.particleToggleRow.setOnClickListener(view -> showParticleStyleDialog());
        binding.themeMenuApply.setOnClickListener(view -> applySelectedTheme());
    }

    private void applyIconTone() {
        binding.freshThemeIcon.setBackground(LauncherTheme.circle(
                this,
                LauncherTheme.primary(this)
        ));
        binding.freshThemeIcon.setClipToOutline(true);
        binding.rinneThemeLogo.setBackground(LauncherTheme.circle(this, LauncherActivity.RINNE_PRIMARY_COLOR));
        binding.rinneThemeLogo.setClipToOutline(true);
        binding.anriThemeLogo.setBackground(LauncherTheme.circle(this, LauncherActivity.ANRI_PRIMARY_COLOR));
        binding.anriThemeLogo.setClipToOutline(true);
        binding.particleToggleIcon.setBackground(LauncherTheme.circle(this));
    }

    private void selectTheme(String themeName) {
        selectedTheme = themeName;
        renderSelection();
    }

    private void renderSelection() {
        boolean freshSelected = THEME_DEFAULT_LABEL.equals(selectedTheme);
        boolean nightSelected = THEME_RINNE_LABEL.equals(selectedTheme);
        boolean pinkSelected = THEME_ANRI_LABEL.equals(selectedTheme);

        binding.freshThemeRow.setBackgroundResource(freshSelected
                ? 0
                : R.drawable.launcher_chat_option_bg);
        if (freshSelected) binding.freshThemeRow.setBackground(LauncherTheme.selectedOption(this));
        binding.nightThemeRow.setBackgroundResource(nightSelected
                ? 0
                : R.drawable.launcher_chat_option_bg);
        if (nightSelected) binding.nightThemeRow.setBackground(LauncherTheme.selectedOption(this));
        binding.pinkThemeRow.setBackgroundResource(pinkSelected
                ? 0
                : R.drawable.launcher_chat_option_bg);
        if (pinkSelected) binding.pinkThemeRow.setBackground(LauncherTheme.selectedOption(this));

        binding.freshThemeCheck.setVisibility(freshSelected ? View.VISIBLE : View.INVISIBLE);
        binding.nightThemeCheck.setVisibility(nightSelected ? View.VISIBLE : View.INVISIBLE);
        binding.pinkThemeCheck.setVisibility(pinkSelected ? View.VISIBLE : View.INVISIBLE);
    }

    private void applySelectedTheme() {
        if (THEME_RINNE_LABEL.equals(selectedTheme)) {
            LauncherActivity.setLauncherThemeStyle(this, LauncherActivity.THEME_STYLE_RINNE);
            Toast.makeText(this, "已应用园神凛弥风格", Toast.LENGTH_SHORT).show();
        } else if (THEME_ANRI_LABEL.equals(selectedTheme)) {
            LauncherActivity.setLauncherThemeStyle(this, LauncherActivity.THEME_STYLE_ANRI);
            Toast.makeText(this, "已应用鹰仓杏璃风格", Toast.LENGTH_SHORT).show();
        } else if (THEME_DEFAULT_LABEL.equals(selectedTheme)) {
            LauncherActivity.setLauncherThemeStyle(this, LauncherActivity.THEME_STYLE_DEFAULT);
            Toast.makeText(this, "已恢复默认主题", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, selectedTheme + " 待接入", Toast.LENGTH_SHORT).show();
            return;
        }
        LauncherMotion.finish(this);
    }

    private void showParticleStyleDialog() {
        AlertDialog dialog = new AlertDialog.Builder(this).create();
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(288), WindowManager.LayoutParams.WRAP_CONTENT);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(20), dp(22), dp(16));
        root.setBackgroundResource(R.drawable.launcher_dialog_bg);

        TextView title = dialogText("动态粒子样式", 16, R.color.launcher_text_color);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);
        boolean enabled = LauncherActivity.isLauncherParticlesEnabled(this);
        String selectedStyle = LauncherActivity.getLauncherParticleStyle(this);
        addParticleStyleOption(root, dialog, "漂浮光点",
                LauncherActivity.PARTICLE_STYLE_FLOATING, enabled, selectedStyle);
        addParticleStyleOption(root, dialog, "斜向雨滴",
                LauncherActivity.PARTICLE_STYLE_RAIN, enabled, selectedStyle);

        TextView disable = dialogText("关闭动态粒子", 13, R.color.launcher_text_color);
        LauncherTheme.secondaryButton(disable);
        disable.setOnClickListener(view -> {
            LauncherActivity.setLauncherParticlesEnabled(this, false);
            renderParticleToggle();
            dialog.dismiss();
            Toast.makeText(this, "已关闭动态粒子", Toast.LENGTH_SHORT).show();
        });
        addWithTopMargin(root, disable, 10, dp(36));

        TextView cancel = dialogText("取消", 13, R.color.launcher_text_color);
        LauncherTheme.secondaryButton(cancel);
        cancel.setOnClickListener(view -> dialog.dismiss());
        addWithTopMargin(root, cancel, 8, dp(36));
        window.setContentView(root);
    }

    private void addParticleStyleOption(LinearLayout root, AlertDialog dialog, String title,
                                        String style, boolean enabled,
                                        String selectedStyle) {
        TextView option = dialogText(title, 13,
                enabled && style.equals(selectedStyle) ? R.color.launcher_on_primary_color : R.color.launcher_text_color);
        option.setGravity(android.view.Gravity.CENTER);
        option.setLineSpacing(dp(2), 1f);
        if (enabled && style.equals(selectedStyle)) {
            LauncherTheme.primaryButton(option);
        } else {
            LauncherTheme.secondaryButton(option);
        }
        option.setOnClickListener(view -> {
            LauncherActivity.setLauncherParticleStyle(this, style);
            LauncherActivity.setLauncherParticlesEnabled(this, true);
            renderParticleToggle();
            dialog.dismiss();
            Toast.makeText(this, "已应用" + title + "效果", Toast.LENGTH_SHORT).show();
        });
        addWithTopMargin(root, option, 11, dp(40));
    }

    private TextView dialogText(String text, int sizeSp, int colorRes) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(ContextCompat.getColor(this, colorRes));
        view.setGravity(android.view.Gravity.CENTER);
        view.setPadding(dp(12), 0, dp(12), 0);
        return view;
    }

    private void addWithTopMargin(LinearLayout root, View view, int topMarginDp, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, height);
        params.setMargins(0, dp(topMarginDp), 0, 0);
        root.addView(view, params);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void renderParticleToggle() {
        binding.particleToggleState.setText("设置");
        LauncherTheme.chip(binding.particleToggleState, true);
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
