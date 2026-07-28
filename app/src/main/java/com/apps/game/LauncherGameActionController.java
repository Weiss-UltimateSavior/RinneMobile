package com.apps.game;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
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

import com.core.R;
import com.core.launcherbridge.LauncherMetadataBridge;
import com.core.launcherbridge.LauncherRepositoryBridge;
import com.core.model.EngineType;
import com.core.model.Game;
import com.core.util.AppExecutors;
import com.core.util.TimeFormatUtil;

import com.apps.settings.LauncherCustomVndbSearchDialog;
import com.apps.settings.LauncherKrkrSettingsActivity;
import com.apps.theme.LauncherDialogFactory;
import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;

/** Shared long-press game actions used by Launcher-style library surfaces. */
public final class LauncherGameActionController {
    public interface Host {
        void refreshGames();
        void editGame(Game game);

        /** Update a single game in-place without resetting list position. Default falls back to full refresh. */
        default void updateGame(Game updated) { refreshGames(); }
        /** Remove a single game by id without resetting list position. Default falls back to full refresh. */
        default void removeGame(long gameId) { refreshGames(); }
        /** Re-fetch a single game from DB and update in-place. Default falls back to full refresh. */
        default void reloadGame(long gameId) { refreshGames(); }
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
        root.addView(createDialogTitle(GameMetadataFormatter.safeTitle(context(), game)));
        addAction(root, context().getString(R.string.game_action_details), dialog, () -> showGameDetailDialog(game));
        if (includeEditAction) {
            addAction(root, context().getString(R.string.game_action_edit), dialog, () -> host.editGame(game));
        }
        addAction(root, context().getString(R.string.game_action_status), dialog, () -> showPlayStatusDialog(game));
        addAction(root, context().getString(game.favorite
                ? R.string.game_action_favorite_remove : R.string.game_action_favorite_add), dialog, () -> toggleFavorite(game));
        addAction(root, context().getString(GamePasswordLock.hasPassword(game)
                ? R.string.game_action_password_remove : R.string.game_action_password_lock), dialog,
                () -> {
                    if (GamePasswordLock.hasPassword(game)) {
                        GamePasswordLock.clearPassword(fragment, game, null);
                    } else {
                        GamePasswordLock.setPassword(fragment, game, null);
                    }
                });
        addAction(root, context().getString(R.string.game_action_more), dialog, () -> showMoreOptionsDialog(game));
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
        String[] labels = {
                context().getString(R.string.game_status_unplayed),
                context().getString(R.string.game_status_playing),
                context().getString(R.string.game_status_completed)
        };
        String[] values = {"unplayed", "playing", "completed"};
        int checkedIndex = -1;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(game.playStatus)) {
                checkedIndex = i;
                break;
            }
        }
        LauncherDialogFactory.showSingleChoice(
                context(),
                context().getString(R.string.game_action_set_status),
                labels,
                checkedIndex,
                index -> updateGameStatus(game, values[index])
        );
    }

    private void updateGameStatus(Game game, String status) {
        runGameUpdate(game, latest -> latest.playStatus = status, null);
    }

    private void showEditPlayTimeDialog(Game game) {
        Dialog dialog = new Dialog(context());
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = createDialogRoot();
        root.addView(createDialogTitle(context().getString(R.string.game_action_edit_duration)));

        TextView info = bodyText(context().getString(R.string.game_action_current_duration,
                TimeFormatUtil.playTime(game.totalPlayTime), game.lastPlayedAt > 0
                        ? TimeFormatUtil.date(game.lastPlayedAt)
                        : context().getString(R.string.game_action_none)), true);
        addWithTopMargin(root, info, 13);

        TextView totalLabel = label(context().getString(R.string.game_action_set_total_duration));
        addWithTopMargin(root, totalLabel, 12);
        EditText totalInput = durationInput(context().getString(R.string.game_action_total_duration_hint));
        addWithTopMargin(root, totalInput, 5);

        TextView addLabel = label(context().getString(R.string.game_action_add_duration));
        addWithTopMargin(root, addLabel, 10);
        EditText addInput = durationInput(context().getString(R.string.game_action_add_duration_hint));
        addWithTopMargin(root, addInput, 5);

        TextView hint = bodyText(context().getString(R.string.game_action_duration_units_hint), true);
        hint.setTextSize(11);
        addWithTopMargin(root, hint, 7);

        LinearLayout buttons = new LinearLayout(context());
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = button(context().getString(R.string.game_common_cancel), false);
        cancel.setOnClickListener(view -> dialog.dismiss());
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0, dp(38), 1f);
        cancelParams.setMargins(0, 0, dp(5), 0);
        buttons.addView(cancel, cancelParams);
        TextView save = button(context().getString(R.string.game_common_save), true);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, dp(38), 1f);
        saveParams.setMargins(dp(5), 0, 0, 0);
        buttons.addView(save, saveParams);
        addWithTopMargin(root, buttons, 12);

        save.setOnClickListener(view -> {
            Long totalMinutes = GameMetadataFormatter.parseDuration(totalInput.getText().toString());
            Long addMinutes = GameMetadataFormatter.parseDuration(addInput.getText().toString());
            if (totalMinutes == null && addMinutes == null) {
                Toast.makeText(context(), R.string.game_action_invalid_duration, Toast.LENGTH_SHORT).show();
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
            Game updated = null;
            boolean failed = false;
            try {
                Game latest = LauncherRepositoryBridge.findGameById(app, game.id);
                if (latest != null) {
                    long duration = latest.totalPlayTime;
                    if (totalMinutes != null) duration = totalMinutes * 60_000L;
                    if (addMinutes != null) duration += addMinutes * 60_000L;
                    long clamped = Math.max(0L, duration);
                    LauncherRepositoryBridge.setManualPlayTimeForGame(app, latest.id, clamped);
                    latest.totalPlayTime = clamped;
                    updated = latest;
                }
            } catch (Exception e) {
                Log.w("LauncherGameAction", "setManualPlayTimeForGame failed", e);
                failed = true;
            }
            final Game result = updated;
            final boolean failedFinal = failed;
            Activity activity = fragment.getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (!fragment.isAdded()) return;
                if (failedFinal) {
                    Toast.makeText(context(), R.string.game_action_edit_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (result != null) host.updateGame(result);
                else host.refreshGames();
            });
        });
    }

    private void showGameDetailDialog(Game game) {
        AlertDialog dialog = createLauncherDialog();
        LinearLayout root = createDialogRoot();
        root.addView(createDialogTitle(GameMetadataFormatter.safeTitle(context(), game)));
        StringBuilder text = new StringBuilder();
        text.append(context().getString(R.string.game_detail_format,
                GameMetadataFormatter.playStatusText(context(), game.playStatus),
                GameMetadataFormatter.engineText(context(), game.engine),
                TimeFormatUtil.playTime(game.totalPlayTime),
                game.lastPlayedAt > 0 ? TimeFormatUtil.date(game.lastPlayedAt)
                        : context().getString(R.string.game_status_never_played)));
        if (!TextUtils.isEmpty(game.emulatorPackage)) {
            text.append("\n").append(context().getString(
                    R.string.game_detail_emulator, game.emulatorPackage));
        }
        text.append("\n\n").append(context().getString(R.string.game_detail_path,
                game.rootUri == null ? "" : Uri.decode(game.rootUri)));
        TextView info = bodyText(text.toString(), false);
        addWithTopMargin(root, info, 13);
        root.addView(createDialogCancelButton(dialog));
        setDialogContent(dialog, root, 360);
    }

    private void showMoreOptionsDialog(Game game) {
        AlertDialog dialog = createLauncherDialog();
        LinearLayout root = createDialogRoot();
        root.addView(createDialogTitle(context().getString(R.string.game_action_more)));
        addMoreOption(root, dialog, context().getString(R.string.game_action_edit_duration), false, () -> showEditPlayTimeDialog(game));
        addMoreOption(root, dialog, context().getString(R.string.game_action_pin_shortcut), false,
                () -> PinnedGameShortcut.requestPinShortcut(context(), game));
        addMoreOption(root, dialog, context().getString(R.string.game_action_rematch_vndb), false,
                () -> rematchMetadata(game));
        addMoreOption(root, dialog, context().getString(R.string.game_action_custom_vndb), false,
                () -> LauncherCustomVndbSearchDialog.show(fragment, game, () -> host.reloadGame(game.id)));
        addMoreOption(root, dialog, context().getString(R.string.game_action_sync_cover), false,
                () -> syncMetadataToCard(game));
        // ONS 引擎游戏支持单独配置 ONS 引擎参数（编码/拉伸/锐化/视频/独立存档目录等）
        if (game.engine == EngineType.ONS) {
            addMoreOption(root, dialog, context().getString(R.string.game_action_ons_settings), false, () -> openOnsGameSettings(game));
        }
        addMoreOption(root, dialog, context().getString(R.string.game_action_delete), true, () -> confirmDeleteGame(game));
        root.addView(createDialogCancelButton(dialog));
        setDialogContent(dialog, root, 320);
    }

    private void openOnsGameSettings(Game game) {
        try {
            Intent intent = new Intent(context(), LauncherKrkrSettingsActivity.class);
            intent.putExtra(LauncherKrkrSettingsActivity.EXTRA_GAME_ID, game.id);
            if (!(context() instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context().startActivity(intent);
        } catch (Throwable ignored) {
            Toast.makeText(context(), R.string.game_action_ons_open_failed, Toast.LENGTH_SHORT).show();
        }
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
        Toast.makeText(context(), R.string.game_vndb_searching, Toast.LENGTH_SHORT).show();
        LauncherMetadataBridge.fetchAndSaveMetadataAsync(context(), game, success -> {
            Activity activity = fragment.getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (!fragment.isAdded()) return;
                Toast.makeText(context(), success
                        ? R.string.game_metadata_updated : R.string.game_metadata_not_found,
                        Toast.LENGTH_SHORT).show();
                if (success) host.reloadGame(game.id);
            });
        });
    }

    private void syncMetadataToCard(Game game) {
        Toast.makeText(context(), R.string.game_cover_syncing, Toast.LENGTH_SHORT).show();
        LauncherMetadataBridge.syncCoverToGameAsync(context(), game, success -> {
            Activity activity = fragment.getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (!fragment.isAdded()) return;
                Toast.makeText(context(), success
                        ? R.string.game_cover_synced : R.string.game_cover_unavailable,
                        Toast.LENGTH_SHORT).show();
                if (success) host.reloadGame(game.id);
            });
        });
    }

    private void confirmDeleteGame(Game game) {
        AlertDialog dialog = createLauncherDialog();
        LinearLayout root = createDialogRoot();
        root.addView(createDialogTitle(context().getString(R.string.game_action_delete)));
        TextView message = bodyText(context().getString(
                R.string.game_delete_message, GameMetadataFormatter.safeTitle(context(), game)), true);
        message.setGravity(Gravity.CENTER);
        addWithTopMargin(root, message, 13);

        LinearLayout buttons = new LinearLayout(context());
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = button(context().getString(R.string.game_common_cancel), false);
        cancel.setOnClickListener(view -> dialog.dismiss());
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0, dp(38), 1f);
        cancelParams.setMargins(0, 0, dp(5), 0);
        buttons.addView(cancel, cancelParams);
        TextView delete = button(context().getString(R.string.game_common_remove), false);
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
            boolean failed = false;
            try {
                failed = LauncherRepositoryBridge.deleteGame(app, game.id) <= 0;
            } catch (Exception e) {
                Log.w("LauncherGameAction", "deleteGame failed", e);
                failed = true;
            }
            final boolean failedFinal = failed;
            Activity activity = fragment.getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (!fragment.isAdded()) return;
                if (failedFinal) {
                    Toast.makeText(context(), R.string.game_delete_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                Toast.makeText(context(), R.string.game_deleted, Toast.LENGTH_SHORT).show();
                host.removeGame(game.id);
            });
        });
    }

    private interface GameMutation {
        void apply(Game game);
    }

    private void runGameUpdate(Game game, GameMutation mutation, String message) {
        Context app = context().getApplicationContext();
        AppExecutors.io().execute(() -> {
            Game updated = null;
            boolean failed = false;
            try {
                Game latest = LauncherRepositoryBridge.findGameById(app, game.id);
                if (latest != null) {
                    mutation.apply(latest);
                    if (LauncherRepositoryBridge.updateGame(app, latest) > 0) {
                        updated = latest;
                    } else {
                        failed = true;
                    }
                } else {
                    failed = true;
                }
            } catch (Exception e) {
                Log.w("LauncherGameAction", "runGameUpdate failed", e);
                failed = true;
            }
            final Game result = updated;
            final boolean failedFinal = failed;
            Activity activity = fragment.getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (!fragment.isAdded()) return;
                if (failedFinal) {
                    Toast.makeText(context(), R.string.game_operation_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (message != null) Toast.makeText(context(), message, Toast.LENGTH_SHORT).show();
                if (result != null) host.updateGame(result);
                else host.refreshGames();
            });
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
        TextView cancel = button(context().getString(R.string.game_common_cancel), false);
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
        EditText input = new com.apps.widget.LauncherEditText(context());
        input.setHint(hint);
        input.setSingleLine(true);
        input.setTextColor(ContextCompat.getColor(context(), R.color.launcher_text_color));
        input.setHintTextColor(ContextCompat.getColor(context(), R.color.launcher_input_hint_color));
        input.setTextSize(13);
        input.setPadding(dp(13), dp(8), dp(13), dp(8));
        input.setBackground(LauncherTheme.cancelChip(context()));
        LauncherTheme.styleTextInput(input);
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

    private int dp(int value) {
        return (int) (value * context().getResources().getDisplayMetrics().density + 0.5f);
    }
}
