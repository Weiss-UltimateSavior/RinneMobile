package com.apps.settings;

import android.content.ActivityNotFoundException;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.core.R;
import com.core.databinding.ActivityLauncherKrkrSettingsBinding;
import com.core.launcherbridge.LauncherGameLaunchBridge;
import com.core.launcherbridge.LauncherKrkrBridge;
import com.core.launcherbridge.LauncherOnsGameSettingsBridge;
import com.core.launcherbridge.LauncherRepositoryBridge;
import com.core.model.Game;
import com.core.ons.OnsSettings;
import com.apps.LauncherActivity;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherTabletPortraitScaler;
import com.core.util.DevLogger;

public class LauncherKrkrSettingsActivity extends AppCompatActivity {
    public static final String EXTRA_GAME_ID = "extra_game_id";

    private static final String[] ONS_ENCODING_LABELS = {"gbk", "sjis", "utf8"};
    private static final String STATE_ENGINE_VERSION_INDEX = "engine_version_index";
    private static final String STATE_ONS_ENCODING_INDEX = "ons_encoding_index";
    private ActivityLauncherKrkrSettingsBinding binding;
    private int selectedEngineVersionIndex;
    private int selectedOnsEncodingIndex;
    private boolean restoreEngineVersionSelection;
    private long gameId;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherActivity.applySavedToneMode(this);
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();

        gameId = getIntent().getLongExtra(EXTRA_GAME_ID, 0L);

        binding = ActivityLauncherKrkrSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        LauncherTabletPortraitScaler.applyActivityContent(this);
        applySystemBarInsets();
        bindActions();
        applyThemeTone();
        if (isPerGameMode()) {
            applyPerGameLayout();
        }
        loadConfig(savedInstanceState);
    }

    private boolean isPerGameMode() {
        return gameId > 0L;
    }

    /** Per-game 模式下隐藏与 ONS 无关的全局区段，仅保留 ONS 配置。 */
    private void applyPerGameLayout() {
        binding.krVersionSection.setVisibility(View.GONE);
        binding.krScopedSection.setVisibility(View.GONE);
        binding.artemisScopedSection.setVisibility(View.GONE);
        binding.tyranoScopedSection.setVisibility(View.GONE);
        binding.tyranoExternalNetworkSection.setVisibility(View.GONE);
        binding.btnNativeKrkr.setText(R.string.settings_restore_global_defaults);
        binding.btnNativeKrkr.setOnClickListener(v -> clearPerGameSettings());

        Game game = LauncherRepositoryBridge.findGameById(this, gameId);
        String title = (game != null && game.title != null && !game.title.trim().isEmpty())
                ? game.title.trim() : getString(R.string.settings_ons_engine_title);
        binding.krkrSectionTitle.setText(title);
        binding.krkrSectionDescription.setText(R.string.settings_ons_game_override_summary);
    }

    private void clearPerGameSettings() {
        LauncherOnsGameSettingsBridge.clearOverride(this, gameId);
        Toast.makeText(this, R.string.settings_ons_global_restored, Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_ENGINE_VERSION_INDEX, selectedEngineVersionIndex);
        outState.putInt(STATE_ONS_ENCODING_INDEX, selectedOnsEncodingIndex);
    }

    private void applySystemBarInsets() {
        int left = binding.krkrScroll.getPaddingLeft();
        int top = binding.krkrScroll.getPaddingTop();
        int right = binding.krkrScroll.getPaddingRight();
        int bottom = binding.krkrScroll.getPaddingBottom();
        binding.krkrScroll.setOnApplyWindowInsetsListener((view, insets) -> {
            binding.krkrScroll.setPadding(left, top + insets.getSystemWindowInsetTop(), right, bottom);
            return insets;
        });
        binding.krkrScroll.requestApplyInsets();
    }

    private void bindActions() {
        binding.btnSave.setOnClickListener(v -> save());
        binding.btnCancel.setOnClickListener(v -> finish());
        binding.btnNativeKrkr.setOnClickListener(v -> enterNativeKrkr());
        binding.engineVersionText.setOnClickListener(v -> showEngineVersionPicker());
        binding.onsEncodingText.setOnClickListener(v -> showOnsEncodingPicker());
    }

    private void applyThemeTone() {
        LauncherTheme.styleMaterialSwitch(binding.krScopedSwitch);
        LauncherTheme.styleMaterialSwitch(binding.artemisScopedSwitch);
        LauncherTheme.styleMaterialSwitch(binding.onsScopedSwitch);
        LauncherTheme.styleMaterialSwitch(binding.onsStretchSwitch);
        LauncherTheme.styleMaterialSwitch(binding.onsCutoutSwitch);
        LauncherTheme.styleMaterialSwitch(binding.onsDisableVideoSwitch);
        LauncherTheme.styleMaterialSwitch(binding.onsSharpnessSwitch);
        LauncherTheme.styleMaterialSwitch(binding.tyranoScopedSwitch);
        LauncherTheme.styleMaterialSwitch(binding.tyranoExternalNetworkSwitch);
        LauncherTheme.formInputs(binding.onsSharpnessValueInput);
        LauncherTheme.applyPrimaryTone(binding.getRoot());
        LauncherTheme.longActionButton(binding.btnNativeKrkr);
        LauncherTheme.longActionButton(binding.btnSave);
        LauncherTheme.longActionButton(binding.btnCancel);
    }

    private void loadConfig(@Nullable Bundle savedInstanceState) {
        String version = LauncherKrkrBridge.getEngineVersion(this);
        int selection = 0;
        if (LauncherKrkrBridge.ENGINE_VERSION_139.equals(version)) selection = 1;
        else if (LauncherKrkrBridge.ENGINE_VERSION_134.equals(version)) selection = 2;
        else if (LauncherKrkrBridge.ENGINE_VERSION_126.equals(version)) selection = 3;
        restoreEngineVersionSelection = savedInstanceState != null
                && savedInstanceState.containsKey(STATE_ENGINE_VERSION_INDEX);
        setEngineVersionSelection(restoreEngineVersionSelection
                ? savedInstanceState.getInt(STATE_ENGINE_VERSION_INDEX, 0) : selection);
        binding.krScopedSwitch.setChecked(LauncherKrkrBridge.isKrScopedSaveDir(this));
        binding.artemisScopedSwitch.setChecked(LauncherKrkrBridge.isArtemisScopedSaveDir(this));
        OnsSettings onsSettings = isPerGameMode()
                ? LauncherOnsGameSettingsBridge.load(this, gameId)
                : OnsSettings.load(this);
        binding.onsScopedSwitch.setChecked(onsSettings.scopedSaveDir);
        binding.onsStretchSwitch.setChecked(onsSettings.stretchFull);
        binding.onsCutoutSwitch.setChecked(onsSettings.ignoreCutout);
        binding.onsDisableVideoSwitch.setChecked(onsSettings.disableVideo);
        binding.onsSharpnessSwitch.setChecked(onsSettings.sharpness);
        binding.onsSharpnessValueInput.setText(onsSettings.sharpnessValue);
        int onsEncodingIndex = onsEncodingIndex(onsSettings.encoding);
        if (savedInstanceState != null && savedInstanceState.containsKey(STATE_ONS_ENCODING_INDEX)) {
            onsEncodingIndex = savedInstanceState.getInt(STATE_ONS_ENCODING_INDEX, onsEncodingIndex);
        }
        setOnsEncodingSelection(onsEncodingIndex);
        binding.tyranoScopedSwitch.setChecked(LauncherKrkrBridge.isTyranoScopedSaveDir(this));
        binding.tyranoExternalNetworkSwitch.setChecked(LauncherKrkrBridge.isTyranoExternalNetworkEnabled(this));
    }

    private void save() {
        int pos = selectedEngineVersionIndex;
        String version = LauncherKrkrBridge.ENGINE_VERSION_AUTO;
        if (pos == 1) version = LauncherKrkrBridge.ENGINE_VERSION_139;
        else if (pos == 2) version = LauncherKrkrBridge.ENGINE_VERSION_134;
        else if (pos == 3) version = LauncherKrkrBridge.ENGINE_VERSION_126;

        if (isPerGameMode()) {
            // Per-game 模式：仅写入该游戏的 ONS 覆盖；KR/Tyrano/Artemis 等全局项保持原值。
            OnsSettings perGame = LauncherOnsGameSettingsBridge.load(this, gameId);
            perGame.scopedSaveDir = binding.onsScopedSwitch.isChecked();
            perGame.stretchFull = binding.onsStretchSwitch.isChecked();
            perGame.ignoreCutout = binding.onsCutoutSwitch.isChecked();
            perGame.disableVideo = binding.onsDisableVideoSwitch.isChecked();
            perGame.sharpness = binding.onsSharpnessSwitch.isChecked();
            perGame.sharpnessValue = binding.onsSharpnessValueInput.getText().toString().trim();
            perGame.encoding = ONS_ENCODING_LABELS[selectedOnsEncodingIndex];
            LauncherOnsGameSettingsBridge.save(this, gameId, perGame);
            Toast.makeText(this, R.string.settings_ons_game_saved, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        LauncherKrkrBridge.setEngineVersion(this, version);
        LauncherKrkrBridge.setKrScopedSaveDir(this, binding.krScopedSwitch.isChecked());
        LauncherKrkrBridge.setArtemisScopedSaveDir(this, binding.artemisScopedSwitch.isChecked());
        OnsSettings onsSettings = OnsSettings.load(this);
        onsSettings.scopedSaveDir = binding.onsScopedSwitch.isChecked();
        onsSettings.stretchFull = binding.onsStretchSwitch.isChecked();
        onsSettings.ignoreCutout = binding.onsCutoutSwitch.isChecked();
        onsSettings.disableVideo = binding.onsDisableVideoSwitch.isChecked();
        onsSettings.sharpness = binding.onsSharpnessSwitch.isChecked();
        onsSettings.sharpnessValue = binding.onsSharpnessValueInput.getText().toString().trim();
        onsSettings.encoding = ONS_ENCODING_LABELS[selectedOnsEncodingIndex];
        onsSettings.save(this);
        LauncherKrkrBridge.setTyranoScopedSaveDir(this, binding.tyranoScopedSwitch.isChecked());
        LauncherKrkrBridge.setTyranoExternalNetworkEnabled(this, binding.tyranoExternalNetworkSwitch.isChecked());

        Toast.makeText(this, getString(R.string.settings_engine_saved,
                engineVersionLabels()[selectedEngineVersionIndex]), Toast.LENGTH_SHORT).show();
        finish();
    }

    private void showEngineVersionPicker() {
        com.apps.theme.LauncherDialogFactory.showSingleChoice(this,
                getString(R.string.settings_choose_kr_engine_version),
                engineVersionLabels(), selectedEngineVersionIndex, this::setEngineVersionSelection);
    }

    private void setEngineVersionSelection(int index) {
        String[] labels = engineVersionLabels();
        selectedEngineVersionIndex = index >= 0 && index < labels.length ? index : 0;
        binding.engineVersionText.setText(labels[selectedEngineVersionIndex]);
    }

    private void showOnsEncodingPicker() {
        com.apps.theme.LauncherDialogFactory.showSingleChoice(this,
                getString(R.string.settings_ons_text_encoding),
                ONS_ENCODING_LABELS, selectedOnsEncodingIndex, this::setOnsEncodingSelection);
    }

    private void setOnsEncodingSelection(int index) {
        selectedOnsEncodingIndex = index >= 0 && index < ONS_ENCODING_LABELS.length ? index : 0;
        binding.onsEncodingText.setText(ONS_ENCODING_LABELS[selectedOnsEncodingIndex]);
    }

    private static int onsEncodingIndex(String encoding) {
        String normalized = OnsSettings.normalizeEncoding(encoding);
        for (int i = 0; i < ONS_ENCODING_LABELS.length; i++) {
            if (ONS_ENCODING_LABELS[i].equals(normalized)) return i;
        }
        return 0;
    }

    private void enterNativeKrkr() {
        try {
            startActivity(LauncherGameLaunchBridge.buildInternalKrkrOriginIntent(this));
        } catch (ActivityNotFoundException | IllegalArgumentException error) {
            DevLogger.w("LauncherKrkrSettings", "Failed to open native KRKR settings", error);
            Toast.makeText(this, R.string.settings_native_krkr_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    private String[] engineVersionLabels() {
        return new String[]{getString(R.string.settings_auto), "1.3.9", "1.3.4", "1.2.6"};
    }

    private void configureEdgeToEdgeWindow() {
        com.apps.LauncherEdgeToEdgeHelper.apply(this);
    }

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase));
    }
}
