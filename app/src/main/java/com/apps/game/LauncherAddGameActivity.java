package com.apps.game;

import android.app.Dialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.core.R;
import com.core.databinding.ActivityLauncherAddGameBinding;
import com.core.launcherbridge.LauncherCoverBridge;
import com.core.launcherbridge.LauncherGameHubShortcutBridge;
import com.core.launcherbridge.LauncherRepositoryBridge;
import com.core.launcherbridge.LauncherScanBridge;
import com.core.model.EngineType;
import com.core.model.Game;
import com.core.util.AppExecutors;
import com.core.util.DevLogger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private static final String STATE_DIRECTORY_PERMISSION_DEGRADED =
            "directory_permission_degraded";
    private static final String STATE_COVER_PERMISSION_DEGRADED = "cover_permission_degraded";
    private ActivityLauncherAddGameBinding binding;
    private String launchTargetName = "";
    private EngineOption selectedEngineOption;
    private EngineOption[] engineOptions;

    private Uri gameDirUri;
    private Uri coverUri;
    private String lastEngineDefaultPackage = "";
    /** 标记游戏目录 SAF 持久化授权降级为只读或彻底失败，便于 saveGame 时给用户提示。 */
    private boolean directoryPermissionDegraded;
    /** 标记封面 SAF 持久化授权降级为只读或彻底失败（封面只需读，不影响写入）。 */
    private boolean coverPermissionDegraded;
    private static final int SHIZUKU_GAMEHUB_PERMISSION_REQUEST = 62002;

    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener =
            (requestCode, grantResult) -> {
                if (requestCode != SHIZUKU_GAMEHUB_PERMISSION_REQUEST) return;
                if (grantResult == PackageManager.PERMISSION_GRANTED) importGameHubShortcutFromShizuku();
                else Toast.makeText(this, R.string.game_shizuku_manual_id, Toast.LENGTH_LONG).show();
            };

    private final ActivityResultLauncher<Uri> directoryPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) return;
                directoryPermissionDegraded = !persistUriPermission(uri);
                gameDirUri = uri;
                binding.addGameDirText.setText(displayUri(uri));
                fillTitleFromDirIfEmpty(uri);
                launchTargetName = "";
                binding.addGameLaunchTargetInput.setText(R.string.game_launch_file_select);
            });

    private final ActivityResultLauncher<String[]> coverPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                coverPermissionDegraded = !persistUriPermission(uri);
                coverUri = uri;
                binding.addGameCoverText.setText(displayUri(uri));
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherActivity.applySavedToneMode(this);
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();
        binding = ActivityLauncherAddGameBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        engineOptions = EngineOptionCatalog.create(this, false);
        LauncherTabletPortraitScaler.applyActivityContent(this);

        bindViews();
        restoreTransientState(savedInstanceState);
        applySystemBarInsets();
        setupEnginePicker();
        bindActions();
        applyThemeTone();
        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
        } catch (Exception error) {
            DevLogger.w("LauncherAddGame", "Failed to register Shizuku permission listener", error);
        }
    }

    private void bindViews() {
        selectedEngineOption = engineOptions[0];
        binding.addGameEngineText.setText(selectedEngineOption.label);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_ENGINE_OPTION_INDEX, selectedEngineOptionIndex());
        outState.putString(STATE_LAST_ENGINE_DEFAULT_PACKAGE, lastEngineDefaultPackage);
        if (gameDirUri != null) outState.putString(STATE_GAME_DIRECTORY_URI, gameDirUri.toString());
        if (coverUri != null) outState.putString(STATE_COVER_URI, coverUri.toString());
        outState.putString(STATE_LAUNCH_TARGET, launchTargetName);
        outState.putBoolean(STATE_DIRECTORY_PERMISSION_DEGRADED, directoryPermissionDegraded);
        outState.putBoolean(STATE_COVER_PERMISSION_DEGRADED, coverPermissionDegraded);
    }

    private void restoreTransientState(@Nullable Bundle savedInstanceState) {
        if (savedInstanceState == null) return;
        selectedEngineOption = engineOptions[boundedEngineOptionIndex(
                savedInstanceState.getInt(STATE_ENGINE_OPTION_INDEX, 0))];
        binding.addGameEngineText.setText(selectedEngineOption.label);
        lastEngineDefaultPackage = savedInstanceState.getString(STATE_LAST_ENGINE_DEFAULT_PACKAGE, "");
        gameDirUri = uriFromState(savedInstanceState.getString(STATE_GAME_DIRECTORY_URI));
        if (gameDirUri != null) binding.addGameDirText.setText(displayUri(gameDirUri));
        coverUri = uriFromState(savedInstanceState.getString(STATE_COVER_URI));
        if (coverUri != null) binding.addGameCoverText.setText(displayUri(coverUri));
        launchTargetName = savedInstanceState.getString(STATE_LAUNCH_TARGET, "");
        if (!launchTargetName.isEmpty()) binding.addGameLaunchTargetInput.setText(launchTargetName);
        directoryPermissionDegraded =
                savedInstanceState.getBoolean(STATE_DIRECTORY_PERMISSION_DEGRADED, false);
        coverPermissionDegraded =
                savedInstanceState.getBoolean(STATE_COVER_PERMISSION_DEGRADED, false);
    }

    @Nullable
    private Uri uriFromState(@Nullable String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try {
            return Uri.parse(value);
        } catch (IllegalArgumentException error) {
            DevLogger.w("LauncherAddGame", "Invalid saved URI state", error);
            return null;
        }
    }

    private void applySystemBarInsets() {
        int originalLeft = binding.addGameScroll.getPaddingLeft();
        int originalTop = binding.addGameScroll.getPaddingTop();
        int originalRight = binding.addGameScroll.getPaddingRight();
        int originalBottom = binding.addGameScroll.getPaddingBottom();
        binding.addGameScroll.setOnApplyWindowInsetsListener((view, insets) -> {
            binding.addGameScroll.setPadding(
                    originalLeft,
                    originalTop + insets.getSystemWindowInsetTop(),
                    originalRight,
                    originalBottom
            );
            return insets;
        });
        binding.addGameScroll.requestApplyInsets();
    }

    private void setupEnginePicker() {
        binding.addGameEngineText.setOnClickListener(v -> {
            CharSequence[] labels = new CharSequence[engineOptions.length];
            for (int i = 0; i < engineOptions.length; i++) labels[i] = engineOptions[i].label;
            int checked = 0;
            for (int i = 0; i < engineOptions.length; i++) {
                if (engineOptions[i] == selectedEngineOption) { checked = i; break; }
            }
            com.apps.theme.LauncherDialogFactory.showSingleChoice(this, getString(R.string.game_select_engine_title),
                    labels, checked, index -> {
                        applyEngineSelection(index);
                    });
        });
    }

    private void applyEngineSelection(int index) {
        selectedEngineOption = engineOptions[boundedEngineOptionIndex(index)];
        binding.addGameEngineText.setText(selectedEngineOption.label);
        // 切换引擎时无条件重置为该引擎的默认包名，覆盖用户手动输入或列表选择的值。
        String nextDefault = EnginePackageResolver.forOption(selectedEngineOption);
        binding.addGameEmulatorInput.setText(nextDefault);
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
        binding.addGameDirText.setOnClickListener(view -> directoryPicker.launch(null));
        binding.addGameLaunchTargetInput.setOnClickListener(view -> showLaunchTargetPicker());
        // 模拟器包名支持手动输入；右侧图标点击从应用列表选择。
        binding.addGamePickEmulatorApp.setOnClickListener(view -> showAppPicker(binding.addGameEmulatorInput));
        binding.addGameImportGameHubShortcut.setOnClickListener(view -> importGameHubShortcutFromShizuku());
        binding.addGameCoverText.setOnClickListener(view -> coverPicker.launch(new String[]{"image/*"}));
        binding.addGameSave.setOnClickListener(view -> saveGame());
    }

    private void applyThemeTone() {
        LauncherTheme.longActionButton(binding.addGameSave);
        LauncherTheme.applyPrimaryTone(binding.getRoot());
        LauncherTheme.formInputs(binding.addGameNameInput, binding.addGameEmulatorInput, binding.addGameGameHubIdInput, binding.addGameDescriptionInput);
        binding.addGamePickEmulatorApp.setImageTintList(
                ColorStateList.valueOf(LauncherTheme.primary(this)));
        binding.addGameImportGameHubShortcut.setImageTintList(
                ColorStateList.valueOf(LauncherTheme.primary(this)));
    }

    private void importGameHubShortcutFromShizuku() {
        if (selectedEngine() != EngineType.GAMEHUB) {
            Toast.makeText(this, R.string.game_select_gamehub_first, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!LauncherGameHubShortcutBridge.isShizukuRunning()) {
            Toast.makeText(this, R.string.game_shizuku_start_first, Toast.LENGTH_LONG).show();
            return;
        }
        if (!LauncherGameHubShortcutBridge.hasShizukuPermission()) {
            try { LauncherGameHubShortcutBridge.requestShizukuPermission(SHIZUKU_GAMEHUB_PERMISSION_REQUEST); }
            catch (Exception error) {
                DevLogger.w("LauncherAddGame", "Failed to request Shizuku permission", error);
                Toast.makeText(this, R.string.game_shizuku_request_failed, Toast.LENGTH_LONG).show();
            }
            return;
        }
        binding.addGameImportGameHubShortcut.setEnabled(false);
        binding.addGameImportGameHubShortcut.setAlpha(0.45f);
        binding.addGameImportGameHubShortcut.setContentDescription(getString(R.string.game_gamehub_reading_shortcuts));
        AppExecutors.runOnIo(() -> {
            List<LauncherGameHubShortcutBridge.Shortcut> items;
            try { items = LauncherGameHubShortcutBridge.loadShortcuts(); }
            catch (Exception error) {
                DevLogger.w("LauncherAddGame", "Failed to load GameHub shortcuts", error);
                items = new ArrayList<>();
            }
            final List<LauncherGameHubShortcutBridge.Shortcut> shortcuts = items;
            runOnUiThread(() -> {
                if (isUiUnavailable()) return;
                binding.addGameImportGameHubShortcut.setEnabled(true);
                binding.addGameImportGameHubShortcut.setAlpha(1f);
                binding.addGameImportGameHubShortcut.setContentDescription(getString(R.string.game_gamehub_import_shortcut));
                if (shortcuts.isEmpty()) {
                    Toast.makeText(this, R.string.game_gamehub_no_shortcuts, Toast.LENGTH_LONG).show();
                    return;
                }
                CharSequence[] labels = new CharSequence[shortcuts.size()];
                for (int i = 0; i < shortcuts.size(); i++) labels[i] = shortcuts.get(i).displayLabel + "\n" + shortcuts.get(i).localGameId;
                com.apps.theme.LauncherDialogFactory.showActionChoices(this, getString(R.string.game_gamehub_choose_shortcut),
                        labels, which -> applyGameHubShortcut(shortcuts.get(which)));
            });
        });
    }

    private void applyGameHubShortcut(LauncherGameHubShortcutBridge.Shortcut item) {
        if (item == null) return;
        binding.addGameGameHubIdInput.setText(item.localGameId);
        if (textOf(binding.addGameNameInput).isEmpty()) binding.addGameNameInput.setText(item.localAppName);
        if (textOf(binding.addGameEmulatorInput).isEmpty()) binding.addGameEmulatorInput.setText("com.xiaoji.egggame");
    }

    /** 扫描游戏目录下的相关游戏文件，弹出列表供用户选择启动入口。 */
    private void showLaunchTargetPicker() {
        LauncherLaunchTargetPicker.show(this, gameDirUri, selectedEngine(), target -> {
            launchTargetName = target;
            binding.addGameLaunchTargetInput.setText(target);
        });
    }

    private void saveGame() {
        String title = textOf(binding.addGameNameInput);
        if (title.isEmpty()) {
            Toast.makeText(this, R.string.game_name_required, Toast.LENGTH_SHORT).show();
            return;
        }
        if (gameDirUri == null) {
            Toast.makeText(this, R.string.game_directory_required, Toast.LENGTH_SHORT).show();
            return;
        }

        binding.addGameSave.setEnabled(false);
        binding.addGameSave.setText(R.string.game_common_saving);

        android.content.Context appContext = getApplicationContext();
        EngineType selectedEngine = selectedEngine();
        // 在 UI 线程读取选择器的 RPGMAKER 子类型（rpgmxp/rpgmvx/rpgmvxace/mkxp-z），
        // 用户显式选择时优先于此值，避免被扫描器误判的 detected.rpgMakerSubtype 覆盖。
        String userRpgSubtype = selectedRpgMakerSubtype();
        String selectedLaunchTarget = launchTargetName;
        String selectedEmulator = textOf(binding.addGameEmulatorInput);
        String selectedGameHubId = textOf(binding.addGameGameHubIdInput);
        String selectedDescription = textOf(binding.addGameDescriptionInput);
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
                } catch (Exception error) {
                    DevLogger.w("LauncherAddGame", "Engine detection failed; using selected engine", error);
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
            // emulatorPackage 优先级：用户手动填的 binding.addGameEmulatorInput > 用户在选择器显式选的子类型
            // （RPGMAKER 的 rpgmxp/rpgmvx/rpgmvxace/mkxp-z 或 RENPY 的 renpy）
            // > 扫描器检测到的子类型 > 引擎默认包名。
            // 关键：用户显式选了 RPG Maker XP/VX/VX Ace/mkxp-z 时，必须用对应的 mkxp native 库
            // （libmkxp18/19/30.so），否则会出现 Ruby 1.8 语法在 Ruby 3.x 下报 SyntaxError 等问题。
            String emulatorFallback;
            if ((finalEngine == EngineType.RPGMAKER || finalEngine == EngineType.RENPY)
                    && !userRpgSubtype.isEmpty()) {
                emulatorFallback = "internal." + userRpgSubtype;
            } else {
                emulatorFallback = EnginePackageResolver.forDetection(finalEngine, detected);
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
                if (isUiUnavailable()) return;
                binding.addGameSave.setEnabled(true);
                binding.addGameSave.setText(R.string.game_common_save);
                if (id > 0) {
                    if (directoryPermissionDegraded) {
                        // 游戏目录 SAF 持久化授权降级为只读或彻底失败，提示用户可能无法写入存档
                        Toast.makeText(this, R.string.game_added_limited_access, Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, R.string.game_added, Toast.LENGTH_SHORT).show();
                    }
                    setResult(RESULT_OK);
                    finish();
                } else {
                    Toast.makeText(this, R.string.game_save_duplicate_failed, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private EngineType selectedEngine() {
        return selectedEngineOption != null ? selectedEngineOption.engine : EngineType.AUTO;
    }

    /**
     * 取当前选择的 EngineOption 的子引擎标识（RPG Maker 或 Ren'Py）。
     * 仅当选中的引擎有 subtype 且非空时返回，否则返回空串。
     * 必须在 UI 线程调用（读取选择状态）。
     */
    private String selectedRpgMakerSubtype() {
        return EnginePackageResolver.subtypeForOption(selectedEngineOption);
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
        } catch (OutOfMemoryError error) {
            throw error;
        } catch (Exception error) {
            DevLogger.w("LauncherAddGame", "Failed to copy cover to internal storage", error);
            return null;
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    /** 持久化 URI 授权。返回 true 表示 RW 授权成功，false 表示降级为只读或彻底失败。 */
    private boolean persistUriPermission(Uri uri) {
        if (uri == null) return false;
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
            return true;
        } catch (Exception first) {
            Log.w("LauncherAddGame", "takePersistableUriPermission(RW) failed, retry RO", first);
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                return false;
            } catch (Exception e) {
                Log.w("LauncherAddGame", "takePersistableUriPermission(RO) failed", e);
                return false;
            }
        }
    }

    private void fillTitleFromDirIfEmpty(Uri uri) {
        if (!textOf(binding.addGameNameInput).isEmpty() || uri == null) return;
        String display = displayUri(uri);
        int slash = display.lastIndexOf('/');
        String title = slash >= 0 && slash < display.length() - 1 ? display.substring(slash + 1) : display;
        if (title.startsWith("primary:")) title = title.substring("primary:".length());
        if (!title.trim().isEmpty()) binding.addGameNameInput.setText(title.trim());
    }

    private String displayUri(Uri uri) {
        if (uri == null) return "";
        try {
            String docId = DocumentsContract.getTreeDocumentId(uri);
            if (docId != null && !docId.trim().isEmpty()) return Uri.decode(docId);
        } catch (IllegalArgumentException | SecurityException error) {
            DevLogger.w("LauncherAddGame", "Failed to read tree document id", error);
        }
        try {
            String docId = DocumentsContract.getDocumentId(uri);
            if (docId != null && !docId.trim().isEmpty()) return Uri.decode(docId);
        } catch (IllegalArgumentException | SecurityException error) {
            DevLogger.w("LauncherAddGame", "Failed to read document id", error);
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

    private void configureEdgeToEdgeWindow() {
        com.apps.LauncherEdgeToEdgeHelper.apply(this);
    }

    private boolean isUiUnavailable() {
        return isFinishing() || isDestroyed() || binding == null;
    }

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase));
    }

    @Override
    protected void onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
        } catch (Exception error) {
            DevLogger.w("LauncherAddGame", "Failed to remove Shizuku permission listener", error);
        }
        super.onDestroy();
    }
}
