package com.apps.theme;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.yuki.yukihub.R;
import com.yuki.yukihub.databinding.ActivityLauncherThemeMenuBinding;
import com.apps.LauncherActivity;
import com.apps.widget.LauncherTabletPortraitScaler;

public class LauncherThemeMenuActivity extends AppCompatActivity {
    private static final String THEME_DEFAULT_LABEL = "清新绿意（默认）";
    private static final String THEME_RINNE_LABEL = "园神凛弥（风格）";
    private static final String THEME_ANRI_LABEL = "鹰仓杏璃（风格）";
    private static final String THEME_XINHAITIAN_LABEL = "新海天（风格）";

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
        } else if (LauncherActivity.THEME_STYLE_XINHAITIAN.equals(style)) {
            selectedTheme = THEME_XINHAITIAN_LABEL;
        } else {
            selectedTheme = THEME_DEFAULT_LABEL;
        }
        applySystemBarInsets();
        bindActions();
        LauncherTheme.applyPrimaryTone(binding.getRoot());
        LauncherTheme.longActionButton(binding.themeMenuApply);
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
        binding.xinhaitianThemeRow.setOnClickListener(view -> selectTheme(THEME_XINHAITIAN_LABEL));
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
        binding.xinhaitianThemeLogo.setBackground(LauncherTheme.xinhaitianCircle(this));
        binding.xinhaitianThemeLogo.setClipToOutline(true);
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
        boolean xinhaitianSelected = THEME_XINHAITIAN_LABEL.equals(selectedTheme);

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
        binding.xinhaitianThemeRow.setBackgroundResource(xinhaitianSelected
                ? 0
                : R.drawable.launcher_chat_option_bg);
        if (xinhaitianSelected) binding.xinhaitianThemeRow.setBackground(LauncherTheme.selectedOption(this));

        binding.freshThemeCheck.setVisibility(freshSelected ? View.VISIBLE : View.INVISIBLE);
        binding.nightThemeCheck.setVisibility(nightSelected ? View.VISIBLE : View.INVISIBLE);
        binding.pinkThemeCheck.setVisibility(pinkSelected ? View.VISIBLE : View.INVISIBLE);
        binding.xinhaitianThemeCheck.setVisibility(xinhaitianSelected ? View.VISIBLE : View.INVISIBLE);
    }

    private void applySelectedTheme() {
        if (THEME_RINNE_LABEL.equals(selectedTheme)) {
            LauncherActivity.setLauncherThemeStyle(this, LauncherActivity.THEME_STYLE_RINNE);
            Toast.makeText(this, "已应用园神凛弥风格", Toast.LENGTH_SHORT).show();
        } else if (THEME_ANRI_LABEL.equals(selectedTheme)) {
            LauncherActivity.setLauncherThemeStyle(this, LauncherActivity.THEME_STYLE_ANRI);
            Toast.makeText(this, "已应用鹰仓杏璃风格", Toast.LENGTH_SHORT).show();
        } else if (THEME_XINHAITIAN_LABEL.equals(selectedTheme)) {
            LauncherActivity.setLauncherThemeStyle(this, LauncherActivity.THEME_STYLE_XINHAITIAN);
            Toast.makeText(this, "已应用心海天风格", Toast.LENGTH_SHORT).show();
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
        boolean enabled = LauncherActivity.isLauncherParticlesEnabled(this);
        String selectedStyle = LauncherActivity.getLauncherParticleStyle(this);
        String[] styles = {
                LauncherActivity.PARTICLE_STYLE_FLOATING,
                LauncherActivity.PARTICLE_STYLE_RAIN,
                LauncherActivity.PARTICLE_STYLE_STAR
        };
        int checkedIndex = 3;
        if (enabled) {
            for (int i = 0; i < styles.length; i++) {
                if (styles[i].equals(selectedStyle)) {
                    checkedIndex = i;
                    break;
                }
            }
        }
        LauncherDialogFactory.showSingleChoice(
                this,
                "动态粒子样式",
                new String[]{"漂浮光点", "斜向雨滴", "星星粒子", "关闭动态粒子"},
                checkedIndex,
                index -> {
                    if (index == 3) {
                        LauncherActivity.setLauncherParticlesEnabled(this, false);
                        renderParticleToggle();
                        Toast.makeText(this, "已关闭动态粒子", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    LauncherActivity.setLauncherParticleStyle(this, styles[index]);
                    LauncherActivity.setLauncherParticlesEnabled(this, true);
                    renderParticleToggle();
                    Toast.makeText(this, "已应用" + (index == 0 ? "漂浮光点" : index == 1 ? "斜向雨滴" : "星星粒子") + "效果", Toast.LENGTH_SHORT).show();
                }
        );
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
