package com.apps.game;

import android.app.Dialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.yuki.yukihub.R;
import com.yuki.yukihub.launcherbridge.LauncherCoverBridge;
import com.yuki.yukihub.launcherbridge.LauncherGameHubShortcutBridge;
import com.yuki.yukihub.launcherbridge.LauncherRepositoryBridge;
import com.yuki.yukihub.launcherbridge.LauncherScanBridge;
import com.yuki.yukihub.model.EngineType;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.util.AppExecutors;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import rikka.shizuku.Shizuku;
import com.apps.LauncherActivity;
import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherTabletPortraitScaler;

public class LauncherAddGameActivity extends AppCompatActivity {
    private static final String STATE_ENGINE_OPTION_INDEX = "engine_option_index";
    private static final String STATE_LAST_ENGINE_DEFAULT_PACKAGE = "last_engine_default_package";
    private static final String STATE_GAME_DIRECTORY_URI = "game_directory_uri";
    private static final String STATE_COVER_URI = "cover_uri";
    private static final String STATE_LAUNCH_TARGET = "launch_target";
    private ScrollView scroll;
    private EditText nameInput;
    private TextView launchTargetText;
    private String launchTargetName = "";
    private TextView emulatorText;
    private EditText gameHubIdInput;
    private ImageView importGameHubShortcutButton;
    private EditText descriptionInput;
    private TextView dirText;
    private TextView coverText;
    private TextView saveButton;
    private TextView engineText;
    private EngineOption selectedEngineOption;
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
            new EngineOption(EngineType.GODOT, "Godot (自动检测 3/4)", "godot4")
    };
    private Uri gameDirUri;
    private Uri coverUri;
    private String lastEngineDefaultPackage = "";
    private static final int SHIZUKU_GAMEHUB_PERMISSION_REQUEST = 62002;

    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener =
            (requestCode, grantResult) -> {
                if (requestCode != SHIZUKU_GAMEHUB_PERMISSION_REQUEST) return;
                if (grantResult == PackageManager.PERMISSION_GRANTED) importGameHubShortcutFromShizuku();
                else Toast.makeText(this, "未获得 Shizuku 授权，仍可手动填写 localGameId", Toast.LENGTH_LONG).show();
            };

    private final ActivityResultLauncher<Uri> directoryPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) return;
                persistUriPermission(uri);
                gameDirUri = uri;
                dirText.setText(displayUri(uri));
                fillTitleFromDirIfEmpty(uri);
                launchTargetName = "";
                launchTargetText.setText("点击选择启动文件");
            });

    private final ActivityResultLauncher<String[]> coverPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                persistUriPermission(uri);
                coverUri = uri;
                coverText.setText(displayUri(uri));
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherActivity.applySavedToneMode(this);
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();
        setContentView(R.layout.activity_launcher_add_game);
        LauncherTabletPortraitScaler.applyActivityContent(this);

        bindViews();
        restoreTransientState(savedInstanceState);
        applySystemBarInsets();
        setupEnginePicker();
        bindActions();
        applyThemeTone();
        try { Shizuku.addRequestPermissionResultListener(shizukuPermissionListener); } catch (Throwable ignored) { }
    }

    private void bindViews() {
        scroll = findViewById(R.id.addGameScroll);
        nameInput = findViewById(R.id.addGameNameInput);
        launchTargetText = findViewById(R.id.addGameLaunchTargetInput);
        emulatorText = findViewById(R.id.addGameEmulatorInput);
        gameHubIdInput = findViewById(R.id.addGameGameHubIdInput);
        importGameHubShortcutButton = findViewById(R.id.addGameImportGameHubShortcut);
        descriptionInput = findViewById(R.id.addGameDescriptionInput);
        dirText = findViewById(R.id.addGameDirText);
        coverText = findViewById(R.id.addGameCoverText);
        saveButton = findViewById(R.id.addGameSave);
        engineText = findViewById(R.id.addGameEngineText);
        selectedEngineOption = engineOptions[0];
        engineText.setText(selectedEngineOption.label);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_ENGINE_OPTION_INDEX, selectedEngineOptionIndex());
        outState.putString(STATE_LAST_ENGINE_DEFAULT_PACKAGE, lastEngineDefaultPackage);
        if (gameDirUri != null) outState.putString(STATE_GAME_DIRECTORY_URI, gameDirUri.toString());
        if (coverUri != null) outState.putString(STATE_COVER_URI, coverUri.toString());
        outState.putString(STATE_LAUNCH_TARGET, launchTargetName);
    }

    private void restoreTransientState(@Nullable Bundle savedInstanceState) {
        if (savedInstanceState == null) return;
        selectedEngineOption = engineOptions[boundedEngineOptionIndex(
                savedInstanceState.getInt(STATE_ENGINE_OPTION_INDEX, 0))];
        engineText.setText(selectedEngineOption.label);
        lastEngineDefaultPackage = savedInstanceState.getString(STATE_LAST_ENGINE_DEFAULT_PACKAGE, "");
        gameDirUri = uriFromState(savedInstanceState.getString(STATE_GAME_DIRECTORY_URI));
        if (gameDirUri != null) dirText.setText(displayUri(gameDirUri));
        coverUri = uriFromState(savedInstanceState.getString(STATE_COVER_URI));
        if (coverUri != null) coverText.setText(displayUri(coverUri));
        launchTargetName = savedInstanceState.getString(STATE_LAUNCH_TARGET, "");
        if (!launchTargetName.isEmpty()) launchTargetText.setText(launchTargetName);
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

    private void applySystemBarInsets() {
        int originalLeft = scroll.getPaddingLeft();
        int originalTop = scroll.getPaddingTop();
        int originalRight = scroll.getPaddingRight();
        int originalBottom = scroll.getPaddingBottom();
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            scroll.setPadding(
                    originalLeft,
                    originalTop + insets.getSystemWindowInsetTop(),
                    originalRight,
                    originalBottom
            );
            return insets;
        });
        scroll.requestApplyInsets();
    }

    private void setupEnginePicker() {
        engineText.setOnClickListener(v -> {
            CharSequence[] labels = new CharSequence[engineOptions.length];
            for (int i = 0; i < engineOptions.length; i++) labels[i] = engineOptions[i].label;
            int checked = 0;
            for (int i = 0; i < engineOptions.length; i++) {
                if (engineOptions[i] == selectedEngineOption) { checked = i; break; }
            }
            com.apps.theme.LauncherDialogFactory.showSingleChoice(this, "选择游戏引擎",
                    labels, checked, index -> {
                        applyEngineSelection(index);
                    });
        });
    }

    private void applyEngineSelection(int index) {
        selectedEngineOption = engineOptions[boundedEngineOptionIndex(index)];
        engineText.setText(selectedEngineOption.label);
        String nextDefault = defaultEmulatorPackageForOption(selectedEngineOption);
        String current = textOf(emulatorText);
        if (current.isEmpty() || current.equals(lastEngineDefaultPackage)) {
            emulatorText.setText(nextDefault);
        }
        lastEngineDefaultPackage = nextDefault;
    }

    private int selectedEngineOptionIndex() {
        for (int i = 0; i < engineOptions.length; i++) {
            if (engineOptions[i] == selectedEngineOption) return i;
        }
        return 0;
    }

    private int boundedEngineOptionIndex(int index) {
        return index >= 0 && index < engineOptions.length ? index : 0;
    }

    private void bindActions() {
        dirText.setOnClickListener(view -> directoryPicker.launch(null));
        launchTargetText.setOnClickListener(view -> showLaunchTargetPicker());
        emulatorText.setOnClickListener(view -> showAppPicker(emulatorText));
        importGameHubShortcutButton.setOnClickListener(view -> importGameHubShortcutFromShizuku());
        coverText.setOnClickListener(view -> coverPicker.launch(new String[]{"image/*"}));
        saveButton.setOnClickListener(view -> saveGame());
    }

    private void applyThemeTone() {
        LauncherTheme.longActionButton(saveButton);
        LauncherTheme.applyPrimaryTone(findViewById(android.R.id.content));
        LauncherTheme.formInputs(nameInput, gameHubIdInput, descriptionInput);
        importGameHubShortcutButton.setImageTintList(
                ColorStateList.valueOf(LauncherTheme.primary(this)));
    }

    private void importGameHubShortcutFromShizuku() {
        if (selectedEngine() != EngineType.GAMEHUB) {
            Toast.makeText(this, "请先将游戏引擎设为 GameHub", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!LauncherGameHubShortcutBridge.isShizukuRunning()) {
            Toast.makeText(this, "请先启动 Shizuku，再授权读取盖世快捷方式", Toast.LENGTH_LONG).show();
            return;
        }
        if (!LauncherGameHubShortcutBridge.hasShizukuPermission()) {
            try { LauncherGameHubShortcutBridge.requestShizukuPermission(SHIZUKU_GAMEHUB_PERMISSION_REQUEST); }
            catch (Throwable error) { Toast.makeText(this, "无法请求 Shizuku 授权", Toast.LENGTH_LONG).show(); }
            return;
        }
        importGameHubShortcutButton.setEnabled(false);
        importGameHubShortcutButton.setAlpha(0.45f);
        importGameHubShortcutButton.setContentDescription("正在读取 GameHub 快捷方式");
        AppExecutors.runOnIo(() -> {
            List<LauncherGameHubShortcutBridge.Shortcut> items;
            try { items = LauncherGameHubShortcutBridge.loadShortcuts(); }
            catch (Throwable ignored) { items = new ArrayList<>(); }
            final List<LauncherGameHubShortcutBridge.Shortcut> shortcuts = items;
            runOnUiThread(() -> {
                importGameHubShortcutButton.setEnabled(true);
                importGameHubShortcutButton.setAlpha(1f);
                importGameHubShortcutButton.setContentDescription("导入 GameHub 快捷方式");
                if (isFinishing()) return;
                if (shortcuts.isEmpty()) {
                    Toast.makeText(this, "未读取到快捷方式；请确认盖世已创建桌面快捷方式", Toast.LENGTH_LONG).show();
                    return;
                }
                CharSequence[] labels = new CharSequence[shortcuts.size()];
                for (int i = 0; i < shortcuts.size(); i++) labels[i] = shortcuts.get(i).displayLabel + "\n" + shortcuts.get(i).localGameId;
                com.apps.theme.LauncherDialogFactory.showActionChoices(this, "选择盖世快捷方式",
                        labels, which -> applyGameHubShortcut(shortcuts.get(which)));
            });
        });
    }

    private void applyGameHubShortcut(LauncherGameHubShortcutBridge.Shortcut item) {
        if (item == null) return;
        gameHubIdInput.setText(item.localGameId);
        if (textOf(nameInput).isEmpty()) nameInput.setText(item.localAppName);
        if (textOf(emulatorText).isEmpty()) emulatorText.setText("com.xiaoji.egggame");
    }

    /** 扫描游戏目录下的相关游戏文件，弹出列表供用户选择启动入口。 */
    private void showLaunchTargetPicker() {
        LauncherLaunchTargetPicker.show(this, gameDirUri, selectedEngine(), target -> {
            launchTargetName = target;
            launchTargetText.setText(target);
        });
    }

    private void saveGame() {
        String title = textOf(nameInput);
        if (title.isEmpty()) {
            Toast.makeText(this, "请填写游戏名称", Toast.LENGTH_SHORT).show();
            return;
        }
        if (gameDirUri == null) {
            Toast.makeText(this, "请选择游戏目录", Toast.LENGTH_SHORT).show();
            return;
        }

        saveButton.setEnabled(false);
        saveButton.setText("保存中...");

        android.content.Context appContext = getApplicationContext();
        EngineType selectedEngine = selectedEngine();
        // 在 UI 线程读取选择器的 RPGMAKER 子类型（rpgmxp/rpgmvx/rpgmvxace/mkxp-z），
        // 用户显式选择时优先于此值，避免被扫描器误判的 detected.rpgMakerSubtype 覆盖。
        String userRpgSubtype = selectedRpgMakerSubtype();
        String selectedLaunchTarget = launchTargetName;
        String selectedEmulator = textOf(emulatorText);
        String selectedGameHubId = textOf(gameHubIdInput);
        String selectedDescription = textOf(descriptionInput);
        Uri selectedGameDir = gameDirUri;
        Uri selectedCover = coverUri;
        AppExecutors.runOnSingle(() -> {
            LauncherScanBridge.DetectionResult detected = null;
            // AUTO 让扫描器决定引擎；RPGMAKER 也走一次扫描以拿到具体子类型（rpgmxp/rpgmvx/rpgmvxace/mkxp-z），
            // 子类型用于选择对应的 mkxp native 库，但不会覆盖用户选择的 EngineType。
            if (selectedEngine == EngineType.AUTO || selectedEngine == EngineType.RPGMAKER) {
                try {
                    DocumentFile root = DocumentFile.fromTreeUri(appContext, selectedGameDir);
                    detected = LauncherScanBridge.detectEngine(root, 2);
                } catch (Throwable ignored) {
                }
            }
            EngineType finalEngine = selectedEngine;
            if (selectedEngine == EngineType.AUTO
                    && detected != null && detected.confidence > 0 && detected.engine != EngineType.UNKNOWN) {
                finalEngine = detected.engine;
            }

            Game game = new Game();
            game.title = title;
            game.engine = finalEngine;
            game.rootUri = selectedGameDir.toString();
            String copiedCover = copyCoverToInternalStorage(selectedCover);
            game.coverUri = copiedCover;
            game.coverPersistUri = copiedCover;
            game.coverSourceType = copiedCover == null ? 0 : 1;
            game.launchTarget = textOrDefault(
                    selectedLaunchTarget,
                    detected != null && detected.launchTarget != null && !detected.launchTarget.trim().isEmpty()
                            ? detected.launchTarget
                            : "[游戏目录]"
            );
            // emulatorPackage 优先级：用户手动填的 emulatorText > 用户在选择器显式选的子类型
            // （RPGMAKER 的 rpgmxp/rpgmvx/rpgmvxace/mkxp-z 或 RENPY 的 renpy）
            // > 扫描器检测到的子类型 > 引擎默认包名。
            // 关键：用户显式选了 RPG Maker XP/VX/VX Ace/mkxp-z 时，必须用对应的 mkxp native 库
            // （libmkxp18/19/30.so），否则会出现 Ruby 1.8 语法在 Ruby 3.x 下报 SyntaxError 等问题。
            String emulatorFallback;
            if ((finalEngine == EngineType.RPGMAKER || finalEngine == EngineType.RENPY)
                    && !userRpgSubtype.isEmpty()) {
                emulatorFallback = "internal." + userRpgSubtype;
            } else {
                emulatorFallback = defaultEmulatorPackageForDetected(finalEngine, detected);
            }
            game.emulatorPackage = textOrDefault(selectedEmulator, emulatorFallback);
            game.description = selectedDescription;
            game.gamehubLocalGameId = selectedGameHubId;
            if (game.engine == EngineType.GAMEHUB && selectedGameHubId.isEmpty()) {
                game.gamehubLaunchMode = "program";
            }

            long id = LauncherRepositoryBridge.insertGameIfNotExists(appContext, game);
            if (id > 0 && copiedCover == null) {
                game.id = id;
                LauncherCoverBridge.fetchCoverForGameAsync(appContext, game);
            }
            runOnUiThread(() -> {
                saveButton.setEnabled(true);
                saveButton.setText("保存");
                if (id > 0) {
                    Toast.makeText(this, "游戏已添加", Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                } else {
                    Toast.makeText(this, "保存失败：该目录可能已经在游戏库中", Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private EngineType selectedEngine() {
        return selectedEngineOption != null ? selectedEngineOption.engine : EngineType.AUTO;
    }

    private String defaultEmulatorPackage(EngineType engine) {
        if (engine == EngineType.KIRIKIRI) return "internal.krkr";
        if (engine == EngineType.ONS) return "internal.ons";
        if (engine == EngineType.TYRANO) return "internal.tyrano";
        if (engine == EngineType.ARTEMIS) return "internal.artemis";
        if (engine == EngineType.PSP) return "org.ppsspp.ppsspp";
        if (engine == EngineType.NINTENDO_3DS) return "io.github.azaharplus.android";
        if (engine == EngineType.GAMEHUB) return "com.xiaoji.egggame";
        // RPG Maker 默认走 RPGXP（Ruby 1.8）：老 RGSS1 语法（如 ?(...) 三元运算符）在 1.8 下才兼容，
        // buildLaunchIntent 会在 rpgmxp 时自动传 useRuby18=true 加载 libmkxp18.so。
        // 检测到具体子类型时由 defaultEmulatorPackageForDetected 覆盖为更精确的别名。
        if (engine == EngineType.RPGMAKER) return "internal.rpgmxp";
        if (engine == EngineType.RENPY) return "internal.renpy";
        if (engine == EngineType.GODOT) return "internal.godot";
        return "";
    }

    /**
     * 当扫描器给出 RPG Maker 子引擎（rpgmxp/rpgmvx/rpgmvxace/mkxp-z）时，
     * 使用 {@code internal.<subtype>} 作为默认 packageName，覆盖通用默认。
     * 这样无需用户手动调整 emulatorText 即可调用对应的 mkxp native 库。
     */
    private String defaultEmulatorPackageForDetected(EngineType engine, LauncherScanBridge.DetectionResult detected) {
        String fallback = defaultEmulatorPackage(engine);
        if (detected == null) return fallback;
        if (engine == EngineType.RPGMAKER) {
            String subtype = detected.rpgMakerSubtype;
            if (subtype == null || subtype.trim().isEmpty()) return fallback;
            return "internal." + subtype.trim();
        }
        if (engine == EngineType.RENPY) {
            String subtype = detected.renpySubtype;
            if (subtype == null || subtype.trim().isEmpty()) return fallback;
            return "internal." + subtype.trim();
        }
        if (engine == EngineType.GODOT) {
            String subtype = detected.godotSubtype;
            if (subtype == null || subtype.trim().isEmpty()) return fallback;
            return "internal." + subtype.trim();
        }
        return fallback;
    }

    /**
     * 根据当前选择的 EngineOption 推算默认 packageName。
     * 用于选择器回调：当用户选 RPG Maker 子引擎或 Ren'Py 时，
     * 返回 {@code internal.<subtype>}；其他引擎回退到 {@link #defaultEmulatorPackage}。
     */
    private String defaultEmulatorPackageForOption(EngineOption opt) {
        if (opt == null) return "";
        if (opt.rpgMakerSubtype != null && !opt.rpgMakerSubtype.isEmpty()
                && (opt.engine == EngineType.RPGMAKER || opt.engine == EngineType.RENPY
                    || opt.engine == EngineType.GODOT)) {
            return "internal." + opt.rpgMakerSubtype;
        }
        return defaultEmulatorPackage(opt.engine);
    }

    /**
     * 取当前选择的 EngineOption 的子引擎标识（RPG Maker 或 Ren'Py）。
     * 仅当选中的引擎有 subtype 且非空时返回，否则返回空串。
     * 必须在 UI 线程调用（读取选择状态）。
     */
    private String selectedRpgMakerSubtype() {
        if (selectedEngineOption == null) return "";
        if (selectedEngineOption.engine != EngineType.RPGMAKER
                && selectedEngineOption.engine != EngineType.RENPY
                && selectedEngineOption.engine != EngineType.GODOT) return "";
        return selectedEngineOption.rpgMakerSubtype == null ? "" : selectedEngineOption.rpgMakerSubtype;
    }

    private String copyCoverToInternalStorage(Uri uri) {
        if (uri == null) return null;
        Bitmap bitmap = null;
        try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(inputStream);
            if (bitmap == null) return null;
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int max = 720;
            if (width > max || height > max) {
                float scale = Math.min(max / (float) width, max / (float) height);
                Bitmap scaled = Bitmap.createScaledBitmap(
                        bitmap,
                        Math.max(1, (int) (width * scale)),
                        Math.max(1, (int) (height * scale)),
                        true
                );
                bitmap.recycle();
                bitmap = scaled;
            }
            File dir = new File(getFilesDir(), "covers");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File file = new File(dir, "cover_" + System.currentTimeMillis() + ".jpg");
            try (FileOutputStream outputStream = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, outputStream);
                outputStream.flush();
            }
            return Uri.fromFile(file).toString();
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private void persistUriPermission(Uri uri) {
        if (uri == null) return;
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

    private void fillTitleFromDirIfEmpty(Uri uri) {
        if (!textOf(nameInput).isEmpty() || uri == null) return;
        String display = displayUri(uri);
        int slash = display.lastIndexOf('/');
        String title = slash >= 0 && slash < display.length() - 1 ? display.substring(slash + 1) : display;
        if (title.startsWith("primary:")) title = title.substring("primary:".length());
        if (!title.trim().isEmpty()) nameInput.setText(title.trim());
    }

    private String displayUri(Uri uri) {
        if (uri == null) return "";
        try {
            String docId = DocumentsContract.getTreeDocumentId(uri);
            if (docId != null && !docId.trim().isEmpty()) return Uri.decode(docId);
        } catch (Throwable ignored) {
        }
        try {
            String docId = DocumentsContract.getDocumentId(uri);
            if (docId != null && !docId.trim().isEmpty()) return Uri.decode(docId);
        } catch (Throwable ignored) {
        }
        return uri.toString();
    }

    private String textOf(EditText editText) {
        return editText == null || editText.getText() == null ? "" : editText.getText().toString().trim();
    }

    private String textOf(TextView textView) {
        return textView == null || textView.getText() == null ? "" : textView.getText().toString().trim();
    }

    private String textOrDefault(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private void showAppPicker(TextView target) {
        LauncherAppPickerDialog.show(this, target::setText);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private void configureEdgeToEdgeWindow() {
        boolean darkMode = LauncherActivity.isLauncherDarkMode(this);
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(ContextCompat.getColor(this, R.color.launcher_bg_color));
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        if (!darkMode) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
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

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase));
    }

    @Override
    protected void onDestroy() {
        try { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener); } catch (Throwable ignored) { }
        super.onDestroy();
    }
}
