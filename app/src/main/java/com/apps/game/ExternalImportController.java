package com.apps.game;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;
import com.core.R;
import com.core.importer.ImportGameData;
import com.core.importer.ImportResult;
import com.core.importer.ImporterService;
import com.core.importer.LunaBoxImporter;
import com.core.importer.PlayniteImporter;
import com.core.importer.PotatoVnImporter;
import com.core.importer.VniteImporter;
import com.core.util.AppExecutors;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 LauncherManageFragment 抽取的跨端同步导入控制器。
 *
 * 负责从 Playnite / PotatoVN / Vnite / LunaBox 各平台解析数据、预览候选列表、
 * 用户勾选后写入库。所有 Fragment 相关能力通过 {@link ManageHost} 桥接，
 * 各平台 ActivityResultLauncher 由 Fragment 注册后通过构造器注入。
 */
public final class ExternalImportController {

    private final ManageHost host;
    private final ActivityResultLauncher<String[]> playniteLauncher;
    private final ActivityResultLauncher<String[]> potatovnLauncher;
    private final ActivityResultLauncher<Uri> vniteLauncher;
    private final ActivityResultLauncher<String[]> lunaboxLauncher;
    private AlertDialog importLoadingDialog;

    public ExternalImportController(ManageHost host,
                                    ActivityResultLauncher<String[]> playniteLauncher,
                                    ActivityResultLauncher<String[]> potatovnLauncher,
                                    ActivityResultLauncher<Uri> vniteLauncher,
                                    ActivityResultLauncher<String[]> lunaboxLauncher) {
        this.host = host;
        this.playniteLauncher = playniteLauncher;
        this.potatovnLauncher = potatovnLauncher;
        this.vniteLauncher = vniteLauncher;
        this.lunaboxLauncher = lunaboxLauncher;
    }

    public void showExternalImportDialog() {
        if (host.isImportInProgress()) return;
        AlertDialog dialog = new AlertDialog.Builder(host.requireContext()).create();
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);

        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(host.dp(252), WindowManager.LayoutParams.WRAP_CONTENT);

        LinearLayout root = new LinearLayout(host.requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(host.dp(22), host.dp(20), host.dp(22), host.dp(16));
        root.setBackgroundResource(com.core.R.drawable.launcher_dialog_bg);

        root.addView(host.createDialogTitle(host.getString(R.string.game_import_cross_platform)),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView info = new TextView(host.requireContext());
        info.setText(R.string.game_import_source_message);
        info.setGravity(android.view.Gravity.CENTER);
        info.setTextColor(ContextCompat.getColor(host.requireContext(), com.core.R.color.launcher_text_muted_color));
        host.setResponsiveTextSize(info, 12);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        infoLp.setMargins(0, host.dp(11), 0, 0);
        root.addView(info, infoLp);

        host.addFeedbackOption(root, "Playnite（JSON）", dialog, () ->
                playniteLauncher.launch(new String[]{"application/json", "text/*", "*/*"}));
        host.addFeedbackOption(root, "PotatoVN（ZIP）", dialog, () ->
                potatovnLauncher.launch(new String[]{"application/zip", "application/*zip*", "*/*"}));
        host.addFeedbackOption(root, host.getString(R.string.game_import_vnite_directory), dialog, () ->
                vniteLauncher.launch(null));
        host.addFeedbackOption(root, "LunaBox（ZIP）", dialog, () ->
                lunaboxLauncher.launch(new String[]{"application/zip", "application/*zip*", "*/*"}));

        TextView cancel = host.createDialogCancelButton(dialog);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, host.dp(36));
        cancelLp.setMargins(0, host.dp(9), 0, 0);
        root.addView(cancel, cancelLp);

        window.setContentView(root);
    }

    public void doImportFromPlaynite(Uri uri) {
        android.content.Context appContext = host.getAppContext();
        parseAndPreview(appContext, () -> PlayniteImporter.parse(appContext, uri));
    }

    public void doImportFromPotatoVn(Uri uri) {
        android.content.Context appContext = host.getAppContext();
        parseAndPreview(appContext, () -> PotatoVnImporter.parse(appContext, uri));
    }

    public void doImportFromVnite(Uri uri) {
        android.content.Context appContext = host.getAppContext();
        parseAndPreview(appContext, () -> VniteImporter.parse(appContext, uri));
    }

    public void doImportFromLunaBox(Uri uri) {
        android.content.Context appContext = host.getAppContext();
        parseAndPreview(appContext, () -> LunaBoxImporter.parse(appContext, uri));
    }

    @FunctionalInterface
    private interface ParseTask {
        List<ImportGameData> parse() throws Exception;
    }

    private void parseAndPreview(android.content.Context appContext, ParseTask task) {
        host.setImportInProgress(true);
        showImportLoading(host.getString(R.string.game_import_parsing));
        AppExecutors.runOnSingle(() -> {
            try {
                List<ImportGameData> games = task.parse();
                new ImporterService(appContext).markExisting(games);
                host.getMainQueue().post(() -> {
                    if (!host.isAdded()) return;
                    dismissImportLoading();
                    showImportPreviewDialog(games);
                });
            } catch (Error error) {
                // OOM/VirtualMachineError 必须传播，避免在已损坏的 JVM 状态下继续运行
                throw error;
            } catch (Exception e) {
                Log.e("LauncherManage", "external import parse failed", e);
                host.getMainQueue().post(() -> {
                    if (!host.isAdded()) return;
                    dismissImportLoading();
                    host.setImportInProgress(false);
                    host.showConfirmDialog(host.getString(R.string.game_import_parse_failed),
                            e.getMessage() != null ? e.getMessage()
                                    : host.getString(R.string.game_common_unknown_error),
                            host.getString(R.string.game_common_got_it), () -> {});
                });
            }
        });
    }

    private void showImportLoading(String hint) {
        dismissImportLoading();
        importLoadingDialog = host.showScanLoadingDialog(
                host.getString(R.string.game_import_importing), hint);
        importLoadingDialog.setCancelable(false);
        importLoadingDialog.setCanceledOnTouchOutside(false);
    }

    private void dismissImportLoading() {
        if (importLoadingDialog != null && importLoadingDialog.isShowing()) {
            importLoadingDialog.dismiss();
        }
        importLoadingDialog = null;
    }

    private void showImportPreviewDialog(List<ImportGameData> games) {
        if (games == null || games.isEmpty()) {
            host.setImportInProgress(false);
            host.showConfirmDialog(host.getString(R.string.game_import_none_title),
                    host.getString(R.string.game_import_none_message),
                    host.getString(R.string.game_common_got_it), () -> {});
            return;
        }

        AlertDialog dialog = new AlertDialog.Builder(host.requireContext()).create();
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);

        Window window = dialog.getWindow();
        if (window == null) {
            host.setImportInProgress(false);
            ImporterService.cancelImport();
            if (dialog.isShowing()) dialog.dismiss();
            return;
        }
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(host.dp(300), WindowManager.LayoutParams.WRAP_CONTENT);

        LinearLayout root = new LinearLayout(host.requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(host.dp(22), host.dp(18), host.dp(22), host.dp(15));
        root.setBackgroundResource(com.core.R.drawable.launcher_dialog_bg);

        root.addView(host.createDialogTitle(host.getString(R.string.game_import_preview)),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        int totalCount = games.size();
        long existCount = games.stream().filter(g -> g.exists).count();
        TextView info = new TextView(host.requireContext());
        info.setText(host.getString(R.string.game_import_preview_count, totalCount, existCount));
        info.setGravity(android.view.Gravity.CENTER);
        info.setTextColor(ContextCompat.getColor(host.requireContext(), com.core.R.color.launcher_text_muted_color));
        host.setResponsiveTextSize(info, 12);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        infoLp.setMargins(0, host.dp(10), 0, 0);
        root.addView(info, infoLp);

        LinearLayout listContainer = new LinearLayout(host.requireContext());
        listContainer.setOrientation(LinearLayout.VERTICAL);

        List<CheckBox> checkBoxes = new ArrayList<>();
        for (ImportGameData g : games) {
            listContainer.addView(createImportPreviewRow(g, checkBoxes));
        }

        ScrollView scroll = new ScrollView(host.requireContext());
        scroll.addView(listContainer);
        int scrollHeight = Math.min(host.dp(280), host.dp(8) + totalCount * host.dp(54));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, scrollHeight);
        scrollLp.setMargins(0, host.dp(4), 0, 0);
        root.addView(scroll, scrollLp);

        LinearLayout buttons = new LinearLayout(host.requireContext());
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setWeightSum(3);
        LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, host.dp(36));
        buttonsLp.setMargins(0, host.dp(12), 0, 0);

        TextView toggleAll = new TextView(host.requireContext());
        toggleAll.setText(R.string.game_import_select_all);
        toggleAll.setGravity(android.view.Gravity.CENTER);
        host.setResponsiveTextSize(toggleAll, 13);
        toggleAll.setTypeface(null, android.graphics.Typeface.BOLD);
        LauncherTheme.secondaryButton(toggleAll);
        toggleAll.setOnClickListener(v -> {
            boolean anyUnchecked = false;
            for (int i = 0; i < games.size(); i++) {
                if (!games.get(i).exists && !checkBoxes.get(i).isChecked()) {
                    anyUnchecked = true;
                    break;
                }
            }
            for (int i = 0; i < games.size(); i++) {
                if (!games.get(i).exists) checkBoxes.get(i).setChecked(anyUnchecked);
            }
        });
        buttons.addView(toggleAll, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));

        TextView importBtn = new TextView(host.requireContext());
        importBtn.setText(R.string.game_common_import);
        importBtn.setGravity(android.view.Gravity.CENTER);
        host.setResponsiveTextSize(importBtn, 13);
        importBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        LauncherTheme.primaryButton(importBtn);
        LinearLayout.LayoutParams importLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        importLp.setMargins(host.dp(6), 0, 0, 0);
        importBtn.setOnClickListener(v -> {
            dialog.dismiss();
            for (int i = 0; i < games.size(); i++) {
                games.get(i).selected = checkBoxes.get(i).isChecked() && !games.get(i).exists;
            }
            executeExternalImport(games);
        });
        buttons.addView(importBtn, importLp);

        TextView cancel = new TextView(host.requireContext());
        cancel.setText(R.string.game_common_cancel);
        cancel.setGravity(android.view.Gravity.CENTER);
        host.setResponsiveTextSize(cancel, 13);
        cancel.setTypeface(null, android.graphics.Typeface.BOLD);
        LauncherTheme.secondaryButton(cancel);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        cancelLp.setMargins(host.dp(6), 0, 0, 0);
        cancel.setOnClickListener(v -> {
            dialog.dismiss();
            host.setImportInProgress(false);
            ImporterService.cancelImport();
        });
        buttons.addView(cancel, cancelLp);

        root.addView(buttons, buttonsLp);
        window.setContentView(root);
    }

    private View createImportPreviewRow(ImportGameData g, List<CheckBox> outCheckBoxes) {
        LinearLayout row = new LinearLayout(host.requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(host.dp(2), host.dp(7), host.dp(2), host.dp(7));

        CheckBox cb = new CheckBox(host.requireContext());
        cb.setChecked(g.selected);
        cb.setEnabled(!g.exists);
        cb.setClickable(!g.exists);
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(host.dp(28), host.dp(28));
        row.addView(cb, cbLp);
        outCheckBoxes.add(cb);

        LinearLayout textCol = new LinearLayout(host.requireContext());
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textColLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        textColLp.setMargins(host.dp(6), 0, 0, 0);
        textCol.setLayoutParams(textColLp);

        TextView name = new TextView(host.requireContext());
        name.setText(g.name);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTextColor(ContextCompat.getColor(host.requireContext(),
                g.exists ? com.core.R.color.launcher_text_muted_color
                        : com.core.R.color.launcher_text_color));
        host.setResponsiveTextSize(name, 13);
        textCol.addView(name, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView status = new TextView(host.requireContext());
        StringBuilder statusText = new StringBuilder();
        if (g.exists) {
            statusText.append(host.getString(R.string.game_import_exists_skip));
        } else {
            statusText.append(host.getString(R.string.game_import_new_game));
            if (g.totalPlayTime > 0) statusText.append(
                    host.getString(R.string.game_import_duration, formatSeconds(g.totalPlayTime)));
            if (g.lunaBoxSessions != null && !g.lunaBoxSessions.isEmpty()) {
                statusText.append(host.getString(
                        R.string.game_import_records, g.lunaBoxSessions.size()));
            } else if (g.vniteTimers != null && !g.vniteTimers.isEmpty()) {
                statusText.append(host.getString(
                        R.string.game_import_records, g.vniteTimers.size()));
            } else if (g.playedTimeMap != null && !g.playedTimeMap.isEmpty()) {
                statusText.append(host.getString(
                        R.string.game_import_records, g.playedTimeMap.size()));
            }
        }
        status.setText(statusText.toString());
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setTextColor(ContextCompat.getColor(host.requireContext(), com.core.R.color.launcher_text_muted_color));
        host.setResponsiveTextSize(status, 10);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusLp.setMargins(0, host.dp(2), 0, 0);
        textCol.addView(status, statusLp);

        row.addView(textCol);
        return row;
    }

    private String formatSeconds(long seconds) {
        if (seconds <= 0) return host.getString(R.string.game_duration_minutes, 0);
        long minutes = seconds / 60;
        if (minutes < 60) return host.getString(R.string.game_duration_minutes, minutes);
        long hours = minutes / 60;
        long remainMinutes = minutes % 60;
        return host.getString(R.string.game_duration_hours_minutes, hours,
                remainMinutes > 0
                        ? host.getString(R.string.game_duration_remaining_minutes, remainMinutes) : "");
    }

    private void executeExternalImport(List<ImportGameData> games) {
        android.content.Context appContext = host.getAppContext();
        showImportLoading(host.getString(R.string.game_import_writing));
        AppExecutors.runOnSingle(() -> {
            try {
                ImportResult result = new ImporterService(appContext).importSelected(games);
                host.getMainQueue().post(() -> {
                    if (!host.isAdded()) return;
                    dismissImportLoading();
                    host.setImportInProgress(false);
                    afterExternalImport(result);
                });
            } catch (Error error) {
                // OOM/VirtualMachineError 必须传播，避免在已损坏的 JVM 状态下继续运行
                throw error;
            } catch (Exception e) {
                Log.e("LauncherManage", "external import write failed", e);
                host.getMainQueue().post(() -> {
                    if (!host.isAdded()) return;
                    dismissImportLoading();
                    host.setImportInProgress(false);
                    host.showConfirmDialog(host.getString(R.string.game_import_failed),
                            e.getMessage() != null ? e.getMessage()
                                    : host.getString(R.string.game_common_unknown_error),
                            host.getString(R.string.game_common_got_it), () -> {});
                });
            }
        });
    }

    private void afterExternalImport(ImportResult result) {
        if (result == null) {
            host.showConfirmDialog(host.getString(R.string.game_import_complete),
                    host.getString(R.string.game_import_not_performed),
                    host.getString(R.string.game_common_got_it), () -> {});
            return;
        }
        StringBuilder msg = new StringBuilder(result.summary());
        if (!result.skippedNames.isEmpty()) {
            msg.append(host.getString(R.string.game_import_skipped_items));
            for (String n : result.skippedNames) msg.append("\n• ").append(n);
        }
        if (!result.failedNames.isEmpty()) {
            msg.append(host.getString(R.string.game_import_failed_items));
            for (String n : result.failedNames) msg.append("\n• ").append(n);
        }
        host.showConfirmDialog(host.getString(R.string.game_import_complete), msg.toString(),
                host.getString(R.string.game_common_got_it), () -> {});
    }

    public void cleanup() {
        dismissImportLoading();
        host.setImportInProgress(false);
    }
}
