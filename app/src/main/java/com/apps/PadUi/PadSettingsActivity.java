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
import com.apps.theme.LauncherMotion;
import com.apps.sync.LauncherSyncScheduler;
import com.apps.theme.LauncherTheme;
import com.apps.UserData.LauncherUserData;
import com.yuki.yukihub.R;
import com.yuki.yukihub.databinding.ActivityPadSettingsBinding;
import com.yuki.yukihub.launcherbridge.LauncherAuthBridge;
import com.yuki.yukihub.launcherbridge.LauncherGameLaunchBridge;
import com.yuki.yukihub.launcherbridge.LauncherKrkrBridge;
import com.yuki.yukihub.launcherbridge.LauncherMetadataBridge;
import com.yuki.yukihub.metadata.MetadataController;
import com.yuki.yukihub.ons.OnsSettings;

/** 横屏设置页，仅提供与 Pad 游戏模式一致的设置入口布局。 */
public class PadSettingsActivity extends AppCompatActivity {
    private enum Section { GENERAL, THEME, METADATA, ACCOUNT }

    private static final String ACCOUNT_SETTINGS_PREFS = "launcher_account_settings";
    private static final String THEME_DEFAULT_LABEL = "清新绿意（默认）";
    private static final String THEME_RINNE_LABEL = "园神凛弥（风格）";
    private static final String THEME_ANRI_LABEL = "鹰仓杏璃（风格）";
    private static final String THEME_XINHAITIAN_LABEL = "心海天（风格）";
    private static final String[] ENGINE_VERSION_LABELS = {"自动", "1.3.9", "1.3.4", "1.2.6"};
    private static final String[] ONS_ENCODING_LABELS = {"gbk", "sjis", "utf8"};
    private static final String[] METADATA_SOURCE_LABELS = {
            "VNDB（默认）", "Bangumi（需要 Token）", "Bangumi 镜像（需要 Token）", "月幕 Gal（公开 API）"
    };
    private static final String STATE_ENGINE_VERSION_INDEX = "engine_version_index";
    private static final String STATE_METADATA_SOURCE_INDEX = "metadata_source_index";
    private static final String STATE_ONS_ENCODING_INDEX = "ons_encoding_index";

    private ActivityPadSettingsBinding binding;
    private Section currentSection = Section.GENERAL;
    private String selectedTheme = THEME_DEFAULT_LABEL;
    private AlertDialog accountLoadingDialog;
    private boolean emailSubscriptionUpdating;
    private int selectedEngineVersionIndex;
    private int selectedMetadataSourceIndex;
    private int selectedOnsEncodingIndex;
    private Bundle restoredState;

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
        restoredState = savedInstanceState;
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
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_ENGINE_VERSION_INDEX, selectedEngineVersionIndex);
        outState.putInt(STATE_METADATA_SOURCE_INDEX, selectedMetadataSourceIndex);
        outState.putInt(STATE_ONS_ENCODING_INDEX, selectedOnsEncodingIndex);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAccountSectionVisibility();
        if (currentSection == Section.ACCOUNT) refreshEmailSubscription();
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
        binding.padXinhaitianThemeRow.setOnClickListener(view -> selectTheme(THEME_XINHAITIAN_LABEL));
        binding.padParticleToggleRow.setOnClickListener(view -> showParticleStyleDialog());
        binding.padThemeApply.setOnClickListener(view -> applySelectedTheme());
        binding.padKrkrSaveButton.setOnClickListener(view -> saveKrkrConfig());
        binding.padKrkrCancelButton.setOnClickListener(view -> finish());
        binding.padNativeKrkrButton.setOnClickListener(view -> enterNativeKrkr());
        binding.padMetadataSaveButton.setOnClickListener(view -> saveMetadataConfig());
        binding.padMetadataCancelButton.setOnClickListener(view -> finish());
        binding.padMetadataTokenLink.setOnClickListener(view -> openMetadataTokenUrl());
        binding.padEngineVersionText.setOnClickListener(view -> showEngineVersionPicker());
        binding.padOnsEncodingText.setOnClickListener(view -> showOnsEncodingPicker());
        binding.padMetadataSourceText.setOnClickListener(view -> showMetadataSourcePicker());
        binding.padRowSyncConfig.setOnClickListener(view -> onSyncConfigClick());
        binding.padRowRealtimePlaytime.setOnClickListener(view -> onRealtimePlaytimeClick());
        binding.padRowEmailSubscribe.setOnClickListener(view -> onEmailSubscriptionClick());
    }

    private void setupKrkrControls() {
        loadKrkrConfig();
    }

    private void loadKrkrConfig() {
        String version = LauncherKrkrBridge.getEngineVersion(this);
        int selection = 0;
        if (LauncherKrkrBridge.ENGINE_VERSION_139.equals(version)) selection = 1;
        else if (LauncherKrkrBridge.ENGINE_VERSION_134.equals(version)) selection = 2;
        else if (LauncherKrkrBridge.ENGINE_VERSION_126.equals(version)) selection = 3;
        setEngineVersionSelection(restoredState != null && restoredState.containsKey(STATE_ENGINE_VERSION_INDEX)
                ? restoredState.getInt(STATE_ENGINE_VERSION_INDEX, 0) : selection);
        binding.padKrScopedSwitch.setChecked(LauncherKrkrBridge.isKrScopedSaveDir(this));
        binding.padArtemisScopedSwitch.setChecked(LauncherKrkrBridge.isArtemisScopedSaveDir(this));
        OnsSettings onsSettings = OnsSettings.load(this);
        binding.padOnsScopedSwitch.setChecked(onsSettings.scopedSaveDir);
        binding.padOnsStretchSwitch.setChecked(onsSettings.stretchFull);
        binding.padOnsCutoutSwitch.setChecked(onsSettings.ignoreCutout);
        binding.padOnsDisableVideoSwitch.setChecked(onsSettings.disableVideo);
        binding.padOnsSharpnessSwitch.setChecked(onsSettings.sharpness);
        binding.padOnsSharpnessValueInput.setText(onsSettings.sharpnessValue);
        int onsEncodingIndex = onsEncodingIndex(onsSettings.encoding);
        if (restoredState != null && restoredState.containsKey(STATE_ONS_ENCODING_INDEX)) {
            onsEncodingIndex = restoredState.getInt(STATE_ONS_ENCODING_INDEX, onsEncodingIndex);
        }
        setOnsEncodingSelection(onsEncodingIndex);
        binding.padTyranoScopedSwitch.setChecked(LauncherKrkrBridge.isTyranoScopedSaveDir(this));
    }

    private void setupMetadataControls() {
        loadMetadataConfig();
    }

    private void loadMetadataConfig() {
        String source = LauncherMetadataBridge.getMetadataSource(this);
        int selection = 0;
        if (MetadataController.SOURCE_BANGUMI.equals(source)) selection = 1;
        else if (MetadataController.SOURCE_BANGUMI_MIRROR.equals(source)) selection = 2;
        else if (MetadataController.SOURCE_YMGAL.equals(source)) selection = 3;
        setMetadataSourceSelection(restoredState != null && restoredState.containsKey(STATE_METADATA_SOURCE_INDEX)
                ? restoredState.getInt(STATE_METADATA_SOURCE_INDEX, 0) : selection);
        binding.padMetadataTokenInput.setText(LauncherMetadataBridge.getBangumiToken(this));
    }

    private void showEngineVersionPicker() {
        PadDialogFactory.showSingleChoice(this, "选择 KR 引擎版本", ENGINE_VERSION_LABELS,
                selectedEngineVersionIndex, this::setEngineVersionSelection);
    }

    private void setEngineVersionSelection(int index) {
        selectedEngineVersionIndex = index >= 0 && index < ENGINE_VERSION_LABELS.length ? index : 0;
        binding.padEngineVersionText.setText(ENGINE_VERSION_LABELS[selectedEngineVersionIndex]);
    }

    private void showOnsEncodingPicker() {
        PadDialogFactory.showSingleChoice(this, "ONS 文本编码", ONS_ENCODING_LABELS,
                selectedOnsEncodingIndex, this::setOnsEncodingSelection);
    }

    private void setOnsEncodingSelection(int index) {
        selectedOnsEncodingIndex = index >= 0 && index < ONS_ENCODING_LABELS.length ? index : 0;
        binding.padOnsEncodingText.setText(ONS_ENCODING_LABELS[selectedOnsEncodingIndex]);
    }

    private static int onsEncodingIndex(String encoding) {
        String normalized = OnsSettings.normalizeEncoding(encoding);
        for (int i = 0; i < ONS_ENCODING_LABELS.length; i++) {
            if (ONS_ENCODING_LABELS[i].equals(normalized)) return i;
        }
        return 0;
    }

    private void showMetadataSourcePicker() {
        PadDialogFactory.showSingleChoice(this, "选择资料源", METADATA_SOURCE_LABELS,
                selectedMetadataSourceIndex, this::setMetadataSourceSelection);
    }

    private void setMetadataSourceSelection(int index) {
        selectedMetadataSourceIndex = index >= 0 && index < METADATA_SOURCE_LABELS.length ? index : 0;
        binding.padMetadataSourceText.setText(METADATA_SOURCE_LABELS[selectedMetadataSourceIndex]);
    }

    private void restoreSelectedTheme() {
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
                : showMetadata ? "封面设置" : showAccount ? "账号设置" : "引擎设置");
        binding.padSettingsPageDescription.setText(showTheme
                ? "选择 Launcher 的主题风格与动态背景"
                : showMetadata ? "选择游戏信息与封面获取的资料源"
                : showAccount ? "管理云端同步、资料显示与账户功能偏好"
                : "Rinne 默认使用统一存档管理目录，便于统一管理；遇到兼容性问题可关闭并使用游戏原目录。");
        if (showAccount) {
            renderAllAccountChips();
            refreshEmailSubscription();
        }
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
        LauncherTheme.styleSwitch(binding.padKrScopedSwitch);
        LauncherTheme.styleSwitch(binding.padArtemisScopedSwitch);
        LauncherTheme.styleSwitch(binding.padOnsScopedSwitch);
        LauncherTheme.styleSwitch(binding.padOnsStretchSwitch);
        LauncherTheme.styleSwitch(binding.padOnsCutoutSwitch);
        LauncherTheme.styleSwitch(binding.padOnsDisableVideoSwitch);
        LauncherTheme.styleSwitch(binding.padOnsSharpnessSwitch);
        LauncherTheme.styleSwitch(binding.padTyranoScopedSwitch);
        LauncherTheme.formInputs(binding.padOnsSharpnessValueInput);
        PadDialogFactory.secondaryInlineAction(binding.padNativeKrkrButton);
        PadDialogFactory.secondaryInlineAction(binding.padKrkrCancelButton);
        PadDialogFactory.primaryInlineAction(binding.padKrkrSaveButton);
        LauncherTheme.textPrimary(binding.padMetadataTokenLink);
        PadDialogFactory.secondaryInlineAction(binding.padMetadataCancelButton);
        PadDialogFactory.primaryInlineAction(binding.padMetadataSaveButton);
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
        binding.padXinhaitianThemeLogo.setBackground(LauncherTheme.xinhaitianCircle(this));
        binding.padXinhaitianThemeLogo.setClipToOutline(true);
        binding.padParticleToggleIcon.setBackground(LauncherTheme.circle(this));
        binding.padParticleToggleIcon.setTextColor(LauncherTheme.onPrimary(this));
        PadDialogFactory.primaryInlineAction(binding.padThemeApply);
    }

    private void selectTheme(String themeName) {
        selectedTheme = themeName;
        renderThemeSelection();
    }

    private void renderThemeSelection() {
        boolean freshSelected = THEME_DEFAULT_LABEL.equals(selectedTheme);
        boolean rinneSelected = THEME_RINNE_LABEL.equals(selectedTheme);
        boolean anriSelected = THEME_ANRI_LABEL.equals(selectedTheme);
        boolean xinhaitianSelected = THEME_XINHAITIAN_LABEL.equals(selectedTheme);
        styleThemeRow(binding.padFreshThemeRow, freshSelected);
        styleThemeRow(binding.padRinneThemeRow, rinneSelected);
        styleThemeRow(binding.padAnriThemeRow, anriSelected);
        styleThemeRow(binding.padXinhaitianThemeRow, xinhaitianSelected);
        binding.padFreshThemeCheck.setVisibility(freshSelected ? View.VISIBLE : View.INVISIBLE);
        binding.padRinneThemeCheck.setVisibility(rinneSelected ? View.VISIBLE : View.INVISIBLE);
        binding.padAnriThemeCheck.setVisibility(anriSelected ? View.VISIBLE : View.INVISIBLE);
        binding.padXinhaitianThemeCheck.setVisibility(xinhaitianSelected ? View.VISIBLE : View.INVISIBLE);
        int primary = LauncherTheme.primary(this);
        binding.padFreshThemeCheck.setTextColor(primary);
        binding.padRinneThemeCheck.setTextColor(primary);
        binding.padAnriThemeCheck.setTextColor(primary);
        binding.padXinhaitianThemeCheck.setTextColor(primary);
    }

    private void styleThemeRow(View row, boolean selected) {
        if (selected) {
            row.setBackground(LauncherTheme.selectedOption(this));
        } else {
            row.setBackgroundResource(R.drawable.launcher_chat_option_bg);
        }
    }

    private void renderParticleToggle() {
        binding.padParticleToggleState.setText("设置");
        LauncherTheme.chip(binding.padParticleToggleState, true);
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
        root.setBackground(LauncherTheme.secondaryButton(this, 20f));

        TextView title = dialogButton("动态粒子样式", false);
        title.setTextSize(16);
        title.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_color));
        title.setBackground(null);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(32)));

        boolean enabled = LauncherActivity.isLauncherParticlesEnabled(this);
        String selectedStyle = LauncherActivity.getLauncherParticleStyle(this);
        addParticleStyleOption(root, dialog, "漂浮光点",
                LauncherActivity.PARTICLE_STYLE_FLOATING, enabled, selectedStyle);
        addParticleStyleOption(root, dialog, "斜向雨滴",
                LauncherActivity.PARTICLE_STYLE_RAIN, enabled, selectedStyle);
        addParticleStyleOption(root, dialog, "星星粒子",
                LauncherActivity.PARTICLE_STYLE_STAR, enabled, selectedStyle);

        TextView disable = dialogButton("关闭动态粒子", false);
        disable.setOnClickListener(view -> {
            LauncherActivity.setLauncherParticlesEnabled(this, false);
            renderParticles();
            renderParticleToggle();
            dialog.dismiss();
            Toast.makeText(this, "已关闭动态粒子", Toast.LENGTH_SHORT).show();
        });
        addWithMargin(root, disable, 10);

        TextView cancel = dialogButton("取消", false);
        cancel.setOnClickListener(view -> dialog.dismiss());
        addWithMargin(root, cancel, 8);
        window.setContentView(root);
    }

    private void addParticleStyleOption(LinearLayout root, AlertDialog dialog, String title,
                                        String style, boolean enabled, String selectedStyle) {
        TextView option = dialogButton(title, enabled && style.equals(selectedStyle));
        option.setOnClickListener(view -> {
            LauncherActivity.setLauncherParticleStyle(this, style);
            LauncherActivity.setLauncherParticlesEnabled(this, true);
            renderParticles();
            renderParticleToggle();
            dialog.dismiss();
            Toast.makeText(this, "已应用" + title + "效果", Toast.LENGTH_SHORT).show();
        });
        addWithMargin(root, option, 11);
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
        } else if (THEME_XINHAITIAN_LABEL.equals(selectedTheme)) {
            style = LauncherActivity.THEME_STYLE_XINHAITIAN;
            message = "已应用心海天风格";
        } else {
            style = LauncherActivity.THEME_STYLE_DEFAULT;
            message = "已恢复默认主题";
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        LauncherMotion.recreateWithToneOverlay(this,
                () -> LauncherActivity.setLauncherThemeStyle(this, style));
    }

    private void saveKrkrConfig() {
        int position = selectedEngineVersionIndex;
        String version = LauncherKrkrBridge.ENGINE_VERSION_AUTO;
        if (position == 1) version = LauncherKrkrBridge.ENGINE_VERSION_139;
        else if (position == 2) version = LauncherKrkrBridge.ENGINE_VERSION_134;
        else if (position == 3) version = LauncherKrkrBridge.ENGINE_VERSION_126;

        LauncherKrkrBridge.setEngineVersion(this, version);
        LauncherKrkrBridge.setKrScopedSaveDir(this, binding.padKrScopedSwitch.isChecked());
        LauncherKrkrBridge.setArtemisScopedSaveDir(this, binding.padArtemisScopedSwitch.isChecked());
        OnsSettings onsSettings = OnsSettings.load(this);
        onsSettings.scopedSaveDir = binding.padOnsScopedSwitch.isChecked();
        onsSettings.stretchFull = binding.padOnsStretchSwitch.isChecked();
        onsSettings.ignoreCutout = binding.padOnsCutoutSwitch.isChecked();
        onsSettings.disableVideo = binding.padOnsDisableVideoSwitch.isChecked();
        onsSettings.sharpness = binding.padOnsSharpnessSwitch.isChecked();
        onsSettings.sharpnessValue = binding.padOnsSharpnessValueInput.getText().toString().trim();
        onsSettings.encoding = ONS_ENCODING_LABELS[selectedOnsEncodingIndex];
        onsSettings.save(this);
        LauncherKrkrBridge.setTyranoScopedSaveDir(this, binding.padTyranoScopedSwitch.isChecked());
        Toast.makeText(this, "引擎设置已保存："
                + LauncherKrkrBridge.engineVersionLabel(version), Toast.LENGTH_SHORT).show();
        finish();
    }

    private void enterNativeKrkr() {
        try {
            startActivity(LauncherGameLaunchBridge.buildInternalKrkrOriginIntent(this));
        } catch (Throwable throwable) {
            Toast.makeText(this, "无法进入原生 KRKR", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveMetadataConfig() {
        int position = selectedMetadataSourceIndex;
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

    private void refreshEmailSubscription() {
        if (!LauncherAuthBridge.isLoggedIn(this)) return;
        LauncherAuthBridge.fetchEmailSubscription(this, new LauncherAuthBridge.SubscriptionCallback() {
            @Override
            public void onSuccess(boolean subscribed) {
                if (isFinishing()) return;
                saveEmailSubscription(subscribed);
                renderAccountChip(binding.padChipEmailSubscribe, subscribed);
            }

            @Override
            public void onError(String message) {
                // 保留本地缓存状态；网络错误不影响其他 Pad 设置项。
            }
        });
    }

    private void onEmailSubscriptionClick() {
        if (emailSubscriptionUpdating) return;
        if (!LauncherAuthBridge.isLoggedIn(this)) {
            showAccountResult("需要登录", "登录后才能管理邮件订阅");
            return;
        }
        boolean subscribed = getSharedPreferences(ACCOUNT_SETTINGS_PREFS, MODE_PRIVATE)
                .getBoolean("email_subscribe", false);
        if (subscribed) {
            updateEmailSubscription(false);
            return;
        }
        showAccountConfirmDialog("开启邮件订阅",
                "开启后，管理员可向你的注册邮箱发送系统通知和广播邮件。",
                "开启订阅", () -> updateEmailSubscription(true));
    }

    private void updateEmailSubscription(boolean subscribed) {
        emailSubscriptionUpdating = true;
        binding.padRowEmailSubscribe.setEnabled(false);
        LauncherAuthBridge.updateEmailSubscription(this, subscribed,
                new LauncherAuthBridge.SubscriptionCallback() {
                    @Override
                    public void onSuccess(boolean actualSubscribed) {
                        if (isFinishing()) return;
                        emailSubscriptionUpdating = false;
                        binding.padRowEmailSubscribe.setEnabled(true);
                        saveEmailSubscription(actualSubscribed);
                        renderAccountChip(binding.padChipEmailSubscribe, actualSubscribed);
                        Toast.makeText(PadSettingsActivity.this,
                                actualSubscribed ? "已开启邮件订阅" : "已取消邮件订阅",
                                Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(String message) {
                        if (isFinishing()) return;
                        emailSubscriptionUpdating = false;
                        binding.padRowEmailSubscribe.setEnabled(true);
                        showAccountResult("邮件订阅更新失败", message);
                    }
                });
    }

    private void saveEmailSubscription(boolean subscribed) {
        getSharedPreferences(ACCOUNT_SETTINGS_PREFS, MODE_PRIVATE).edit()
                .putBoolean("email_subscribe", subscribed).apply();
    }

    private void renderAllAccountChips() {
        SharedPreferences prefs = getSharedPreferences(ACCOUNT_SETTINGS_PREFS, MODE_PRIVATE);
        renderAccountChip(binding.padChipSyncConfig,
                prefs.getBoolean("sync_config", accountDefault("sync_config")));
        renderAccountChip(binding.padChipRealtimePlaytime,
                prefs.getBoolean("realtime_playtime", accountDefault("realtime_playtime")));
        renderAccountChip(binding.padChipEmailSubscribe,
                prefs.getBoolean("email_subscribe", accountDefault("email_subscribe")));
    }

    private boolean accountDefault(String key) {
        return "realtime_playtime".equals(key);
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
        PadDialogFactory.showStandardConfirm(this, title, message, confirmText, onConfirm);
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
        return PadDialogFactory.showLoading(this, title, hint);
    }

    private void dismissAccountLoading() {
        if (accountLoadingDialog != null && accountLoadingDialog.isShowing()) {
            accountLoadingDialog.dismiss();
        }
        accountLoadingDialog = null;
    }

    private void showAccountResult(String title, String message) {
        PadDialogFactory.showInfo(this, title, message);
    }

    private LinearLayout dialogRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(20), dp(22), dp(16));
        root.setBackground(LauncherTheme.secondaryButton(this, 20f));
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
        binding.padSettingsParticleView.setParticleStyle(LauncherActivity.getLauncherParticleStyle(this));
        binding.padSettingsParticleView.setParticlesEnabled(enabled);
    }
}
