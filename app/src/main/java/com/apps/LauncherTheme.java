package com.apps;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.yuki.yukihub.R;

final class LauncherTheme {
    private LauncherTheme() {
    }

    static int primary(Context context) {
        return LauncherActivity.launcherPrimaryColor(context);
    }

    static int onPrimary(Context context) {
        return ContextCompat.getColor(context, R.color.launcher_on_primary_color);
    }

    static int card(Context context) {
        return ContextCompat.getColor(context, R.color.launcher_card_color);
    }

    static int line(Context context) {
        return ContextCompat.getColor(context, R.color.launcher_line_color);
    }

    static int primaryText(Context context) {
        return ContextCompat.getColor(context, R.color.launcher_primary_color);
    }

    static GradientDrawable primaryButton(Context context, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(primary(context));
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
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(card(context));
        drawable.setCornerRadius(dp(context, 999f));
        drawable.setStroke(dp(context, 1f), line(context));
        return drawable;
    }

    static GradientDrawable selectedOption(Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(card(context));
        drawable.setCornerRadius(dp(context, 10f));
        drawable.setStroke(dp(context, 1f), primary(context));
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
        view.setBackground(selected ? selectedChip(view.getContext()) : view.getContext().getDrawable(R.drawable.launcher_filter_chip_unselected));
    }

    static void primaryButton(TextView view) {
        if (view == null) return;
        view.setTextColor(onPrimary(view.getContext()));
        view.setBackground(primaryButton(view.getContext(), 22f));
    }

    static void dialogButtons(TextView cancel, TextView confirm) {
        if (cancel != null) {
            cancel.setTextColor(primary(cancel.getContext()));
            cancel.setBackground(cancelChip(cancel.getContext()));
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
                    new int[]{themedPrimary, ContextCompat.getColor(context, R.color.launcher_text_muted_color)}
            ));
        }

        String idName = idName(root);
        if (isPrimaryButtonId(idName) && root instanceof TextView) {
            primaryButton((TextView) root);
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
                || "registerCreate".equals(idName)
                || "chatSelectContinue".equals(idName)
                || "themeMenuApply".equals(idName)
                || "pendingClose".equals(idName);
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
