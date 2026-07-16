package com.apps.profile;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.apps.LauncherActivity;
import com.apps.theme.LauncherDialogFactory;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherTabletPortraitScaler;
import com.yuki.yukihub.R;
import com.yuki.yukihub.databinding.ActivityLauncherModuleCompatibilityBinding;
import com.yuki.yukihub.launcher.ExternalRenPyPluginStrategy;
import com.yuki.yukihub.launcher.ExternalRpgMakerPluginStrategy;
import com.yuki.yukihub.util.AppExecutors;

/** Static placeholder surface for future module compatibility entries. */
public class LauncherModuleCompatibilityActivity extends AppCompatActivity {
    /** Temporary routable placeholder; replace with the published RPGM module URL when available. */
    private static final String RPGM_INSTALL_URL = "https://example.com/";
    /** Temporary routable placeholder; replace with the published RenPy module URL when available. */
    private static final String RENPY_INSTALL_URL = "https://example.com/";

    private ActivityLauncherModuleCompatibilityBinding binding;
    private boolean rpgmModuleInstalled;
    private boolean renpyModuleInstalled;

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
            boolean rpgmInstalled = ExternalRpgMakerPluginStrategy.isRpgMakerPluginInstalled(this);
            boolean renpyInstalled = ExternalRenPyPluginStrategy.isRenPyPluginInstalled(this);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                rpgmModuleInstalled = rpgmInstalled;
                renpyModuleInstalled = renpyInstalled;
                binding.moduleRpgmRow.setEnabled(true);
                binding.moduleRenpyRow.setEnabled(true);
                binding.moduleRpgmRow.setAlpha(1f);
                binding.moduleRenpyRow.setAlpha(1f);
                binding.moduleRpgmIcon.setImageTintList(ColorStateList.valueOf(
                        rpgmInstalled ? LauncherTheme.primary(this) : LauncherTheme.danger(this)));
                binding.moduleRenpyIcon.setImageTintList(ColorStateList.valueOf(
                        renpyInstalled ? LauncherTheme.primary(this) : LauncherTheme.danger(this)));
            });
        });
    }

    private void openRpgmModule() {
        if (rpgmModuleInstalled) {
            LauncherDialogFactory.showStandardConfirm(
                    this,
                    "RPGM 模块",
                    "该模块已安装。",
                    "确定",
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
                    "该模块已安装。",
                    "确定",
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
