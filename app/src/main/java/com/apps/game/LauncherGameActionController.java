package com.apps.game;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.yuki.yukihub.R;
import com.yuki.yukihub.launcherbridge.LauncherMetadataBridge;
import com.yuki.yukihub.launcherbridge.LauncherRepositoryBridge;
import com.yuki.yukihub.model.EngineType;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.util.AppExecutors;
import com.yuki.yukihub.util.TimeFormatUtil;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.apps.settings.LauncherCustomVndbSearchDialog;
import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;

/** Shared long-press game actions used by Launcher-style library surfaces. */
public final class LauncherGameActionController {
    public interface Host {
        void refreshGames();
        void editGame(Game game);
    }

    private final Fragment fragment;
    private final Host host;
    private final boolean includeEditAction;

    private LauncherGameActionController(Fragment fragment, Host host, boolean includeEditAction) {
        this.fragment = fragment;
        this.host = host;
        this.includeEditAction = includeEditAction;
    }

    public static void show(Fragment fragment, Game game, Host host) {
        show(fragment, game, host, true);
    }

    public static void show(Fragment fragment, Game game, Host host, boolean includeEditAction) {
        if (fragment == null || game == null || host == null || !fragment.isAdded()) return;
        new LauncherGameActionController(fragment, host, includeEditAction).showGameActionMenu(game);
    }

    private Context context() {
        return fragment.requireContext();
    }

    private void showGameActionMenu(Game game) {
        AlertDialog dialog = createLauncherDialog();
        LinearLayout root = createDialogRoot();
        root.addView(createDialogTitle(safeTitle(game)));
        addAction(root, "详情", dialog, () -> showGameDetailDialog(game));
        if (includeEditAction) {
            addAction(root, "编辑", dialog, () -> host.editGame(game));
        }
        addAction(root, "状态", dialog, () -> showPlayStatusDialog(game));
        addAction(root, "修改时长", dialog, () -> showEditPlayTimeDialog(game));
        addAction(root, "更多选项", dialog, () -> showMoreOptionsDialog(game));
        root.addView(createDialogCancelButton(dialog));
        setDialogContent(dialog, root, 300);
    }

    private void addAction(LinearLayout root, String label, AlertDialog dialog, Runnable action) {
        TextView option = new TextView(context());
        option.setText(label);
        option.setGravity(Gravity.CENTER);
        option.setSingleLine(true);
        option.setTextSize(13);
        option.setTypeface(null, Typeface.BOLD);
        LauncherTheme.menuItem(option);
        option.setOnClickListener(view -> {
            dialog.dismiss();
            action.run();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        params.setMargins(0, dp(10), 0, 0);
        root.addView(option, params);
    }

    private void showPlayStatusDialog(Game game) {
        AlertDialog dialog = createLauncherDialog();
        LinearLayout root = createDialogRoot();
        root.addView(createDialogTitle("设置游玩状态"));
        String[] labels = {"☆ 未玩", "🎮 在玩", "🏆 玩过"};
        String[] values = {"unplayed", "playing", "completed"};
        for (int i = 0; i < labels.length; i++) {
            String status = values[i];
            TextView option = new TextView(context());
            option.setText((status.equals(game.playStatus) ? "● " : "○ ") + labels[i]);
            option.setGravity(Gravity.CENTER);
            option.setTextColor(ContextCompat.getColor(context(), R.color.launcher_text_color));
            option.setTextSize(13);
            option.setTypeface(null, Typeface.BOLD);
            option.setBackground(LauncherTheme.cancelChip(context()));
            option.setOnClickListener(view -> {
                dialog.dismiss();
                updateGameStatus(game, status);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
            params.setMargins(0, dp(11), 0, 0);
            root.addView(option, params);
        }
        root.addView(createDialogCancelButton(dialog));
        setDialogContent(dialog, root, 280);
    }

    private void updateGameStatus(Game game, String status) {
        runGameUpdate(game, latest -> latest.playStatus = status, null);
    }

    private void showEditPlayTimeDialog(Game game) {
        Dialog dialog = new Dialog(context());
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = createDialogRoot();
        root.addView(createDialogTitle("修改游玩时长"));

        TextView info = bodyText("当前总时长：" + TimeFormatUtil.playTime(game.totalPlayTime)
                + "\n最近游玩：" + (game.lastPlayedAt > 0
                ? new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(new Date(game.lastPlayedAt)) : "无"), true);
        addWithTopMargin(root, info, 13);

        TextView totalLabel = label("设置新的总时长");
        addWithTopMargin(root, totalLabel, 12);
        EditText totalInput = durationInput("例如 3h 20m / 200m / 7200s / 2.5h");
        addWithTopMargin(root, totalInput, 5);

        TextView addLabel = label("追加游玩时长");
        addWithTopMargin(root, addLabel, 10);
        EditText addInput = durationInput("例如 30m / 1h30m / 0.5h");
        addWithTopMargin(root, addInput, 5);

        TextView hint = bodyText("可填 d/h/m/s 单位组合，纯数字视为分钟", true);
        hint.setTextSize(11);
        addWithTopMargin(root, hint, 7);

        LinearLayout buttons = new LinearLayout(context());
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = button("取消", false);
        cancel.setOnClickListener(view -> dialog.dismiss());
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0, dp(38), 1f);
        cancelParams.setMargins(0, 0, dp(5), 0);
        buttons.addView(cancel, cancelParams);
        TextView save = button("保存", true);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, dp(38), 1f);
        saveParams.setMargins(dp(5), 0, 0, 0);
        buttons.addView(save, saveParams);
        addWithTopMargin(root, buttons, 12);

        save.setOnClickListener(view -> {
            Long totalMinutes = parseDuration(totalInput.getText().toString());
            Long addMinutes = parseDuration(addInput.getText().toString());
            if (totalMinutes == null && addMinutes == null) {
                Toast.makeText(context(), "请输入有效时长", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            updatePlayTime(game, totalMinutes, addMinutes);
        });

        dialog.setContentView(root);
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
            window.setLayout(dp(340), WindowManager.LayoutParams.WRAP_CONTENT);
        }
        totalInput.requestFocus();
        totalInput.post(() -> {
            InputMethodManager manager = (InputMethodManager)
                    context().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (manager != null) manager.showSoftInput(totalInput, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void updatePlayTime(Game game, Long totalMinutes, Long addMinutes) {
        Context app = context().getApplicationContext();
        AppExecutors.io().execute(() -> {
            try {
                Game latest = LauncherRepositoryBridge.findGameById(app, game.id);
                if (latest != null) {
                    long duration = latest.totalPlayTime;
                    if (totalMinutes != null) duration = totalMinutes * 60_000L;
                    if (addMinutes != null) duration += addMinutes * 60_000L;
                    LauncherRepositoryBridge.setManualPlayTimeForGame(app, latest.id, Math.max(0L, duration));
                }
            } catch (Throwable ignored) { }
            postRefresh(null);
        });
    }

    private void showGameDetailDialog(Game game) {
        AlertDialog dialog = createLauncherDialog();
        LinearLayout root = createDialogRoot();
        root.addView(createDialogTitle(safeTitle(game)));
        StringBuilder text = new StringBuilder();
        text.append("状态：").append(playStatusText(game.playStatus));
        text.append("\n引擎：").append(engineText(game.engine));
        text.append("\n总时长：").append(TimeFormatUtil.playTime(game.totalPlayTime));
        text.append("\n最近游玩：").append(game.lastPlayedAt > 0
                ? new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(new Date(game.lastPlayedAt)) : "未游玩");
        if (!TextUtils.isEmpty(game.emulatorPackage)) text.append("\n模拟器：").append(game.emulatorPackage);
        text.append("\n\n路径：").append(game.rootUri);
        TextView info = bodyText(text.toString(), false);
        addWithTopMargin(root, info, 13);
        root.addView(createDialogCancelButton(dialog));
        setDialogContent(dialog, root, 360);
    }

    private void showMoreOptionsDialog(Game game) {
        AlertDialog dialog = createLauncherDialog();
        LinearLayout root = createDialogRoot();
        root.addView(createDialogTitle("更多选项"));
        addMoreOption(root, dialog, game.favorite ? "取消收藏" : "添加收藏", false,
                () -> toggleFavorite(game));
        addMoreOption(root, dialog, "重新匹配 VNDB 元数据", false,
                () -> rematchMetadata(game));
        addMoreOption(root, dialog, "自定义搜索 VNDB", false,
                () -> LauncherCustomVndbSearchDialog.show(fragment, game, host::refreshGames));
        addMoreOption(root, dialog, "同步元数据封面到卡片", false,
                () -> syncMetadataToCard(game));
        addMoreOption(root, dialog, "删除游戏", true, () -> confirmDeleteGame(game));
        root.addView(createDialogCancelButton(dialog));
        setDialogContent(dialog, root, 320);
    }

    private void addMoreOption(LinearLayout root, AlertDialog dialog, String text,
                               boolean danger, Runnable action) {
        TextView option = new TextView(context());
        option.setText(text);
        option.setGravity(Gravity.CENTER);
        option.setTextSize(13);
        option.setTypeface(null, Typeface.BOLD);
        if (danger) LauncherTheme.dangerMenuItem(option); else LauncherTheme.menuItem(option);
        option.setOnClickListener(view -> {
            dialog.dismiss();
            action.run();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
        params.setMargins(0, dp(11), 0, 0);
        root.addView(option, params);
    }

    private void toggleFavorite(Game game) {
        runGameUpdate(game, latest -> latest.favorite = !latest.favorite, null);
    }

    private void rematchMetadata(Game game) {
        Toast.makeText(context(), "正在搜索 VNDB...", Toast.LENGTH_SHORT).show();
        LauncherMetadataBridge.fetchAndSaveMetadataAsync(context(), game, success ->
                postRefresh(success ? "元数据已更新" : "未找到匹配的元数据", success));
    }

    private void syncMetadataToCard(Game game) {
        Toast.makeText(context(), "正在同步封面...", Toast.LENGTH_SHORT).show();
        LauncherMetadataBridge.syncCoverToGameAsync(context(), game, success ->
                postRefresh(success ? "封面已同步" : "无可用封面", success));
    }

    private void confirmDeleteGame(Game game) {
        AlertDialog dialog = createLauncherDialog();
        LinearLayout root = createDialogRoot();
        root.addView(createDialogTitle("删除游戏"));
        TextView message = bodyText("要删除「" + safeTitle(game)
                + "」吗？此操作仅移除游戏库不进行实际删除。", true);
        message.setGravity(Gravity.CENTER);
        addWithTopMargin(root, message, 13);

        LinearLayout buttons = new LinearLayout(context());
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = button("取消", false);
        cancel.setOnClickListener(view -> dialog.dismiss());
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0, dp(38), 1f);
        cancelParams.setMargins(0, 0, dp(5), 0);
        buttons.addView(cancel, cancelParams);
        TextView delete = button("移除", false);
        LauncherTheme.dangerButton(delete);
        delete.setOnClickListener(view -> {
            dialog.dismiss();
            deleteGame(game);
        });
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(0, dp(38), 1f);
        deleteParams.setMargins(dp(5), 0, 0, 0);
        buttons.addView(delete, deleteParams);
        addWithTopMargin(root, buttons, 13);
        setDialogContent(dialog, root, 320);
    }

    private void deleteGame(Game game) {
        Context app = context().getApplicationContext();
        AppExecutors.io().execute(() -> {
            try {
                LauncherRepositoryBridge.deleteGame(app, game.id);
            } catch (Throwable ignored) { }
            postRefresh("已删除");
        });
    }

    private interface GameMutation {
        void apply(Game game);
    }

    private void runGameUpdate(Game game, GameMutation mutation, String message) {
        Context app = context().getApplicationContext();
        AppExecutors.io().execute(() -> {
            try {
                Game latest = LauncherRepositoryBridge.findGameById(app, game.id);
                if (latest != null) {
                    mutation.apply(latest);
                    LauncherRepositoryBridge.updateGame(app, latest);
                }
            } catch (Throwable ignored) { }
            postRefresh(message);
        });
    }

    private void postRefresh(String message) {
        postRefresh(message, true);
    }

    private void postRefresh(String message, boolean refresh) {
        Activity activity = fragment.getActivity();
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            if (!fragment.isAdded()) return;
            if (message != null) Toast.makeText(context(), message, Toast.LENGTH_SHORT).show();
            if (refresh) host.refreshGames();
        });
    }

    private AlertDialog createLauncherDialog() {
        AlertDialog dialog = new AlertDialog.Builder(context()).create();
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);
        Window window = dialog.getWindow();
        if (window != null) window.setBackgroundDrawableResource(android.R.color.transparent);
        return dialog;
    }

    private LinearLayout createDialogRoot() {
        LinearLayout root = new LinearLayout(context());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(18), dp(22), dp(15));
        root.setBackgroundResource(R.drawable.launcher_dialog_bg);
        return root;
    }

    private TextView createDialogTitle(String text) {
        TextView title = new TextView(context());
        title.setText(text);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextColor(ContextCompat.getColor(context(), R.color.launcher_text_color));
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        return title;
    }

    private TextView createDialogCancelButton(Dialog dialog) {
        TextView cancel = button("取消", false);
        cancel.setOnClickListener(view -> dialog.dismiss());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        params.setMargins(0, dp(9), 0, 0);
        cancel.setLayoutParams(params);
        return cancel;
    }

    private TextView button(String text, boolean primary) {
        TextView button = new TextView(context());
        button.setText(text);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(13);
        button.setTypeface(null, Typeface.BOLD);
        if (primary) LauncherTheme.primaryButton(button); else LauncherTheme.secondaryButton(button);
        return button;
    }

    private TextView label(String text) {
        TextView label = new TextView(context());
        label.setText(text);
        label.setTextColor(ContextCompat.getColor(context(), R.color.launcher_text_color));
        label.setTextSize(12);
        label.setTypeface(null, Typeface.BOLD);
        return label;
    }

    private TextView bodyText(String text, boolean muted) {
        TextView view = new TextView(context());
        view.setText(text);
        view.setTextColor(ContextCompat.getColor(context(), muted
                ? R.color.launcher_text_muted_color : R.color.launcher_text_color));
        view.setTextSize(12);
        view.setLineSpacing(dp(4), 1f);
        return view;
    }

    private EditText durationInput(String hint) {
        EditText input = new EditText(context());
        input.setHint(hint);
        input.setSingleLine(true);
        input.setTextColor(ContextCompat.getColor(context(), R.color.launcher_text_color));
        input.setHintTextColor(ContextCompat.getColor(context(), R.color.launcher_input_hint_color));
        input.setTextSize(13);
        input.setPadding(dp(13), dp(8), dp(13), dp(8));
        input.setBackground(LauncherTheme.cancelChip(context()));
        return input;
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

    private Long parseDuration(String input) {
        if (input == null || input.trim().isEmpty()) return null;
        String text = input.trim().toLowerCase(Locale.ROOT);
        try {
            if (!text.matches(".*[dhms].*")) return (long) Double.parseDouble(text);
            long minutes = 0L;
            boolean found = false;
            Matcher matcher = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*([dhms])").matcher(text);
            while (matcher.find()) {
                found = true;
                double value = Double.parseDouble(matcher.group(1));
                String unit = matcher.group(2);
                if ("d".equals(unit)) minutes += value * 1440;
                else if ("h".equals(unit)) minutes += value * 60;
                else if ("m".equals(unit)) minutes += value;
                else minutes += value / 60;
            }
            return found ? minutes : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String safeTitle(Game game) {
        return game == null || TextUtils.isEmpty(game.title) ? "未命名游戏" : game.title.trim();
    }

    private String playStatusText(String status) {
        if ("playing".equals(status)) return "在玩";
        if ("completed".equals(status)) return "玩过";
        return "未玩";
    }

    private String engineText(EngineType engine) {
        if (engine == null) return "未知";
        switch (engine) {
            case KIRIKIRI: return "Kirikiri";
            case ONS: return "ONS";
            case TYRANO: return "Tyrano";
            case ARTEMIS: return "Artemis";
            case WINLATOR: return "Winlator";
            case GAMEHUB: return "GameHub";
            case PSP: return "PSP";
            default: return "未知";
        }
    }

    private int dp(int value) {
        return (int) (value * context().getResources().getDisplayMetrics().density + 0.5f);
    }
}
