package com.apps.game;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.apps.LauncherActivity;
import com.apps.theme.LauncherDialogFactory;
import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherTabletPortraitScaler;
import com.yuki.yukihub.R;
import com.yuki.yukihub.data.GameSaveFileManager;
import com.yuki.yukihub.diagnostics.GameDiagnostics;
import com.yuki.yukihub.databinding.ActivityLauncherSaveGameListBinding;
import com.yuki.yukihub.launcherbridge.LauncherRepositoryBridge;
import com.yuki.yukihub.model.EngineType;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.util.AppExecutors;
import com.yuki.yukihub.util.TimeFormatUtil;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

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
        configureEdgeToEdgeWindow();
        binding = ActivityLauncherSaveGameListBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        LauncherTabletPortraitScaler.applyActivityContent(this);
        saveManager = new GameSaveFileManager(this);
        engineName = getIntent().getStringExtra(EXTRA_ENGINE);
        EngineType engine = EngineType.fromString(engineName);
        binding.saveGameListTitle.setText(LauncherSaveCategoryActivity.engineLabel(engine) + " 游戏");
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
                    } catch (Throwable ignored) {
                        // An unreadable location is presented as no save instead of blocking the list.
                    }
                    saveStates.add(hasSave);
                }
            }
            runOnUiThread(() -> {
                binding.saveGameList.removeAllViews();
                if (!LauncherSaveCategoryActivity.isSupportedBuiltInEngine(requestedEngine)) {
                    binding.saveGameListStatus.setText("该类型不是内置模拟器，不提供存档管理。");
                    return;
                }
                for (int index = 0; index < managedGames.size(); index++) {
                    addGame(managedGames.get(index), saveStates.get(index));
                }
                int count = managedGames.size();
                binding.saveGameListStatus.setText(count == 0 ? "该模拟器下暂无游戏。" : "共 " + count + " 个游戏，选择后管理真实存档文件。 ");
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
        String gameTitle = game.title == null || game.title.trim().isEmpty() ? "未命名游戏" : game.title.trim();
        icon.setText(firstTitleChar(gameTitle));
        title.setText(gameTitle);
        meta.setText(recentMeta(game));
        status.setText("●");
        status.setTextColor(hasSave ? LauncherTheme.primary(this)
                : ContextCompat.getColor(this, R.color.launcher_danger_color));
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
                "暂无存档",
                "“" + safeTitle(game) + "”当前没有可管理的存档文件。可从 ZIP 备份恢复存档。",
                "导入 ZIP",
                () -> {
                    selectedSaveGame = game;
                    overwriteZipPicker.launch(new String[]{"application/zip", "application/x-zip-compressed"});
                }
        );
    }

    private void showSaveActionsDialog(Game game) {
        LauncherDialogFactory.showStandardActionChoices(
                this,
                abbreviateGameTitle(game) + " 存档",
                new String[]{"导出 ZIP", "导入 ZIP"},
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
                "覆盖导入",
                "将清空当前游戏的真实存档，再从 ZIP 备份导入。",
                "选择 ZIP",
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
                runOnUiThread(() -> Toast.makeText(this, "已导出 ZIP（" + count + " 个文件）", Toast.LENGTH_LONG).show());
            } catch (Exception error) {
                GameDiagnostics.record(this, "save_exception", game,
                        "导出存档失败：" + (error.getMessage() == null ? "未知错误" : error.getMessage()));
                showError("导出失败", error);
            }
        });
    }

    private void importSaveFromZip(Game game, Uri sourceUri) {
        takeReadPermission(sourceUri);
        AppExecutors.runOnSingle(() -> {
            try {
                int count = saveManager.importInternalSaveFromZip(game, sourceUri, true);
                runOnUiThread(() -> Toast.makeText(this, "已覆盖导入 " + count + " 个文件", Toast.LENGTH_LONG).show());
            } catch (Exception error) {
                GameDiagnostics.record(this, "save_exception", game,
                        "导入存档失败：" + (error.getMessage() == null ? "未知错误" : error.getMessage()));
                showError("覆盖导入失败", error);
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
        runOnUiThread(() -> LauncherDialogFactory.showInfo(this, title,
                error.getMessage() == null ? "未知错误" : error.getMessage()));
    }

    private AlertDialog createLauncherDialog() {
        AlertDialog dialog = new AlertDialog.Builder(this).create();
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);
        Window window = dialog.getWindow();
        if (window != null) window.setBackgroundDrawableResource(android.R.color.transparent);
        return dialog;
    }

    private LinearLayout createDialogRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(20), dp(22), dp(16));
        root.setBackgroundResource(R.drawable.launcher_dialog_bg);
        LauncherTheme.applyPrimaryTone(root);
        return root;
    }

    private TextView createDialogTitle(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_color));
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        return title;
    }

    private TextView createDialogOption(String text) {
        TextView option = new TextView(this);
        option.setText(text);
        option.setGravity(Gravity.CENTER);
        option.setSingleLine(true);
        option.setTextSize(13);
        option.setTypeface(null, Typeface.BOLD);
        LauncherTheme.menuItem(option);
        option.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)));
        return option;
    }

    private TextView createDialogCancelButton(AlertDialog dialog) {
        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setGravity(Gravity.CENTER);
        cancel.setTextColor(LauncherTheme.primary(this));
        cancel.setTextSize(13);
        cancel.setTypeface(null, Typeface.BOLD);
        cancel.setBackground(LauncherTheme.cancelChip(this));
        cancel.setOnClickListener(view -> dialog.dismiss());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        params.setMargins(0, dp(9), 0, 0);
        cancel.setLayoutParams(params);
        return cancel;
    }

    private void addWithTopMargin(LinearLayout root, View child, int marginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(marginDp), 0, 0);
        root.addView(child, params);
    }

    private void setDialogContent(AlertDialog dialog, View content, int widthDp) {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setContentView(content);
        window.setLayout(dp(widthDp), WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String buildArchiveFileName(Game game) {
        String title = safeTitle(game).replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (title.isEmpty()) title = "游戏存档";
        return title + "_存档.zip";
    }

    private static String safeTitle(Game game) {
        return game == null || game.title == null || game.title.trim().isEmpty() ? "未命名游戏" : game.title.trim();
    }

    private static String abbreviateGameTitle(Game game) {
        String title = safeTitle(game);
        if (title.codePointCount(0, title.length()) <= 6) return title;
        return title.substring(0, title.offsetByCodePoints(0, 6)) + "...";
    }

    private static String firstTitleChar(String title) {
        if (title == null || title.isEmpty()) return "游";
        int end = title.offsetByCodePoints(0, 1);
        return title.substring(0, end);
    }

    private static String recentMeta(Game game) {
        if (game != null && game.lastPlayedAt > 0L) {
            String time = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(game.lastPlayedAt));
            return time + " · " + TimeFormatUtil.playTime(game.totalPlayTime);
        }
        return "尚未游玩";
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
