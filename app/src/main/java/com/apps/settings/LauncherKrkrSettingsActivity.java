package com.apps.settings;

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
import com.yuki.yukihub.databinding.ActivityLauncherKrkrSettingsBinding;
import com.yuki.yukihub.launcherbridge.LauncherGameLaunchBridge;
import com.yuki.yukihub.launcherbridge.LauncherKrkrBridge;
import com.yuki.yukihub.launcherbridge.LauncherOnsGameSettingsBridge;
import com.yuki.yukihub.launcherbridge.LauncherRepositoryBridge;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.ons.OnsSettings;
import com.apps.LauncherActivity;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherTabletPortraitScaler;

public class LauncherKrkrSettingsActivity extends AppCompatActivity {
    public static final String EXTRA_GAME_ID = "extra_game_id";

    private static final String[] ENGINE_VERSION_LABELS = {"自动", "1.3.9", "1.3.4"};
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
        binding.btnNativeKrkr.setText("恢复全局默认");
        binding.btnNativeKrkr.setOnClickListener(v -> clearPerGameSettings());

        Game game = LauncherRepositoryBridge.findGameById(this, gameId);
        String title = (game != null && game.title != null && !game.title.trim().isEmpty())
                ? game.title.trim() : "ONS 引擎设置";
        binding.krkrSectionTitle.setText(title);
        binding.krkrSectionDescription.setText(
                "以下设置将完整覆盖该 ONS 游戏的全局默认；可使用“恢复全局默认”取消覆盖。下次启动该游戏时生效。");
    }

    private void clearPerGameSettings() {
        LauncherOnsGameSettingsBridge.clearOverride(this, gameId);
        Toast.makeText(this, "已恢复全局 ONS 设置", Toast.LENGTH_SHORT).show();
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
        LauncherTheme.styleSwitch(binding.krScopedSwitch);
        LauncherTheme.styleSwitch(binding.artemisScopedSwitch);
        LauncherTheme.styleSwitch(binding.onsScopedSwitch);
        LauncherTheme.styleSwitch(binding.onsStretchSwitch);
        LauncherTheme.styleSwitch(binding.onsCutoutSwitch);
        LauncherTheme.styleSwitch(binding.onsDisableVideoSwitch);
        LauncherTheme.styleSwitch(binding.onsSharpnessSwitch);
        LauncherTheme.styleSwitch(binding.tyranoScopedSwitch);
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
    }

    private void save() {
        int pos = selectedEngineVersionIndex;
        String version = LauncherKrkrBridge.ENGINE_VERSION_AUTO;
        if (pos == 1) version = LauncherKrkrBridge.ENGINE_VERSION_139;
        else if (pos == 2) version = LauncherKrkrBridge.ENGINE_VERSION_134;

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
            Toast.makeText(this, "已保存该游戏的 ONS 设置", Toast.LENGTH_SHORT).show();
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

        Toast.makeText(this, "引擎设置已保存：" + LauncherKrkrBridge.engineVersionLabel(version), Toast.LENGTH_SHORT).show();
        finish();
    }

    private void showEngineVersionPicker() {
        com.apps.theme.LauncherDialogFactory.showSingleChoice(this, "选择 KR 引擎版本",
                ENGINE_VERSION_LABELS, selectedEngineVersionIndex, this::setEngineVersionSelection);
    }

    private void setEngineVersionSelection(int index) {
        selectedEngineVersionIndex = index >= 0 && index < ENGINE_VERSION_LABELS.length ? index : 0;
        binding.engineVersionText.setText(ENGINE_VERSION_LABELS[selectedEngineVersionIndex]);
    }

    private void showOnsEncodingPicker() {
        com.apps.theme.LauncherDialogFactory.showSingleChoice(this, "ONS 文本编码",
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
        } catch (Throwable t) {
            Toast.makeText(this, "无法进入原生 KRKR", Toast.LENGTH_SHORT).show();
        }
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
