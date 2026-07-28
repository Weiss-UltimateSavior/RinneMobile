package com.apps.game;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.core.R;
import com.core.databinding.ActivityLauncherGameEditBinding;
import com.core.launcherbridge.LauncherRepositoryBridge;
import com.core.diagnostics.GameDiagnostics;
import com.core.model.EngineType;
import com.core.model.Game;
import com.core.util.AppExecutors;
import com.apps.LauncherActivity;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherTabletPortraitScaler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import rikka.shizuku.Shizuku;

import com.core.launcherbridge.LauncherGameHubShortcutBridge;

public class LauncherGameEditActivity extends AppCompatActivity {
    public static final String EXTRA_GAME_ID = "extra_game_id";
    private static final int SHIZUKU_GAMEHUB_PERMISSION_REQUEST = 62001;
    private static final String STATE_ENGINE_OPTION_INDEX = "engine_option_index";
    private static final String STATE_LAST_ENGINE_DEFAULT_PACKAGE = "last_engine_default_package";
    private static final String STATE_GAME_DIRECTORY_URI = "game_directory_uri";
    private static final String STATE_COVER_URI = "cover_uri";
    private static final String STATE_TITLE = "title";
    private static final String STATE_EMULATOR_PACKAGE = "emulator_package";
    private static final String STATE_LAUNCH_TARGET = "launch_target";
    private static final String STATE_GAMEHUB_LOCAL_GAME_ID = "gamehub_local_game_id";
    private static final String STATE_DESCRIPTION = "description";
    private static final String STATE_DIRECTORY_REBOUND = "directory_rebound";
    private static final String STATE_ENGINE_CHANGED = "engine_changed";
    private static final String STATE_DIRECTORY_PERMISSION_DEGRADED =
            "directory_permission_degraded";

    private ActivityLauncherGameEditBinding binding;
    private EngineOption currentEngineOption;
    private EngineOption[] engineOptions;

    private EngineOption[] createEngineOptions() {
        return new EngineOption[]{
            new EngineOption(EngineType.AUTO, getString(R.string.game_engine_auto), null),
            new EngineOption(EngineType.KIRIKIRI, "Kirikiri", null),
            new EngineOption(EngineType.ONS, "ONScripter", null),
            new EngineOption(EngineType.TYRANO, "Tyrano", null),
            new EngineOption(EngineType.ARTEMIS, "Artemis", null),
            new EngineOption(EngineType.WINLATOR, "Winlator", null),
            new EngineOption(EngineType.GAMEHUB, "GameHub", null),
            new EngineOption(EngineType.PSP, "PSP", null),
            new EngineOption(EngineType.NINTENDO_3DS, "Nintendo 3DS", null),
            new EngineOption(EngineType.RPGMAKER, "RPG Maker XP (RGSS1, Ruby 1.8)", "rpgmxp"),
            new EngineOption(EngineType.RPGMAKER, "RPG Maker VX (RGSS2, Ruby 1.9)", "rpgmvx"),
            new EngineOption(EngineType.RPGMAKER, "RPG Maker VX Ace (RGSS3, Ruby 1.9)", "rpgmvxace"),
            new EngineOption(EngineType.RPGMAKER, getString(R.string.game_engine_rpgmaker_mkxp), "mkxp-z"),
            new EngineOption(EngineType.RENPY, "Ren'Py", "renpy"),
            new EngineOption(EngineType.GODOT, getString(R.string.game_engine_godot_auto), "godot4"),
            new EngineOption(EngineType.UNKNOWN, getString(R.string.game_common_unknown), null)
        };
    }
    private Game game;
    private Uri selectedCoverUri;
    private Uri selectedGameDirectoryUri;
    private boolean directoryRebound;
    /** True only after the user explicitly selected a different engine in this edit session. */
    private boolean engineChanged;
    private EngineType originalEngine;
    private String lastEngineDefaultPackage = "";
    /** 标记游戏目录 SAF 持久化授权降级为只读或彻底失败，便于 saveGame 时给用户提示。 */
    private boolean directoryPermissionDegraded;
    private boolean restoreEngineSelection;
    private boolean restoreDirectorySelection;
    private boolean restoreCoverSelection;
    private boolean restoreFormState;

    private final ActivityResultLauncher<Uri> directoryPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) return;
                directoryPermissionDegraded = !persistUriPermission(uri);
                selectedGameDirectoryUri = uri;
                directoryRebound = game != null && game.rootUri != null
                        && !game.rootUri.equals(uri.toString());
                binding.editDir.setText(displayDirectoryUri(uri));
                binding.editDir.setTextColor(LauncherTheme.primary(this));
            });

    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener =
            (requestCode, grantResult) -> {
                if (requestCode != SHIZUKU_GAMEHUB_PERMISSION_REQUEST) return;
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    importGameHubShortcutFromShizuku();
                } else {
                    Toast.makeText(this, R.string.game_shizuku_manual_id, Toast.LENGTH_LONG).show();
                }
            };

    private final ActivityResultLauncher<Intent> coverPicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null && result.getData().getData() != null) {
                    selectedCoverUri = result.getData().getData();
                    binding.editCoverStatus.setText(R.string.game_cover_selected);
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherActivity.applySavedToneMode(this);
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();
        binding = ActivityLauncherGameEditBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        engineOptions = createEngineOptions();
        LauncherTabletPortraitScaler.applyActivityContent(this);
        bindViews();
        restoreTransientState(savedInstanceState);
        applySystemBarInsets();
        bindActions();
        applyThemeTone();
        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
        } catch (Throwable ignored) {
        }
        loadGame();
    }

    private void bindViews() {
        currentEngineOption = engineOptions[0];
        binding.editEngineText.setText(currentEngineOption.label);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_ENGINE_OPTION_INDEX, selectedEngineOptionIndex());
        outState.putString(STATE_LAST_ENGINE_DEFAULT_PACKAGE, lastEngineDefaultPackage);
        if (selectedGameDirectoryUri != null) outState.putString(STATE_GAME_DIRECTORY_URI, selectedGameDirectoryUri.toString());
        if (selectedCoverUri != null) outState.putString(STATE_COVER_URI, selectedCoverUri.toString());
        outState.putString(STATE_TITLE, binding.editTitle.getText().toString());
        outState.putString(STATE_EMULATOR_PACKAGE, binding.editEmulator.getText().toString());
        outState.putString(STATE_LAUNCH_TARGET, binding.editLaunchTarget.getText().toString());
        outState.putString(STATE_GAMEHUB_LOCAL_GAME_ID, binding.editGameHubLocalGameId.getText().toString());
        outState.putString(STATE_DESCRIPTION, binding.editDescription.getText().toString());
        outState.putBoolean(STATE_DIRECTORY_REBOUND, directoryRebound);
        outState.putBoolean(STATE_ENGINE_CHANGED, engineChanged);
        outState.putBoolean(STATE_DIRECTORY_PERMISSION_DEGRADED, directoryPermissionDegraded);
    }

    private void restoreTransientState(@Nullable Bundle savedInstanceState) {
        if (savedInstanceState == null) return;
        restoreFormState = savedInstanceState.containsKey(STATE_TITLE);
        directoryRebound = savedInstanceState.getBoolean(STATE_DIRECTORY_REBOUND, false);
        engineChanged = savedInstanceState.getBoolean(STATE_ENGINE_CHANGED, false);
        directoryPermissionDegraded =
                savedInstanceState.getBoolean(STATE_DIRECTORY_PERMISSION_DEGRADED, false);
        if (restoreFormState) {
            binding.editTitle.setText(savedInstanceState.getString(STATE_TITLE, ""));
            binding.editEmulator.setText(savedInstanceState.getString(STATE_EMULATOR_PACKAGE, ""));
            binding.editLaunchTarget.setText(savedInstanceState.getString(STATE_LAUNCH_TARGET, ""));
            binding.editGameHubLocalGameId.setText(savedInstanceState.getString(STATE_GAMEHUB_LOCAL_GAME_ID, ""));
            binding.editDescription.setText(savedInstanceState.getString(STATE_DESCRIPTION, ""));
        }
        restoreEngineSelection = savedInstanceState.containsKey(STATE_ENGINE_OPTION_INDEX);
        if (restoreEngineSelection) {
            currentEngineOption = engineOptions[boundedEngineOptionIndex(
                    savedInstanceState.getInt(STATE_ENGINE_OPTION_INDEX, 0))];
            binding.editEngineText.setText(currentEngineOption.label);
            lastEngineDefaultPackage = savedInstanceState.getString(STATE_LAST_ENGINE_DEFAULT_PACKAGE, "");
        }
        selectedGameDirectoryUri = uriFromState(savedInstanceState.getString(STATE_GAME_DIRECTORY_URI));
        restoreDirectorySelection = selectedGameDirectoryUri != null;
        if (restoreDirectorySelection) {
            binding.editDir.setText(displayDirectoryUri(selectedGameDirectoryUri));
            binding.editDir.setTextColor(LauncherTheme.primary(this));
        }
        selectedCoverUri = uriFromState(savedInstanceState.getString(STATE_COVER_URI));
        restoreCoverSelection = selectedCoverUri != null;
        if (restoreCoverSelection) binding.editCoverStatus.setText(R.string.game_cover_selected);
    }

    @Nullable
    private Uri uriFromState(@Nullable String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try {
            return Uri.parse(value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void bindActions() {
        binding.editEngineText.setOnClickListener(v -> showEnginePicker());
        // 模拟器包名支持手动输入；右侧图标点击从应用列表选择。
        binding.btnPickEmulatorApp.setOnClickListener(v -> LauncherAppPickerDialog.show(this, binding.editEmulator::setText));
        binding.editLaunchTarget.setOnClickListener(v -> LauncherLaunchTargetPicker.show(
                this, selectedGameDirectoryUri, selectedEngineOption().engine, binding.editLaunchTarget::setText));
        binding.btnPickDirectory.setOnClickListener(v -> directoryPicker.launch(selectedGameDirectoryUri));
        binding.btnPickCover.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            coverPicker.launch(intent);
        });
        binding.btnImportGameHubShortcut.setOnClickListener(v -> importGameHubShortcutFromShizuku());
        binding.btnCancel.setOnClickListener(v -> finish());
        binding.btnSave.setOnClickListener(v -> saveGame());
    }

    private void loadGame() {
        long gameId = getIntent().getLongExtra(EXTRA_GAME_ID, -1);
        if (gameId <= 0) { finish(); return; }
        AppExecutors.io().execute(() -> {
            Game g = LauncherRepositoryBridge.findGameById(this, gameId);
            runOnUiThread(() -> {
                if (g == null) { Toast.makeText(this, R.string.game_not_found, Toast.LENGTH_SHORT).show(); finish(); return; }
                game = g;
                originalEngine = game.engine;
                if (!restoreFormState) {
                    binding.editTitle.setText(game.title);
                    binding.editEmulator.setText(game.emulatorPackage);
                    binding.editLaunchTarget.setText(game.launchTarget);
                    binding.editGameHubLocalGameId.setText(game.gamehubLocalGameId);
                    binding.editDescription.setText(game.description);
                }
                if (!restoreEngineSelection) {
                    currentEngineOption = findEngineOption(game.engine, game.emulatorPackage);
                    binding.editEngineText.setText(currentEngineOption.label);
                    lastEngineDefaultPackage = defaultEmulatorPackageForOption(currentEngineOption);
                }
                if (!restoreDirectorySelection && game.rootUri != null && game.rootUri.startsWith("content://")) {
                    selectedGameDirectoryUri = Uri.parse(game.rootUri);
                    binding.editDir.setText(displayDirectoryUri(selectedGameDirectoryUri));
                } else if (!restoreDirectorySelection) {
                    binding.editDir.setText(game.rootUri == null || game.rootUri.trim().isEmpty()
                            ? getString(R.string.game_directory_not_selected) : game.rootUri);
                }
                if (!restoreCoverSelection && game.coverUri != null && !game.coverUri.trim().isEmpty()) {
                    binding.editCoverStatus.setText(R.string.game_cover_existing);
                }
            });
        });
    }

    private void saveGame() {
        if (game == null) return;
        String title = binding.editTitle.getText().toString().trim();
        if (title.isEmpty()) {
            Toast.makeText(this, R.string.game_title_required, Toast.LENGTH_SHORT).show();
            return;
        }
        binding.btnSave.setEnabled(false);
        binding.btnSave.setText(R.string.game_common_saving);

        game.title = title;
        EngineOption opt = selectedEngineOption();
        // Rebinding a directory is independent of engine selection.  In particular, do not
        // downgrade an existing detected engine to AUTO after an Activity recreation unless
        // the user deliberately chose another engine.
        game.engine = directoryRebound && !engineChanged && originalEngine != null
                ? originalEngine
                : (opt != null ? opt.engine : EngineType.UNKNOWN);
        String emuPkg = binding.editEmulator.getText().toString().trim();
        // 若用户未手动改 emulatorPackage，根据选中子引擎自动填 internal.<subtype>。
        if (emuPkg.isEmpty() && opt != null
                && (opt.engine == EngineType.RPGMAKER || opt.engine == EngineType.RENPY
                    || opt.engine == EngineType.GODOT)
                && opt.rpgMakerSubtype != null && !opt.rpgMakerSubtype.isEmpty()) {
            emuPkg = "internal." + opt.rpgMakerSubtype;
        }
        game.emulatorPackage = emuPkg;
        game.launchTarget = binding.editLaunchTarget.getText().toString().trim();
        // launchTarget is persisted engine data. Keep the canonical sentinel language-neutral
        // from the UI locale so internal launchers never receive a translated display label.
        if (game.launchTarget.isEmpty()) game.launchTarget = "[游戏目录]";
        if (selectedGameDirectoryUri != null) game.rootUri = selectedGameDirectoryUri.toString();
        game.gamehubLocalGameId = binding.editGameHubLocalGameId.getText().toString().trim();
        game.description = binding.editDescription.getText().toString().trim();

        AppExecutors.io().execute(() -> {
            try {
                if (selectedCoverUri != null) {
                    String cover = com.core.launcherbridge.LauncherScanBridge.copyCoverToInternalStorage(this, selectedCoverUri.toString());
                    if (cover != null) {
                        game.coverUri = cover;
                        game.coverPersistUri = cover;
                        game.coverSourceType = 1;
                    }
                }
                int affected = LauncherRepositoryBridge.updateGame(this, game);
                if (affected <= 0) {
                    throw new IllegalStateException(getString(R.string.game_record_write_failed));
                }
                if (directoryRebound) GameDiagnostics.recordDirectoryRebound(this, game);
                runOnUiThread(() -> {
                    if (directoryPermissionDegraded) {
                        // 游戏目录 SAF 持久化授权降级为只读或彻底失败，提示用户可能无法写入存档
                        Toast.makeText(this, R.string.game_saved_limited_access, Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, R.string.game_saved, Toast.LENGTH_SHORT).show();
                    }
                    setResult(RESULT_OK);
                    finish();
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    binding.btnSave.setEnabled(true);
                    binding.btnSave.setText(R.string.game_common_save);
                    Toast.makeText(this, getString(R.string.game_save_failed_reason, t.getMessage()), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void applyThemeTone() {
        LauncherTheme.applyPrimaryTone(binding.getRoot());
        LauncherTheme.formInputs(binding.editTitle, binding.editEmulator, binding.editLaunchTarget, binding.editGameHubLocalGameId, binding.editDescription);
        LauncherTheme.longActionButton(binding.btnPickDirectory);
        LauncherTheme.longActionButton(binding.btnPickCover);
        binding.btnPickEmulatorApp.setImageTintList(
                ColorStateList.valueOf(LauncherTheme.primary(this)));
        binding.btnImportGameHubShortcut.setImageTintList(
                ColorStateList.valueOf(LauncherTheme.primary(this)));
        LauncherTheme.longActionButton(binding.btnSave);
        LauncherTheme.longActionButton(binding.btnCancel);
    }

    /** 持久化 URI 授权。返回 true 表示 RW 授权成功，false 表示降级为只读或彻底失败。 */
    private boolean persistUriPermission(Uri uri) {
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
            return true;
        } catch (Exception first) {
            Log.w("LauncherGameEdit", "takePersistableUriPermission(RW) failed, retry RO", first);
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                return false;
            } catch (Exception e) {
                Log.w("LauncherGameEdit", "takePersistableUriPermission(RO) failed", e);
                return false;
            }
        }
    }

    private String displayDirectoryUri(Uri uri) {
        if (uri == null) return getString(R.string.game_directory_not_selected);
        try {
            String documentId = DocumentsContract.getTreeDocumentId(uri);
            if (documentId != null && !documentId.trim().isEmpty()) return Uri.decode(documentId);
        } catch (Throwable ignored) {
        }
        return uri.toString();
    }

    private void importGameHubShortcutFromShizuku() {
        EngineOption opt = selectedEngineOption();
        if (opt == null || opt.engine != EngineType.GAMEHUB) {
            Toast.makeText(this, R.string.game_select_gamehub_first, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, R.string.game_shizuku_start_first, Toast.LENGTH_LONG).show();
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(SHIZUKU_GAMEHUB_PERMISSION_REQUEST);
                return;
            }
        } catch (Throwable error) {
            Toast.makeText(this, getString(R.string.game_shizuku_connect_failed,
                    error.getClass().getSimpleName()), Toast.LENGTH_LONG).show();
            return;
        }

        binding.btnImportGameHubShortcut.setEnabled(false);
        binding.btnImportGameHubShortcut.setAlpha(0.45f);
        binding.btnImportGameHubShortcut.setContentDescription(getString(R.string.game_gamehub_reading_shortcuts));
        AppExecutors.runOnIo(() -> {
            List<LauncherGameHubShortcutBridge.Shortcut> items;
            try {
                items = LauncherGameHubShortcutBridge.loadShortcuts();
            } catch (Throwable ignored) {
                items = new ArrayList<>();
            }
            List<LauncherGameHubShortcutBridge.Shortcut> result = items;
            runOnUiThread(() -> {
                binding.btnImportGameHubShortcut.setEnabled(true);
                binding.btnImportGameHubShortcut.setAlpha(1f);
                binding.btnImportGameHubShortcut.setContentDescription(getString(R.string.game_gamehub_import_shortcut));
                if (isFinishing()) return;
                if (result.isEmpty()) {
                    showGameHubImportUnavailableDialog();
                    return;
                }
                showGameHubShortcutPicker(result);
            });
        });
    }

    private void showGameHubShortcutPicker(List<LauncherGameHubShortcutBridge.Shortcut> items) {
        CharSequence[] labels = new CharSequence[items.size()];
        for (int i = 0; i < items.size(); i++) {
            LauncherGameHubShortcutBridge.Shortcut item = items.get(i);
            labels[i] = item.displayLabel + "\n" + item.localGameId;
        }
        com.apps.theme.LauncherDialogFactory.showActionChoices(this, getString(R.string.game_gamehub_choose_shortcut),
                labels, which -> applyGameHubShortcut(items.get(which)));
    }

    private void applyGameHubShortcut(LauncherGameHubShortcutBridge.Shortcut item) {
        if (item == null) return;
        binding.editGameHubLocalGameId.setText(item.localGameId);
        if (binding.editTitle.getText() == null || binding.editTitle.getText().toString().trim().isEmpty()) {
            binding.editTitle.setText(item.localAppName);
        }
        if (binding.editEmulator.getText() == null || binding.editEmulator.getText().toString().trim().isEmpty()) {
            binding.editEmulator.setText("com.xiaoji.egggame");
        }
    }

    private void showGameHubImportUnavailableDialog() {
        com.apps.theme.LauncherDialogFactory.showInfo(this,
                getString(R.string.game_gamehub_no_shortcut_title),
                getString(R.string.game_gamehub_no_shortcut_help));
    }

    private EngineOption findEngineOption(EngineType engine, String emulatorPackage) {
        if (engine == null) return engineOptions[0];
        String pkg = emulatorPackage == null ? "" : emulatorPackage.trim().toLowerCase(Locale.ROOT);
        EngineOption fallback = null;
        for (EngineOption opt : engineOptions) {
            if (opt.engine != engine) continue;
            if (engine == EngineType.RPGMAKER || engine == EngineType.RENPY
                    || engine == EngineType.GODOT) {
                if (opt.rpgMakerSubtype == null || opt.rpgMakerSubtype.isEmpty()) {
                    if (fallback == null) fallback = opt;
                    continue;
                }
                String alias = "internal." + opt.rpgMakerSubtype;
                if (alias.equals(pkg) || ("internal." + opt.rpgMakerSubtype.replace("-", ""))
                        .equals(pkg.replace("-", ""))) {
                    return opt;
                }
                if (fallback == null) fallback = opt;
            } else {
                return opt;
            }
        }
        return fallback != null ? fallback : engineOptions[0];
    }

    private EngineOption selectedEngineOption() {
        return currentEngineOption != null ? currentEngineOption : engineOptions[0];
    }

    private void showEnginePicker() {
        CharSequence[] labels = new CharSequence[engineOptions.length];
        for (int i = 0; i < engineOptions.length; i++) labels[i] = engineOptions[i].label;
        int checked = 0;
        for (int i = 0; i < engineOptions.length; i++) {
            if (engineOptions[i] == currentEngineOption) { checked = i; break; }
        }
        com.apps.theme.LauncherDialogFactory.showSingleChoice(this,
                getString(R.string.game_select_engine_title),
                labels, checked, index -> {
                    applyEngineSelection(index);
                });
    }

    private void applyEngineSelection(int index) {
        currentEngineOption = engineOptions[boundedEngineOptionIndex(index)];
        engineChanged = true;
        binding.editEngineText.setText(currentEngineOption.label);
        // 切换引擎时无条件重置为该引擎的默认包名，覆盖用户手动输入或列表选择的值。
        String nextDefault = defaultEmulatorPackageForOption(currentEngineOption);
        binding.editEmulator.setText(nextDefault);
        lastEngineDefaultPackage = nextDefault;
    }

    private String defaultEmulatorPackageForOption(EngineOption option) {
        if (option == null) return "";
        if ((option.engine == EngineType.RPGMAKER || option.engine == EngineType.RENPY
                    || option.engine == EngineType.GODOT)
                && option.rpgMakerSubtype != null
                && !option.rpgMakerSubtype.isEmpty()) return "internal." + option.rpgMakerSubtype;
        if (option.engine == EngineType.KIRIKIRI) return "internal.krkr";
        if (option.engine == EngineType.ONS) return "internal.ons";
        if (option.engine == EngineType.TYRANO) return "internal.tyrano";
        if (option.engine == EngineType.ARTEMIS) return "internal.artemis";
        if (option.engine == EngineType.PSP) return "org.ppsspp.ppsspp";
        if (option.engine == EngineType.NINTENDO_3DS) return "io.github.azaharplus.android";
        if (option.engine == EngineType.GAMEHUB) return "com.xiaoji.egggame";
        return "";
    }

    private int selectedEngineOptionIndex() {
        for (int i = 0; i < engineOptions.length; i++) {
            if (engineOptions[i] == currentEngineOption) return i;
        }
        return 0;
    }

    private int boundedEngineOptionIndex(int index) {
        return index >= 0 && index < engineOptions.length ? index : 0;
    }

    private static final class EngineOption {
        final EngineType engine;
        final String label;
        /** 仅 RPGMAKER 用：rpgmxp / rpgmvx / rpgmvxace / mkxp-z；null 表示非 RPGMAKER。 */
        final String rpgMakerSubtype;

        EngineOption(EngineType engine, String label, String rpgMakerSubtype) {
            this.engine = engine;
            this.label = label;
            this.rpgMakerSubtype = rpgMakerSubtype;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private void applySystemBarInsets() {
        int left = binding.editScroll.getPaddingLeft();
        int top = binding.editScroll.getPaddingTop();
        int right = binding.editScroll.getPaddingRight();
        int bottom = binding.editScroll.getPaddingBottom();
        binding.editScroll.setOnApplyWindowInsetsListener((view, insets) -> {
            binding.editScroll.setPadding(left, top + insets.getSystemWindowInsetTop(), right, bottom);
            return insets;
        });
        binding.editScroll.requestApplyInsets();
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

    @Override
    protected void onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}
