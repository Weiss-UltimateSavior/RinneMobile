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

import com.core.R;
import com.core.databinding.ActivityLauncherThemeMenuBinding;
import com.apps.LauncherActivity;
import com.apps.LauncherPreferences;
import com.apps.LauncherThemeStyle;
import com.apps.widget.LauncherTabletPortraitScaler;

public class LauncherThemeMenuActivity extends AppCompatActivity {
    private ActivityLauncherThemeMenuBinding binding;
    private String selectedTheme = LauncherThemeStyle.THEME_STYLE_DEFAULT;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        applySavedToneMode();
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();

        binding = ActivityLauncherThemeMenuBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        LauncherTabletPortraitScaler.applyActivityContent(this);
        selectedTheme = LauncherActivity.getLauncherThemeStyle(this);
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
        binding.freshThemeRow.setOnClickListener(view -> selectTheme(LauncherThemeStyle.THEME_STYLE_DEFAULT));
        binding.nightThemeRow.setOnClickListener(view -> selectTheme(LauncherThemeStyle.THEME_STYLE_RINNE));
        binding.pinkThemeRow.setOnClickListener(view -> selectTheme(LauncherThemeStyle.THEME_STYLE_ANRI));
        binding.xinhaitianThemeRow.setOnClickListener(view -> selectTheme(LauncherThemeStyle.THEME_STYLE_XINHAITIAN));
        binding.natsumeThemeRow.setOnClickListener(view -> selectTheme(LauncherThemeStyle.THEME_STYLE_NATSUME));
        binding.particleToggleRow.setOnClickListener(view -> showParticleStyleDialog());
        binding.themeMenuApply.setOnClickListener(view -> applySelectedTheme());
    }

    private void applyIconTone() {
        binding.freshThemeIcon.setBackground(LauncherTheme.circle(
                this,
                LauncherTheme.primary(this)
        ));
        binding.freshThemeIcon.setClipToOutline(true);
        binding.rinneThemeLogo.setBackground(LauncherTheme.circle(this, LauncherThemeStyle.RINNE_PRIMARY_COLOR));
        binding.rinneThemeLogo.setClipToOutline(true);
        binding.anriThemeLogo.setBackground(LauncherTheme.circle(this, LauncherThemeStyle.ANRI_PRIMARY_COLOR));
        binding.anriThemeLogo.setClipToOutline(true);
        binding.xinhaitianThemeLogo.setBackground(LauncherTheme.xinhaitianCircle(this));
        binding.xinhaitianThemeLogo.setClipToOutline(true);
        binding.natsumeThemeLogo.setBackground(LauncherTheme.circle(this, LauncherThemeStyle.NATSUME_PRIMARY_COLOR));
        binding.natsumeThemeLogo.setClipToOutline(true);
        binding.particleToggleIcon.setBackground(LauncherTheme.circle(this));
    }

    private void selectTheme(String themeName) {
        selectedTheme = themeName;
        renderSelection();
    }

    private void renderSelection() {
        boolean freshSelected = LauncherThemeStyle.THEME_STYLE_DEFAULT.equals(selectedTheme);
        boolean nightSelected = LauncherThemeStyle.THEME_STYLE_RINNE.equals(selectedTheme);
        boolean pinkSelected = LauncherThemeStyle.THEME_STYLE_ANRI.equals(selectedTheme);
        boolean xinhaitianSelected = LauncherThemeStyle.THEME_STYLE_XINHAITIAN.equals(selectedTheme);
        boolean natsumeSelected = LauncherThemeStyle.THEME_STYLE_NATSUME.equals(selectedTheme);

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
        binding.natsumeThemeRow.setBackgroundResource(natsumeSelected
                ? 0
                : R.drawable.launcher_chat_option_bg);
        if (natsumeSelected) binding.natsumeThemeRow.setBackground(LauncherTheme.selectedOption(this));

        binding.freshThemeCheck.setVisibility(freshSelected ? View.VISIBLE : View.INVISIBLE);
        binding.nightThemeCheck.setVisibility(nightSelected ? View.VISIBLE : View.INVISIBLE);
        binding.pinkThemeCheck.setVisibility(pinkSelected ? View.VISIBLE : View.INVISIBLE);
        binding.xinhaitianThemeCheck.setVisibility(xinhaitianSelected ? View.VISIBLE : View.INVISIBLE);
        binding.natsumeThemeCheck.setVisibility(natsumeSelected ? View.VISIBLE : View.INVISIBLE);
    }

    private void applySelectedTheme() {
        if (LauncherThemeStyle.THEME_STYLE_RINNE.equals(selectedTheme)) {
            LauncherThemeStyle.setThemeStyle(this, LauncherThemeStyle.THEME_STYLE_RINNE);
            Toast.makeText(this, R.string.theme_rinne_applied, Toast.LENGTH_SHORT).show();
        } else if (LauncherThemeStyle.THEME_STYLE_ANRI.equals(selectedTheme)) {
            LauncherThemeStyle.setThemeStyle(this, LauncherThemeStyle.THEME_STYLE_ANRI);
            Toast.makeText(this, R.string.theme_anri_applied, Toast.LENGTH_SHORT).show();
        } else if (LauncherThemeStyle.THEME_STYLE_XINHAITIAN.equals(selectedTheme)) {
            LauncherThemeStyle.setThemeStyle(this, LauncherThemeStyle.THEME_STYLE_XINHAITIAN);
            Toast.makeText(this, R.string.theme_xinhaitian_applied, Toast.LENGTH_SHORT).show();
        } else if (LauncherThemeStyle.THEME_STYLE_NATSUME.equals(selectedTheme)) {
            LauncherThemeStyle.setThemeStyle(this, LauncherThemeStyle.THEME_STYLE_NATSUME);
            Toast.makeText(this, R.string.theme_natsume_applied, Toast.LENGTH_SHORT).show();
        } else if (LauncherThemeStyle.THEME_STYLE_DEFAULT.equals(selectedTheme)) {
            LauncherThemeStyle.setThemeStyle(this, LauncherThemeStyle.THEME_STYLE_DEFAULT);
            Toast.makeText(this, R.string.theme_default_restored, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, getString(R.string.theme_not_available, selectedTheme),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        LauncherMotion.finish(this);
    }

    private void showParticleStyleDialog() {
        boolean enabled = LauncherActivity.isLauncherParticlesEnabled(this);
        String selectedStyle = LauncherActivity.getLauncherParticleStyle(this);
        String[] styles = {
                LauncherPreferences.PARTICLE_STYLE_FLOATING,
                LauncherPreferences.PARTICLE_STYLE_RAIN,
                LauncherPreferences.PARTICLE_STYLE_STAR,
                LauncherPreferences.PARTICLE_STYLE_SAKURA,
                LauncherPreferences.PARTICLE_STYLE_FIREFLIES,
                LauncherPreferences.PARTICLE_STYLE_CONSTELLATION,
                LauncherPreferences.PARTICLE_STYLE_RIPPLES
        };
        String[] labels = {
                getString(R.string.theme_particle_floating),
                getString(R.string.theme_particle_rain),
                getString(R.string.theme_particle_star),
                getString(R.string.theme_particle_button_waterfall),
                getString(R.string.theme_particle_fireflies),
                getString(R.string.theme_particle_constellation),
                getString(R.string.theme_particle_ripples),
                getString(R.string.theme_particles_off)
        };
        int checkedIndex = styles.length; // 关闭位置 = 7
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
                getString(R.string.theme_particle_style_title),
                labels,
                checkedIndex,
                index -> {
                    if (index == styles.length) {
                        LauncherActivity.setLauncherParticlesEnabled(this, false);
                        renderParticleToggle();
                        Toast.makeText(this, R.string.theme_particles_disabled, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    LauncherActivity.setLauncherParticleStyle(this, styles[index]);
                    LauncherActivity.setLauncherParticlesEnabled(this, true);
                    renderParticleToggle();
                    Toast.makeText(this, getString(R.string.theme_particle_applied, labels[index]),
                            Toast.LENGTH_SHORT).show();
                }
        );
    }

    private void renderParticleToggle() {
        binding.particleToggleState.setText(R.string.theme_configure);
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
