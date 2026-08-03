package com.apps.game;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.apps.LauncherActivity;
import com.apps.theme.LauncherDialogFactory;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherTabletPortraitScaler;
import com.core.R;
import com.core.data.GameSaveFileManager;
import com.core.diagnostics.GameDiagnostics;
import com.core.databinding.ActivityLauncherSaveGameListBinding;
import com.core.launcherbridge.LauncherRepositoryBridge;
import com.core.model.EngineType;
import com.core.model.Game;
import com.core.util.AppExecutors;
import com.core.util.TimeFormatUtil;

import java.util.ArrayList;
import java.util.List;

/** Second-level screen: lists only games belonging to one emulator type. */
public class LauncherSaveGameListActivity extends AppCompatActivity {
    public static final String EXTRA_ENGINE = "save_engine";
    private ActivityLauncherSaveGameListBinding binding;
    private String engineName;
    private GameSaveFileManager saveManager;
    private Game selectedSaveGame;
    private final ActivityResultLauncher<String> exportZipPicker =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/zip"), uri -> {
                if (uri != null && selectedSaveGame != null) exportSaveToZip(selectedSaveGame, uri);
            });
    private final ActivityResultLauncher<String[]> overwriteZipPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null && selectedSaveGame != null) importSaveFromZip(selectedSaveGame, uri);
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherActivity.applySavedToneMode(this);
        super.onCreate(savedInstanceState);
        com.apps.LauncherEdgeToEdgeHelper.apply(this);
        binding = ActivityLauncherSaveGameListBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        LauncherTabletPortraitScaler.applyActivityContent(this);
        saveManager = new GameSaveFileManager(this);
        engineName = getIntent().getStringExtra(EXTRA_ENGINE);
        EngineType engine = EngineType.fromString(engineName);
        binding.saveGameListTitle.setText(getString(R.string.game_save_engine_games,
                LauncherSaveCategoryActivity.engineLabel(this, engine)));
        applySystemBarInsets();
        LauncherTheme.applyPrimaryTone(binding.getRoot());
        loadGames();
    }

    private void loadGames() {
        AppExecutors.runOnSingle(() -> {
            List<Game> games = LauncherRepositoryBridge.getAllGames(this);
            EngineType requestedEngine = EngineType.fromString(engineName);
            List<Game> managedGames = new ArrayList<>();
            List<Boolean> saveStates = new ArrayList<>();
            if (LauncherSaveCategoryActivity.isSupportedBuiltInEngine(requestedEngine)) {
                for (Game game : games) {
                    String gameEngine = game == null || game.engine == null ? EngineType.UNKNOWN.name() : game.engine.name();
                    if (!gameEngine.equals(engineName) || !LauncherSaveCategoryActivity.isSupportedBuiltInGame(game)) continue;
                    managedGames.add(game);
                    boolean hasSave = false;
                    try {
                        hasSave = !saveManager.listInternalSaveFiles(game).isEmpty();
                    } catch (Exception ignored) {
                        // An unreadable location is presented as no save instead of blocking the list.
                    }
                    saveStates.add(hasSave);
                }
            }
            runOnUiThread(() -> {
                if (isUiUnavailable()) return;
                binding.saveGameList.removeAllViews();
                if (!LauncherSaveCategoryActivity.isSupportedBuiltInEngine(requestedEngine)) {
                    binding.saveGameListStatus.setText(R.string.game_save_not_internal);
                    return;
                }
                for (int index = 0; index < managedGames.size(); index++) {
                    addGame(managedGames.get(index), saveStates.get(index));
                }
                int count = managedGames.size();
                binding.saveGameListStatus.setText(count == 0
                        ? getString(R.string.game_save_engine_empty)
                        : getString(R.string.game_save_engine_count, count));
            });
        });
    }

    private void addGame(Game game, boolean hasSave) {
        // Reuse the same recent-activity card as the homepage so this
        // secondary list stays visually aligned with the launcher feed.
        View itemView = LayoutInflater.from(this).inflate(R.layout.item_launcher_recent, binding.saveGameList, false);
        LauncherTabletPortraitScaler.apply(itemView);
        TextView icon = itemView.findViewById(R.id.recentIcon);
        TextView title = itemView.findViewById(R.id.recentTitle);
        TextView meta = itemView.findViewById(R.id.recentMeta);
        TextView status = itemView.findViewById(R.id.recentStatus);
        String gameTitle = safeTitle(game);
        icon.setText(firstTitleChar(gameTitle));
        title.setText(gameTitle);
        meta.setText(recentMeta(game));
        status.setText("●");
        status.setTextColor(hasSave ? LauncherTheme.primary(this)
                : LauncherTheme.danger(this));
        itemView.setClickable(true);
        itemView.setFocusable(true);
        itemView.setOnClickListener(v -> {
            if (hasSave) showSaveActionsDialog(game);
            else showNoSaveImportDialog(game);
        });
        LauncherTheme.applyPrimaryTone(itemView);
        binding.saveGameList.addView(itemView);
    }

    private void showNoSaveImportDialog(Game game) {
        LauncherDialogFactory.showStandardConfirm(
                this,
                getString(R.string.game_save_none),
                getString(R.string.game_save_none_message, safeTitle(game)),
                getString(R.string.game_save_import_zip),
                () -> {
                    selectedSaveGame = game;
                    overwriteZipPicker.launch(new String[]{"application/zip", "application/x-zip-compressed"});
                }
        );
    }

    private void showSaveActionsDialog(Game game) {
        LauncherDialogFactory.showStandardActionChoices(
                this,
                getString(R.string.game_save_game_title, abbreviateGameTitle(game)),
                new String[]{getString(R.string.game_save_export_zip),
                        getString(R.string.game_save_import_zip)},
                index -> {
                    if (index == 0) {
                        selectedSaveGame = game;
                        exportZipPicker.launch(buildArchiveFileName(game));
                    } else {
                        showOverwriteConfirmDialog(game);
                    }
                }
        );
    }

    private void showOverwriteConfirmDialog(Game game) {
        LauncherDialogFactory.showStandardConfirm(
                this,
                getString(R.string.game_save_overwrite_import),
                getString(R.string.game_save_overwrite_short_message),
                getString(R.string.game_save_choose_zip),
                () -> {
                    selectedSaveGame = game;
                    overwriteZipPicker.launch(new String[]{"application/zip", "application/x-zip-compressed"});
                }
        );
    }

    private void exportSaveToZip(Game game, Uri destinationUri) {
        AppExecutors.runOnSingle(() -> {
            try {
                int count = saveManager.exportInternalSaveToZip(game, destinationUri);
                runOnUiThread(() -> {
                    if (isUiUnavailable()) return;
                    Toast.makeText(this,
                            getString(R.string.game_save_exported_count, count), Toast.LENGTH_LONG).show();
                });
            } catch (Exception error) {
                GameDiagnostics.record(this, "save_exception", game,
                        getString(R.string.game_save_export_failed_detail,
                                error.getMessage() == null
                                        ? getString(R.string.game_common_unknown_error) : error.getMessage()));
                showError(getString(R.string.game_save_export_failed), error);
            }
        });
    }

    private void importSaveFromZip(Game game, Uri sourceUri) {
        takeReadPermission(sourceUri);
        AppExecutors.runOnSingle(() -> {
            try {
                int count = saveManager.importInternalSaveFromZip(game, sourceUri, true);
                runOnUiThread(() -> {
                    if (isUiUnavailable()) return;
                    Toast.makeText(this,
                            getString(R.string.game_save_imported_count, count), Toast.LENGTH_LONG).show();
                });
            } catch (Exception error) {
                GameDiagnostics.record(this, "save_exception", game,
                        getString(R.string.game_save_import_failed_detail,
                                error.getMessage() == null
                                        ? getString(R.string.game_common_unknown_error) : error.getMessage()));
                showError(getString(R.string.game_save_overwrite_failed), error);
            }
        });
    }

    private void takeReadPermission(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // The picker grant remains valid for providers without persistable access.
        }
    }

    private void showError(String title, Exception error) {
        runOnUiThread(() -> {
            if (isUiUnavailable()) return;
            LauncherDialogFactory.showInfo(this, title,
                    error.getMessage() == null
                            ? getString(R.string.game_common_unknown_error) : error.getMessage());
        });
    }

    private boolean isUiUnavailable() {
        return isFinishing() || isDestroyed();
    }

    private String buildArchiveFileName(Game game) {
        String title = safeTitle(game).replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (title.isEmpty()) title = getString(R.string.game_save_default_archive_title);
        return title + getString(R.string.game_save_archive_suffix);
    }

    private String safeTitle(Game game) {
        return game == null || game.title == null || game.title.trim().isEmpty()
                ? getString(R.string.game_unnamed) : game.title.trim();
    }

    private String abbreviateGameTitle(Game game) {
        String title = safeTitle(game);
        if (title.codePointCount(0, title.length()) <= 6) return title;
        return title.substring(0, title.offsetByCodePoints(0, 6)) + "...";
    }

    private String firstTitleChar(String title) {
        if (title == null || title.isEmpty()) return getString(R.string.game_default_initial);
        int end = title.offsetByCodePoints(0, 1);
        return title.substring(0, end);
    }

    private String recentMeta(Game game) {
        if (game != null && game.lastPlayedAt > 0L) {
            String time = TimeFormatUtil.shortDate(game.lastPlayedAt);
            return time + " · " + TimeFormatUtil.playTime(game.totalPlayTime);
        }
        return getString(R.string.game_save_never_played);
    }

    private void applySystemBarInsets() {
        int left = binding.saveGameListScroll.getPaddingLeft();
        int top = binding.saveGameListScroll.getPaddingTop();
        int right = binding.saveGameListScroll.getPaddingRight();
        int bottom = binding.saveGameListScroll.getPaddingBottom();
        binding.saveGameListScroll.setOnApplyWindowInsetsListener((view, insets) -> {
            binding.saveGameListScroll.setPadding(left, top + insets.getSystemWindowInsetTop(), right, bottom);
            return insets;
        });
        binding.saveGameListScroll.requestApplyInsets();
    }

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase));
    }
}
