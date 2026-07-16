package com.apps.PadUi;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;
import com.yuki.yukihub.R;

/** Shared dialog shell for the landscape Pad surfaces. */
public final class PadDialogFactory {
    public static final int WIDTH_COMPACT_DP = 270;
    public static final int WIDTH_CONFIRM_DP = 288;
    public static final int WIDTH_FORM_DP = 288;

    private PadDialogFactory() {
    }

    public interface ChoiceListener {
        void onChoice(int index);
    }

    public static void showConfirm(Context context, String title, String message,
                                   String confirmText, Runnable onConfirm) {
        AlertDialog dialog = open(context, WIDTH_CONFIRM_DP, true);
        View content = LayoutInflater.from(context).inflate(R.layout.dialog_launcher_confirm, null);
        TextView titleView = content.findViewById(R.id.dialogTitle);
        TextView messageView = content.findViewById(R.id.dialogMessage);
        TextView cancel = content.findViewById(R.id.dialogBtnCancel);
        TextView confirm = content.findViewById(R.id.dialogBtnConfirm);
        titleView.setText(title);
        messageView.setText(message);
        confirm.setText(confirmText);
        LauncherTheme.dialogButtons(cancel, confirm);
        cancel.setOnClickListener(view -> dialog.dismiss());
        confirm.setOnClickListener(view -> {
            dialog.dismiss();
            if (onConfirm != null) onConfirm.run();
        });
        setContent(dialog, content, WIDTH_CONFIRM_DP);
    }

    public static void showStandardConfirm(Context context, String title, String message,
                                           String confirmText, Runnable onConfirm) {
        AlertDialog dialog = open(context, WIDTH_COMPACT_DP, true);
        LinearLayout root = root(context);
        root.addView(title(context, title));
        root.addView(message(context, message), topMargin(context, 13));

        TextView confirm = button(context, confirmText, true);
        confirm.setOnClickListener(view -> {
            dialog.dismiss();
            if (onConfirm != null) onConfirm.run();
        });
        root.addView(confirm, fixedHeightTopMargin(context, 11, 36));

        TextView cancel = cancelButton(context);
        cancel.setOnClickListener(view -> dialog.dismiss());
        root.addView(cancel, fixedHeightTopMargin(context, 9, 36));
        setContent(dialog, root, WIDTH_COMPACT_DP);
    }

    public static void showInfo(Context context, String title, String message) {
        AlertDialog dialog = open(context, WIDTH_COMPACT_DP, true);
        LinearLayout root = root(context);
        root.addView(title(context, title));
        root.addView(message(context, message), topMargin(context, 13));

        TextView acknowledge = button(context, "知道了", true);
        acknowledge.setOnClickListener(view -> dialog.dismiss());
        root.addView(acknowledge, fixedHeightTopMargin(context, 11, 36));
        setContent(dialog, root, WIDTH_COMPACT_DP);
    }

    /** Non-cancelable compact loading dialog; the caller owns its lifecycle. */
    public static AlertDialog showLoading(Context context, String title, String hint) {
        AlertDialog dialog = open(context, WIDTH_COMPACT_DP, false);
        LinearLayout root = root(context);
        root.addView(title(context, title));

        ProgressBar progress = new ProgressBar(context);
        progress.setIndeterminate(true);
        progress.getIndeterminateDrawable().setColorFilter(
                LauncherTheme.primary(context), PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                dp(context, 32), dp(context, 32));
        progressParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        progressParams.setMargins(0, dp(context, 14), 0, 0);
        root.addView(progress, progressParams);

        TextView hintView = message(context, hint);
        hintView.setTextSize(11);
        root.addView(hintView, topMargin(context, 10));
        setContent(dialog, root, WIDTH_COMPACT_DP);
        return dialog;
    }

    public static void showActionChoices(Context context, String title, CharSequence[] choices,
                                         int dangerIndex, ChoiceListener listener) {
        AlertDialog dialog = open(context, WIDTH_COMPACT_DP, true);
        LinearLayout root = root(context);
        TextView titleView = title(context, title);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        root.addView(titleView);

        ScrollView scroll = new ScrollView(context);
        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; choices != null && i < choices.length; i++) {
            final int index = i;
            TextView option = new TextView(context);
            option.setText(choices[i]);
            option.setGravity(android.view.Gravity.CENTER);
            option.setSingleLine(true);
            option.setTextSize(13);
            option.setTypeface(null, Typeface.BOLD);
            if (index == dangerIndex) {
                LauncherTheme.dangerMenuItem(option);
            } else {
                LauncherTheme.menuItem(option);
            }
            option.setOnClickListener(view -> {
                dialog.dismiss();
                if (listener != null) listener.onChoice(index);
            });
            LinearLayout.LayoutParams optionParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 36));
            optionParams.setMargins(0, index == 0 ? 0 : dp(context, 11), 0, 0);
            actions.addView(option, optionParams);
        }
        scroll.addView(actions);
        int choiceCount = choices == null ? 0 : choices.length;
        int listHeight = choiceCount * 36 + Math.max(0, choiceCount - 1) * 11;
        LinearLayout.LayoutParams scrollParams = topMargin(context, 11);
        scrollParams.height = Math.min(dp(context, 252), dp(context, listHeight));
        root.addView(scroll, scrollParams);

        TextView cancel = cancelButton(context);
        cancel.setOnClickListener(view -> dialog.dismiss());
        root.addView(cancel, fixedHeightTopMargin(context, 9, 36));
        setContent(dialog, root, WIDTH_COMPACT_DP);
    }

    public static void showSingleChoice(Context context, String title, CharSequence[] choices,
                                        int checkedIndex, ChoiceListener listener) {
        AlertDialog dialog = open(context, WIDTH_COMPACT_DP, true);
        LinearLayout root = root(context);
        root.addView(title(context, title));

        ScrollView scroll = new ScrollView(context);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        int optionCount = choices == null ? 0 : choices.length;
        for (int i = 0; choices != null && i < choices.length; i++) {
            final int index = i;
            TextView option = compactChoice(context, choices[i], index == checkedIndex);
            option.setOnClickListener(view -> {
                dialog.dismiss();
                if (listener != null) listener.onChoice(index);
            });
            LinearLayout.LayoutParams optionParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 38));
            optionParams.setMargins(0, dp(context, 7), 0, 0);
            list.addView(option, optionParams);
        }
        scroll.addView(list);
        int listHeight = optionCount * (38 + 7);
        LinearLayout.LayoutParams scrollParams = topMargin(context, 7);
        scrollParams.height = Math.min(dp(context, 280), dp(context, listHeight));
        root.addView(scroll, scrollParams);
        TextView cancel = cancelButton(context);
        cancel.setOnClickListener(view -> dialog.dismiss());
        root.addView(cancel, fixedHeightTopMargin(context, 9, 36));
        setContent(dialog, root, WIDTH_COMPACT_DP);
    }

    public static void showDangerConfirm(Context context, String title, String message,
                                         String dangerText, Runnable onConfirm) {
        AlertDialog dialog = open(context, WIDTH_COMPACT_DP, true);
        LinearLayout root = root(context);
        root.addView(title(context, title));
        root.addView(message(context, message), topMargin(context, 13));

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = button(context, "取消", false);
        cancel.setOnClickListener(view -> dialog.dismiss());
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(context, 38), 1f));

        TextView danger = new TextView(context);
        danger.setText(dangerText);
        danger.setGravity(android.view.Gravity.CENTER);
        danger.setTextSize(13);
        danger.setTypeface(null, Typeface.BOLD);
        LauncherTheme.dangerButton(danger);
        danger.setOnClickListener(view -> {
            dialog.dismiss();
            if (onConfirm != null) onConfirm.run();
        });
        LinearLayout.LayoutParams dangerParams = new LinearLayout.LayoutParams(0, dp(context, 38), 1f);
        dangerParams.setMargins(dp(context, 8), 0, 0, 0);
        actions.addView(danger, dangerParams);
        root.addView(actions, fixedHeightTopMargin(context, 13, 38));
        setContent(dialog, root, WIDTH_COMPACT_DP);
    }

    public static void primaryInlineAction(TextView view) {
        styleInlineAction(view);
        LauncherTheme.primaryButton(view);
    }

    public static void secondaryInlineAction(TextView view) {
        styleInlineAction(view);
        LauncherTheme.secondaryButton(view);
    }

    private static AlertDialog open(Context context, int widthDp, boolean cancelable) {
        AlertDialog dialog = new AlertDialog.Builder(context).create();
        dialog.setCancelable(cancelable);
        dialog.setCanceledOnTouchOutside(cancelable);
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout(dialogWidthPx(context, widthDp), WindowManager.LayoutParams.WRAP_CONTENT);
        }
        return dialog;
    }

    private static void setContent(AlertDialog dialog, View content, int widthDp) {
        Window window = dialog.getWindow();
        if (window == null) return;
        content.setBackground(LauncherTheme.secondaryButton(content.getContext(), 20f));
        LauncherTheme.applyPrimaryTone(content);
        window.setContentView(content);
        window.setLayout(dialogWidthPx(content.getContext(), widthDp), WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout root(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(context, 22), dp(context, 20), dp(context, 22), dp(context, 16));
        return root;
    }

    private static TextView title(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setGravity(android.view.Gravity.CENTER);
        view.setTextColor(LauncherTheme.text(context));
        view.setTextSize(16);
        view.setTypeface(null, Typeface.BOLD);
        return view;
    }

    private static TextView message(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setGravity(android.view.Gravity.CENTER);
        view.setTextColor(LauncherTheme.textMuted(context));
        view.setTextSize(12);
        view.setLineSpacing(dp(context, 4), 1f);
        return view;
    }

    private static TextView button(Context context, CharSequence text, boolean primary) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setGravity(android.view.Gravity.CENTER);
        view.setTextSize(13);
        view.setTypeface(null, Typeface.BOLD);
        if (primary) LauncherTheme.primaryButton(view); else LauncherTheme.secondaryButton(view);
        return view;
    }

    private static TextView compactChoice(Context context, CharSequence text, boolean selected) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setGravity(android.view.Gravity.CENTER);
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        view.setTextSize(13);
        view.setTextColor(selected ? LauncherTheme.primary(context) : LauncherTheme.text(context));
        view.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        view.setBackground(LauncherTheme.cancelChip(context));
        return view;
    }

    private static TextView cancelButton(Context context) {
        TextView view = button(context, "取消", false);
        return view;
    }

    private static void styleInlineAction(TextView view) {
        if (view == null) return;
        view.setGravity(android.view.Gravity.CENTER);
        view.setTextSize(13);
        view.setTypeface(null, Typeface.BOLD);
        view.setMinHeight(dp(view.getContext(), 38));
    }

    private static LinearLayout.LayoutParams topMargin(Context context, int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(context, topMarginDp), 0, 0);
        return params;
    }

    private static LinearLayout.LayoutParams fixedHeightTopMargin(Context context, int topMarginDp,
                                                                    int heightDp) {
        LinearLayout.LayoutParams params = topMargin(context, topMarginDp);
        params.height = dp(context, heightDp);
        return params;
    }

    private static int dialogWidthPx(Context context, int widthDp) {
        int densityWidth = dp(context, widthDp);
        int horizontalMargin = dp(context, 48);
        int availableWidth = context.getResources().getDisplayMetrics().widthPixels - horizontalMargin;
        return Math.max(0, Math.min(densityWidth, availableWidth));
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
