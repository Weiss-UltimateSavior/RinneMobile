package com.apps.game;

import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.apps.theme.LauncherDialogFactory;
import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;
import com.core.R;
import com.core.importer.ImportGameData;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨端导入预览专用弹窗。
 *
 * Controller 只负责业务流转，本类集中处理带多选列表的复杂 UI 模板。
 */
final class ExternalImportPreviewDialog {

    interface Callback {
        void onImport();

        void onCancel();
    }

    private ExternalImportPreviewDialog() {
    }

    static void show(ManageHost host, List<ImportGameData> games, Callback callback) {
        AlertDialog dialog = new AlertDialog.Builder(host.requireContext()).create();
        dialog.setOnCancelListener(ignored -> callback.onCancel());
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);

        Window window = dialog.getWindow();
        if (window == null) {
            callback.onCancel();
            if (dialog.isShowing()) dialog.dismiss();
            return;
        }
        window.setBackgroundDrawableResource(android.R.color.transparent);
        // 宽度按 300dp 指定，并通过 LauncherDialogFactory 做屏幕宽度兜底（左右各留 16dp + 平板竖屏缩放），避免小屏溢出
        window.setLayout(LauncherDialogFactory.dialogWidthPx(host.requireContext(), 300),
                WindowManager.LayoutParams.WRAP_CONTENT);

        LinearLayout root = new LinearLayout(host.requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(host.dp(22), host.dp(18), host.dp(22), host.dp(15));
        root.setBackgroundResource(com.core.R.drawable.launcher_dialog_bg);

        root.addView(createTitle(host, host.getString(R.string.game_import_preview)),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        int totalCount = games.size();
        long existCount = games.stream().filter(g -> g.exists).count();
        TextView info = new TextView(host.requireContext());
        info.setText(host.getString(R.string.game_import_preview_count, totalCount, existCount));
        info.setGravity(android.view.Gravity.CENTER);
        info.setTextColor(ContextCompat.getColor(host.requireContext(), com.core.R.color.launcher_text_muted_color));
        host.setResponsiveTextSize(info, 12);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        infoLp.setMargins(0, host.dp(10), 0, 0);
        root.addView(info, infoLp);

        LinearLayout listContainer = new LinearLayout(host.requireContext());
        listContainer.setOrientation(LinearLayout.VERTICAL);

        List<CheckBox> checkBoxes = new ArrayList<>();
        for (ImportGameData game : games) {
            listContainer.addView(createImportPreviewRow(host, game, checkBoxes));
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

        TextView importButton = new TextView(host.requireContext());
        importButton.setText(R.string.game_common_import);
        importButton.setGravity(android.view.Gravity.CENTER);
        host.setResponsiveTextSize(importButton, 13);
        importButton.setTypeface(null, android.graphics.Typeface.BOLD);
        LauncherTheme.primaryButton(importButton);
        LinearLayout.LayoutParams importLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        importLp.setMargins(host.dp(6), 0, 0, 0);
        importButton.setOnClickListener(v -> {
            dialog.dismiss();
            for (int i = 0; i < games.size(); i++) {
                games.get(i).selected = checkBoxes.get(i).isChecked() && !games.get(i).exists;
            }
            callback.onImport();
        });
        buttons.addView(importButton, importLp);

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
            callback.onCancel();
        });
        buttons.addView(cancel, cancelLp);

        root.addView(buttons, buttonsLp);
        window.setContentView(root);
    }

    private static TextView createTitle(ManageHost host, String text) {
        TextView title = new TextView(host.requireContext());
        title.setText(text);
        title.setGravity(android.view.Gravity.CENTER);
        title.setTextColor(ContextCompat.getColor(host.requireContext(), com.core.R.color.launcher_text_color));
        host.setResponsiveTextSize(title, 16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        return title;
    }

    private static View createImportPreviewRow(ManageHost host, ImportGameData game, List<CheckBox> outCheckBoxes) {
        LinearLayout row = new LinearLayout(host.requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(host.dp(2), host.dp(7), host.dp(2), host.dp(7));

        CheckBox checkBox = new CheckBox(host.requireContext());
        checkBox.setChecked(game.selected);
        checkBox.setEnabled(!game.exists);
        checkBox.setClickable(!game.exists);
        LinearLayout.LayoutParams checkBoxLp = new LinearLayout.LayoutParams(host.dp(28), host.dp(28));
        row.addView(checkBox, checkBoxLp);
        outCheckBoxes.add(checkBox);

        LinearLayout textColumn = new LinearLayout(host.requireContext());
        textColumn.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textColumnLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        textColumnLp.setMargins(host.dp(6), 0, 0, 0);
        textColumn.setLayoutParams(textColumnLp);

        TextView name = new TextView(host.requireContext());
        name.setText(game.name);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTextColor(ContextCompat.getColor(host.requireContext(),
                game.exists ? com.core.R.color.launcher_text_muted_color
                        : com.core.R.color.launcher_text_color));
        host.setResponsiveTextSize(name, 13);
        textColumn.addView(name, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView status = new TextView(host.requireContext());
        status.setText(buildStatusText(host, game));
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setTextColor(ContextCompat.getColor(host.requireContext(), com.core.R.color.launcher_text_muted_color));
        host.setResponsiveTextSize(status, 10);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusLp.setMargins(0, host.dp(2), 0, 0);
        textColumn.addView(status, statusLp);

        row.addView(textColumn);
        return row;
    }

    private static String buildStatusText(ManageHost host, ImportGameData game) {
        StringBuilder statusText = new StringBuilder();
        if (game.exists) {
            statusText.append(host.getString(R.string.game_import_exists_skip));
        } else {
            statusText.append(host.getString(R.string.game_import_new_game));
            if (game.totalPlayTime > 0) {
                statusText.append(host.getString(
                        R.string.game_import_duration, formatSeconds(host, game.totalPlayTime)));
            }
            if (game.lunaBoxSessions != null && !game.lunaBoxSessions.isEmpty()) {
                statusText.append(host.getString(R.string.game_import_records, game.lunaBoxSessions.size()));
            } else if (game.vniteTimers != null && !game.vniteTimers.isEmpty()) {
                statusText.append(host.getString(R.string.game_import_records, game.vniteTimers.size()));
            } else if (game.playedTimeMap != null && !game.playedTimeMap.isEmpty()) {
                statusText.append(host.getString(R.string.game_import_records, game.playedTimeMap.size()));
            }
        }
        return statusText.toString();
    }

    private static String formatSeconds(ManageHost host, long seconds) {
        if (seconds <= 0) return host.getString(R.string.game_duration_minutes, 0);
        long minutes = seconds / 60;
        if (minutes < 60) return host.getString(R.string.game_duration_minutes, minutes);
        long hours = minutes / 60;
        long remainMinutes = minutes % 60;
        return host.getString(R.string.game_duration_hours_minutes, hours,
                remainMinutes > 0
                        ? host.getString(R.string.game_duration_remaining_minutes, remainMinutes) : "");
    }
}
