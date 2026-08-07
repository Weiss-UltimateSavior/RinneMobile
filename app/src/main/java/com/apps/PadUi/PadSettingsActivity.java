package com.apps.PadUi;

import android.content.Context;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.os.LocaleListCompat;

import com.apps.LauncherActivity;
import com.apps.LauncherPreferences;
import com.apps.LauncherThemeStyle;
import com.apps.common.LauncherInsetsHelper;
import com.apps.home.HomeStyle;
import com.apps.theme.LauncherMotion;
import com.apps.sync.LauncherSyncScheduler;
import com.apps.theme.LauncherTheme;
import com.apps.util.LauncherUrlOpener;
import com.core.userdata.LauncherUserData;
import com.core.R;
import com.core.databinding.ActivityPadSettingsBinding;
import com.core.launcherbridge.ConfigCallback;
import com.core.launcherbridge.LauncherAuthBridge;
import com.core.launcherbridge.LauncherGameLaunchBridge;
import com.core.launcherbridge.LauncherKrkrBridge;
import com.core.launcherbridge.LauncherMetadataBridge;
import com.core.launcherbridge.PlayDataCallback;
import com.core.launcherbridge.SubscriptionCallback;
import com.core.metadata.MetadataController;
import com.core.ons.OnsSettings;

/** 横屏设置页，仅提供与 Pad 游戏模式一致的设置入口布局。 */
public class PadSettingsActivity extends AppCompatActivity {
    private enum Section { GENERAL, APPLICATION, THEME, METADATA, ACCOUNT }

    private static final String THEME_DEFAULT_LABEL = LauncherThemeStyle.THEME_STYLE_DEFAULT;
    private static final String THEME_RINNE_LABEL = LauncherThemeStyle.THEME_STYLE_RINNE;
    private static final String THEME_ANRI_LABEL = LauncherThemeStyle.THEME_STYLE_ANRI;
    private static final String THEME_XINHAITIAN_LABEL = LauncherThemeStyle.THEME_STYLE_XINHAITIAN;
    private static final String THEME_NATSUME_LABEL = LauncherThemeStyle.THEME_STYLE_NATSUME;
    private static final String[] ONS_ENCODING_LABELS = {"gbk", "sjis", "utf8"};
    private static final String STATE_ENGINE_VERSION_INDEX = "engine_version_index";
    private static final String STATE_METADATA_SOURCE_INDEX = "metadata_source_index";
    private static final String STATE_ONS_ENCODING_INDEX = "ons_encoding_index";
    private static final String[] LANGUAGE_TAGS = {"zh-CN", "en", "ja"};

    private ActivityPadSettingsBinding binding;
    private Section currentSection = Section.GENERAL;
    private String selectedTheme = THEME_DEFAULT_LABEL;
    private AlertDialog accountLoadingDialog;
    private boolean emailSubscriptionUpdating;
    private boolean kernelSwitchConfirming;
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
        LauncherInsetsHelper.applyInsets(binding.getRoot(), binding.padSettingsContent);
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
        binding.padSettingsSidebarApplication.setOnClickListener(view -> selectSection(Section.APPLICATION));
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
        binding.padNatsumeThemeRow.setOnClickListener(view -> selectTheme(THEME_NATSUME_LABEL));
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
        binding.padAppLanguageText.setOnClickListener(view -> showLanguagePicker());
        binding.padAppStartPageText.setOnClickListener(view -> showStartPagePicker());
        binding.padAppHomeStyleText.setOnClickListener(view -> showHomeStylePicker());
        binding.padAppNavigationStyleText.setOnClickListener(view -> showNavigationStylePicker());
        // krkrsdl3 内核开关：开启需确认（全新引擎内核，稳定性不可预测）。
        binding.padKrEngineKernelSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (kernelSwitchConfirming || !checked) return;
            binding.padKrEngineKernelSwitch.setChecked(false);
            showKrEngineKernelConfirmDialog();
        });
        LauncherTheme.styleMaterialSwitch(binding.padFollowSystemToneSwitch);
        binding.padFollowSystemToneSwitch.setChecked(LauncherActivity.isFollowingSystemTone(this));
        binding.padFollowSystemToneSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (checked != LauncherActivity.isFollowingSystemTone(this)) {
                LauncherActivity.setFollowingSystemTone(this, checked);
            }
        });
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
        String kernel = LauncherKrkrBridge.getEngineKernel(this);
        binding.padKrEngineKernelSwitch.setChecked(LauncherKrkrBridge.KERNEL_KRKRSDL3.equals(kernel));
        binding.padKrScopedSwitch.setChecked(LauncherKrkrBridge.isKrScopedSaveDir(this));
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
        binding.padTyranoExternalNetworkSwitch.setChecked(LauncherKrkrBridge.isTyranoExternalNetworkEnabled(this));
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
        PadDialogFactory.showSingleChoice(this, getString(R.string.pad_select_kr_version), engineVersionLabels(),
                selectedEngineVersionIndex, this::setEngineVersionSelection);
    }

    private void setEngineVersionSelection(int index) {
        String[] labels = engineVersionLabels();
        selectedEngineVersionIndex = index >= 0 && index < labels.length ? index : 0;
        binding.padEngineVersionText.setText(labels[selectedEngineVersionIndex]);
    }

    private void showOnsEncodingPicker() {
        PadDialogFactory.showSingleChoice(this, getString(R.string.pad_select_ons_encoding), ONS_ENCODING_LABELS,
                selectedOnsEncodingIndex, this::setOnsEncodingSelection);
    }

    /** krkrsdl3 内核开关开启确认：全新引擎内核，稳定性不可预测。 */
    private void showKrEngineKernelConfirmDialog() {
        PadDialogFactory.showStandardConfirm(this, getString(R.string.settings_kr_kernel_switch_title),
                getString(R.string.settings_kr_kernel_switch_message),
                getString(R.string.settings_kr_kernel_switch_confirm), () -> {
                    kernelSwitchConfirming = true;
                    binding.padKrEngineKernelSwitch.setChecked(true);
                    kernelSwitchConfirming = false;
                });
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
        PadDialogFactory.showSingleChoice(this, getString(R.string.pad_select_metadata_source), metadataSourceLabels(),
                selectedMetadataSourceIndex, this::setMetadataSourceSelection);
    }

    private void setMetadataSourceSelection(int index) {
        String[] labels = metadataSourceLabels();
        selectedMetadataSourceIndex = index >= 0 && index < labels.length ? index : 0;
        binding.padMetadataSourceText.setText(labels[selectedMetadataSourceIndex]);
    }

    private String[] engineVersionLabels() {
        return getResources().getStringArray(R.array.engine_version_options);
    }

    private String[] metadataSourceLabels() {
        return new String[] {
                getString(R.string.settings_metadata_vndb_default),
                getString(R.string.pad_metadata_bangumi),
                getString(R.string.pad_metadata_bangumi_mirror),
                getString(R.string.pad_metadata_ymgal)
        };
    }

    private void showLanguagePicker() {
        String[] labels = languageLabels();
        PadDialogFactory.showSingleChoice(this, getString(R.string.app_language_dialog_title), labels,
                currentLanguageIndex(), index -> {
                    int safeIndex = index >= 0 && index < LANGUAGE_TAGS.length ? index : 0;
                    AppCompatDelegate.setApplicationLocales(
                            LocaleListCompat.forLanguageTags(LANGUAGE_TAGS[safeIndex]));
                });
    }

    private void showStartPagePicker() {
        String[] labels = {
                getString(R.string.app_start_page_portrait),
                getString(R.string.app_start_page_landscape)
        };
        PadDialogFactory.showSingleChoice(this, getString(R.string.app_start_page_dialog_title), labels,
                LauncherActivity.isLandscapeStartupPage(this) ? 1 : 0, index -> {
                    LauncherActivity.setLandscapeStartupPage(this, index == 1);
                    renderApplicationSettings();
                });
    }

    private void showHomeStylePicker() {
        HomeStyle[] styles = HomeStyle.values();
        String[] labels = new String[styles.length];
        int selectedIndex = 0;
        HomeStyle selectedStyle = LauncherActivity.getHomeStyle(this);
        for (int i = 0; i < styles.length; i++) {
            labels[i] = getString(styles[i].getLabelResId());
            if (styles[i] == selectedStyle) selectedIndex = i;
        }
        PadDialogFactory.showSingleChoice(this, getString(R.string.app_home_style_dialog_title), labels,
                selectedIndex, index -> {
                    LauncherActivity.setHomeStyle(this, styles[index]);
                    finish();
                });
    }

    private void showNavigationStylePicker() {
        String[] labels = {
                getString(R.string.app_navigation_style_default),
                getString(R.string.app_navigation_style_pill),
                getString(R.string.app_navigation_style_card),
                getString(R.string.app_navigation_style_liquid_glass)
        };
        PadDialogFactory.showSingleChoice(this, getString(R.string.app_navigation_style_dialog_title), labels,
                currentNavigationStyleIndex(), index -> {
                    LauncherActivity.setPillNavigationStyle(this, index == 1);
                    LauncherActivity.setCardNavigationStyle(this, index == 2);
                    LauncherActivity.setLiquidGlassNavigationStyle(this, index == 3);
                    finish();
                });
    }

    private void renderApplicationSettings() {
        if (binding == null) return;
        binding.padAppLanguageText.setText(languageLabels()[currentLanguageIndex()]);
        binding.padAppStartPageText.setText(LauncherActivity.isLandscapeStartupPage(this)
                ? R.string.app_start_page_landscape : R.string.app_start_page_portrait);
        binding.padAppHomeStyleText.setText(LauncherActivity.getHomeStyle(this).getLabelResId());
        int navigationLabel = R.string.app_navigation_style_default;
        if (LauncherActivity.isLiquidGlassNavigationStyle(this)) {
            navigationLabel = R.string.app_navigation_style_liquid_glass;
        } else if (LauncherActivity.isCardNavigationStyle(this)) {
            navigationLabel = R.string.app_navigation_style_card;
        } else if (LauncherActivity.isPillNavigationStyle(this)) {
            navigationLabel = R.string.app_navigation_style_pill;
        }
        binding.padAppNavigationStyleText.setText(navigationLabel);
        binding.padFollowSystemToneSwitch.setChecked(LauncherActivity.isFollowingSystemTone(this));
    }

    private int currentNavigationStyleIndex() {
        if (LauncherActivity.isLiquidGlassNavigationStyle(this)) return 3;
        if (LauncherActivity.isCardNavigationStyle(this)) return 2;
        if (LauncherActivity.isPillNavigationStyle(this)) return 1;
        return 0;
    }

    private String[] languageLabels() {
        return new String[] {
                getString(R.string.language_simplified_chinese),
                getString(R.string.language_english),
                getString(R.string.language_japanese)
        };
    }

    private int currentLanguageIndex() {
        LocaleListCompat appLocales = AppCompatDelegate.getApplicationLocales();
        String language = !appLocales.isEmpty() && appLocales.get(0) != null
                ? appLocales.get(0).getLanguage()
                : getResources().getConfiguration().getLocales().get(0).getLanguage();
        if ("en".equals(language)) return 1;
        if ("ja".equals(language)) return 2;
        return 0;
    }

    private void restoreSelectedTheme() {
        String style = LauncherActivity.getLauncherThemeStyle(this);
        if (LauncherThemeStyle.THEME_STYLE_RINNE.equals(style)) {
            selectedTheme = THEME_RINNE_LABEL;
        } else if (LauncherThemeStyle.THEME_STYLE_ANRI.equals(style)) {
            selectedTheme = THEME_ANRI_LABEL;
        } else if (LauncherThemeStyle.THEME_STYLE_XINHAITIAN.equals(style)) {
            selectedTheme = THEME_XINHAITIAN_LABEL;
        } else if (LauncherThemeStyle.THEME_STYLE_NATSUME.equals(style)) {
            selectedTheme = THEME_NATSUME_LABEL;
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
        boolean showApplication = section == Section.APPLICATION;
        boolean showMetadata = section == Section.METADATA;
        boolean showAccount = section == Section.ACCOUNT;
        binding.padSettingsGeneralActionList.setVisibility(
                section == Section.GENERAL ? View.VISIBLE : View.GONE);
        binding.padSettingsApplicationActionList.setVisibility(showApplication ? View.VISIBLE : View.GONE);
        binding.padSettingsThemeActionList.setVisibility(showTheme ? View.VISIBLE : View.GONE);
        binding.padSettingsMetadataActionList.setVisibility(showMetadata ? View.VISIBLE : View.GONE);
        binding.padSettingsAccountActionList.setVisibility(showAccount ? View.VISIBLE : View.GONE);
        binding.padSettingsActionScroll.scrollTo(0, 0);
        binding.padSettingsPageTitle.setText(showApplication ? R.string.app_settings_title
                : showTheme ? R.string.pad_theme_settings
                : showMetadata ? R.string.settings_cover_title
                : showAccount ? R.string.settings_account_title : R.string.settings_engine_title);
        binding.padSettingsPageDescription.setText(showApplication
                ? getString(R.string.pad_app_settings_summary)
                : showTheme
                ? getString(R.string.pad_theme_page_summary)
                : showMetadata ? getString(R.string.settings_metadata_summary)
                : showAccount ? getString(R.string.pad_account_page_summary)
                : getString(R.string.settings_engine_summary));
        if (showAccount) {
            renderAllAccountChips();
            refreshEmailSubscription();
        }
        applyTheme();
    }

    // Pad 横屏全出血窗口：系统栏着色为页面背景色（LauncherTheme.bg）+ 刘海短边裁切 +
    // 关闭对比度增强。与 LauncherEdgeToEdgeHelper（透明状态栏 + 明暗自适应）语义不同，
    // 故不走 helper（豁免，见 agent.md §8 grep 监控与重构计划 4.7 项 2）。
    private void configureLandscapeWindow() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        int background = LauncherTheme.bg(this);
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

    private void applyTheme() {
        if (binding == null) return;
        LauncherTheme.applyPrimaryTone(binding.getRoot());
        styleSidebarItem(binding.padSettingsSidebarGeneral, currentSection == Section.GENERAL);
        styleSidebarItem(binding.padSettingsSidebarApplication, currentSection == Section.APPLICATION);
        styleSidebarItem(binding.padSettingsSidebarTheme, currentSection == Section.THEME);
        styleSidebarItem(binding.padSettingsSidebarMetadata, currentSection == Section.METADATA);
        styleSidebarItem(binding.padSettingsSidebarAccount, currentSection == Section.ACCOUNT);
        LauncherTheme.secondaryButton(binding.padSettingsBackButton);
        LauncherTheme.textPrimary(binding.padSettingsPageTitle);
        LauncherTheme.styleMaterialSwitch(binding.padKrScopedSwitch);
        LauncherTheme.styleMaterialSwitch(binding.padKrEngineKernelSwitch);
        LauncherTheme.styleMaterialSwitch(binding.padOnsScopedSwitch);
        LauncherTheme.styleMaterialSwitch(binding.padOnsStretchSwitch);
        LauncherTheme.styleMaterialSwitch(binding.padOnsCutoutSwitch);
        LauncherTheme.styleMaterialSwitch(binding.padOnsDisableVideoSwitch);
        LauncherTheme.styleMaterialSwitch(binding.padOnsSharpnessSwitch);
        LauncherTheme.styleMaterialSwitch(binding.padTyranoScopedSwitch);
        LauncherTheme.styleMaterialSwitch(binding.padTyranoExternalNetworkSwitch);
        LauncherTheme.styleMaterialSwitch(binding.padFollowSystemToneSwitch);
        LauncherTheme.styleMaterialSwitch(binding.padChipSyncConfig);
        LauncherTheme.styleMaterialSwitch(binding.padChipRealtimePlaytime);
        LauncherTheme.styleMaterialSwitch(binding.padChipEmailSubscribe);
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
        renderApplicationSettings();
    }

    private void styleSidebarItem(TextView item, boolean selected) {
        if (selected) {
            item.setBackground(LauncherTheme.selectedChip(this));
            item.setTextColor(LauncherTheme.onPrimary(this));
        } else {
            item.setBackground(null);
            item.setTextColor(LauncherTheme.text(this));
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
                LauncherTheme.circle(this, LauncherThemeStyle.RINNE_PRIMARY_COLOR));
        binding.padRinneThemeLogo.setClipToOutline(true);
        binding.padAnriThemeLogo.setBackground(
                LauncherTheme.circle(this, LauncherThemeStyle.ANRI_PRIMARY_COLOR));
        binding.padAnriThemeLogo.setClipToOutline(true);
        binding.padXinhaitianThemeLogo.setBackground(LauncherTheme.xinhaitianCircle(this));
        binding.padXinhaitianThemeLogo.setClipToOutline(true);
        binding.padNatsumeThemeLogo.setBackground(LauncherTheme.circle(this, LauncherThemeStyle.NATSUME_PRIMARY_COLOR));
        binding.padNatsumeThemeLogo.setClipToOutline(true);
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
        boolean natsumeSelected = THEME_NATSUME_LABEL.equals(selectedTheme);
        styleThemeRow(binding.padFreshThemeRow, freshSelected);
        styleThemeRow(binding.padRinneThemeRow, rinneSelected);
        styleThemeRow(binding.padAnriThemeRow, anriSelected);
        styleThemeRow(binding.padXinhaitianThemeRow, xinhaitianSelected);
        styleThemeRow(binding.padNatsumeThemeRow, natsumeSelected);
        binding.padFreshThemeCheck.setVisibility(freshSelected ? View.VISIBLE : View.INVISIBLE);
        binding.padRinneThemeCheck.setVisibility(rinneSelected ? View.VISIBLE : View.INVISIBLE);
        binding.padAnriThemeCheck.setVisibility(anriSelected ? View.VISIBLE : View.INVISIBLE);
        binding.padXinhaitianThemeCheck.setVisibility(xinhaitianSelected ? View.VISIBLE : View.INVISIBLE);
        binding.padNatsumeThemeCheck.setVisibility(natsumeSelected ? View.VISIBLE : View.INVISIBLE);
        int primary = LauncherTheme.primary(this);
        binding.padFreshThemeCheck.setTextColor(primary);
        binding.padRinneThemeCheck.setTextColor(primary);
        binding.padAnriThemeCheck.setTextColor(primary);
        binding.padXinhaitianThemeCheck.setTextColor(primary);
        binding.padNatsumeThemeCheck.setTextColor(primary);
    }

    private void styleThemeRow(View row, boolean selected) {
        if (selected) {
            row.setBackground(LauncherTheme.selectedOption(this));
        } else {
            row.setBackgroundResource(R.drawable.launcher_chat_option_bg);
        }
    }

    private void renderParticleToggle() {
        binding.padParticleToggleState.setText(R.string.pad_particle_settings);
        LauncherTheme.chip(binding.padParticleToggleState, true);
    }

    private void showParticleStyleDialog() {
        String[] styles = {
                LauncherPreferences.PARTICLE_STYLE_FLOATING,
                LauncherPreferences.PARTICLE_STYLE_RAIN,
                LauncherPreferences.PARTICLE_STYLE_STAR,
                LauncherPreferences.PARTICLE_STYLE_SAKURA,
                LauncherPreferences.PARTICLE_STYLE_FIREFLIES,
                LauncherPreferences.PARTICLE_STYLE_CONSTELLATION,
                LauncherPreferences.PARTICLE_STYLE_RIPPLES
        };
        String[] labels = getResources().getStringArray(R.array.pad_particle_style_labels);
        boolean enabled = LauncherActivity.isLauncherParticlesEnabled(this);
        String selectedStyle = LauncherActivity.getLauncherParticleStyle(this);
        int checkedIndex = styles.length; // 关闭位置 = 7
        if (enabled) {
            for (int i = 0; i < styles.length; i++) {
                if (styles[i].equals(selectedStyle)) {
                    checkedIndex = i;
                    break;
                }
            }
        }
        PadDialogFactory.showSingleChoice(this, getString(R.string.pad_particle_style_title), labels, checkedIndex, index -> {
            if (index >= styles.length) {
                LauncherActivity.setLauncherParticlesEnabled(this, false);
                renderParticles();
                renderParticleToggle();
                Toast.makeText(this, R.string.pad_particles_disabled, Toast.LENGTH_SHORT).show();
                return;
            }
            LauncherActivity.setLauncherParticleStyle(this, styles[index]);
            LauncherActivity.setLauncherParticlesEnabled(this, true);
            renderParticles();
            renderParticleToggle();
            Toast.makeText(this, getString(R.string.pad_particle_applied, labels[index]), Toast.LENGTH_SHORT).show();
        });
    }

    private void applySelectedTheme() {
        final String style;
        final String message;
        if (THEME_RINNE_LABEL.equals(selectedTheme)) {
            style = LauncherThemeStyle.THEME_STYLE_RINNE;
            message = getString(R.string.pad_theme_rinne_applied);
        } else if (THEME_ANRI_LABEL.equals(selectedTheme)) {
            style = LauncherThemeStyle.THEME_STYLE_ANRI;
            message = getString(R.string.pad_theme_anri_applied);
        } else if (THEME_XINHAITIAN_LABEL.equals(selectedTheme)) {
            style = LauncherThemeStyle.THEME_STYLE_XINHAITIAN;
            message = getString(R.string.pad_theme_xinhaitian_applied);
        } else if (THEME_NATSUME_LABEL.equals(selectedTheme)) {
            style = LauncherThemeStyle.THEME_STYLE_NATSUME;
            message = getString(R.string.pad_theme_natsume_applied);
        } else {
            style = LauncherThemeStyle.THEME_STYLE_DEFAULT;
            message = getString(R.string.pad_theme_default_restored);
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
        String kernel = binding.padKrEngineKernelSwitch.isChecked()
                ? LauncherKrkrBridge.KERNEL_KRKRSDL3 : LauncherKrkrBridge.KERNEL_AUTO;
        LauncherKrkrBridge.setEngineKernel(this, kernel);
        LauncherKrkrBridge.setKrScopedSaveDir(this, binding.padKrScopedSwitch.isChecked());
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
        LauncherKrkrBridge.setTyranoExternalNetworkEnabled(this, binding.padTyranoExternalNetworkSwitch.isChecked());
        Toast.makeText(this, getString(R.string.pad_engine_saved,
                LauncherKrkrBridge.engineVersionLabel(version)), Toast.LENGTH_SHORT).show();
        finish();
    }

    private void enterNativeKrkr() {
        try {
            startActivity(LauncherGameLaunchBridge.buildInternalKrkrOriginIntent(this));
        } catch (ActivityNotFoundException | IllegalArgumentException throwable) {
            Toast.makeText(this, R.string.pad_native_krkr_failed, Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, R.string.pad_bangumi_token_required, Toast.LENGTH_SHORT).show();
            return;
        }
        LauncherMetadataBridge.setMetadataSource(this, source);
        LauncherMetadataBridge.setBangumiToken(this, token);
        Toast.makeText(this, getString(R.string.pad_metadata_saved, LauncherMetadataBridge.sourceLabel(source)),
                Toast.LENGTH_SHORT).show();
        finish();
    }

    private void openMetadataTokenUrl() {
        if (!LauncherUrlOpener.open(this, "https://next.bgm.tv/demo/access-token/create")) {
            Toast.makeText(this, R.string.home_cannot_open_link, Toast.LENGTH_SHORT).show();
        }
    }

    private void onSyncConfigClick() {
        SharedPreferences prefs = getSharedPreferences(LauncherPreferences.ACCOUNT_SETTINGS_PREFS, MODE_PRIVATE);
        if (prefs.getBoolean("sync_config", false)) {
            prefs.edit().putBoolean("sync_config", false).apply();
            renderAccountChip(binding.padChipSyncConfig, false);
            LauncherSyncScheduler.updateSchedule(this);
            return;
        }
        showAccountConfirmDialog(getString(R.string.pad_config_sync), getString(R.string.pad_config_sync_message),
                getString(R.string.pad_confirm_upload), this::enableSyncAndUpload);
    }

    private void onRealtimePlaytimeClick() {
        SharedPreferences prefs = getSharedPreferences(LauncherPreferences.ACCOUNT_SETTINGS_PREFS, MODE_PRIVATE);
        if (prefs.getBoolean("realtime_playtime", accountDefault("realtime_playtime"))) {
            prefs.edit().putBoolean("realtime_playtime", false).apply();
            renderAccountChip(binding.padChipRealtimePlaytime, false);
            return;
        }
        showAccountConfirmDialog(getString(R.string.pad_realtime_playtime),
                getString(R.string.pad_realtime_playtime_message),
                getString(R.string.pad_confirm_enable), () -> {
                    getSharedPreferences(LauncherPreferences.ACCOUNT_SETTINGS_PREFS, MODE_PRIVATE).edit()
                            .putBoolean("realtime_playtime", true).apply();
                    renderAccountChip(binding.padChipRealtimePlaytime, true);
                });
    }

    private void refreshEmailSubscription() {
        if (!LauncherAuthBridge.isLoggedIn(this)) return;
        LauncherAuthBridge.fetchEmailSubscription(this, new SubscriptionCallback() {
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
            showAccountResult(getString(R.string.pad_login_required), getString(R.string.pad_login_for_email));
            return;
        }
        boolean subscribed = getSharedPreferences(LauncherPreferences.ACCOUNT_SETTINGS_PREFS, MODE_PRIVATE)
                .getBoolean("email_subscribe", false);
        if (subscribed) {
            updateEmailSubscription(false);
            return;
        }
        showAccountConfirmDialog(getString(R.string.pad_enable_email),
                getString(R.string.pad_enable_email_message),
                getString(R.string.pad_enable_subscription), () -> updateEmailSubscription(true));
    }

    private void updateEmailSubscription(boolean subscribed) {
        emailSubscriptionUpdating = true;
        binding.padRowEmailSubscribe.setEnabled(false);
        LauncherAuthBridge.updateEmailSubscription(this, subscribed,
                new SubscriptionCallback() {
                    @Override
                    public void onSuccess(boolean actualSubscribed) {
                        if (isFinishing()) return;
                        emailSubscriptionUpdating = false;
                        binding.padRowEmailSubscribe.setEnabled(true);
                        saveEmailSubscription(actualSubscribed);
                        renderAccountChip(binding.padChipEmailSubscribe, actualSubscribed);
                        Toast.makeText(PadSettingsActivity.this,
                                actualSubscribed ? R.string.pad_email_enabled : R.string.pad_email_disabled,
                                Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(String message) {
                        if (isFinishing()) return;
                        emailSubscriptionUpdating = false;
                        binding.padRowEmailSubscribe.setEnabled(true);
                        showAccountResult(getString(R.string.pad_email_update_failed), message);
                    }
                });
    }

    private void saveEmailSubscription(boolean subscribed) {
        getSharedPreferences(LauncherPreferences.ACCOUNT_SETTINGS_PREFS, MODE_PRIVATE).edit()
                .putBoolean("email_subscribe", subscribed).apply();
    }

    private void renderAllAccountChips() {
        SharedPreferences prefs = getSharedPreferences(LauncherPreferences.ACCOUNT_SETTINGS_PREFS, MODE_PRIVATE);
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

    private void renderAccountChip(SwitchCompat chip, boolean enabled) {
        chip.setChecked(enabled);
    }

    private void showAccountConfirmDialog(String title, String message, String confirmText,
                                          Runnable onConfirm) {
        PadDialogFactory.showStandardConfirm(this, title, message, confirmText, onConfirm);
    }

    private void enableSyncAndUpload() {
        getSharedPreferences(LauncherPreferences.ACCOUNT_SETTINGS_PREFS, MODE_PRIVATE).edit()
                .putBoolean("sync_config", true).apply();
        renderAccountChip(binding.padChipSyncConfig, true);
        LauncherSyncScheduler.updateSchedule(this);
        accountLoadingDialog = showAccountLoading(
                getString(R.string.pad_uploading_config),
                getString(R.string.pad_uploading_config_hint));
        String settingsJson = LauncherUserData.exportSettingsJson(this);
        LauncherAuthBridge.uploadConfig(this, settingsJson, new ConfigCallback() {
            @Override
            public void onSuccess(String configJson) {
                String playData = LauncherUserData.exportCloudPlayData(PadSettingsActivity.this);
                if (playData == null || playData.trim().isEmpty()) {
                    dismissAccountLoading();
                    showAccountResult(getString(R.string.pad_partial_upload_failed),
                            getString(R.string.pad_local_export_failed));
                    return;
                }
                LauncherAuthBridge.uploadPlayData(PadSettingsActivity.this, playData,
                        new PlayDataCallback() {
                            @Override
                            public void onSuccess(String playData) {
                                dismissAccountLoading();
                                showAccountResult(getString(R.string.pad_upload_success),
                                        getString(R.string.pad_upload_success_message));
                            }

                            @Override
                            public void onError(String message) {
                                dismissAccountLoading();
                                showAccountResult(getString(R.string.pad_partial_upload_failed),
                                        getString(R.string.pad_playdata_upload_failed, message));
                            }
                        });
            }

            @Override
            public void onError(String message) {
                dismissAccountLoading();
                showAccountResult(getString(R.string.pad_upload_failed), message);
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

    private void renderParticles() {
        if (binding == null) return;
        boolean enabled = LauncherActivity.isLauncherParticlesEnabled(this);
        binding.padSettingsParticleView.setVisibility(enabled ? View.VISIBLE : View.GONE);
        binding.padSettingsParticleView.setParticleStyle(LauncherActivity.getLauncherParticleStyle(this));
        binding.padSettingsParticleView.setParticlesEnabled(enabled);
    }
}
