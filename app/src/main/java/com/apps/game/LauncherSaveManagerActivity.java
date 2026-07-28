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
import com.core.R;
import com.core.data.GameSaveFileManager;
import com.core.diagnostics.GameDiagnostics;
import com.core.databinding.ActivityLauncherSaveManagerBinding;
import com.core.launcherbridge.LauncherRepositoryBridge;
import com.core.model.Game;
import com.core.util.AppExecutors;

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
            binding.saveGameLabel.setText(R.string.game_save_unavailable);
            binding.saveManagerStatus.setText(R.string.game_save_record_not_found);
            return;
        }
        binding.saveGameLabel.setText(getString(
                R.string.game_save_game_format, safeTitle(game), game.engine));
        GameSaveFileManager.SaveLocation location = saveManager.resolveInternalSaveLocation(game);
        binding.saveManagerStatus.setText(location.available
                ? getString(R.string.game_save_zip_description)
                : getString(R.string.game_save_unavailable_reason, location.reason));
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
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.game_save_exported_count, count), Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                GameDiagnostics.record(this, "save_exception", game,
                        getString(R.string.game_save_export_failed_detail,
                                e.getMessage() == null
                                        ? getString(R.string.game_common_unknown_error) : e.getMessage()));
                showError(getString(R.string.game_save_export_failed), e);
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
                    Toast.makeText(this, getString(R.string.game_save_imported_count, count),
                            Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                GameDiagnostics.record(this, "save_exception", game,
                        getString(R.string.game_save_import_failed_detail,
                                e.getMessage() == null
                                        ? getString(R.string.game_common_unknown_error) : e.getMessage()));
                showError(getString(R.string.game_save_overwrite_failed), e);
            }
        });
    }

    private void confirmOverwrite() {
        if (game == null) return;
        com.apps.theme.LauncherDialogFactory.showStandardConfirm(this,
                getString(R.string.game_save_overwrite_import),
                getString(R.string.game_save_overwrite_message),
                getString(R.string.game_save_choose_zip), this::chooseOverwriteZip);
    }

    private void takeReadPermission(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // The current SAF grant remains sufficient for providers without persistable access.
        }
    }

    private String buildArchiveFileName(Game game) {
        String title = safeTitle(game).replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (title.isEmpty()) title = getString(R.string.game_save_default_archive_title);
        return title + getString(R.string.game_save_archive_suffix);
    }

    private String safeTitle(Game game) {
        return game == null || game.title == null || game.title.trim().isEmpty()
                ? getString(R.string.game_unnamed) : game.title;
    }

    private void showError(String title, Exception error) {
        runOnUiThread(() -> com.apps.theme.LauncherDialogFactory.showInfo(this, title,
                error.getMessage() == null
                        ? getString(R.string.game_common_unknown_error) : error.getMessage()));
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
