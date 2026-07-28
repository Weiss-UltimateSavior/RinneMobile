package com.apps.profile;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.apps.LauncherActivity;
import com.apps.theme.LauncherDialogFactory;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherTabletPortraitScaler;
import com.core.R;
import com.core.databinding.ActivityLauncherModuleCompatibilityBinding;
import com.core.launcherbridge.LauncherModuleBridge;
import com.core.util.AppExecutors;

/** 模块兼容页面：展示并管理 Rinne 所兼容的第三方 JoiPlay 插件（RPGM / RenPy / Godot）。 */
public class LauncherModuleCompatibilityActivity extends AppCompatActivity {
    private static final String RPGM_INSTALL_URL = "https://github.com/Weiss-UltimateSavior/RinneMobile/releases/download/test/RPGMPlugin-1.22.00-patreon-release.apk";
    private static final String RENPY_INSTALL_URL = "https://github.com/Weiss-UltimateSavior/RinneMobile/releases/download/test/RenPyPlugin-8.5.0-1.01.00.apk";
    private static final String GODOT_INSTALL_URL = "https://github.com/Weiss-UltimateSavior/RinneMobile/releases/download/test/Godot.4.3-Plugin-1.00.60.apk";

    private ActivityLauncherModuleCompatibilityBinding binding;
    private boolean rpgmModuleInstalled;
    private boolean renpyModuleInstalled;
    private boolean godotModuleInstalled;
    private boolean rpgmModuleEnabled = false;
    private boolean renpyModuleEnabled = false;
    private boolean godotModuleEnabled = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherActivity.applySavedToneMode(this);
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();

        binding = ActivityLauncherModuleCompatibilityBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        LauncherTabletPortraitScaler.applyActivityContent(this);
        applySystemBarInsets();
        LauncherTheme.applyPrimaryTone(binding.getRoot());
        binding.moduleRpgmRow.setOnClickListener(view -> openRpgmModule());
        binding.moduleRenpyRow.setOnClickListener(view -> openRenpyModule());
        binding.moduleGodotRow.setOnClickListener(view -> openGodotModule());
        // 长按列表项：弹窗提醒跳转浏览器下载。
        binding.moduleRpgmRow.setOnLongClickListener(view -> { promptDownload("RPGM", this::openRpgmInstallPage); return true; });
        binding.moduleRenpyRow.setOnLongClickListener(view -> { promptDownload("RenPy", this::openRenpyInstallPage); return true; });
        binding.moduleGodotRow.setOnLongClickListener(view -> { promptDownload("Godot", this::openGodotInstallPage); return true; });
        // 右侧图标：已安装时点击切换启用/禁用；未安装时点击等价于行点击（前往安装）。
        binding.moduleRpgmIcon.setOnClickListener(view -> handleRpgmIconClick());
        binding.moduleRenpyIcon.setOnClickListener(view -> handleRenpyIconClick());
        binding.moduleGodotIcon.setOnClickListener(view -> handleGodotIconClick());
        refreshInstalledModules();
    }

    private void refreshInstalledModules() {
        binding.moduleRpgmRow.setEnabled(false);
        binding.moduleRenpyRow.setEnabled(false);
        binding.moduleGodotRow.setEnabled(false);
        binding.moduleRpgmRow.setAlpha(1f);
        binding.moduleRenpyRow.setAlpha(1f);
        binding.moduleGodotRow.setAlpha(1f);
        binding.moduleRpgmIcon.setImageTintList(ColorStateList.valueOf(LauncherTheme.textMuted(this)));
        binding.moduleRenpyIcon.setImageTintList(ColorStateList.valueOf(LauncherTheme.textMuted(this)));
        binding.moduleGodotIcon.setImageTintList(ColorStateList.valueOf(LauncherTheme.textMuted(this)));
        AppExecutors.runOnIo(() -> {
            boolean rpgmInstalled = LauncherModuleBridge.isRpgMakerModuleInstalled(this);
            boolean renpyInstalled = LauncherModuleBridge.isRenPyModuleInstalled(this);
            boolean godotInstalled = LauncherModuleBridge.isGodotModuleInstalled(this);
            boolean rpgmEnabled = LauncherModuleBridge.isRpgMakerModuleEnabled(this);
            boolean renpyEnabled = LauncherModuleBridge.isRenPyModuleEnabled(this);
            boolean godotEnabled = LauncherModuleBridge.isGodotModuleEnabled(this);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                rpgmModuleInstalled = rpgmInstalled;
                renpyModuleInstalled = renpyInstalled;
                godotModuleInstalled = godotInstalled;
                rpgmModuleEnabled = rpgmEnabled;
                renpyModuleEnabled = renpyEnabled;
                godotModuleEnabled = godotEnabled;
                binding.moduleRpgmRow.setEnabled(true);
                binding.moduleRenpyRow.setEnabled(true);
                binding.moduleGodotRow.setEnabled(true);
                binding.moduleRpgmRow.setAlpha(1f);
                binding.moduleRenpyRow.setAlpha(1f);
                binding.moduleGodotRow.setAlpha(1f);
                applyModuleIconTint(binding.moduleRpgmIcon, rpgmInstalled, rpgmEnabled);
                applyModuleIconTint(binding.moduleRenpyIcon, renpyInstalled, renpyEnabled);
                applyModuleIconTint(binding.moduleGodotIcon, godotInstalled, godotEnabled);
                updateModuleDescription(binding.moduleRpgmDescription, rpgmInstalled, rpgmEnabled,
                        R.string.module_rpgm_detail);
                updateModuleDescription(binding.moduleRenpyDescription, renpyInstalled, renpyEnabled,
                        R.string.module_renpy_detail);
                updateModuleDescription(binding.moduleGodotDescription, godotInstalled, godotEnabled,
                        R.string.module_godot_detail);
            });
        });
    }

    /**
     * 图标着色规则：
     * <ul>
     *   <li>未安装 → danger 红</li>
     *   <li>已安装 + 已启用 → primary 主题色</li>
     *   <li>已安装 + 未启用 → textMuted 灰，表示「关闭」状态</li>
     * </ul>
     */
    private void applyModuleIconTint(android.widget.ImageView icon, boolean installed, boolean enabled) {
        int color;
        if (!installed) {
            color = LauncherTheme.danger(this);
        } else if (enabled) {
            color = LauncherTheme.primary(this);
        } else {
            color = LauncherTheme.textMuted(this);
        }
        icon.setImageTintList(ColorStateList.valueOf(color));
    }

    /**
     * 左侧状态描述格式：
     * <ul>
     *   <li>未安装：{@code 未安装 - <detail>}（danger 红）</li>
     *   <li>已安装 · 已启用：{@code 已安装 · 已启用 - <detail>}（primary 主题色）</li>
     *   <li>已安装 · 未启用：{@code 已安装 · 未启用 - <detail>}（textMuted 灰）</li>
     * </ul>
     */
    private void updateModuleDescription(TextView description, boolean installed, boolean enabled, int detailRes) {
        String detail = getString(detailRes);
        String text;
        int color;
        if (!installed) {
            text = getString(R.string.module_status_not_installed, detail);
            color = LauncherTheme.danger(this);
        } else if (enabled) {
            text = getString(R.string.module_status_installed_enabled, detail);
            color = LauncherTheme.primary(this);
        } else {
            text = getString(R.string.module_status_installed_disabled, detail);
            color = LauncherTheme.textMuted(this);
        }
        description.setText(text);
        description.setTextColor(color);
    }

    // ----- 长按：跳转浏览器下载 -----

    private void promptDownload(String moduleName, Runnable openInstallPage) {
        LauncherDialogFactory.showStandardConfirm(
                this,
                getString(R.string.module_download_title, moduleName),
                getString(R.string.module_download_message),
                getString(R.string.theme_go_to_download),
                openInstallPage);
    }

    // ----- 行点击 -----

    private void openRpgmModule() {
        if (rpgmModuleInstalled) {
            LauncherDialogFactory.showStandardConfirm(
                    this,
                    getString(R.string.module_rpgm_name),
                    rpgmModuleEnabled
                            ? getString(R.string.module_installed_enabled_hint)
                            : getString(R.string.module_installed_disabled_hint),
                    getString(R.string.settings_got_it),
                    null);
            return;
        }
        LauncherDialogFactory.showStandardConfirm(
                this,
                getString(R.string.module_install_title, "RPGM"),
                getString(R.string.module_install_message),
                getString(R.string.module_go_to_install),
                this::openRpgmInstallPage);
    }

    private void openRenpyModule() {
        if (renpyModuleInstalled) {
            LauncherDialogFactory.showStandardConfirm(
                    this,
                    getString(R.string.module_renpy_name),
                    renpyModuleEnabled
                            ? getString(R.string.module_installed_enabled_hint)
                            : getString(R.string.module_installed_disabled_hint),
                    getString(R.string.settings_got_it),
                    null);
            return;
        }
        LauncherDialogFactory.showStandardConfirm(
                this,
                getString(R.string.module_install_title, "RenPy"),
                getString(R.string.module_install_message),
                getString(R.string.module_go_to_install),
                this::openRenpyInstallPage);
    }

    private void openGodotModule() {
        if (godotModuleInstalled) {
            LauncherDialogFactory.showStandardConfirm(
                    this,
                    getString(R.string.module_godot_name),
                    godotModuleEnabled
                            ? getString(R.string.module_installed_enabled_hint)
                            : getString(R.string.module_installed_disabled_hint),
                    getString(R.string.settings_got_it),
                    null);
            return;
        }
        LauncherDialogFactory.showStandardConfirm(
                this,
                getString(R.string.module_install_title, "Godot"),
                getString(R.string.module_install_message),
                getString(R.string.module_go_to_install),
                this::openGodotInstallPage);
    }

    // ----- 图标点击：启停切换 -----

    private void handleRpgmIconClick() {
        if (!rpgmModuleInstalled) {
            openRpgmModule();
            return;
        }
        if (rpgmModuleEnabled) {
            LauncherDialogFactory.showStandardConfirm(
                    this,
                    getString(R.string.module_disable_title, "RPGM"),
                    getString(R.string.module_disable_message, "RPGM"),
                    getString(R.string.module_disable),
                    () -> {
                        LauncherModuleBridge.setRpgMakerModuleEnabled(this, false);
                        rpgmModuleEnabled = false;
                        applyModuleIconTint(binding.moduleRpgmIcon, rpgmModuleInstalled, rpgmModuleEnabled);
                        updateModuleDescription(binding.moduleRpgmDescription, rpgmModuleInstalled, rpgmModuleEnabled,
                                R.string.module_rpgm_detail);
                    });
        } else {
            LauncherDialogFactory.showStandardConfirm(
                    this,
                    getString(R.string.module_enable_title, "RPGM"),
                    getString(R.string.module_enable_message, "RPGM"),
                    getString(R.string.module_enable),
                    () -> {
                        LauncherModuleBridge.setRpgMakerModuleEnabled(this, true);
                        rpgmModuleEnabled = true;
                        applyModuleIconTint(binding.moduleRpgmIcon, rpgmModuleInstalled, rpgmModuleEnabled);
                        updateModuleDescription(binding.moduleRpgmDescription, rpgmModuleInstalled, rpgmModuleEnabled,
                                R.string.module_rpgm_detail);
                    });
        }
    }

    private void handleRenpyIconClick() {
        if (!renpyModuleInstalled) {
            openRenpyModule();
            return;
        }
        if (renpyModuleEnabled) {
            LauncherDialogFactory.showStandardConfirm(
                    this,
                    getString(R.string.module_disable_title, "RenPy"),
                    getString(R.string.module_disable_message, "RenPy"),
                    getString(R.string.module_disable),
                    () -> {
                        LauncherModuleBridge.setRenPyModuleEnabled(this, false);
                        renpyModuleEnabled = false;
                        applyModuleIconTint(binding.moduleRenpyIcon, renpyModuleInstalled, renpyModuleEnabled);
                        updateModuleDescription(binding.moduleRenpyDescription, renpyModuleInstalled, renpyModuleEnabled,
                                R.string.module_renpy_detail);
                    });
        } else {
            LauncherDialogFactory.showStandardConfirm(
                    this,
                    getString(R.string.module_enable_title, "RenPy"),
                    getString(R.string.module_enable_message, "RenPy"),
                    getString(R.string.module_enable),
                    () -> {
                        LauncherModuleBridge.setRenPyModuleEnabled(this, true);
                        renpyModuleEnabled = true;
                        applyModuleIconTint(binding.moduleRenpyIcon, renpyModuleInstalled, renpyModuleEnabled);
                        updateModuleDescription(binding.moduleRenpyDescription, renpyModuleInstalled, renpyModuleEnabled,
                                R.string.module_renpy_detail);
                    });
        }
    }

    private void handleGodotIconClick() {
        if (!godotModuleInstalled) {
            openGodotModule();
            return;
        }
        if (godotModuleEnabled) {
            LauncherDialogFactory.showStandardConfirm(
                    this,
                    getString(R.string.module_disable_title, "Godot"),
                    getString(R.string.module_disable_message, "Godot"),
                    getString(R.string.module_disable),
                    () -> {
                        LauncherModuleBridge.setGodotModuleEnabled(this, false);
                        godotModuleEnabled = false;
                        applyModuleIconTint(binding.moduleGodotIcon, godotModuleInstalled, godotModuleEnabled);
                        updateModuleDescription(binding.moduleGodotDescription, godotModuleInstalled, godotModuleEnabled,
                                R.string.module_godot_detail);
                    });
        } else {
            LauncherDialogFactory.showStandardConfirm(
                    this,
                    getString(R.string.module_enable_title, "Godot"),
                    getString(R.string.module_enable_message, "Godot"),
                    getString(R.string.module_enable),
                    () -> {
                        LauncherModuleBridge.setGodotModuleEnabled(this, true);
                        godotModuleEnabled = true;
                        applyModuleIconTint(binding.moduleGodotIcon, godotModuleInstalled, godotModuleEnabled);
                        updateModuleDescription(binding.moduleGodotDescription, godotModuleInstalled, godotModuleEnabled,
                                R.string.module_godot_detail);
                    });
        }
    }

    // ----- 安装页跳转 -----

    private void openRpgmInstallPage() {
        openInstallPage(RPGM_INSTALL_URL);
    }

    private void openRenpyInstallPage() {
        openInstallPage(RENPY_INSTALL_URL);
    }

    private void openGodotInstallPage() {
        openInstallPage(GODOT_INSTALL_URL);
    }

    private void openInstallPage(String installUrl) {
        try {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(installUrl));
            browserIntent.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(browserIntent);
        } catch (Throwable ignored) {
            LauncherDialogFactory.showInfo(this,
                    getString(R.string.module_cannot_open_browser),
                    getString(R.string.module_try_again_later));
        }
    }

    // ----- 窗口 / 主题 -----

    private void applySystemBarInsets() {
        int left = binding.moduleCompatibilityScroll.getPaddingLeft();
        int top = binding.moduleCompatibilityScroll.getPaddingTop();
        int right = binding.moduleCompatibilityScroll.getPaddingRight();
        int bottom = binding.moduleCompatibilityScroll.getPaddingBottom();
        binding.getRoot().setOnApplyWindowInsetsListener((view, insets) -> {
            binding.moduleCompatibilityScroll.setPadding(
                    left, top + insets.getSystemWindowInsetTop(), right, bottom);
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
        if (!darkMode) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        window.getDecorView().setSystemUiVisibility(flags);
    }

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase));
    }
}
