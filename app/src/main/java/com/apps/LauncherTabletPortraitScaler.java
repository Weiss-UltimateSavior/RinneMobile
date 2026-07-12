package com.apps;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Applies the Launcher portrait tablet size policy to layouts that still use the phone-first
 * dimensions in XML. PadUi is deliberately not a caller of this helper.
 */
public final class LauncherTabletPortraitScaler {
    private static final float SMALL_TABLET_SCALE = 1.35f;
    private static final float LARGE_TABLET_SCALE = 1.55f;

    private LauncherTabletPortraitScaler() { }

    public static void applyActivityContent(Activity activity) {
        if (activity == null) return;
        apply(activity.findViewById(android.R.id.content));
    }

    public static void apply(View root) {
        float scale = scaleFor(root);
        if (root == null || scale <= 1f) return;
        if (root.getTag(com.yuki.yukihub.R.id.launcher_tablet_portrait_scaled) != null) return;
        applyToTree(root, scale);
        root.setTag(com.yuki.yukihub.R.id.launcher_tablet_portrait_scaled, Boolean.TRUE);
    }

    public static float scaleFor(View view) {
        if (view == null) return 1f;
        return scaleFor(view.getResources());
    }

    public static float scaleFor(Resources resources) {
        if (resources == null) return 1f;
        Configuration configuration = resources.getConfiguration();
        if (configuration.orientation != Configuration.ORIENTATION_PORTRAIT
                || configuration.smallestScreenWidthDp < 600) {
            return 1f;
        }
        return configuration.smallestScreenWidthDp >= 720
                ? LARGE_TABLET_SCALE
                : SMALL_TABLET_SCALE;
    }

    public static boolean isTabletPortrait(Resources resources) {
        return scaleFor(resources) > 1f;
    }

    public static int libraryGridColumns(Resources resources) {
        if (!isTabletPortrait(resources)) return 2;
        return resources.getConfiguration().smallestScreenWidthDp >= 720 ? 4 : 3;
    }

    public static int libraryPageSize(Resources resources) {
        return libraryGridColumns(resources) * 4;
    }

    public static int dp(Context context, int baseDp) {
        if (context == null) return baseDp;
        return Math.round(baseDp * context.getResources().getDisplayMetrics().density
                * scaleFor(context.getResources()));
    }

    private static void applyToTree(View view, float scale) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null) {
            if (params.width > 0) params.width = scalePx(params.width, scale);
            if (params.height > 0) params.height = scalePx(params.height, scale);
            if (params instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                margins.leftMargin = scalePx(margins.leftMargin, scale);
                margins.topMargin = scalePx(margins.topMargin, scale);
                margins.rightMargin = scalePx(margins.rightMargin, scale);
                margins.bottomMargin = scalePx(margins.bottomMargin, scale);
            }
            view.setLayoutParams(params);
        }

        view.setPaddingRelative(
                scalePx(view.getPaddingStart(), scale),
                scalePx(view.getPaddingTop(), scale),
                scalePx(view.getPaddingEnd(), scale),
                scalePx(view.getPaddingBottom(), scale)
        );
        if (view.getMinimumWidth() > 0) view.setMinimumWidth(scalePx(view.getMinimumWidth(), scale));
        if (view.getMinimumHeight() > 0) view.setMinimumHeight(scalePx(view.getMinimumHeight(), scale));
        if (view.getElevation() > 0f) view.setElevation(view.getElevation() * scale);

        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, textView.getTextSize() * scale);
            textView.setCompoundDrawablePadding(scalePx(textView.getCompoundDrawablePadding(), scale));
            textView.setLineSpacing(textView.getLineSpacingExtra() * scale, textView.getLineSpacingMultiplier());
        }
        if (view instanceof ImageView) {
            ImageView imageView = (ImageView) view;
            if (imageView.getMaxWidth() > 0) imageView.setMaxWidth(scalePx(imageView.getMaxWidth(), scale));
            if (imageView.getMaxHeight() > 0) imageView.setMaxHeight(scalePx(imageView.getMaxHeight(), scale));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                applyToTree(group.getChildAt(index), scale);
            }
        }
    }

    private static int scalePx(int value, float scale) {
        return value == 0 ? 0 : Math.round(value * scale);
    }
}
