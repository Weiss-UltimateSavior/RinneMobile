package com.apps.game;

import android.graphics.Color;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.apps.LauncherActivity;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherTabletPortraitScaler;
import com.yuki.yukihub.R;
import com.yuki.yukihub.data.GameSaveFileManager;
import com.yuki.yukihub.diagnostics.GameDiagnostics;
import com.yuki.yukihub.databinding.ActivityLauncherSaveManagerBinding;
import com.yuki.yukihub.launcherbridge.LauncherRepositoryBridge;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.util.AppExecutors;

/** File operations for one game's automatically resolved built-in-engine save location. */
public class LauncherSaveManagerActivity extends AppCompatActivity {
    public static final String EXTRA_GAME_ID = "save_game_id";

    private ActivityLauncherSaveManagerBinding binding;
    private Game game;
    private GameSaveFileManager saveManager;
    private final ActivityResultLauncher<String> exportZipPicker =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/zip"), uri -> {
                if (uri != null) exportSaveToZip(uri);
            });
    private final ActivityResultLauncher<String[]> overwriteZipPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) importSaveFromZip(uri);
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherActivity.applySavedToneMode(this);
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();
        binding = ActivityLauncherSaveManagerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        LauncherTabletPortraitScaler.applyActivityContent(this);
        saveManager = new GameSaveFileManager(this);
        applySystemBarInsets();
        LauncherTheme.applyPrimaryTone(binding.getRoot());
        LauncherTheme.shortSecondaryActionButton(binding.btnExportSave);
        LauncherTheme.shortActionButton(binding.btnOverwriteSave);
        binding.btnExportSave.setOnClickListener(v -> chooseExportZip());
        binding.btnOverwriteSave.setOnClickListener(v -> confirmOverwrite());
        loadGame();
    }

    private void loadGame() {
        long gameId = getIntent().getLongExtra(EXTRA_GAME_ID, -1L);
        AppExecutors.runOnSingle(() -> {
            Game loaded = LauncherRepositoryBridge.findGameById(this, gameId);
            runOnUiThread(() -> {
                game = loaded;
                renderGame();
            });
        });
    }

    private void renderGame() {
        if (game == null) {
            binding.saveGameLabel.setText("游戏不可用");
            binding.saveManagerStatus.setText("未找到游戏记录。");
            return;
        }
        binding.saveGameLabel.setText("游戏：" + safeTitle(game) + " · " + game.engine);
        GameSaveFileManager.SaveLocation location = saveManager.resolveInternalSaveLocation(game);
        binding.saveManagerStatus.setText(location.available
                ? "导出与覆盖导入均使用 ZIP 压缩包。"
                : "无法管理存档：" + location.reason);
    }

    private void chooseExportZip() {
        if (game == null) return;
        exportZipPicker.launch(buildArchiveFileName(game));
    }

    private void exportSaveToZip(Uri destinationUri) {
        if (game == null) return;
        AppExecutors.runOnSingle(() -> {
            try {
                int count = saveManager.exportInternalSaveToZip(game, destinationUri);
                runOnUiThread(() -> Toast.makeText(this, "已导出 ZIP（" + count + " 个文件）", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                GameDiagnostics.record(this, "save_exception", game,
                        "导出存档失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage()));
                showError("导出失败", e);
            }
        });
    }

    private void chooseOverwriteZip() {
        if (game == null) return;
        String[] mimeTypes = new String[]{"application/zip", "application/x-zip-compressed"};
        overwriteZipPicker.launch(mimeTypes);
    }

    private void importSaveFromZip(Uri sourceUri) {
        if (game == null) return;
        takeReadPermission(sourceUri);
        AppExecutors.runOnSingle(() -> {
            try {
                int count = saveManager.importInternalSaveFromZip(game, sourceUri, true);
                runOnUiThread(() -> {
                    Toast.makeText(this, "已覆盖导入 " + count + " 个文件", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                GameDiagnostics.record(this, "save_exception", game,
                        "导入存档失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage()));
                showError("覆盖导入失败", e);
            }
        });
    }

    private void confirmOverwrite() {
        if (game == null) return;
        com.apps.theme.LauncherDialogFactory.showStandardConfirm(this, "覆盖导入",
                "将清空当前游戏的真实存档目录，再从 ZIP 备份导入。是否继续？",
                "选择 ZIP", this::chooseOverwriteZip);
    }

    private void takeReadPermission(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // The current SAF grant remains sufficient for providers without persistable access.
        }
    }

    private static String buildArchiveFileName(Game game) {
        String title = safeTitle(game).replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (title.isEmpty()) title = "游戏存档";
        return title + "_存档.zip";
    }

    private static String safeTitle(Game game) {
        return game == null || game.title == null || game.title.trim().isEmpty() ? "未命名游戏" : game.title;
    }

    private void showError(String title, Exception error) {
        runOnUiThread(() -> com.apps.theme.LauncherDialogFactory.showInfo(this, title,
                error.getMessage() == null ? "未知错误" : error.getMessage()));
    }

    private void applySystemBarInsets() {
        int left = binding.saveManagerScroll.getPaddingLeft();
        int top = binding.saveManagerScroll.getPaddingTop();
        int right = binding.saveManagerScroll.getPaddingRight();
        int bottom = binding.saveManagerScroll.getPaddingBottom();
        binding.saveManagerScroll.setOnApplyWindowInsetsListener((view, insets) -> {
            binding.saveManagerScroll.setPadding(left, top + insets.getSystemWindowInsetTop(), right, bottom);
            return insets;
        });
        binding.saveManagerScroll.requestApplyInsets();
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
