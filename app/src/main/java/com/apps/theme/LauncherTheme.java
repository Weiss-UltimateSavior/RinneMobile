package com.apps.theme;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;

import com.yuki.yukihub.launcherbridge.LauncherUpdateBridge;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.content.ContextCompat;

import com.yuki.yukihub.R;
import com.apps.LauncherActivity;

public final class LauncherTheme {
    private LauncherTheme() {
    }

    private static Context uiContext(Context context) {
        Context wrapped = LauncherActivity.wrapLauncherUiMode(context);
        return wrapped == null ? context : wrapped;
    }

    private static int color(Context context, int colorResId) {
        return ContextCompat.getColor(uiContext(context), colorResId);
    }

    public static int primary(Context context) {
        return LauncherActivity.launcherPrimaryColor(context);
    }

    public static int onPrimary(Context context) {
        return color(context, R.color.launcher_on_primary_color);
    }

    public static int card(Context context) {
        return color(context, R.color.launcher_card_color);
    }

    public static int line(Context context) {
        return color(context, R.color.launcher_line_color);
    }

    public static int text(Context context) {
        return color(context, R.color.launcher_text_color);
    }

    public static int textMuted(Context context) {
        return color(context, R.color.launcher_text_muted_color);
    }

    public static int primaryText(Context context) {
        return color(context, R.color.launcher_primary_color);
    }

    public static int danger(Context context) {
        return color(context, R.color.launcher_danger_color);
    }

    public static int onDanger(Context context) {
        return color(context, R.color.launcher_on_danger_color);
    }

    public static GradientDrawable primaryButton(Context context, float radiusDp) {
        if (LauncherActivity.isXinhaitianTheme(context)) {
            return xinhaitianGradient(context, radiusDp, false);
        }
        return solidPrimary(context, radiusDp);
    }

    /** Primary tone without theme-specific gradients. */
    public static GradientDrawable solidPrimary(Context context, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(primary(context));
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    /** Theme-colored card copy overlay with the same opacity as launcher_game_text_overlay. */
    public static GradientDrawable primaryTextOverlay(Context context) {
        GradientDrawable drawable = primaryButton(context, 0f);
        drawable.setAlpha(0xD9);
        return drawable;
    }

    public static GradientDrawable secondaryButton(Context context, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(card(context));
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    public static GradientDrawable dangerButton(Context context, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(danger(context));
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    public static GradientDrawable primaryGradientCard(Context context, float radiusDp) {
        int baseColor = primary(context);
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{
                        shiftColor(baseColor, 0.76f),
                        baseColor,
                        shiftColor(baseColor, 1.18f)
                }
        );
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    /** Outgoing messages use the active tone; incoming messages use the neutral card surface. */
    public static GradientDrawable chatBubble(Context context, boolean outgoing) {
        return outgoing ? primaryButton(context, 18f) : secondaryButton(context, 18f);
    }

    public static GradientDrawable selectedChip(Context context) {
        return primaryButton(context, 999f);
    }

    public static GradientDrawable cancelChip(Context context) {
        return secondaryButton(context, 999f);
    }

    public static GradientDrawable selectedOption(Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(card(context));
        drawable.setCornerRadius(dp(context, 9f));
        return drawable;
    }

    public static GradientDrawable circle(Context context) {
        if (LauncherActivity.isXinhaitianTheme(context)) {
            return xinhaitianGradient(context, 0f, true);
        }
        return circle(context, primary(context));
    }

    public static GradientDrawable circle(Context context, int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    /** Circle with card background color, matching the white-card style of manage rows. */
    public static GradientDrawable cardCircle(Context context) {
        return circle(context, card(context));
    }

    public static GradientDrawable xinhaitianCircle(Context context) {
        return xinhaitianGradient(context, 0f, true);
    }

    private static GradientDrawable xinhaitianGradient(Context context, float radiusDp, boolean oval) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{
                        LauncherActivity.XINHAITIAN_PRIMARY_COLOR,
                        LauncherActivity.XINHAITIAN_ACCENT_COLOR
                }
        );
        drawable.setShape(oval ? GradientDrawable.OVAL : GradientDrawable.RECTANGLE);
        if (!oval) drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    public static GradientDrawable statsScrim(Context context) {
        return statsScrim(primary(context));
    }

    public static GradientDrawable statsScrim(int baseColor) {
        return new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{
                        Color.argb(230, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)),
                        Color.argb(179, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)),
                        Color.argb(0, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
                }
        );
    }

    public static void textPrimary(TextView view) {
        if (view != null) view.setTextColor(primary(view.getContext()));
    }

    public static void textOnPrimary(TextView view) {
        if (view != null) view.setTextColor(onPrimary(view.getContext()));
    }

    public static void chip(TextView view, boolean selected) {
        if (view == null) return;
        view.setTextColor(selected ? onPrimary(view.getContext()) : primary(view.getContext()));
        view.setBackground(selected ? selectedChip(view.getContext()) : secondaryButton(view.getContext(), 999f));
    }

    public static void primaryButton(TextView view) {
        if (view == null) return;
        view.setTextColor(onPrimary(view.getContext()));
        view.setBackground(primaryButton(view.getContext(), 20f));
    }

    /** Applies the common full-width action treatment used by Launcher setting pages. */
    public static void longActionButton(TextView view) {
        if (view == null) return;
        view.setGravity(android.view.Gravity.CENTER);
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        primaryButton(view);
    }

    /** Applies the compact form of the shared Launcher action treatment. */
    public static void shortActionButton(TextView view) {
        longActionButton(view);
    }

    /** Applies the compact secondary action treatment while preserving shared button metrics. */
    public static void shortSecondaryActionButton(TextView view) {
        if (view == null) return;
        view.setGravity(android.view.Gravity.CENTER);
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        secondaryButton(view);
    }

    /** Normalizes ordinary page form fields; call only from non-dialog page roots. */
    public static void formInputs(EditText... views) {
        if (views == null) return;
        for (EditText view : views) {
            if (view == null) continue;
            Context context = view.getContext();
            view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f);
            view.setPaddingRelative(dp(context, 13f), view.getPaddingTop(), dp(context, 13f), view.getPaddingBottom());
            view.setBackground(secondaryButton(context, 20f));
            int inputType = view.getInputType();
            boolean multiline = (inputType & android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
            if (!multiline && view.getLayoutParams() != null) {
                view.getLayoutParams().height = dp(context, 45f);
                view.requestLayout();
            }
            styleTextInput(view);
        }
    }

    public static void secondaryButton(TextView view) {
        if (view == null) return;
        view.setTextColor(primary(view.getContext()));
        view.setBackground(secondaryButton(view.getContext(), 20f));
    }

    public static void dangerButton(TextView view) {
        if (view == null) return;
        view.setTextColor(onDanger(view.getContext()));
        view.setBackground(dangerButton(view.getContext(), 20f));
    }

    public static void menuItem(TextView view) {
        if (view == null) return;
        view.setTextColor(primary(view.getContext()));
        view.setBackground(secondaryButton(view.getContext(), 999f));
    }

    public static void dangerMenuItem(TextView view) {
        if (view == null) return;
        view.setTextColor(danger(view.getContext()));
        view.setBackground(secondaryButton(view.getContext(), 999f));
    }

    public static void styleSpinner(Spinner spinner) {
        if (spinner == null) return;
        Context context = spinner.getContext();
        spinner.setBackground(secondaryButton(context, 20f));
        // dropdown 容器使用与弹窗一致的圆角背景
        spinner.setPopupBackgroundResource(R.drawable.launcher_spinner_popup_bg);
    }

    /**
     * 统一 SwitchCompat 启停按钮的色调：开启时使用主题主色，关闭时使用中性灰。
     * 必须在 Activity 创建后调用，确保主题已加载。
     */
    public static void styleSwitch(SwitchCompat switchCompat) {
        if (switchCompat == null) return;
        Context context = switchCompat.getContext();
        int primary = primary(context);
        int mutedGray = ContextCompat.getColor(context, R.color.launcher_text_muted_color);

        // thumb：开关圆点。开启时主色，关闭时浅灰
        int[][] thumbStates = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{-android.R.attr.state_checked}
        };
        int[] thumbColors = new int[]{primary, mutedGray};
        switchCompat.setThumbTintList(new ColorStateList(thumbStates, thumbColors));

        // track：开关轨道。开启时半透明主色，关闭时更浅的灰
        int trackOn = blend(primary, Color.WHITE, 0.6f);
        int trackOff = blend(mutedGray, Color.WHITE, 0.6f);
        int[][] trackStates = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{-android.R.attr.state_checked}
        };
        int[] trackColors = new int[]{trackOn, trackOff};
        switchCompat.setTrackTintList(new ColorStateList(trackStates, trackColors));
    }

    /** Applies the active Launcher tone to a text input's insertion cursor. */
    public static void styleTextInput(EditText input) {
        if (input == null) return;
        int primary = primary(input.getContext());
        input.setHighlightColor(ColorUtils.setAlphaComponent(primary, 82));
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        GradientDrawable cursor = new GradientDrawable();
        cursor.setColor(primary);
        cursor.setSize(dp(input.getContext(), 2f), -1);
        input.setTextCursorDrawable(cursor);
        input.setTextSelectHandle(selectionHandle(input.getContext(), primary));
        input.setTextSelectHandleLeft(selectionHandle(input.getContext(), primary));
        input.setTextSelectHandleRight(selectionHandle(input.getContext(), primary));
    }

    private static GradientDrawable selectionHandle(Context context, int color) {
        GradientDrawable handle = new GradientDrawable();
        handle.setShape(GradientDrawable.OVAL);
        handle.setColor(color);
        int size = dp(context, 18f);
        handle.setSize(size, size);
        return handle;
    }

    private static int blend(int color1, int color2, float ratio) {
        int r = (int) (Color.red(color1) * (1 - ratio) + Color.red(color2) * ratio);
        int g = (int) (Color.green(color1) * (1 - ratio) + Color.green(color2) * ratio);
        int b = (int) (Color.blue(color1) * (1 - ratio) + Color.blue(color2) * ratio);
        return Color.rgb(r, g, b);
    }

    public static <T> ArrayAdapter<T> spinnerAdapter(Context context, T[] items) {
        return new ArrayAdapter<T>(context, R.layout.spinner_item_themed, items) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                styleSpinnerItemView(view, false);
                return view;
            }

            @NonNull
            @Override
            public View getDropDownView(int position, View convertView, @NonNull ViewGroup parent) {
                View view = convertView;
                if (view == null) {
                    view = LayoutInflater.from(getContext()).inflate(R.layout.spinner_dropdown_themed, parent, false);
                }
                if (view instanceof TextView) {
                    TextView textView = (TextView) view;
                    textView.setText(String.valueOf(getItem(position)));
                }
                styleSpinnerItemView(view, true);
                return view;
            }
        };
    }

    public static void dialogButtons(TextView cancel, TextView confirm) {
        if (cancel != null) {
            secondaryButton(cancel);
        }
        primaryButton(confirm);
    }

    public static void applyPrimaryTone(View root) {
        if (root == null) return;
        Context context = root.getContext();
        int defaultPrimary = primaryText(context);
        int themedPrimary = primary(context);

        if (root instanceof TextView) {
            TextView textView = (TextView) root;
            if (textView.getCurrentTextColor() == defaultPrimary) {
                textView.setTextColor(themedPrimary);
            }
        }
        if (root instanceof CompoundButton) {
            ((CompoundButton) root).setButtonTintList(new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{themedPrimary, textMuted(context)}
            ));
        }
        if (root instanceof EditText) {
            styleTextInput((EditText) root);
        }

        String idName = idName(root);
        if (isPrimaryButtonId(idName) && root instanceof TextView) {
            primaryButton((TextView) root);
        } else if (isSecondaryButtonId(idName) && root instanceof TextView) {
            secondaryButton((TextView) root);
        } else if (isDangerButtonId(idName) && root instanceof TextView) {
            dangerButton((TextView) root);
        }

        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyPrimaryTone(group.getChildAt(i));
            }
        }
    }

    /** Applies the shared icon and arrow treatment used by Launcher action rows. */
    public static void styleManageRow(View row) {
        if (!(row instanceof ViewGroup)) return;
        Context context = row.getContext();
        ViewGroup group = (ViewGroup) row;
        if (group.getChildCount() > 0 && group.getChildAt(0) instanceof TextView) {
            TextView icon = (TextView) group.getChildAt(0);
            icon.setBackground(circle(context));
            icon.setTextColor(onPrimary(context));
        } else if (group.getChildCount() > 0 && group.getChildAt(0) instanceof ImageView) {
            ImageView icon = (ImageView) group.getChildAt(0);
            icon.setBackground(null);
            icon.setImageTintList(ColorStateList.valueOf(primary(context)));
        }
        if (group.getChildCount() > 2 && group.getChildAt(2) instanceof ImageView) {
            ((ImageView) group.getChildAt(2)).setImageTintList(
                    ColorStateList.valueOf(primary(context)));
        }
    }

    public static String idName(View view) {
        if (view == null || view.getId() == View.NO_ID) return "";
        try {
            return view.getResources().getResourceEntryName(view.getId());
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static int dp(Context context, float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static boolean isPrimaryButtonId(String idName) {
        if (idName == null) return false;
        return "btnSubmit".equals(idName)
                || "addGameSave".equals(idName)
                || "aiDetailClose".equals(idName)
                || "aiGenerateSubmit".equals(idName)
                || "aiHistoryClear".equals(idName)
                || "aiReviewGenerate".equals(idName)
                || "aiReviewSave".equals(idName)
                || "btnSave".equals(idName)
                || "registerCreate".equals(idName)
                || "chatSelectContinue".equals(idName)
                || "disclaimerClose".equals(idName)
                || "imagePreviewShare".equals(idName)
                || "themeMenuApply".equals(idName)
                || "pendingClose".equals(idName);
    }

    private static boolean isSecondaryButtonId(String idName) {
        if (idName == null) return false;
        return "aiReviewHistory".equals(idName)
                || "aiGenerateHistory".equals(idName)
                || "btnCancel".equals(idName)
                || "btnPickCover".equals(idName)
                || "imagePreviewClose".equals(idName)
                || "imagePreviewSave".equals(idName);
    }

    private static boolean isDangerButtonId(String idName) {
        return "dialogDangerButton".equals(idName);
    }

    private static void styleSpinnerItemView(View view, boolean dropdown) {
        if (!(view instanceof TextView)) return;
        TextView textView = (TextView) view;
        Context context = textView.getContext();
        textView.setTextColor(text(context));
        if (dropdown) {
            // dropdown item 透明背景，让 popup 容器的圆角背景统一显示
            textView.setBackgroundColor(Color.TRANSPARENT);
            textView.setPadding(dp(context, 13f), 0, dp(context, 13f), 0);
        } else {
            textView.setBackgroundColor(Color.TRANSPARENT);
        }
    }

    private static int shiftColor(int color, float factor) {
        return Color.rgb(
                clamp(Math.round(Color.red(color) * factor)),
                clamp(Math.round(Color.green(color) * factor)),
                clamp(Math.round(Color.blue(color) * factor))
        );
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static void showUpdateResultDialog(Context context, LauncherUpdateBridge.UpdateInfo info, String currentVersion, boolean hasUpdate, String error) {
        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(context).create();
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);

        android.view.Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(context, 252), android.view.WindowManager.LayoutParams.WRAP_CONTENT);

        android.widget.LinearLayout root = new android.widget.LinearLayout(context);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(dp(context, 22), dp(context, 20), dp(context, 22), dp(context, 16));
        root.setBackgroundResource(com.yuki.yukihub.R.drawable.launcher_dialog_bg);

        android.widget.TextView title = new android.widget.TextView(context);
        title.setText(hasUpdate ? "发现新版本" : "检查更新");
        title.setGravity(android.view.Gravity.CENTER);
        title.setTextColor(ContextCompat.getColor(context, com.yuki.yukihub.R.color.launcher_text_color));
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        android.widget.LinearLayout.LayoutParams optionLp = new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 36));
        optionLp.setMargins(0, dp(context, 11), 0, 0);

        android.widget.LinearLayout.LayoutParams cancelLp = new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 36));
        cancelLp.setMargins(0, dp(context, 9), 0, 0);

        if (error != null) {
            android.widget.TextView message = new android.widget.TextView(context);
            message.setText(error);
            message.setGravity(android.view.Gravity.CENTER);
            message.setTextColor(ContextCompat.getColor(context, com.yuki.yukihub.R.color.launcher_text_muted_color));
            message.setTextSize(12);
            message.setLineSpacing(dp(context, 2), 1.05f);
            android.widget.LinearLayout.LayoutParams msgLp = new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            msgLp.setMargins(0, dp(context, 11), 0, 0);
            root.addView(message, msgLp);

            android.widget.TextView btn = new android.widget.TextView(context);
            btn.setText("知道了");
            btn.setGravity(android.view.Gravity.CENTER);
            btn.setTextColor(primary(context));
            btn.setTextSize(13);
            btn.setTypeface(null, android.graphics.Typeface.BOLD);
            btn.setBackground(cancelChip(context));
            btn.setOnClickListener(v -> dialog.dismiss());
            root.addView(btn, cancelLp);
        } else if (hasUpdate && info != null) {
            android.widget.TextView message = new android.widget.TextView(context);
            StringBuilder sb = new StringBuilder();
            sb.append("当前版本：").append(emptyOr(currentVersion, "未知")).append("\n");
            sb.append("最新版本：").append(emptyOr(info.tagName, info.version)).append("\n\n");
            String body = trimUpdateBody(info.body, 1600);
            if (body != null && !body.trim().isEmpty()) {
                sb.append("更新内容：\n").append(body.trim());
            } else {
                sb.append("发现新的 GitHub Release，可前往发布页查看详情。");
            }
            message.setText(sb.toString());
            message.setGravity(android.view.Gravity.CENTER);
            message.setTextColor(ContextCompat.getColor(context, com.yuki.yukihub.R.color.launcher_text_muted_color));
            message.setTextSize(12);
            message.setLineSpacing(dp(context, 2), 1.05f);
            android.widget.LinearLayout.LayoutParams msgLp = new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            msgLp.setMargins(0, dp(context, 11), 0, 0);
            root.addView(message, msgLp);

            addDialogOption(root, "前往下载", dialog, () -> openUrl(context, emptyOr(info.apkUrl, info.releaseUrl)), optionLp);
            addDialogOption(root, "发布页", dialog, () -> openUrl(context, emptyOr(info.releaseUrl, "https://github.com/Weiss-UltimateSavior/RinneMobile/releases/tag/test")), optionLp);

            android.widget.TextView cancel = new android.widget.TextView(context);
            cancel.setText("稍后");
            cancel.setGravity(android.view.Gravity.CENTER);
            cancel.setTextColor(primary(context));
            cancel.setTextSize(13);
            cancel.setTypeface(null, android.graphics.Typeface.BOLD);
            cancel.setBackground(cancelChip(context));
            cancel.setOnClickListener(v -> dialog.dismiss());
            root.addView(cancel, cancelLp);
        } else {
            android.widget.TextView message = new android.widget.TextView(context);
            message.setText("已是最新版本：" + emptyOr(currentVersion, "未知"));
            message.setGravity(android.view.Gravity.CENTER);
            message.setTextColor(ContextCompat.getColor(context, com.yuki.yukihub.R.color.launcher_text_muted_color));
            message.setTextSize(12);
            message.setLineSpacing(dp(context, 2), 1.05f);
            android.widget.LinearLayout.LayoutParams msgLp = new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            msgLp.setMargins(0, dp(context, 11), 0, 0);
            root.addView(message, msgLp);

            android.widget.TextView btn = new android.widget.TextView(context);
            btn.setText("知道了");
            btn.setGravity(android.view.Gravity.CENTER);
            btn.setTextColor(primary(context));
            btn.setTextSize(13);
            btn.setTypeface(null, android.graphics.Typeface.BOLD);
            btn.setBackground(cancelChip(context));
            btn.setOnClickListener(v -> dialog.dismiss());
            root.addView(btn, cancelLp);
        }

        window.setContentView(root);
    }

    private static void addDialogOption(android.widget.LinearLayout root, String label, androidx.appcompat.app.AlertDialog dialog, Runnable action, android.widget.LinearLayout.LayoutParams lp) {
        android.widget.TextView option = new android.widget.TextView(root.getContext());
        option.setText(label);
        option.setGravity(android.view.Gravity.CENTER);
        option.setSingleLine(true);
        option.setTextSize(13);
        option.setTypeface(null, android.graphics.Typeface.BOLD);
        menuItem(option);
        option.setOnClickListener(v -> {
            dialog.dismiss();
            action.run();
        });
        root.addView(option, lp);
    }

    private static String emptyOr(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private static String trimUpdateBody(String text, int max) {
        if (text == null) return "";
        String t = text.trim();
        if (max <= 0 || t.length() <= max) return t;
        return t.substring(0, max) + "\n...";
    }

    private static void openUrl(Context context, String url) {
        try {
            context.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (Throwable ignored) {
        }
    }
}
