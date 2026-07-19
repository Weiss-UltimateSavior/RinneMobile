package com.apps.game;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.yuki.yukihub.R;
import com.yuki.yukihub.launcherbridge.LauncherRepositoryBridge;
import com.yuki.yukihub.model.EngineType;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.util.AppExecutors;
import com.apps.LauncherActivity;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherTabletPortraitScaler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import rikka.shizuku.Shizuku;

import com.yuki.yukihub.launcherbridge.LauncherGameHubShortcutBridge;

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

    private EditText etTitle;
    private TextView tvEngine;
    private EngineOption currentEngineOption;
    private final EngineOption[] engineOptions = new EngineOption[]{
            new EngineOption(EngineType.AUTO, "自动识别", null),
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
            new EngineOption(EngineType.RPGMAKER, "mkxp-z (Ruby 3.x, 自定义/通用)", "mkxp-z"),
            new EngineOption(EngineType.RENPY, "Ren'Py", "renpy"),
            new EngineOption(EngineType.GODOT, "Godot (自动检测 3/4)", "godot4"),
            new EngineOption(EngineType.UNKNOWN, "未知", null)
    };
    private TextView etEmulator;
    private EditText etLaunchTarget;
    private EditText etGameHubLocalGameId;
    private EditText etDescription;
    private TextView tvDir;
    private TextView btnPickDirectory;
    private TextView tvCoverStatus;
    private TextView btnPickCover;
    private ImageView btnImportGameHubShortcut;
    private TextView btnCancel;
    private TextView btnSave;

    private Game game;
    private Uri selectedCoverUri;
    private Uri selectedGameDirectoryUri;
    private String lastEngineDefaultPackage = "";
    private boolean restoreEngineSelection;
    private boolean restoreDirectorySelection;
    private boolean restoreCoverSelection;
    private boolean restoreFormState;

    private final ActivityResultLauncher<Uri> directoryPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) return;
                persistUriPermission(uri);
                selectedGameDirectoryUri = uri;
                tvDir.setText(displayDirectoryUri(uri));
                tvDir.setTextColor(LauncherTheme.primary(this));
            });

    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener =
            (requestCode, grantResult) -> {
                if (requestCode != SHIZUKU_GAMEHUB_PERMISSION_REQUEST) return;
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    importGameHubShortcutFromShizuku();
                } else {
                    Toast.makeText(this, "未获得 Shizuku 授权，仍可手动填写 localGameId", Toast.LENGTH_LONG).show();
                }
            };

    private final ActivityResultLauncher<Intent> coverPicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null && result.getData().getData() != null) {
                    selectedCoverUri = result.getData().getData();
                    tvCoverStatus.setText("封面：已选择封面");
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherActivity.applySavedToneMode(this);
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();
        setContentView(R.layout.activity_launcher_game_edit);
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
        etTitle = findViewById(R.id.editTitle);
        tvEngine = findViewById(R.id.editEngineText);
        currentEngineOption = engineOptions[0];
        tvEngine.setText(currentEngineOption.label);
        etEmulator = findViewById(R.id.editEmulator);
        etLaunchTarget = findViewById(R.id.editLaunchTarget);
        etGameHubLocalGameId = findViewById(R.id.editGameHubLocalGameId);
        etDescription = findViewById(R.id.editDescription);
        tvDir = findViewById(R.id.editDir);
        btnPickDirectory = findViewById(R.id.btnPickDirectory);
        tvCoverStatus = findViewById(R.id.editCoverStatus);
        btnPickCover = findViewById(R.id.btnPickCover);
        btnImportGameHubShortcut = findViewById(R.id.btnImportGameHubShortcut);
        btnCancel = findViewById(R.id.btnCancel);
        btnSave = findViewById(R.id.btnSave);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_ENGINE_OPTION_INDEX, selectedEngineOptionIndex());
        outState.putString(STATE_LAST_ENGINE_DEFAULT_PACKAGE, lastEngineDefaultPackage);
        if (selectedGameDirectoryUri != null) outState.putString(STATE_GAME_DIRECTORY_URI, selectedGameDirectoryUri.toString());
        if (selectedCoverUri != null) outState.putString(STATE_COVER_URI, selectedCoverUri.toString());
        outState.putString(STATE_TITLE, etTitle.getText().toString());
        outState.putString(STATE_EMULATOR_PACKAGE, etEmulator.getText().toString());
        outState.putString(STATE_LAUNCH_TARGET, etLaunchTarget.getText().toString());
        outState.putString(STATE_GAMEHUB_LOCAL_GAME_ID, etGameHubLocalGameId.getText().toString());
        outState.putString(STATE_DESCRIPTION, etDescription.getText().toString());
    }

    private void restoreTransientState(@Nullable Bundle savedInstanceState) {
        if (savedInstanceState == null) return;
        restoreFormState = savedInstanceState.containsKey(STATE_TITLE);
        if (restoreFormState) {
            etTitle.setText(savedInstanceState.getString(STATE_TITLE, ""));
            etEmulator.setText(savedInstanceState.getString(STATE_EMULATOR_PACKAGE, ""));
            etLaunchTarget.setText(savedInstanceState.getString(STATE_LAUNCH_TARGET, ""));
            etGameHubLocalGameId.setText(savedInstanceState.getString(STATE_GAMEHUB_LOCAL_GAME_ID, ""));
            etDescription.setText(savedInstanceState.getString(STATE_DESCRIPTION, ""));
        }
        restoreEngineSelection = savedInstanceState.containsKey(STATE_ENGINE_OPTION_INDEX);
        if (restoreEngineSelection) {
            currentEngineOption = engineOptions[boundedEngineOptionIndex(
                    savedInstanceState.getInt(STATE_ENGINE_OPTION_INDEX, 0))];
            tvEngine.setText(currentEngineOption.label);
            lastEngineDefaultPackage = savedInstanceState.getString(STATE_LAST_ENGINE_DEFAULT_PACKAGE, "");
        }
        selectedGameDirectoryUri = uriFromState(savedInstanceState.getString(STATE_GAME_DIRECTORY_URI));
        restoreDirectorySelection = selectedGameDirectoryUri != null;
        if (restoreDirectorySelection) {
            tvDir.setText(displayDirectoryUri(selectedGameDirectoryUri));
            tvDir.setTextColor(LauncherTheme.primary(this));
        }
        selectedCoverUri = uriFromState(savedInstanceState.getString(STATE_COVER_URI));
        restoreCoverSelection = selectedCoverUri != null;
        if (restoreCoverSelection) tvCoverStatus.setText("封面：已选择封面");
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
        tvEngine.setOnClickListener(v -> showEnginePicker());
        etEmulator.setOnClickListener(v -> LauncherAppPickerDialog.show(this, etEmulator::setText));
        etLaunchTarget.setOnClickListener(v -> LauncherLaunchTargetPicker.show(
                this, selectedGameDirectoryUri, selectedEngineOption().engine, etLaunchTarget::setText));
        btnPickDirectory.setOnClickListener(v -> directoryPicker.launch(selectedGameDirectoryUri));
        btnPickCover.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            coverPicker.launch(intent);
        });
        btnImportGameHubShortcut.setOnClickListener(v -> importGameHubShortcutFromShizuku());
        btnCancel.setOnClickListener(v -> finish());
        btnSave.setOnClickListener(v -> saveGame());
    }

    private void loadGame() {
        long gameId = getIntent().getLongExtra(EXTRA_GAME_ID, -1);
        if (gameId <= 0) { finish(); return; }
        AppExecutors.io().execute(() -> {
            Game g = LauncherRepositoryBridge.findGameById(this, gameId);
            runOnUiThread(() -> {
                if (g == null) { Toast.makeText(this, "游戏不存在", Toast.LENGTH_SHORT).show(); finish(); return; }
                game = g;
                if (!restoreFormState) {
                    etTitle.setText(game.title);
                    etEmulator.setText(game.emulatorPackage);
                    etLaunchTarget.setText(game.launchTarget);
                    etGameHubLocalGameId.setText(game.gamehubLocalGameId);
                    etDescription.setText(game.description);
                }
                if (!restoreEngineSelection) {
                    currentEngineOption = findEngineOption(game.engine, game.emulatorPackage);
                    tvEngine.setText(currentEngineOption.label);
                    lastEngineDefaultPackage = defaultEmulatorPackageForOption(currentEngineOption);
                }
                if (!restoreDirectorySelection && game.rootUri != null && game.rootUri.startsWith("content://")) {
                    selectedGameDirectoryUri = Uri.parse(game.rootUri);
                    tvDir.setText(displayDirectoryUri(selectedGameDirectoryUri));
                } else if (!restoreDirectorySelection) {
                    tvDir.setText(game.rootUri == null || game.rootUri.trim().isEmpty()
                            ? "尚未选择游戏目录" : game.rootUri);
                }
                if (!restoreCoverSelection && game.coverUri != null && !game.coverUri.trim().isEmpty()) {
                    tvCoverStatus.setText("封面：已有封面");
                }
            });
        });
    }

    private void saveGame() {
        if (game == null) return;
        String title = etTitle.getText().toString().trim();
        if (title.isEmpty()) {
            Toast.makeText(this, "请输入标题", Toast.LENGTH_SHORT).show();
            return;
        }
        btnSave.setEnabled(false);
        btnSave.setText("保存中...");

        game.title = title;
        EngineOption opt = selectedEngineOption();
        game.engine = opt != null ? opt.engine : EngineType.UNKNOWN;
        String emuPkg = etEmulator.getText().toString().trim();
        // 若用户未手动改 emulatorPackage，根据选中子引擎自动填 internal.<subtype>。
        if (emuPkg.isEmpty() && opt != null
                && (opt.engine == EngineType.RPGMAKER || opt.engine == EngineType.RENPY
                    || opt.engine == EngineType.GODOT)
                && opt.rpgMakerSubtype != null && !opt.rpgMakerSubtype.isEmpty()) {
            emuPkg = "internal." + opt.rpgMakerSubtype;
        }
        game.emulatorPackage = emuPkg;
        game.launchTarget = etLaunchTarget.getText().toString().trim();
        if (game.launchTarget.isEmpty()) game.launchTarget = "[游戏目录]";
        if (selectedGameDirectoryUri != null) game.rootUri = selectedGameDirectoryUri.toString();
        game.gamehubLocalGameId = etGameHubLocalGameId.getText().toString().trim();
        game.description = etDescription.getText().toString().trim();

        AppExecutors.io().execute(() -> {
            try {
                if (selectedCoverUri != null) {
                    String cover = com.yuki.yukihub.launcherbridge.LauncherScanBridge.copyCoverToInternalStorage(this, selectedCoverUri.toString());
                    if (cover != null) {
                        game.coverUri = cover;
                        game.coverPersistUri = cover;
                        game.coverSourceType = 1;
                    }
                }
                LauncherRepositoryBridge.updateGame(this, game);
                runOnUiThread(() -> {
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    btnSave.setEnabled(true);
                    btnSave.setText("保存");
                    Toast.makeText(this, "保存失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void applyThemeTone() {
        LauncherTheme.applyPrimaryTone(findViewById(android.R.id.content));
        LauncherTheme.formInputs(etTitle, etLaunchTarget, etGameHubLocalGameId, etDescription);
        LauncherTheme.longActionButton(btnPickDirectory);
        LauncherTheme.longActionButton(btnPickCover);
        btnImportGameHubShortcut.setImageTintList(
                ColorStateList.valueOf(LauncherTheme.primary(this)));
        LauncherTheme.longActionButton(btnSave);
        LauncherTheme.longActionButton(btnCancel);
    }

    private void persistUriPermission(Uri uri) {
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Throwable first) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {
            }
        }
    }

    private String displayDirectoryUri(Uri uri) {
        if (uri == null) return "尚未选择游戏目录";
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
            Toast.makeText(this, "请先将引擎设为 GameHub", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, "请先启动 Shizuku，再授权读取盖世快捷方式", Toast.LENGTH_LONG).show();
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(SHIZUKU_GAMEHUB_PERMISSION_REQUEST);
                return;
            }
        } catch (Throwable error) {
            Toast.makeText(this, "无法连接 Shizuku：" + error.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
            return;
        }

        btnImportGameHubShortcut.setEnabled(false);
        btnImportGameHubShortcut.setAlpha(0.45f);
        btnImportGameHubShortcut.setContentDescription("正在读取 GameHub 快捷方式");
        AppExecutors.runOnIo(() -> {
            List<LauncherGameHubShortcutBridge.Shortcut> items;
            try {
                items = LauncherGameHubShortcutBridge.loadShortcuts();
            } catch (Throwable ignored) {
                items = new ArrayList<>();
            }
            List<LauncherGameHubShortcutBridge.Shortcut> result = items;
            runOnUiThread(() -> {
                btnImportGameHubShortcut.setEnabled(true);
                btnImportGameHubShortcut.setAlpha(1f);
                btnImportGameHubShortcut.setContentDescription("导入 GameHub 快捷方式");
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
        com.apps.theme.LauncherDialogFactory.showActionChoices(this, "选择盖世快捷方式",
                labels, which -> applyGameHubShortcut(items.get(which)));
    }

    private void applyGameHubShortcut(LauncherGameHubShortcutBridge.Shortcut item) {
        if (item == null) return;
        etGameHubLocalGameId.setText(item.localGameId);
        if (etTitle.getText() == null || etTitle.getText().toString().trim().isEmpty()) {
            etTitle.setText(item.localAppName);
        }
        if (etEmulator.getText() == null || etEmulator.getText().toString().trim().isEmpty()) {
            etEmulator.setText("com.xiaoji.egggame");
        }
    }

    private void showGameHubImportUnavailableDialog() {
        com.apps.theme.LauncherDialogFactory.showInfo(this, "未读取到盖世快捷方式",
                "请确认：\n1. Shizuku 正在运行且已授权；\n2. 盖世已创建桌面快捷方式；\n3. 已安装 com.xiaoji.egggamz 或 com.xiaoji.egggame。\n\n也可以手动填写 localGameId。");
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
        com.apps.theme.LauncherDialogFactory.showSingleChoice(this, "选择游戏引擎",
                labels, checked, index -> {
                    applyEngineSelection(index);
                });
    }

    private void applyEngineSelection(int index) {
        currentEngineOption = engineOptions[boundedEngineOptionIndex(index)];
        tvEngine.setText(currentEngineOption.label);
        String nextDefault = defaultEmulatorPackageForOption(currentEngineOption);
        String current = etEmulator.getText().toString().trim();
        if (current.isEmpty() || current.equals(lastEngineDefaultPackage)) {
            etEmulator.setText(nextDefault);
        }
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
        View scroll = findViewById(R.id.editScroll);
        int left = scroll.getPaddingLeft();
        int top = scroll.getPaddingTop();
        int right = scroll.getPaddingRight();
        int bottom = scroll.getPaddingBottom();
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            scroll.setPadding(left, top + insets.getSystemWindowInsetTop(), right, bottom);
            return insets;
        });
        scroll.requestApplyInsets();
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
