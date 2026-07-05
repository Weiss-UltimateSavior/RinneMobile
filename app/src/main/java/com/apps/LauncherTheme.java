package com.apps;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.yuki.yukihub.R;

final class LauncherTheme {
    private LauncherTheme() {
    }

    private static Context uiContext(Context context) {
        Context wrapped = LauncherActivity.wrapLauncherUiMode(context);
        return wrapped == null ? context : wrapped;
    }

    private static int color(Context context, int colorResId) {
        return ContextCompat.getColor(uiContext(context), colorResId);
    }

    static int primary(Context context) {
        return LauncherActivity.launcherPrimaryColor(context);
    }

    static int onPrimary(Context context) {
        return color(context, R.color.launcher_on_primary_color);
    }

    static int card(Context context) {
        return color(context, R.color.launcher_card_color);
    }

    static int line(Context context) {
        return color(context, R.color.launcher_line_color);
    }

    static int text(Context context) {
        return color(context, R.color.launcher_text_color);
    }

    static int textMuted(Context context) {
        return color(context, R.color.launcher_text_muted_color);
    }

    static int primaryText(Context context) {
        return color(context, R.color.launcher_primary_color);
    }

    static int danger(Context context) {
        return color(context, R.color.launcher_danger_color);
    }

    static int onDanger(Context context) {
        return color(context, R.color.launcher_on_danger_color);
    }

    static GradientDrawable primaryButton(Context context, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(primary(context));
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    static GradientDrawable secondaryButton(Context context, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(card(context));
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    static GradientDrawable dangerButton(Context context, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(danger(context));
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    static GradientDrawable primaryGradientCard(Context context, float radiusDp) {
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

    static GradientDrawable selectedChip(Context context) {
        return primaryButton(context, 999f);
    }

    static GradientDrawable cancelChip(Context context) {
        return secondaryButton(context, 999f);
    }

    static GradientDrawable selectedOption(Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(card(context));
        drawable.setCornerRadius(dp(context, 10f));
        return drawable;
    }

    static GradientDrawable circle(Context context) {
        return circle(context, primary(context));
    }

    static GradientDrawable circle(Context context, int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    static GradientDrawable statsScrim(Context context) {
        return statsScrim(primary(context));
    }

    static GradientDrawable statsScrim(int baseColor) {
        return new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{
                        Color.argb(230, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)),
                        Color.argb(179, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)),
                        Color.argb(0, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
                }
        );
    }

    static void textPrimary(TextView view) {
        if (view != null) view.setTextColor(primary(view.getContext()));
    }

    static void textOnPrimary(TextView view) {
        if (view != null) view.setTextColor(onPrimary(view.getContext()));
    }

    static void chip(TextView view, boolean selected) {
        if (view == null) return;
        view.setTextColor(selected ? onPrimary(view.getContext()) : primary(view.getContext()));
        view.setBackground(selected ? selectedChip(view.getContext()) : secondaryButton(view.getContext(), 999f));
    }

    static void primaryButton(TextView view) {
        if (view == null) return;
        view.setTextColor(onPrimary(view.getContext()));
        view.setBackground(primaryButton(view.getContext(), 22f));
    }

    static void secondaryButton(TextView view) {
        if (view == null) return;
        view.setTextColor(primary(view.getContext()));
        view.setBackground(secondaryButton(view.getContext(), 22f));
    }

    static void dangerButton(TextView view) {
        if (view == null) return;
        view.setTextColor(onDanger(view.getContext()));
        view.setBackground(dangerButton(view.getContext(), 22f));
    }

    static void menuItem(TextView view) {
        if (view == null) return;
        view.setTextColor(primary(view.getContext()));
        view.setBackground(secondaryButton(view.getContext(), 999f));
    }

    static void dangerMenuItem(TextView view) {
        if (view == null) return;
        view.setTextColor(danger(view.getContext()));
        view.setBackground(secondaryButton(view.getContext(), 999f));
    }

    static void styleSpinner(Spinner spinner) {
        if (spinner == null) return;
        Context context = spinner.getContext();
        spinner.setBackground(secondaryButton(context, 22f));
        // dropdown 容器使用与弹窗一致的圆角背景
        spinner.setPopupBackgroundResource(R.drawable.launcher_spinner_popup_bg);
    }

    static <T> ArrayAdapter<T> spinnerAdapter(Context context, T[] items) {
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

    static void dialogButtons(TextView cancel, TextView confirm) {
        if (cancel != null) {
            secondaryButton(cancel);
        }
        primaryButton(confirm);
    }

    static void applyPrimaryTone(View root) {
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

    static String idName(View view) {
        if (view == null || view.getId() == View.NO_ID) return "";
        try {
            return view.getResources().getResourceEntryName(view.getId());
        } catch (Throwable ignored) {
            return "";
        }
    }

    static int dp(Context context, float value) {
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
            textView.setPadding(dp(context, 14f), 0, dp(context, 14f), 0);
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
}
