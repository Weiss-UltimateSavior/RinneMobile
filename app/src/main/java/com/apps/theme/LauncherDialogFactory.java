package com.apps.theme;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.PorterDuff;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.apps.widget.LauncherTabletPortraitScaler;
import com.yuki.yukihub.R;

/** Shared non-engine Launcher dialog shell. */
public final class LauncherDialogFactory {
    /** Visual baseline from the Launcher center-navigation confirmation dialog. */
    public static final int WIDTH_COMPACT_DP = 252;
    public static final int WIDTH_STANDARD_DP = WIDTH_COMPACT_DP;
    public static final int WIDTH_FORM_DP = 288;
    public static final int WIDTH_ACTION_MENU_DP = 340;

    private LauncherDialogFactory() {
    }

    public interface ChoiceListener {
        void onChoice(int index);
    }

    public static void showInfo(Context context, String title, String message) {
        showInfo(context, title, message, null);
    }

    /** Standard-width information prompt with an optional acknowledgement callback. */
    public static void showInfo(Context context, String title, String message,
                                Runnable onAcknowledge) {
        AlertDialog dialog = open(context, WIDTH_STANDARD_DP);
        LinearLayout root = root(context, false);
        root.addView(standardTitle(context, title));
        root.addView(standardMessage(context, message), topMargin(context, 13));
        TextView confirm = button(context, "知道了", true);
        confirm.setOnClickListener(view -> {
            dialog.dismiss();
            if (onAcknowledge != null) onAcknowledge.run();
        });
        root.addView(confirm, fixedHeightTopMargin(context, 11, 36));
        setContent(dialog, root, WIDTH_STANDARD_DP);
    }

    public static void showConfirm(Context context, String title, String message,
                                   String confirmText, Runnable onConfirm) {
        AlertDialog dialog = open(context, WIDTH_COMPACT_DP);
        View root = LayoutInflater.from(context).inflate(R.layout.dialog_launcher_confirm, null);
        TextView titleView = root.findViewById(R.id.dialogTitle);
        TextView messageView = root.findViewById(R.id.dialogMessage);
        TextView cancel = root.findViewById(R.id.dialogBtnCancel);
        TextView confirm = root.findViewById(R.id.dialogBtnConfirm);
        titleView.setText(title);
        messageView.setText(message);
        confirm.setText(confirmText);
        LauncherTheme.dialogButtons(cancel, confirm);
        cancel.setOnClickListener(view -> dialog.dismiss());
        confirm.setOnClickListener(view -> {
            dialog.dismiss();
            if (onConfirm != null) onConfirm.run();
        });
        setContent(dialog, root, WIDTH_COMPACT_DP);
    }

    /** Standard-width confirmation used by settings and account flows. */
    public static void showStandardConfirm(Context context, String title, String message,
                                           String confirmText, Runnable onConfirm) {
        AlertDialog dialog = open(context, WIDTH_STANDARD_DP);
        LinearLayout root = root(context, false);
        root.addView(standardTitle(context, title));
        root.addView(standardMessage(context, message), topMargin(context, 13));

        TextView confirm = button(context, confirmText, true);
        confirm.setOnClickListener(view -> {
            dialog.dismiss();
            if (onConfirm != null) onConfirm.run();
        });
        root.addView(confirm, fixedHeightTopMargin(context, 11, 36));

        TextView cancel = cancelButton(context);
        cancel.setOnClickListener(view -> dialog.dismiss());
        root.addView(cancel, fixedHeightTopMargin(context, 9, 36));
        setContent(dialog, root, WIDTH_STANDARD_DP);
    }

    /** Scrollable long-message confirmation for content that cannot safely fit the compact shell. */
    public static void showLongMessageConfirm(Context context, String title, String message,
                                              String confirmText, Runnable onConfirm) {
        AlertDialog dialog = open(context, WIDTH_FORM_DP);
        LinearLayout root = root(context, false);
        root.addView(standardTitle(context, title));

        ScrollView scroll = new ScrollView(context);
        TextView messageView = standardMessage(context, message);
        scroll.addView(messageView);
        LinearLayout.LayoutParams scrollParams = topMargin(context, 13);
        scrollParams.height = dp(context, 220);
        root.addView(scroll, scrollParams);

        TextView confirm = button(context, confirmText, true);
        confirm.setOnClickListener(view -> {
            dialog.dismiss();
            if (onConfirm != null) onConfirm.run();
        });
        root.addView(confirm, fixedHeightTopMargin(context, 11, 36));

        TextView cancel = cancelButton(context);
        cancel.setOnClickListener(view -> dialog.dismiss());
        root.addView(cancel, fixedHeightTopMargin(context, 9, 36));
        setContent(dialog, root, WIDTH_FORM_DP);
    }

    /** Standard-width destructive confirmation with a horizontal action row. */
    public static void showDangerConfirm(Context context, String title, String message,
                                         String dangerText, Runnable onConfirm) {
        AlertDialog dialog = open(context, WIDTH_STANDARD_DP);
        LinearLayout root = root(context, false);
        root.addView(standardTitle(context, title));
        root.addView(standardMessage(context, message), topMargin(context, 13));

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
        dangerParams.setMargins(dp(context, 10), 0, 0, 0);
        actions.addView(danger, dangerParams);
        root.addView(actions, fixedHeightTopMargin(context, 13, 38));
        setContent(dialog, root, WIDTH_STANDARD_DP);
    }

    /** Non-cancelable indeterminate loading shell. The caller owns its lifecycle. */
    public static AlertDialog showLoading(Context context, String title, String hint) {
        AlertDialog dialog = open(context, WIDTH_STANDARD_DP, false);
        LinearLayout root = root(context, false);
        root.addView(standardTitle(context, title));

        ProgressBar progress = new ProgressBar(context);
        progress.setIndeterminate(true);
        progress.getIndeterminateDrawable().setColorFilter(
                LauncherTheme.primary(context), PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                dp(context, 32), dp(context, 32));
        progressParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        progressParams.setMargins(0, dp(context, 14), 0, 0);
        root.addView(progress, progressParams);

        TextView hintView = standardMessage(context, hint);
        hintView.setTextSize(11);
        root.addView(hintView, topMargin(context, 10));
        setContent(dialog, root, WIDTH_STANDARD_DP);
        return dialog;
    }

    public static void showActionChoices(Context context, String title, CharSequence[] choices,
                                         ChoiceListener listener) {
        AlertDialog dialog = open(context, WIDTH_ACTION_MENU_DP);
        LinearLayout root = root(context, true);
        root.addView(title(context, title));
        ScrollView scroll = new ScrollView(context);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; choices != null && i < choices.length; i++) {
            final int index = i;
            TextView option = button(context, choices[i], false);
            option.setGravity(android.view.Gravity.CENTER_VERTICAL);
            option.setPadding(dp(context, 13), 0, dp(context, 13), 0);
            option.setMaxLines(2);
            option.setOnClickListener(view -> {
                dialog.dismiss();
                if (listener != null) listener.onChoice(index);
            });
            LinearLayout.LayoutParams optionParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 42));
            if (i > 0) optionParams.setMargins(0, dp(context, 7), 0, 0);
            list.addView(option, optionParams);
        }
        scroll.addView(list);
        int optionCount = choices == null ? 0 : choices.length;
        int listHeight = optionCount * 42 + Math.max(0, optionCount - 1) * 7;
        LinearLayout.LayoutParams scrollParams = topMargin(context, 12);
        scrollParams.height = Math.min(dp(context, 252), dp(context, listHeight));
        root.addView(scroll, scrollParams);
        TextView cancel = button(context, "取消", false);
        cancel.setOnClickListener(view -> dialog.dismiss());
        root.addView(cancel, fixedHeightTopMargin(context, 12, 36));
        setContent(dialog, root, WIDTH_ACTION_MENU_DP);
    }

    /** Standard-width compact action menu for a small number of short operations. */
    public static void showStandardActionChoices(Context context, String title, CharSequence[] choices,
                                                 ChoiceListener listener) {
        AlertDialog dialog = open(context, WIDTH_STANDARD_DP);
        LinearLayout root = root(context, false);
        root.addView(standardTitle(context, title));
        for (int i = 0; choices != null && i < choices.length; i++) {
            final int index = i;
            TextView option = new TextView(context);
            option.setText(choices[i]);
            option.setGravity(android.view.Gravity.CENTER);
            option.setSingleLine(true);
            option.setTextSize(13);
            option.setTypeface(null, Typeface.BOLD);
            LauncherTheme.menuItem(option);
            option.setOnClickListener(view -> {
                dialog.dismiss();
                if (listener != null) listener.onChoice(index);
            });
            root.addView(option, fixedHeightTopMargin(context, 11, 36));
        }
        TextView cancel = cancelButton(context);
        cancel.setOnClickListener(view -> dialog.dismiss());
        root.addView(cancel, fixedHeightTopMargin(context, 9, 36));
        setContent(dialog, root, WIDTH_STANDARD_DP);
    }

    /** Form-width single choice picker with an explicit selected state. */
    public static void showSingleChoice(Context context, String title, CharSequence[] choices,
                                        int checkedIndex, ChoiceListener listener) {
        AlertDialog dialog = open(context, WIDTH_COMPACT_DP);
        LinearLayout root = root(context, false);
        root.addView(standardTitle(context, title));

        ScrollView scroll = new ScrollView(context);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        int optionCount = choices == null ? 0 : choices.length;
        for (int i = 0; i < optionCount; i++) {
            final int index = i;
            TextView option = button(context, choices[i], index == checkedIndex);
            option.setGravity(android.view.Gravity.CENTER);
            LinearLayout.LayoutParams optionParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 42));
            if (i > 0) optionParams.setMargins(0, dp(context, 7), 0, 0);
            option.setOnClickListener(view -> {
                dialog.dismiss();
                if (listener != null) listener.onChoice(index);
            });
            list.addView(option, optionParams);
        }
        scroll.addView(list);
        int listHeight = optionCount * 42 + Math.max(0, optionCount - 1) * 7;
        LinearLayout.LayoutParams scrollParams = topMargin(context, 11);
        scrollParams.height = Math.min(dp(context, 252), dp(context, listHeight));
        root.addView(scroll, scrollParams);

        TextView cancel = cancelButton(context);
        cancel.setOnClickListener(view -> dialog.dismiss());
        root.addView(cancel, fixedHeightTopMargin(context, 9, 36));
        setContent(dialog, root, WIDTH_COMPACT_DP);
    }

    private static AlertDialog open(Context context, int widthDp) {
        return open(context, widthDp, true);
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
        LauncherTabletPortraitScaler.apply(content);
        window.setContentView(content);
        window.setLayout(dialogWidthPx(content.getContext(), widthDp), WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout root(Context context, boolean scrollable) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(context, 22), dp(context, scrollable ? 18 : 20),
                dp(context, 22), dp(context, scrollable ? 15 : 16));
        root.setBackground(LauncherTheme.secondaryButton(context, 20f));
        return root;
    }

    private static TextView title(Context context, String value) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextColor(LauncherTheme.text(context));
        view.setTextSize(16);
        view.setTypeface(null, Typeface.BOLD);
        return view;
    }

    private static TextView standardTitle(Context context, String value) {
        TextView view = title(context, value);
        view.setGravity(android.view.Gravity.CENTER);
        return view;
    }

    private static TextView message(Context context, String value) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextColor(LauncherTheme.textMuted(context));
        view.setTextSize(13);
        return view;
    }

    private static TextView standardMessage(Context context, String value) {
        TextView view = message(context, value);
        view.setGravity(android.view.Gravity.CENTER);
        view.setTextSize(12);
        return view;
    }

    private static TextView button(Context context, CharSequence value, boolean primary) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setGravity(android.view.Gravity.CENTER);
        view.setTextSize(13);
        view.setTypeface(null, Typeface.BOLD);
        if (primary) LauncherTheme.primaryButton(view); else LauncherTheme.secondaryButton(view);
        return view;
    }

    private static TextView cancelButton(Context context) {
        TextView view = new TextView(context);
        view.setText("取消");
        view.setGravity(android.view.Gravity.CENTER);
        view.setTextColor(LauncherTheme.primary(context));
        view.setTextSize(13);
        view.setTypeface(null, Typeface.BOLD);
        view.setBackground(LauncherTheme.cancelChip(context));
        return view;
    }

    private static LinearLayout.LayoutParams topMargin(Context context, int marginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(context, marginDp), 0, 0);
        return params;
    }

    private static LinearLayout.LayoutParams fixedHeightTopMargin(Context context, int marginDp,
                                                                    int heightDp) {
        LinearLayout.LayoutParams params = topMargin(context, marginDp);
        params.height = dp(context, heightDp);
        return params;
    }

    private static int dp(Context context, int value) {
        return LauncherTheme.dp(context, value);
    }

    private static int dialogWidthPx(Context context, int widthDp) {
        int desiredWidth = LauncherTabletPortraitScaler.dp(context, widthDp);
        int horizontalMargin = dp(context, 16) * 2;
        int maxWidth = Math.max(0, context.getResources().getDisplayMetrics().widthPixels - horizontalMargin);
        return Math.min(desiredWidth, maxWidth);
    }
}
