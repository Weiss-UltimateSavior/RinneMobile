package com.apps;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
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
import androidx.core.content.ContextCompat;

import com.yuki.yukihub.R;
import com.yuki.yukihub.launcherbridge.LauncherRepositoryBridge;
import com.yuki.yukihub.model.EngineType;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.util.AppExecutors;

public class LauncherGameEditActivity extends AppCompatActivity {
    public static final String EXTRA_GAME_ID = "extra_game_id";

    private EditText etTitle;
    private Spinner spEngine;
    private EditText etEmulator;
    private EditText etLaunchTarget;
    private EditText etDescription;
    private TextView tvDir;
    private TextView tvCoverStatus;
    private TextView btnPickCover;
    private TextView btnCancel;
    private TextView btnSave;

    private Game game;
    private Uri selectedCoverUri;

    private final ActivityResultLauncher<Intent> coverPicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null && result.getData().getData() != null) {
                    selectedCoverUri = result.getData().getData();
                    tvCoverStatus.setText("已选择封面");
                    tvCoverStatus.setTextColor(LauncherTheme.primary(this));
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherActivity.applySavedToneMode(this);
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();
        setContentView(R.layout.activity_launcher_game_edit);
        bindViews();
        applySystemBarInsets();
        bindActions();
        applyThemeTone();
        loadGame();
    }

    private void bindViews() {
        etTitle = findViewById(R.id.editTitle);
        spEngine = findViewById(R.id.editEngine);
        etEmulator = findViewById(R.id.editEmulator);
        etLaunchTarget = findViewById(R.id.editLaunchTarget);
        etDescription = findViewById(R.id.editDescription);
        tvDir = findViewById(R.id.editDir);
        tvCoverStatus = findViewById(R.id.editCoverStatus);
        btnPickCover = findViewById(R.id.btnPickCover);
        btnCancel = findViewById(R.id.btnCancel);
        btnSave = findViewById(R.id.btnSave);

        String[] engineNames = {"AUTO", "KIRIKIRI", "ONS", "TYRANO", "ARTEMIS", "WINLATOR", "GAMEHUB", "PSP", "UNKNOWN"};
        ArrayAdapter<String> adapter = LauncherTheme.spinnerAdapter(this, engineNames);
        spEngine.setAdapter(adapter);
        LauncherTheme.styleSpinner(spEngine);
    }

    private void bindActions() {
        btnPickCover.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            coverPicker.launch(intent);
        });
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
                etDescription.setText(game.description);
                tvDir.setText(game.rootUri);
                if (game.coverUri != null && !game.coverUri.trim().isEmpty()) {
                    tvCoverStatus.setText("已有封面");
                    tvCoverStatus.setTextColor(LauncherTheme.primary(this));
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
        LauncherTheme.menuItem(btnPickCover);
        LauncherTheme.secondaryButton(btnCancel);
        LauncherTheme.primaryButton(btnSave);
        LauncherTheme.applyPrimaryTone(findViewById(android.R.id.content));
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
}
