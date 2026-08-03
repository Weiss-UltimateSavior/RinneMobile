package com.apps.profile;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.apps.LauncherActivity;
import com.apps.theme.LauncherDialogFactory;
import com.apps.theme.LauncherTheme;
import com.apps.util.LauncherUrlOpener;
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

    /** 各模块的静态差异：名称/详情资源、安装页 URL、用于弹窗标题的简称。 */
    private enum ModuleType {
        RPGM(R.string.module_rpgm_name, R.string.module_rpgm_detail, RPGM_INSTALL_URL, "RPGM"),
        RENPY(R.string.module_renpy_name, R.string.module_renpy_detail, RENPY_INSTALL_URL, "RenPy"),
        GODOT(R.string.module_godot_name, R.string.module_godot_detail, GODOT_INSTALL_URL, "Godot");

        final int nameRes;
        final int detailRes;
        final String installUrl;
        final String shortName;

        ModuleType(int nameRes, int detailRes, String installUrl, String shortName) {
            this.nameRes = nameRes;
            this.detailRes = detailRes;
            this.installUrl = installUrl;
            this.shortName = shortName;
        }
    }

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
        com.apps.LauncherEdgeToEdgeHelper.apply(this);

        binding = ActivityLauncherModuleCompatibilityBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        LauncherTabletPortraitScaler.applyActivityContent(this);
        applySystemBarInsets();
        LauncherTheme.applyPrimaryTone(binding.getRoot());
        for (ModuleType module : ModuleType.values()) {
            // 行点击：已安装提示状态，未安装前往安装页。
            getModuleRowView(module).setOnClickListener(view -> openModule(module));
            // 长按列表项：弹窗提醒跳转浏览器下载。
            getModuleRowView(module).setOnLongClickListener(view -> {
                promptDownload(module);
                return true;
            });
            // 右侧图标：已安装时点击切换启用/禁用；未安装时点击等价于行点击（前往安装）。
            getModuleIconView(module).setOnClickListener(view -> handleModuleIconClick(module));
        }
        refreshInstalledModules();
    }

    private void refreshInstalledModules() {
        for (ModuleType module : ModuleType.values()) {
            getModuleRowView(module).setEnabled(false);
            getModuleRowView(module).setAlpha(1f);
            getModuleIconView(module).setImageTintList(ColorStateList.valueOf(LauncherTheme.textMuted(this)));
        }
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
                for (ModuleType module : ModuleType.values()) {
                    getModuleRowView(module).setEnabled(true);
                    getModuleRowView(module).setAlpha(1f);
                    boolean installed = isModuleInstalled(module);
                    boolean enabled = isModuleEnabled(module);
                    applyModuleIconTint(getModuleIconView(module), installed, enabled);
                    updateModuleDescription(getModuleDescriptionView(module), installed, enabled,
                            module.detailRes);
                }
            });
        });
    }

    // ----- 模块状态 / 视图 helper（统一封装三个模块的差异） -----

    private boolean isModuleInstalled(ModuleType module) {
        switch (module) {
            case RPGM:
                return rpgmModuleInstalled;
            case RENPY:
                return renpyModuleInstalled;
            case GODOT:
                return godotModuleInstalled;
            default:
                throw new IllegalArgumentException("Unknown module: " + module);
        }
    }

    private boolean isModuleEnabled(ModuleType module) {
        switch (module) {
            case RPGM:
                return rpgmModuleEnabled;
            case RENPY:
                return renpyModuleEnabled;
            case GODOT:
                return godotModuleEnabled;
            default:
                throw new IllegalArgumentException("Unknown module: " + module);
        }
    }

    private void setModuleEnabled(ModuleType module, boolean enabled) {
        switch (module) {
            case RPGM:
                LauncherModuleBridge.setRpgMakerModuleEnabled(this, enabled);
                rpgmModuleEnabled = enabled;
                break;
            case RENPY:
                LauncherModuleBridge.setRenPyModuleEnabled(this, enabled);
                renpyModuleEnabled = enabled;
                break;
            case GODOT:
                LauncherModuleBridge.setGodotModuleEnabled(this, enabled);
                godotModuleEnabled = enabled;
                break;
            default:
                throw new IllegalArgumentException("Unknown module: " + module);
        }
    }

    private View getModuleRowView(ModuleType module) {
        switch (module) {
            case RPGM:
                return binding.moduleRpgmRow;
            case RENPY:
                return binding.moduleRenpyRow;
            case GODOT:
                return binding.moduleGodotRow;
            default:
                throw new IllegalArgumentException("Unknown module: " + module);
        }
    }

    private ImageView getModuleIconView(ModuleType module) {
        switch (module) {
            case RPGM:
                return binding.moduleRpgmIcon;
            case RENPY:
                return binding.moduleRenpyIcon;
            case GODOT:
                return binding.moduleGodotIcon;
            default:
                throw new IllegalArgumentException("Unknown module: " + module);
        }
    }

    private TextView getModuleDescriptionView(ModuleType module) {
        switch (module) {
            case RPGM:
                return binding.moduleRpgmDescription;
            case RENPY:
                return binding.moduleRenpyDescription;
            case GODOT:
                return binding.moduleGodotDescription;
            default:
                throw new IllegalArgumentException("Unknown module: " + module);
        }
    }

    /**
     * 图标着色规则：
     * <ul>
     *   <li>未安装 → danger 红</li>
     *   <li>已安装 + 已启用 → primary 主题色</li>
     *   <li>已安装 + 未启用 → textMuted 灰，表示「关闭」状态</li>
     * </ul>
     */
    private void applyModuleIconTint(ImageView icon, boolean installed, boolean enabled) {
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

    private void promptDownload(ModuleType module) {
        LauncherDialogFactory.showStandardConfirm(
                this,
                getString(R.string.module_download_title, module.shortName),
                getString(R.string.module_download_message),
                getString(R.string.theme_go_to_download),
                () -> openInstallPage(module.installUrl));
    }

    // ----- 行点击 -----

    private void openModule(ModuleType module) {
        if (isModuleInstalled(module)) {
            LauncherDialogFactory.showStandardConfirm(
                    this,
                    getString(module.nameRes),
                    isModuleEnabled(module)
                            ? getString(R.string.module_installed_enabled_hint)
                            : getString(R.string.module_installed_disabled_hint),
                    getString(R.string.settings_got_it),
                    null);
            return;
        }
        LauncherDialogFactory.showStandardConfirm(
                this,
                getString(R.string.module_install_title, module.shortName),
                getString(R.string.module_install_message),
                getString(R.string.module_go_to_install),
                () -> openInstallPage(module.installUrl));
    }

    // ----- 图标点击：启停切换 -----

    private void handleModuleIconClick(ModuleType module) {
        if (!isModuleInstalled(module)) {
            openModule(module);
            return;
        }
        if (isModuleEnabled(module)) {
            LauncherDialogFactory.showStandardConfirm(
                    this,
                    getString(R.string.module_disable_title, module.shortName),
                    getString(R.string.module_disable_message, module.shortName),
                    getString(R.string.module_disable),
                    () -> {
                        setModuleEnabled(module, false);
                        applyModuleIconTint(getModuleIconView(module), isModuleInstalled(module), isModuleEnabled(module));
                        updateModuleDescription(getModuleDescriptionView(module), isModuleInstalled(module), isModuleEnabled(module),
                                module.detailRes);
                    });
        } else {
            LauncherDialogFactory.showStandardConfirm(
                    this,
                    getString(R.string.module_enable_title, module.shortName),
                    getString(R.string.module_enable_message, module.shortName),
                    getString(R.string.module_enable),
                    () -> {
                        setModuleEnabled(module, true);
                        applyModuleIconTint(getModuleIconView(module), isModuleInstalled(module), isModuleEnabled(module));
                        updateModuleDescription(getModuleDescriptionView(module), isModuleInstalled(module), isModuleEnabled(module),
                                module.detailRes);
                    });
        }
    }

    // ----- 安装页跳转 -----

    private void openInstallPage(String installUrl) {
        boolean opened;
        try {
            // 统一走共享 LauncherUrlOpener：scheme 白名单校验 + ActivityNotFoundException 捕获。
            opened = LauncherUrlOpener.open(this, installUrl);
        } catch (SecurityException e) {
            // 受限设备/异常浏览器组件下 startActivity 可能抛 SecurityException，按打开失败统一兜底。
            opened = false;
        }
        if (!opened) {
            // 打开失败弹窗提示同前（成功打开同前）。
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

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase));
    }
}
