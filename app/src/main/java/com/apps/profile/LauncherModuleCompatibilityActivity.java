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
import com.yuki.yukihub.R;
import com.yuki.yukihub.databinding.ActivityLauncherModuleCompatibilityBinding;
import com.yuki.yukihub.launcherbridge.LauncherModuleBridge;
import com.yuki.yukihub.util.AppExecutors;

/** 模块兼容页面：展示并管理 Rinne 所兼容的第三方 JoiPlay 插件（RPGM / RenPy）。 */
public class LauncherModuleCompatibilityActivity extends AppCompatActivity {
    /** Temporary routable placeholder; replace with the published RPGM module URL when available. */
    private static final String RPGM_INSTALL_URL = "https://example.com/";
    /** Temporary routable placeholder; replace with the published RenPy module URL when available. */
    private static final String RENPY_INSTALL_URL = "https://example.com/";

    private ActivityLauncherModuleCompatibilityBinding binding;
    private boolean rpgmModuleInstalled;
    private boolean renpyModuleInstalled;
    private boolean rpgmModuleEnabled = true;
    private boolean renpyModuleEnabled = true;

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
        // 长按列表项：弹窗提醒跳转浏览器下载。
        binding.moduleRpgmRow.setOnLongClickListener(view -> { promptDownload("RPGM", this::openRpgmInstallPage); return true; });
        binding.moduleRenpyRow.setOnLongClickListener(view -> { promptDownload("RenPy", this::openRenpyInstallPage); return true; });
        // 右侧图标：已安装时点击切换启用/禁用；未安装时点击等价于行点击（前往安装）。
        binding.moduleRpgmIcon.setOnClickListener(view -> handleRpgmIconClick());
        binding.moduleRenpyIcon.setOnClickListener(view -> handleRenpyIconClick());
        refreshInstalledModules();
    }

    private void refreshInstalledModules() {
        binding.moduleRpgmRow.setEnabled(false);
        binding.moduleRenpyRow.setEnabled(false);
        binding.moduleRpgmRow.setAlpha(1f);
        binding.moduleRenpyRow.setAlpha(1f);
        binding.moduleRpgmIcon.setImageTintList(ColorStateList.valueOf(LauncherTheme.textMuted(this)));
        binding.moduleRenpyIcon.setImageTintList(ColorStateList.valueOf(LauncherTheme.textMuted(this)));
        AppExecutors.runOnIo(() -> {
            boolean rpgmInstalled = LauncherModuleBridge.isRpgMakerModuleInstalled(this);
            boolean renpyInstalled = LauncherModuleBridge.isRenPyModuleInstalled(this);
            boolean rpgmEnabled = LauncherModuleBridge.isRpgMakerModuleEnabled(this);
            boolean renpyEnabled = LauncherModuleBridge.isRenPyModuleEnabled(this);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                rpgmModuleInstalled = rpgmInstalled;
                renpyModuleInstalled = renpyInstalled;
                rpgmModuleEnabled = rpgmEnabled;
                renpyModuleEnabled = renpyEnabled;
                binding.moduleRpgmRow.setEnabled(true);
                binding.moduleRenpyRow.setEnabled(true);
                binding.moduleRpgmRow.setAlpha(1f);
                binding.moduleRenpyRow.setAlpha(1f);
                applyModuleIconTint(binding.moduleRpgmIcon, rpgmInstalled, rpgmEnabled);
                applyModuleIconTint(binding.moduleRenpyIcon, renpyInstalled, renpyEnabled);
                updateModuleDescription(binding.moduleRpgmDescription, rpgmInstalled, rpgmEnabled,
                        "提供 RPGM 游戏所需环境");
                updateModuleDescription(binding.moduleRenpyDescription, renpyInstalled, renpyEnabled,
                        "提供 RenPy 游戏所需环境");
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
    private void updateModuleDescription(TextView description, boolean installed, boolean enabled, String detail) {
        String text;
        int color;
        if (!installed) {
            text = "未安装 - " + detail;
            color = LauncherTheme.danger(this);
        } else if (enabled) {
            text = "已安装 · 已启用 - " + detail;
            color = LauncherTheme.primary(this);
        } else {
            text = "已安装 · 未启用 - " + detail;
            color = LauncherTheme.textMuted(this);
        }
        description.setText(text);
        description.setTextColor(color);
    }

    // ----- 长按：跳转浏览器下载 -----

    private void promptDownload(String moduleName, Runnable openInstallPage) {
        LauncherDialogFactory.showStandardConfirm(
                this,
                "下载 " + moduleName + " 模块",
                "是否前往浏览器下载该模块？",
                "前往下载",
                openInstallPage);
    }

    // ----- 行点击 -----

    private void openRpgmModule() {
        if (rpgmModuleInstalled) {
            LauncherDialogFactory.showStandardConfirm(
                    this,
                    "RPGM 模块",
                    rpgmModuleEnabled
                            ? "该模块已安装并启用。点击右侧图标可禁用。"
                            : "该模块已安装但未启用。点击右侧图标可启用。",
                    "知道了",
                    null);
            return;
        }
        LauncherDialogFactory.showStandardConfirm(
                this,
                "安装 RPGM 模块",
                "是否安装该模块？",
                "前往安装",
                this::openRpgmInstallPage);
    }

    private void openRenpyModule() {
        if (renpyModuleInstalled) {
            LauncherDialogFactory.showStandardConfirm(
                    this,
                    "RenPy 模块",
                    renpyModuleEnabled
                            ? "该模块已安装并启用。点击右侧图标可禁用。"
                            : "该模块已安装但未启用。点击右侧图标可启用。",
                    "知道了",
                    null);
            return;
        }
        LauncherDialogFactory.showStandardConfirm(
                this,
                "安装 RenPy 模块",
                "是否安装该模块？",
                "前往安装",
                this::openRenpyInstallPage);
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
                    "禁用 RPGM 模块",
                    "禁用后该模块将无法用于启动 RPGM 游戏。是否禁用？",
                    "禁用",
                    () -> {
                        LauncherModuleBridge.setRpgMakerModuleEnabled(this, false);
                        rpgmModuleEnabled = false;
                        applyModuleIconTint(binding.moduleRpgmIcon, rpgmModuleInstalled, rpgmModuleEnabled);
                        updateModuleDescription(binding.moduleRpgmDescription, rpgmModuleInstalled, rpgmModuleEnabled,
                                "提供 RPGM 游戏所需环境");
                    });
        } else {
            LauncherDialogFactory.showStandardConfirm(
                    this,
                    "启用 RPGM 模块",
                    "启用后该模块可用于启动 RPGM 游戏。是否启用？",
                    "启用",
                    () -> {
                        LauncherModuleBridge.setRpgMakerModuleEnabled(this, true);
                        rpgmModuleEnabled = true;
                        applyModuleIconTint(binding.moduleRpgmIcon, rpgmModuleInstalled, rpgmModuleEnabled);
                        updateModuleDescription(binding.moduleRpgmDescription, rpgmModuleInstalled, rpgmModuleEnabled,
                                "提供 RPGM 游戏所需环境");
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
                    "禁用 RenPy 模块",
                    "禁用后该模块将无法用于启动 RenPy 游戏。是否禁用？",
                    "禁用",
                    () -> {
                        LauncherModuleBridge.setRenPyModuleEnabled(this, false);
                        renpyModuleEnabled = false;
                        applyModuleIconTint(binding.moduleRenpyIcon, renpyModuleInstalled, renpyModuleEnabled);
                        updateModuleDescription(binding.moduleRenpyDescription, renpyModuleInstalled, renpyModuleEnabled,
                                "提供 RenPy 游戏所需环境");
                    });
        } else {
            LauncherDialogFactory.showStandardConfirm(
                    this,
                    "启用 RenPy 模块",
                    "启用后该模块可用于启动 RenPy 游戏。是否启用？",
                    "启用",
                    () -> {
                        LauncherModuleBridge.setRenPyModuleEnabled(this, true);
                        renpyModuleEnabled = true;
                        applyModuleIconTint(binding.moduleRenpyIcon, renpyModuleInstalled, renpyModuleEnabled);
                        updateModuleDescription(binding.moduleRenpyDescription, renpyModuleInstalled, renpyModuleEnabled,
                                "提供 RenPy 游戏所需环境");
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

    private void openInstallPage(String installUrl) {
        try {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(installUrl));
            browserIntent.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(browserIntent);
        } catch (Throwable ignored) {
            LauncherDialogFactory.showInfo(this, "无法打开浏览器", "请稍后重试。");
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
