package com.apps.PadUi;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.apps.LauncherActivity;
import com.apps.LauncherMotion;
import com.apps.LauncherSyncScheduler;
import com.apps.LauncherTheme;
import com.apps.UserData.LauncherUserData;
import com.yuki.yukihub.R;
import com.yuki.yukihub.databinding.ActivityPadSettingsBinding;
import com.yuki.yukihub.launcher.EmulatorLauncher;
import com.yuki.yukihub.launcherbridge.LauncherAuthBridge;
import com.yuki.yukihub.launcherbridge.LauncherKrkrBridge;
import com.yuki.yukihub.launcherbridge.LauncherMetadataBridge;
import com.yuki.yukihub.metadata.MetadataController;

/** 横屏设置页，仅提供与 Pad 游戏模式一致的设置入口布局。 */
public class PadSettingsActivity extends AppCompatActivity {
    private enum Section { GENERAL, THEME, METADATA, ACCOUNT }

    private static final String ACCOUNT_SETTINGS_PREFS = "launcher_account_settings";
    private static final String THEME_DEFAULT_LABEL = "清新绿意（默认）";
    private static final String THEME_RINNE_LABEL = "园神凛弥（风格）";
    private static final String THEME_ANRI_LABEL = "鹰仓杏璃（风格）";

    private ActivityPadSettingsBinding binding;
    private Section currentSection = Section.GENERAL;
    private String selectedTheme = THEME_DEFAULT_LABEL;
    private AlertDialog accountLoadingDialog;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        LauncherActivity.applySavedToneMode(this);
        super.onCreate(savedInstanceState);
        configureLandscapeWindow();
        binding = ActivityPadSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applySystemBarInsets();
        restoreSelectedTheme();
        setupKrkrControls();
        setupMetadataControls();
        updateAccountSectionVisibility();
        bindActions();
        selectSection(Section.GENERAL);
        renderParticles();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAccountSectionVisibility();
        applyTheme();
        renderParticles();
    }

    private void bindActions() {
        binding.padSettingsSidebarGeneral.setOnClickListener(view -> selectSection(Section.GENERAL));
        binding.padSettingsSidebarTheme.setOnClickListener(view -> selectSection(Section.THEME));
        binding.padSettingsSidebarMetadata.setOnClickListener(view -> selectSection(Section.METADATA));
        binding.padSettingsSidebarAccount.setOnClickListener(view -> selectSection(Section.ACCOUNT));
        binding.padSettingsBackButton.setOnClickListener(view -> {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            finish();
        });
        binding.padFreshThemeRow.setOnClickListener(view -> selectTheme(THEME_DEFAULT_LABEL));
        binding.padRinneThemeRow.setOnClickListener(view -> selectTheme(THEME_RINNE_LABEL));
        binding.padAnriThemeRow.setOnClickListener(view -> selectTheme(THEME_ANRI_LABEL));
        binding.padParticleToggleRow.setOnClickListener(view -> toggleParticles());
        binding.padThemeApply.setOnClickListener(view -> applySelectedTheme());
        binding.padKrkrSaveButton.setOnClickListener(view -> saveKrkrConfig());
        binding.padKrkrCancelButton.setOnClickListener(view -> finish());
        binding.padNativeKrkrButton.setOnClickListener(view -> enterNativeKrkr());
        binding.padMetadataSaveButton.setOnClickListener(view -> saveMetadataConfig());
        binding.padMetadataCancelButton.setOnClickListener(view -> finish());
        binding.padMetadataTokenLink.setOnClickListener(view -> openMetadataTokenUrl());
        binding.padRowSyncConfig.setOnClickListener(view -> onSyncConfigClick());
        binding.padRowRealtimePlaytime.setOnClickListener(view -> onRealtimePlaytimeClick());
        binding.padRowProfileDisplay.setOnClickListener(view ->
                toggleAccountSetting("profile_display", binding.padChipProfileDisplay));
        binding.padRowModelFeature.setOnClickListener(view ->
                toggleAccountSetting("model_feature", binding.padChipModelFeature));
        binding.padRowEmailSubscribe.setOnClickListener(view ->
                toggleAccountSetting("email_subscribe", binding.padChipEmailSubscribe));
    }

    private void setupKrkrControls() {
        binding.padEngineVersionSpinner.setAdapter(LauncherTheme.spinnerAdapter(this,
                new String[]{"自动", "1.3.9", "1.3.4"}));
        loadKrkrConfig();
    }

    private void loadKrkrConfig() {
        String version = LauncherKrkrBridge.getEngineVersion(this);
        int selection = 0;
        if (LauncherKrkrBridge.ENGINE_VERSION_139.equals(version)) selection = 1;
        else if (LauncherKrkrBridge.ENGINE_VERSION_134.equals(version)) selection = 2;
        binding.padEngineVersionSpinner.setSelection(selection);
        binding.padCompatModeSwitch.setChecked(LauncherKrkrBridge.isCompatMode(this));
        binding.padKrScopedSwitch.setChecked(LauncherKrkrBridge.isKrScopedSaveDir(this));
        binding.padArtemisScopedSwitch.setChecked(LauncherKrkrBridge.isArtemisScopedSaveDir(this));
    }

    private void setupMetadataControls() {
        binding.padMetadataSourceSpinner.setAdapter(LauncherTheme.spinnerAdapter(this,
                new String[]{"VNDB（默认）", "Bangumi（需要 Token）", "Bangumi 镜像（需要 Token）", "月幕 Gal（公开 API）"}));
        loadMetadataConfig();
    }

    private void loadMetadataConfig() {
        String source = LauncherMetadataBridge.getMetadataSource(this);
        int selection = 0;
        if (MetadataController.SOURCE_BANGUMI.equals(source)) selection = 1;
        else if (MetadataController.SOURCE_BANGUMI_MIRROR.equals(source)) selection = 2;
        else if (MetadataController.SOURCE_YMGAL.equals(source)) selection = 3;
        binding.padMetadataSourceSpinner.setSelection(selection);
        binding.padMetadataTokenInput.setText(LauncherMetadataBridge.getBangumiToken(this));
    }

    private void restoreSelectedTheme() {
        String style = LauncherActivity.getLauncherThemeStyle(this);
        if (LauncherActivity.THEME_STYLE_RINNE.equals(style)) {
            selectedTheme = THEME_RINNE_LABEL;
        } else if (LauncherActivity.THEME_STYLE_ANRI.equals(style)) {
            selectedTheme = THEME_ANRI_LABEL;
        } else {
            selectedTheme = THEME_DEFAULT_LABEL;
        }
    }

    private void updateAccountSectionVisibility() {
        boolean online = LauncherAuthBridge.isLoggedIn(this);
        binding.padSettingsSidebarAccount.setVisibility(online ? View.VISIBLE : View.GONE);
        if (!online && currentSection == Section.ACCOUNT) selectSection(Section.GENERAL);
    }

    private void selectSection(Section section) {
        if (section == Section.ACCOUNT && !LauncherAuthBridge.isLoggedIn(this)) {
            section = Section.GENERAL;
        }
        currentSection = section;
        boolean showTheme = section == Section.THEME;
        boolean showMetadata = section == Section.METADATA;
        boolean showAccount = section == Section.ACCOUNT;
        binding.padSettingsGeneralActionList.setVisibility(
                section == Section.GENERAL ? View.VISIBLE : View.GONE);
        binding.padSettingsThemeActionList.setVisibility(showTheme ? View.VISIBLE : View.GONE);
        binding.padSettingsMetadataActionList.setVisibility(showMetadata ? View.VISIBLE : View.GONE);
        binding.padSettingsAccountActionList.setVisibility(showAccount ? View.VISIBLE : View.GONE);
        binding.padSettingsActionScroll.scrollTo(0, 0);
        binding.padSettingsPageTitle.setText(showTheme ? "主题设置"
                : showMetadata ? "资料源设置" : showAccount ? "账号设置" : "KRKR 引擎");
        binding.padSettingsPageDescription.setText(showTheme
                ? "选择 Launcher 的主题风格与动态背景"
                : showMetadata ? "选择游戏信息与封面获取的资料源"
                : showAccount ? "管理云端同步、资料显示与账户功能偏好"
                : "华为等部分机型如因存储权限导致引擎崩溃或闪退，可开启对应引擎的独立存档目录。");
        if (showAccount) renderAllAccountChips();
        applyTheme();
    }

    private void configureLandscapeWindow() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        int background = ContextCompat.getColor(this, R.color.launcher_bg_color);
        window.setStatusBarColor(background);
        window.setNavigationBarColor(background);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attributes);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }

        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        if (!LauncherActivity.isLauncherDarkMode(this)) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private void applySystemBarInsets() {
        final int left = binding.padSettingsContent.getPaddingLeft();
        final int top = binding.padSettingsContent.getPaddingTop();
        final int right = binding.padSettingsContent.getPaddingRight();
        final int bottom = binding.padSettingsContent.getPaddingBottom();
        binding.getRoot().setOnApplyWindowInsetsListener((view, insets) -> {
            binding.padSettingsContent.setPadding(
                    left + insets.getSystemWindowInsetLeft(),
                    top + insets.getSystemWindowInsetTop(),
                    right + insets.getSystemWindowInsetRight(),
                    bottom + insets.getSystemWindowInsetBottom());
            return insets;
        });
        binding.getRoot().requestApplyInsets();
    }

    private void applyTheme() {
        if (binding == null) return;
        LauncherTheme.applyPrimaryTone(binding.getRoot());
        styleSidebarItem(binding.padSettingsSidebarGeneral, currentSection == Section.GENERAL);
        styleSidebarItem(binding.padSettingsSidebarTheme, currentSection == Section.THEME);
        styleSidebarItem(binding.padSettingsSidebarMetadata, currentSection == Section.METADATA);
        styleSidebarItem(binding.padSettingsSidebarAccount, currentSection == Section.ACCOUNT);
        LauncherTheme.secondaryButton(binding.padSettingsBackButton);
        LauncherTheme.textPrimary(binding.padSettingsPageTitle);
        LauncherTheme.styleSpinner(binding.padEngineVersionSpinner);
        LauncherTheme.styleSwitch(binding.padCompatModeSwitch);
        LauncherTheme.styleSwitch(binding.padKrScopedSwitch);
        LauncherTheme.styleSwitch(binding.padArtemisScopedSwitch);
        LauncherTheme.styleSpinner(binding.padMetadataSourceSpinner);
        LauncherTheme.secondaryButton(binding.padNativeKrkrButton);
        LauncherTheme.secondaryButton(binding.padKrkrCancelButton);
        LauncherTheme.primaryButton(binding.padKrkrSaveButton);
        LauncherTheme.textPrimary(binding.padMetadataTokenLink);
        LauncherTheme.secondaryButton(binding.padMetadataCancelButton);
        LauncherTheme.primaryButton(binding.padMetadataSaveButton);
        applyThemeMenuTone();
        renderThemeSelection();
        renderParticleToggle();
    }

    private void styleSidebarItem(TextView item, boolean selected) {
        if (selected) {
            item.setBackground(LauncherTheme.selectedChip(this));
            item.setTextColor(ContextCompat.getColor(this, R.color.launcher_on_primary_color));
        } else {
            item.setBackground(null);
            item.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_color));
        }
    }

    private void styleActionIcons(ViewGroup actionList) {
        for (int i = 0; i < actionList.getChildCount(); i++) {
            View row = actionList.getChildAt(i);
            if (!(row instanceof ViewGroup)) continue;
            View icon = ((ViewGroup) row).getChildAt(0);
            if (icon instanceof TextView) {
                icon.setBackground(LauncherTheme.circle(this));
                ((TextView) icon).setTextColor(LauncherTheme.onPrimary(this));
            }
        }
    }

    private void applyThemeMenuTone() {
        binding.padFreshThemeIcon.setBackground(LauncherTheme.circle(this, LauncherTheme.primary(this)));
        binding.padFreshThemeIcon.setClipToOutline(true);
        binding.padRinneThemeLogo.setBackground(
                LauncherTheme.circle(this, LauncherActivity.RINNE_PRIMARY_COLOR));
        binding.padRinneThemeLogo.setClipToOutline(true);
        binding.padAnriThemeLogo.setBackground(
                LauncherTheme.circle(this, LauncherActivity.ANRI_PRIMARY_COLOR));
        binding.padAnriThemeLogo.setClipToOutline(true);
        binding.padParticleToggleIcon.setBackground(LauncherTheme.circle(this));
        binding.padParticleToggleIcon.setTextColor(LauncherTheme.onPrimary(this));
        LauncherTheme.primaryButton(binding.padThemeApply);
    }

    private void selectTheme(String themeName) {
        selectedTheme = themeName;
        renderThemeSelection();
    }

    private void renderThemeSelection() {
        boolean freshSelected = THEME_DEFAULT_LABEL.equals(selectedTheme);
        boolean rinneSelected = THEME_RINNE_LABEL.equals(selectedTheme);
        boolean anriSelected = THEME_ANRI_LABEL.equals(selectedTheme);
        styleThemeRow(binding.padFreshThemeRow, freshSelected);
        styleThemeRow(binding.padRinneThemeRow, rinneSelected);
        styleThemeRow(binding.padAnriThemeRow, anriSelected);
        binding.padFreshThemeCheck.setVisibility(freshSelected ? View.VISIBLE : View.INVISIBLE);
        binding.padRinneThemeCheck.setVisibility(rinneSelected ? View.VISIBLE : View.INVISIBLE);
        binding.padAnriThemeCheck.setVisibility(anriSelected ? View.VISIBLE : View.INVISIBLE);
        int primary = LauncherTheme.primary(this);
        binding.padFreshThemeCheck.setTextColor(primary);
        binding.padRinneThemeCheck.setTextColor(primary);
        binding.padAnriThemeCheck.setTextColor(primary);
    }

    private void styleThemeRow(View row, boolean selected) {
        if (selected) {
            row.setBackground(LauncherTheme.selectedOption(this));
        } else {
            row.setBackgroundResource(R.drawable.launcher_chat_option_bg);
        }
    }

    private void toggleParticles() {
        boolean enabled = !LauncherActivity.isLauncherParticlesEnabled(this);
        LauncherActivity.setLauncherParticlesEnabled(this, enabled);
        renderParticles();
        renderParticleToggle();
        Toast.makeText(this, enabled ? "已开启动态粒子" : "已关闭动态粒子", Toast.LENGTH_SHORT).show();
    }

    private void renderParticleToggle() {
        boolean enabled = LauncherActivity.isLauncherParticlesEnabled(this);
        binding.padParticleToggleState.setText(enabled ? "关闭" : "开启");
        if (enabled) {
            LauncherTheme.primaryButton(binding.padParticleToggleState);
        } else {
            LauncherTheme.secondaryButton(binding.padParticleToggleState);
        }
    }

    private void applySelectedTheme() {
        final String style;
        final String message;
        if (THEME_RINNE_LABEL.equals(selectedTheme)) {
            style = LauncherActivity.THEME_STYLE_RINNE;
            message = "已应用园神凛弥风格";
        } else if (THEME_ANRI_LABEL.equals(selectedTheme)) {
            style = LauncherActivity.THEME_STYLE_ANRI;
            message = "已应用鹰仓杏璃风格";
        } else {
            style = LauncherActivity.THEME_STYLE_DEFAULT;
            message = "已恢复默认主题";
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        LauncherMotion.recreateWithToneOverlay(this,
                () -> LauncherActivity.setLauncherThemeStyle(this, style));
    }

    private void saveKrkrConfig() {
        int position = binding.padEngineVersionSpinner.getSelectedItemPosition();
        String version = LauncherKrkrBridge.ENGINE_VERSION_AUTO;
        if (position == 1) version = LauncherKrkrBridge.ENGINE_VERSION_139;
        else if (position == 2) version = LauncherKrkrBridge.ENGINE_VERSION_134;

        LauncherKrkrBridge.setEngineVersion(this, version);
        LauncherKrkrBridge.setCompatMode(this, binding.padCompatModeSwitch.isChecked());
        LauncherKrkrBridge.setKrScopedSaveDir(this, binding.padKrScopedSwitch.isChecked());
        LauncherKrkrBridge.setArtemisScopedSaveDir(this, binding.padArtemisScopedSwitch.isChecked());
        Toast.makeText(this, "KRKR 引擎设置已保存："
                + LauncherKrkrBridge.engineVersionLabel(version), Toast.LENGTH_SHORT).show();
        finish();
    }

    private void enterNativeKrkr() {
        try {
            startActivity(EmulatorLauncher.buildInternalKrkrIntent(this, "", "", true));
        } catch (Throwable throwable) {
            Toast.makeText(this, "无法进入原生 KRKR", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveMetadataConfig() {
        int position = binding.padMetadataSourceSpinner.getSelectedItemPosition();
        String source = MetadataController.SOURCE_VNDB;
        if (position == 1) source = MetadataController.SOURCE_BANGUMI;
        else if (position == 2) source = MetadataController.SOURCE_BANGUMI_MIRROR;
        else if (position == 3) source = MetadataController.SOURCE_YMGAL;

        String token = binding.padMetadataTokenInput.getText().toString().trim();
        if ((position == 1 || position == 2) && token.isEmpty()) {
            Toast.makeText(this, "选择 Bangumi 时需要填写 Token", Toast.LENGTH_SHORT).show();
            return;
        }
        LauncherMetadataBridge.setMetadataSource(this, source);
        LauncherMetadataBridge.setBangumiToken(this, token);
        Toast.makeText(this, "已保存资料源：" + LauncherMetadataBridge.sourceLabel(source),
                Toast.LENGTH_SHORT).show();
        finish();
    }

    private void openMetadataTokenUrl() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://next.bgm.tv/demo/access-token/create")));
        } catch (Throwable throwable) {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show();
        }
    }

    private void onSyncConfigClick() {
        SharedPreferences prefs = getSharedPreferences(ACCOUNT_SETTINGS_PREFS, MODE_PRIVATE);
        if (prefs.getBoolean("sync_config", false)) {
            prefs.edit().putBoolean("sync_config", false).apply();
            renderAccountChip(binding.padChipSyncConfig, false);
            LauncherSyncScheduler.updateSchedule(this);
            return;
        }
        showAccountConfirmDialog("配置同步", "是否上传当前配置到云端？开启后将在每晚12点自动备份。",
                "确定上传", this::enableSyncAndUpload);
    }

    private void onRealtimePlaytimeClick() {
        SharedPreferences prefs = getSharedPreferences(ACCOUNT_SETTINGS_PREFS, MODE_PRIVATE);
        if (prefs.getBoolean("realtime_playtime", accountDefault("realtime_playtime"))) {
            prefs.edit().putBoolean("realtime_playtime", false).apply();
            renderAccountChip(binding.padChipRealtimePlaytime, false);
            return;
        }
        showAccountConfirmDialog("实时游玩时间", "此功能启用将实时上传游玩详细信息，确定要使用吗？",
                "确定开启", () -> {
                    getSharedPreferences(ACCOUNT_SETTINGS_PREFS, MODE_PRIVATE).edit()
                            .putBoolean("realtime_playtime", true).apply();
                    renderAccountChip(binding.padChipRealtimePlaytime, true);
                });
    }

    private void toggleAccountSetting(String key, TextView chip) {
        SharedPreferences prefs = getSharedPreferences(ACCOUNT_SETTINGS_PREFS, MODE_PRIVATE);
        boolean enabled = !prefs.getBoolean(key, accountDefault(key));
        prefs.edit().putBoolean(key, enabled).apply();
        renderAccountChip(chip, enabled);
    }

    private void renderAllAccountChips() {
        SharedPreferences prefs = getSharedPreferences(ACCOUNT_SETTINGS_PREFS, MODE_PRIVATE);
        renderAccountChip(binding.padChipSyncConfig,
                prefs.getBoolean("sync_config", accountDefault("sync_config")));
        renderAccountChip(binding.padChipRealtimePlaytime,
                prefs.getBoolean("realtime_playtime", accountDefault("realtime_playtime")));
        renderAccountChip(binding.padChipProfileDisplay,
                prefs.getBoolean("profile_display", accountDefault("profile_display")));
        renderAccountChip(binding.padChipModelFeature,
                prefs.getBoolean("model_feature", accountDefault("model_feature")));
        renderAccountChip(binding.padChipEmailSubscribe,
                prefs.getBoolean("email_subscribe", accountDefault("email_subscribe")));
    }

    private boolean accountDefault(String key) {
        return "realtime_playtime".equals(key) || "profile_display".equals(key);
    }

    private void renderAccountChip(TextView chip, boolean enabled) {
        chip.setText(enabled ? "关闭" : "开启");
        if (enabled) {
            LauncherTheme.primaryButton(chip);
        } else {
            LauncherTheme.secondaryButton(chip);
        }
    }

    private void showAccountConfirmDialog(String title, String message, String confirmText,
                                          Runnable onConfirm) {
        AlertDialog dialog = new AlertDialog.Builder(this).create();
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(270), WindowManager.LayoutParams.WRAP_CONTENT);

        LinearLayout root = dialogRoot();
        root.addView(dialogTitle(title));
        TextView info = dialogMessage(message);
        addWithMargin(root, info, 13);
        TextView confirm = dialogButton(confirmText, true);
        confirm.setOnClickListener(view -> {
            dialog.dismiss();
            if (onConfirm != null) onConfirm.run();
        });
        addWithMargin(root, confirm, 11);
        TextView cancel = dialogButton("取消", false);
        cancel.setOnClickListener(view -> dialog.dismiss());
        addWithMargin(root, cancel, 9);
        window.setContentView(root);
    }

    private void enableSyncAndUpload() {
        getSharedPreferences(ACCOUNT_SETTINGS_PREFS, MODE_PRIVATE).edit()
                .putBoolean("sync_config", true).apply();
        renderAccountChip(binding.padChipSyncConfig, true);
        LauncherSyncScheduler.updateSchedule(this);
        accountLoadingDialog = showAccountLoading("正在上传配置...", "请不要关闭应用及网络，否则可能导致配置出错");
        String settingsJson = LauncherUserData.exportSettingsJson(this);
        LauncherAuthBridge.uploadConfig(this, settingsJson, new LauncherAuthBridge.ConfigCallback() {
            @Override
            public void onSuccess(String configJson) {
                String playData = LauncherUserData.exportCloudPlayData(PadSettingsActivity.this);
                if (playData == null || playData.trim().isEmpty()) {
                    dismissAccountLoading();
                    showAccountResult("部分上传失败", "配置已上传，但本地数据导出失败，游玩记录未能上传");
                    return;
                }
                LauncherAuthBridge.uploadPlayData(PadSettingsActivity.this, playData,
                        new LauncherAuthBridge.PlayDataCallback() {
                            @Override
                            public void onSuccess(String playData) {
                                dismissAccountLoading();
                                showAccountResult("上传成功", "配置及游玩记录已同步到云端");
                            }

                            @Override
                            public void onError(String message) {
                                dismissAccountLoading();
                                showAccountResult("部分上传失败", "配置已上传，游玩记录上传失败：" + message);
                            }
                        });
            }

            @Override
            public void onError(String message) {
                dismissAccountLoading();
                showAccountResult("上传失败", message);
            }
        });
    }

    private AlertDialog showAccountLoading(String title, String hint) {
        AlertDialog dialog = new AlertDialog.Builder(this).create();
        dialog.setCancelable(false);
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);
        Window window = dialog.getWindow();
        if (window == null) return dialog;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(270), WindowManager.LayoutParams.WRAP_CONTENT);

        LinearLayout root = dialogRoot();
        root.addView(dialogTitle(title));
        ProgressBar progress = new ProgressBar(this);
        progress.getIndeterminateDrawable().setColorFilter(
                LauncherTheme.primary(this), android.graphics.PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(32), dp(32));
        progressParams.gravity = Gravity.CENTER_HORIZONTAL;
        progressParams.setMargins(0, dp(14), 0, 0);
        root.addView(progress, progressParams);
        addWithMargin(root, dialogMessage(hint), 10);
        window.setContentView(root);
        return dialog;
    }

    private void dismissAccountLoading() {
        if (accountLoadingDialog != null && accountLoadingDialog.isShowing()) {
            accountLoadingDialog.dismiss();
        }
        accountLoadingDialog = null;
    }

    private void showAccountResult(String title, String message) {
        AlertDialog dialog = new AlertDialog.Builder(this).create();
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(270), WindowManager.LayoutParams.WRAP_CONTENT);
        LinearLayout root = dialogRoot();
        root.addView(dialogTitle(title));
        addWithMargin(root, dialogMessage(message), 13);
        TextView ok = dialogButton("知道了", true);
        ok.setOnClickListener(view -> dialog.dismiss());
        addWithMargin(root, ok, 11);
        window.setContentView(root);
    }

    private LinearLayout dialogRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(20), dp(22), dp(16));
        root.setBackgroundResource(R.drawable.launcher_dialog_bg);
        return root;
    }

    private TextView dialogTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_color));
        view.setTextSize(16);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private TextView dialogMessage(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_muted_color));
        view.setTextSize(12);
        return view;
    }

    private TextView dialogButton(String text, boolean primary) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(36));
        view.setTextSize(13);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        if (primary) LauncherTheme.primaryButton(view); else LauncherTheme.secondaryButton(view);
        return view;
    }

    private void addWithMargin(LinearLayout root, View child, int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(topMarginDp), 0, 0);
        root.addView(child, params);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void renderParticles() {
        if (binding == null) return;
        boolean enabled = LauncherActivity.isLauncherParticlesEnabled(this);
        binding.padSettingsParticleView.setVisibility(enabled ? View.VISIBLE : View.GONE);
        binding.padSettingsParticleView.setParticlesEnabled(enabled);
    }
}
