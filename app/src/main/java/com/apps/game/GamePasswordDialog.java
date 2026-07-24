package com.apps.game;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;
import com.yuki.yukihub.R;

import java.security.MessageDigest;

/**
 * 九宫格数字密码弹窗，支持设置密码（两次确认）和验证密码两种模式。
 * 密码为 6 位纯数字，存储时使用 SHA-256 哈希。
 */
public final class GamePasswordDialog {

    public interface OnPasswordSetListener {
        void onPasswordSet(String hashedPassword);
    }

    private static final int PASSWORD_LENGTH = 6;
    private static final int MODE_SET = 0;
    private static final int MODE_VERIFY = 1;

    private GamePasswordDialog() {
    }

    /** 设置密码弹窗：输入一次 → 再次确认 → 一致则回调 */
    public static void showSetDialog(Context context, String gameTitle, OnPasswordSetListener listener) {
        show(context, gameTitle, MODE_SET, null, listener, null);
    }

    /** 验证密码弹窗：输入一次 → 与 hashedPassword 比对 → 正确则回调 */
    public static void showVerifyDialog(Context context, String gameTitle, String hashedPassword, Runnable onSuccess) {
        show(context, gameTitle, MODE_VERIFY, hashedPassword, null, onSuccess);
    }

    private static void show(Context context, String gameTitle, int mode,
                             String hashedPassword, OnPasswordSetListener setListener, Runnable verifySuccess) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        boolean landscape = context.getResources().getDisplayMetrics().widthPixels
                > context.getResources().getDisplayMetrics().heightPixels;
        int padH = dp(context, landscape ? 20 : 24);
        int padV = dp(context, landscape ? 16 : 28);
        int btnSize = dp(context, landscape ? 42 : 56);
        int btnSpacing = dp(context, landscape ? 4 : 6);
        int sectionGap = dp(context, landscape ? 8 : 16);
        int keypadTop = dp(context, landscape ? 12 : 24);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(padH, padV, padH, padV);
        root.setBackgroundResource(R.drawable.launcher_dialog_bg);

        // 标题
        TextView title = new TextView(context);
        title.setText(mode == MODE_SET ? "设置密码" : "输入密码");
        title.setGravity(Gravity.CENTER);
        title.setTextColor(LauncherTheme.text(context));
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        // 副标题（游戏名）
        if (gameTitle != null && !gameTitle.trim().isEmpty()) {
            TextView subtitle = new TextView(context);
            subtitle.setText(gameTitle);
            subtitle.setGravity(Gravity.CENTER);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            subtitle.setTextColor(LauncherTheme.textMuted(context));
            subtitle.setTextSize(12);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = dp(context, 4);
            root.addView(subtitle, subLp);
        }

        // 提示文字
        TextView hint = new TextView(context);
        hint.setText(mode == MODE_SET ? "请输入 6 位数字密码" : "请输入密码以启动游戏");
        hint.setGravity(Gravity.CENTER);
        hint.setTextColor(LauncherTheme.textMuted(context));
        hint.setTextSize(11);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(context, landscape ? 8 : 12);
        root.addView(hint, hintLp);

        // 密码圆点指示器
        final View[] dots = new View[PASSWORD_LENGTH];
        LinearLayout dotsRow = new LinearLayout(context);
        dotsRow.setOrientation(LinearLayout.HORIZONTAL);
        dotsRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dotsRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dotsRowLp.topMargin = sectionGap;
        int dotSize = dp(context, 12);
        int dotSpacing = dp(context, 10);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            View dot = new View(context);
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(LauncherTheme.card(context));
            dot.setBackground(dotBg);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dotSize, dotSize);
            if (i > 0) dotLp.leftMargin = dotSpacing;
            dotsRow.addView(dot, dotLp);
            dots[i] = dot;
        }
        root.addView(dotsRow, dotsRowLp);

        // 九宫格数字键盘
        final StringBuilder input = new StringBuilder();
        final boolean[] firstInputDone = {false};
        final String[] firstInput = {""};

        LinearLayout keypad = new LinearLayout(context);
        keypad.setOrientation(LinearLayout.VERTICAL);
        keypad.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams keypadLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        keypadLp.topMargin = keypadTop;

        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "del"};

        for (int row = 0; row < 4; row++) {
            LinearLayout rowLayout = new LinearLayout(context);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            rowLayout.setGravity(Gravity.CENTER);
            for (int col = 0; col < 3; col++) {
                int idx = row * 3 + col;
                String key = keys[idx];
                if (key.isEmpty()) {
                    View spacer = new View(context);
                    LinearLayout.LayoutParams spacerLp = new LinearLayout.LayoutParams(btnSize, btnSize);
                    if (col > 0) spacerLp.leftMargin = btnSpacing;
                    rowLayout.addView(spacer, spacerLp);
                    continue;
                }
                TextView btn = new TextView(context);
                btn.setText(key.equals("del") ? "X" : key);
                btn.setGravity(Gravity.CENTER);
                btn.setTextSize(20);
                btn.setTypeface(null, Typeface.NORMAL);
                btn.setTextColor(LauncherTheme.primary(context));
                LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(btnSize, btnSize);
                if (col > 0) btnLp.leftMargin = btnSpacing;
                rowLayout.addView(btn, btnLp);

                btn.setOnClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    if (key.equals("del")) {
                        if (input.length() > 0) {
                            input.deleteCharAt(input.length() - 1);
                        }
                    } else {
                        if (input.length() < PASSWORD_LENGTH) {
                            input.append(key);
                        }
                    }
                    updateDots(dots, input.length(), context);

                    if (input.length() == PASSWORD_LENGTH) {
                        String entered = input.toString();
                        input.setLength(0);
                        // 延迟清空圆点，让用户看到最后一个点亮起
                        dots[0].postDelayed(() -> {
                            updateDots(dots, 0, context);
                            handleComplete(context, dialog, mode, entered, hashedPassword,
                                    firstInputDone, firstInput, hint, setListener, verifySuccess);
                        }, 100);
                    }
                });
            }
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (row > 0) rowLp.topMargin = btnSpacing;
            keypad.addView(rowLayout, rowLp);
        }
        root.addView(keypad, keypadLp);

        // 取消按钮
        TextView cancelBtn = new TextView(context);
        cancelBtn.setText("取消");
        cancelBtn.setGravity(Gravity.CENTER);
        cancelBtn.setTextSize(13);
        cancelBtn.setTypeface(null, Typeface.BOLD);
        cancelBtn.setTextColor(LauncherTheme.primary(context));
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 38));
        cancelLp.topMargin = dp(context, landscape ? 12 : 20);
        cancelBtn.setBackground(LauncherTheme.cancelChip(context));
        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        root.addView(cancelBtn, cancelLp);

        ScrollView scroll = new ScrollView(context);
        scroll.addView(root);
        dialog.setContentView(scroll);
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            int dialogHeight;
            if (landscape) {
                int screenHeight = context.getResources().getDisplayMetrics().heightPixels;
                dialogHeight = (int) (screenHeight * 0.9f);
            } else {
                dialogHeight = WindowManager.LayoutParams.WRAP_CONTENT;
            }
            window.setLayout(dp(context, 280), dialogHeight);
        }
    }

    private static void handleComplete(Context context, Dialog dialog, int mode,
                                       String entered, String hashedPassword,
                                       boolean[] firstInputDone, String[] firstInput,
                                       TextView hint, OnPasswordSetListener setListener,
                                       Runnable verifySuccess) {
        if (mode == MODE_SET) {
            if (!firstInputDone[0]) {
                firstInput[0] = entered;
                firstInputDone[0] = true;
                hint.setText("请再次输入以确认");
            } else {
                if (entered.equals(firstInput[0])) {
                    String hashed = hash(entered);
                    dialog.dismiss();
                    if (setListener != null) setListener.onPasswordSet(hashed);
                } else {
                    firstInputDone[0] = false;
                    firstInput[0] = "";
                    hint.setText("两次输入不一致，请重新设置");
                    shakeError(hint);
                }
            }
        } else {
            String hashed = hash(entered);
            if (hashed.equals(hashedPassword)) {
                dialog.dismiss();
                if (verifySuccess != null) verifySuccess.run();
            } else {
                hint.setText("密码错误，请重新输入");
                shakeError(hint);
                Toast.makeText(context, "密码错误", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private static void updateDots(View[] dots, int count, Context context) {
        int activeColor = LauncherTheme.primary(context);
        int inactiveColor = LauncherTheme.card(context);
        for (int i = 0; i < dots.length; i++) {
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            if (i < count) {
                bg.setColor(activeColor);
            } else {
                bg.setColor(inactiveColor);
            }
            dots[i].setBackground(bg);
        }
    }

    private static void shakeError(View view) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }

    /** SHA-256 哈希，结果转 hex 字符串 */
    public static String hash(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(raw.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return raw;
        }
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
