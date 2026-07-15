package com.apps.game;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.yuki.yukihub.R;
import com.yuki.yukihub.launcherbridge.LauncherRepositoryBridge;
import com.yuki.yukihub.model.EngineType;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.util.AppExecutors;
import com.apps.LauncherActivity;
import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherTabletPortraitScaler;

import java.util.ArrayList;
import java.util.List;

import rikka.shizuku.Shizuku;

import com.yuki.yukihub.launcherbridge.LauncherGameHubShortcutBridge;

public class LauncherGameEditActivity extends AppCompatActivity {
    public static final String EXTRA_GAME_ID = "extra_game_id";
    private static final int SHIZUKU_GAMEHUB_PERMISSION_REQUEST = 62001;

    private EditText etTitle;
    private Spinner spEngine;
    private EditText etEmulator;
    private EditText etLaunchTarget;
    private EditText etGameHubLocalGameId;
    private EditText etDescription;
    private TextView tvDir;
    private TextView btnPickDirectory;
    private TextView tvCoverStatus;
    private TextView btnPickCover;
    private TextView btnImportGameHubShortcut;
    private TextView btnCancel;
    private TextView btnSave;

    private Game game;
    private Uri selectedCoverUri;
    private Uri selectedGameDirectoryUri;

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
        spEngine = findViewById(R.id.editEngine);
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

        String[] engineNames = {"AUTO", "KIRIKIRI", "ONS", "TYRANO", "ARTEMIS", "WINLATOR", "GAMEHUB", "PSP", "UNKNOWN"};
        ArrayAdapter<String> adapter = LauncherTheme.spinnerAdapter(this, engineNames);
        spEngine.setAdapter(adapter);
        LauncherTheme.styleSpinner(spEngine);
    }

    private void bindActions() {
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
                etTitle.setText(game.title);
                spEngine.setSelection(engineIndex(game.engine));
                etEmulator.setText(game.emulatorPackage);
                etLaunchTarget.setText(game.launchTarget);
                etGameHubLocalGameId.setText(game.gamehubLocalGameId);
                etDescription.setText(game.description);
                if (game.rootUri != null && game.rootUri.startsWith("content://")) {
                    selectedGameDirectoryUri = Uri.parse(game.rootUri);
                    tvDir.setText(displayDirectoryUri(selectedGameDirectoryUri));
                } else {
                    tvDir.setText(game.rootUri == null || game.rootUri.trim().isEmpty()
                            ? "尚未选择游戏目录" : game.rootUri);
                }
                if (game.coverUri != null && !game.coverUri.trim().isEmpty()) {
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
        game.engine = engineFromIndex(spEngine.getSelectedItemPosition());
        game.emulatorPackage = etEmulator.getText().toString().trim();
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
        LauncherTheme.formInputs(etTitle, etEmulator, etLaunchTarget, etGameHubLocalGameId, etDescription);
        LauncherTheme.longActionButton(btnPickDirectory);
        LauncherTheme.longActionButton(btnPickCover);
        LauncherTheme.shortActionButton(btnImportGameHubShortcut);
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
        if (engineFromIndex(spEngine.getSelectedItemPosition()) != EngineType.GAMEHUB) {
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
        btnImportGameHubShortcut.setText("读取中...");
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
                btnImportGameHubShortcut.setText("导入");
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
            etEmulator.setText("com.xiaoji.egggamz");
        }
    }

    private void showGameHubImportUnavailableDialog() {
        com.apps.theme.LauncherDialogFactory.showInfo(this, "未读取到盖世快捷方式",
                "请确认：\n1. Shizuku 正在运行且已授权；\n2. 盖世已创建桌面快捷方式；\n3. 已安装 com.xiaoji.egggamz 或 com.xiaoji.egggame。\n\n也可以手动填写 localGameId。");
    }

    private int engineIndex(EngineType engine) {
        if (engine == null) return 0;
        switch (engine) {
            case AUTO: return 0;
            case KIRIKIRI: return 1;
            case ONS: return 2;
            case TYRANO: return 3;
            case ARTEMIS: return 4;
            case WINLATOR: return 5;
            case GAMEHUB: return 6;
            case PSP: return 7;
            default: return 8;
        }
    }

    private EngineType engineFromIndex(int index) {
        switch (index) {
            case 0: return EngineType.AUTO;
            case 1: return EngineType.KIRIKIRI;
            case 2: return EngineType.ONS;
            case 3: return EngineType.TYRANO;
            case 4: return EngineType.ARTEMIS;
            case 5: return EngineType.WINLATOR;
            case 6: return EngineType.GAMEHUB;
            case 7: return EngineType.PSP;
            default: return EngineType.UNKNOWN;
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
