package com.apps.game;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherEditText;
import com.core.R;
import com.core.launcherbridge.LauncherRepositoryBridge;
import com.core.model.Game;
import com.core.util.AppExecutors;
import com.core.util.TimeFormatUtil;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 抽取自 {@link LauncherLibraryFragment} 与 {@code PadManageFragment} 的公共游戏动作菜单与
 * 子对话框逻辑。两个 Fragment 通过 {@link SubDialogFactory} 注入各自偏好的单选对话框实现，
 * 通过 {@link ActionMenuConfig} 控制动作菜单可选项，通过 {@link ActionMenuCallbacks} 把菜单
 * 事件回传给 Fragment 自身实现。
 */
public final class GameActionMenuFactory {

    private GameActionMenuFactory() {
    }

    /** 单选对话框工厂，由 Fragment 注入（Library 用 LauncherDialogFactory，Pad 用 PadDialogFactory）。 */
    public interface SubDialogFactory {
        void showSingleChoice(Context ctx, String title, CharSequence[] labels,
                              int checkedIndex, Consumer<Integer> onChoice);
    }

    /** 游戏数据更新回调，工厂异步写入数据库后把最新 Game 回传给 Fragment 就地刷新。 */
    public interface GameUpdateCallback {
        void onGameUpdated(Game updated);
    }

    /** 动作菜单事件回调，由 Fragment 实现。 */
    public interface ActionMenuCallbacks {
        void onShowGameDetail(Game game);
        void onEditGame(Game game);
        void onShowPlayStatus(Game game);
        void onEditPlayTime(Game game);
        void onToggleFavorite(Game game);
        void onTogglePassword(Game game);
        void onShowMoreOptions(Game game);
    }

    /** 动作菜单可选项配置。 */
    public static class ActionMenuConfig {
        public boolean includeEditAction = true;
        public boolean includeEditPlayTimeAction = false;
        public boolean includeFavoriteAction = true;
        public boolean includePasswordAction = true;
        public int dialogWidthDp = 252;
    }

    // ===== 静态 UI 元素 =====

    /** 创建透明背景的 AlertDialog 并应用 Launcher 动画。 */
    public static AlertDialog createLauncherDialog(Context ctx) {
        AlertDialog dialog = new AlertDialog.Builder(ctx).create();
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
        }
        return dialog;
    }

    /** 创建竖向 LinearLayout，统一使用 launcher_dialog_bg 作为背景。 */
    public static LinearLayout createDialogRoot(Context ctx) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 22), dp(ctx, 20), dp(ctx, 22), dp(ctx, 16));
        root.setBackgroundResource(R.drawable.launcher_dialog_bg);
        return root;
    }

    /** 创建居中加粗的标题 TextView。 */
    public static TextView createDialogTitle(Context ctx, String text) {
        TextView title = new TextView(ctx);
        title.setText(text);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setTextColor(ContextCompat.getColor(ctx, R.color.launcher_text_color));
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        return title;
    }

    /** 创建主/次操作按钮，点击后先关闭对话框再执行 action。 */
    public static TextView createDialogButton(Context ctx, String text, boolean primary,
                                              Runnable action, AlertDialog dialog) {
        TextView btn = new TextView(ctx);
        btn.setText(text);
        btn.setGravity(Gravity.CENTER);
        btn.setTextSize(13);
        btn.setTypeface(null, Typeface.BOLD);
        if (primary) {
            LauncherTheme.primaryButton(btn);
        } else {
            LauncherTheme.secondaryButton(btn);
        }
        btn.setOnClickListener(v -> {
            dialog.dismiss();
            action.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 38));
        lp.setMargins(0, dp(ctx, 9), 0, 0);
        btn.setLayoutParams(lp);
        return btn;
    }

    /** 创建圆角 chip 风格的取消按钮。 */
    public static TextView createDialogCancelButton(Context ctx, AlertDialog dialog) {
        TextView cancel = new TextView(ctx);
        cancel.setText("取消");
        cancel.setGravity(Gravity.CENTER);
        cancel.setTextColor(LauncherTheme.primary(ctx));
        cancel.setTextSize(13);
        cancel.setTypeface(null, Typeface.BOLD);
        cancel.setBackground(LauncherTheme.cancelChip(ctx));
        cancel.setOnClickListener(view -> dialog.dismiss());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 36));
        lp.setMargins(0, dp(ctx, 9), 0, 0);
        cancel.setLayoutParams(lp);
        return cancel;
    }

    /** 设置对话框内容与宽度（widthDp 为 dp 值，内部转 px）。 */
    public static void setDialogContent(AlertDialog dialog, View content, int widthDp) {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setContentView(content);
        window.setLayout(dp(content.getContext(), widthDp),
                WindowManager.LayoutParams.WRAP_CONTENT);
    }

    // ===== 动作菜单 =====

    /** 构造完整的游戏长按动作菜单，可选项由 config 控制，事件由 callbacks 回传。 */
    public static void showGameActionMenu(Fragment fragment, Game game,
                                          ActionMenuConfig config, ActionMenuCallbacks callbacks) {
        if (game == null) return;
        Context ctx = fragment.requireContext();
        AlertDialog dialog = createLauncherDialog(ctx);
        LinearLayout root = createDialogRoot(ctx);
        root.addView(createDialogTitle(ctx, GameMetadataFormatter.safeTitle(game)));

        addActionOption(ctx, root, "游戏详情", dialog, () -> callbacks.onShowGameDetail(game));
        if (config.includeEditAction) {
            addActionOption(ctx, root, "编辑游戏", dialog, () -> callbacks.onEditGame(game));
        }
        addActionOption(ctx, root, "游戏状态", dialog, () -> callbacks.onShowPlayStatus(game));
        if (config.includeEditPlayTimeAction) {
            addActionOption(ctx, root, "修改时长", dialog, () -> callbacks.onEditPlayTime(game));
        }
        if (config.includeFavoriteAction) {
            String favoriteLabel = game.favorite ? "取消收藏" : "添加收藏";
            addActionOption(ctx, root, favoriteLabel, dialog, () -> callbacks.onToggleFavorite(game));
        }
        if (config.includePasswordAction) {
            String passwordLabel = GamePasswordLock.hasPassword(game) ? "取消密码" : "密码锁定";
            addActionOption(ctx, root, passwordLabel, dialog, () -> callbacks.onTogglePassword(game));
        }
        addActionOption(ctx, root, "更多选项", dialog, () -> callbacks.onShowMoreOptions(game));

        root.addView(createDialogCancelButton(ctx, dialog));
        setDialogContent(dialog, root, config.dialogWidthDp);
    }

    /** 内部辅助：往 root 中追加一个菜单选项 TextView。 */
    private static void addActionOption(Context ctx, LinearLayout root, String label,
                                        AlertDialog dialog, Runnable action) {
        TextView option = new TextView(ctx);
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 36));
        lp.setMargins(0, dp(ctx, 11), 0, 0);
        root.addView(option, lp);
    }

    // ===== 子对话框 =====

    /** 显示游玩状态单选对话框，单选实现由 SubDialogFactory 注入。 */
    public static void showPlayStatusDialog(Fragment fragment, Game game,
                                            SubDialogFactory subDialogFactory,
                                            GameUpdateCallback callback) {
        if (game == null) return;
        String[] labels = {"未玩", "在玩", "玩过"};
        String[] values = {"unplayed", "playing", "completed"};
        int checkedIndex = -1;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(game.playStatus)) {
                checkedIndex = i;
                break;
            }
        }
        subDialogFactory.showSingleChoice(
                fragment.requireContext(),
                "设置游玩状态",
                labels,
                checkedIndex,
                index -> updateGameStatus(fragment, game, values[index], callback)
        );
    }

    /** 异步写入游戏状态，完成后通过 callback 回传最新 Game。 */
    public static void updateGameStatus(Fragment fragment, Game game, String status,
                                        GameUpdateCallback callback) {
        AppExecutors.io().execute(() -> {
            Game updated = null;
            try {
                Game latest = LauncherRepositoryBridge.findGameById(fragment.requireContext(), game.id);
                if (latest != null) {
                    latest.playStatus = status;
                    LauncherRepositoryBridge.updateGame(fragment.requireContext(), latest);
                    updated = latest;
                }
            } catch (Throwable ignored) {
            }
            final Game result = updated;
            if (fragment.getActivity() != null) fragment.getActivity().runOnUiThread(() -> {
                if (result != null) callback.onGameUpdated(result);
            });
        });
    }

    /** 显示游戏详情对话框（状态/引擎/时长/最近游玩/模拟器/路径）。 */
    public static void showGameDetailDialog(Fragment fragment, Game game) {
        if (game == null) return;
        Context ctx = fragment.requireContext();
        AlertDialog dialog = createLauncherDialog(ctx);
        LinearLayout root = createDialogRoot(ctx);
        root.addView(createDialogTitle(ctx, GameMetadataFormatter.safeTitle(game)));

        TextView info = new TextView(ctx);
        StringBuilder sb = new StringBuilder();
        sb.append("状态：").append(GameMetadataFormatter.playStatusText(game.playStatus));
        sb.append("\n引擎：").append(GameMetadataFormatter.engineText(game.engine));
        sb.append("\n总时长：").append(TimeFormatUtil.playTime(game.totalPlayTime));
        sb.append("\n最近游玩：").append(game.lastPlayedAt > 0
                ? new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(game.lastPlayedAt))
                : "未游玩");
        if (game.emulatorPackage != null && !game.emulatorPackage.trim().isEmpty()) {
            sb.append("\n模拟器：").append(game.emulatorPackage);
        }
        sb.append("\n\n路径：").append(game.rootUri == null ? "" : Uri.decode(game.rootUri));
        info.setText(sb.toString());
        info.setTextColor(ContextCompat.getColor(ctx, R.color.launcher_text_color));
        info.setTextSize(12);
        info.setLineSpacing(dp(ctx, 4), 1f);
        info.setMaxLines(14);
        info.setVerticalScrollBarEnabled(true);
        info.setMovementMethod(ScrollingMovementMethod.getInstance());
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        infoLp.setMargins(0, dp(ctx, 13), 0, 0);
        root.addView(info, infoLp);

        root.addView(createDialogCancelButton(ctx, dialog));
        setDialogContent(dialog, root, 288);
    }

    /** 显示修改游玩时长对话框，包含总时长与追加时长两个输入框。 */
    public static void showEditPlayTimeDialog(Fragment fragment, Game game,
                                              GameUpdateCallback callback) {
        if (game == null) return;
        Context ctx = fragment.requireContext();
        // 使用 Dialog 而非 AlertDialog，避免 FLAG_NOT_FOCUSABLE 导致输入法无法唤醒
        Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = createDialogRoot(ctx);
        root.addView(createDialogTitle(ctx, "修改游玩时长"));

        TextView info = new TextView(ctx);
        info.setText("当前总时长：" + TimeFormatUtil.playTime(game.totalPlayTime)
                + "\n最近游玩：" + (game.lastPlayedAt > 0
                ? new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(game.lastPlayedAt))
                : "无"));
        info.setTextColor(ContextCompat.getColor(ctx, R.color.launcher_text_muted_color));
        info.setTextSize(12);
        info.setLineSpacing(dp(ctx, 4), 1f);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        infoLp.setMargins(0, dp(ctx, 13), 0, 0);
        root.addView(info, infoLp);

        TextView totalLabel = new TextView(ctx);
        totalLabel.setText("设置新的总时长");
        totalLabel.setTextColor(ContextCompat.getColor(ctx, R.color.launcher_text_color));
        totalLabel.setTextSize(12);
        totalLabel.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams tlLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlLp.setMargins(0, dp(ctx, 13), 0, 0);
        root.addView(totalLabel, tlLp);

        EditText totalInput = new LauncherEditText(ctx);
        totalInput.setHint("例如 3h 20m / 200m / 7200s / 2.5h");
        totalInput.setTextColor(ContextCompat.getColor(ctx, R.color.launcher_text_color));
        totalInput.setHintTextColor(ContextCompat.getColor(ctx, R.color.launcher_input_hint_color));
        totalInput.setTextSize(13);
        totalInput.setPadding(dp(ctx, 13), dp(ctx, 9), dp(ctx, 13), dp(ctx, 9));
        totalInput.setBackground(LauncherTheme.cancelChip(ctx));
        LauncherTheme.styleTextInput(totalInput);
        LinearLayout.LayoutParams tiLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tiLp.setMargins(0, dp(ctx, 5), 0, 0);
        root.addView(totalInput, tiLp);

        TextView addLabel = new TextView(ctx);
        addLabel.setText("追加游玩时长");
        addLabel.setTextColor(ContextCompat.getColor(ctx, R.color.launcher_text_color));
        addLabel.setTextSize(12);
        addLabel.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams alLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alLp.setMargins(0, dp(ctx, 11), 0, 0);
        root.addView(addLabel, alLp);

        EditText addInput = new LauncherEditText(ctx);
        addInput.setHint("例如 30m / 1h30m / 0.5h");
        addInput.setTextColor(ContextCompat.getColor(ctx, R.color.launcher_text_color));
        addInput.setHintTextColor(ContextCompat.getColor(ctx, R.color.launcher_input_hint_color));
        addInput.setTextSize(13);
        addInput.setPadding(dp(ctx, 13), dp(ctx, 9), dp(ctx, 13), dp(ctx, 9));
        addInput.setBackground(LauncherTheme.cancelChip(ctx));
        LauncherTheme.styleTextInput(addInput);
        LinearLayout.LayoutParams aiLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        aiLp.setMargins(0, dp(ctx, 5), 0, 0);
        root.addView(addInput, aiLp);

        TextView hint = new TextView(ctx);
        hint.setText("可填 d/h/m/s 单位组合，纯数字视为分钟");
        hint.setTextColor(ContextCompat.getColor(ctx, R.color.launcher_text_muted_color));
        hint.setTextSize(11);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hLp.setMargins(0, dp(ctx, 7), 0, 0);
        root.addView(hint, hLp);

        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setWeightSum(2f);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        brLp.setMargins(0, dp(ctx, 13), 0, 0);
        btnRow.setLayoutParams(brLp);

        TextView cancelBtn = new TextView(ctx);
        cancelBtn.setText("取消");
        cancelBtn.setGravity(Gravity.CENTER);
        cancelBtn.setTextSize(13);
        cancelBtn.setTypeface(null, Typeface.BOLD);
        LauncherTheme.secondaryButton(cancelBtn);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, dp(ctx, 38), 1f);
        cancelLp.setMargins(0, 0, dp(ctx, 5), 0);
        cancelBtn.setLayoutParams(cancelLp);
        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        btnRow.addView(cancelBtn);

        TextView saveBtn = new TextView(ctx);
        saveBtn.setText("保存");
        saveBtn.setGravity(Gravity.CENTER);
        saveBtn.setTextSize(13);
        saveBtn.setTypeface(null, Typeface.BOLD);
        LauncherTheme.primaryButton(saveBtn);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0, dp(ctx, 38), 1f);
        saveLp.setMargins(dp(ctx, 5), 0, 0, 0);
        saveBtn.setLayoutParams(saveLp);
        saveBtn.setOnClickListener(v -> {
            Long totalMinutes = GameMetadataFormatter.parseDuration(totalInput.getText().toString().trim());
            Long addMinutes = GameMetadataFormatter.parseDuration(addInput.getText().toString().trim());
            if (totalMinutes == null && addMinutes == null) {
                Toast.makeText(ctx, "请输入有效时长", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            updatePlayTime(fragment, game, totalMinutes, addMinutes, callback);
        });
        btnRow.addView(saveBtn);
        root.addView(btnRow);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        dialog.setContentView(root);
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);
        if (window != null) {
            window.setLayout(dp(ctx, 288), WindowManager.LayoutParams.WRAP_CONTENT);
        }

        totalInput.requestFocus();
        totalInput.post(() -> {
            InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(totalInput, 0);
        });
    }

    /** 异步写入游玩时长，完成后通过 callback 回传最新 Game。 */
    public static void updatePlayTime(Fragment fragment, Game game, Long totalMinutes,
                                      Long addMinutes, GameUpdateCallback callback) {
        AppExecutors.io().execute(() -> {
            Game updated = null;
            try {
                Game latest = LauncherRepositoryBridge.findGameById(fragment.requireContext(), game.id);
                if (latest != null) {
                    long finalDuration = latest.totalPlayTime;
                    if (totalMinutes != null) finalDuration = totalMinutes * 60_000L;
                    if (addMinutes != null) finalDuration += addMinutes * 60_000L;
                    long clamped = Math.max(0, finalDuration);
                    LauncherRepositoryBridge.setManualPlayTimeForGame(fragment.requireContext(), latest.id, clamped);
                    latest.totalPlayTime = clamped;
                    updated = latest;
                }
            } catch (Throwable ignored) {
            }
            final Game result = updated;
            if (fragment.getActivity() != null) fragment.getActivity().runOnUiThread(() -> {
                if (result != null) callback.onGameUpdated(result);
            });
        });
    }

    // ===== 工具方法 =====

    /** 通过 LauncherTheme.dp 把 dp 值转换为像素。 */
    public static int dp(Context ctx, int value) {
        return LauncherTheme.dp(ctx, value);
    }

    /** 兼容 long 入参的工具方法。 */
    public static int dp(@NonNull Context ctx, float value) {
        return LauncherTheme.dp(ctx, value);
    }
}
