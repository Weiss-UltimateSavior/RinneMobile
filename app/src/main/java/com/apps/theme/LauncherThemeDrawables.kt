package com.apps.theme

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import com.apps.LauncherActivity

/** 主题背景/图形 drawable 构建（按钮、圆形、渐变卡片、下拉 scrim 等）。 */
internal object LauncherThemeDrawables {

    internal fun primaryButton(context: Context, radiusDp: Float): GradientDrawable {
        if (LauncherActivity.isXinhaitianTheme(context)) {
            return LauncherThemeParts.xinhaitianGradient(context, radiusDp, false)
        }
        return solidPrimary(context, radiusDp)
    }

    internal fun solidPrimary(context: Context, radiusDp: Float): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.setColor(LauncherThemeColors.primary(context))
        drawable.cornerRadius = LauncherThemeParts.dp(context, radiusDp).toFloat()
        return drawable
    }

    internal fun primaryTextOverlay(context: Context): GradientDrawable {
        val drawable = primaryButton(context, 0f)
        drawable.setAlpha(0xD9)
        return drawable
    }

    internal fun secondaryButton(context: Context, radiusDp: Float): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.setColor(LauncherThemeColors.card(context))
        drawable.cornerRadius = LauncherThemeParts.dp(context, radiusDp).toFloat()
        return drawable
    }

    internal fun dangerButton(context: Context, radiusDp: Float): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.setColor(LauncherThemeColors.danger(context))
        drawable.cornerRadius = LauncherThemeParts.dp(context, radiusDp).toFloat()
        return drawable
    }

    internal fun primaryGradientCard(context: Context, radiusDp: Float): GradientDrawable {
        val baseColor = LauncherThemeColors.primary(context)
        val drawable = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                LauncherThemeParts.shiftColor(baseColor, 0.76f),
                baseColor,
                LauncherThemeParts.shiftColor(baseColor, 1.18f)
            )
        )
        drawable.cornerRadius = LauncherThemeParts.dp(context, radiusDp).toFloat()
        return drawable
    }

    internal fun chatBubble(context: Context, outgoing: Boolean): GradientDrawable {
        return if (outgoing) primaryButton(context, 18f) else secondaryButton(context, 18f)
    }

    internal fun selectedChip(context: Context): GradientDrawable = primaryButton(context, 999f)

    internal fun cancelChip(context: Context): GradientDrawable = secondaryButton(context, 999f)

    internal fun selectedOption(context: Context): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.setColor(LauncherThemeColors.card(context))
        drawable.cornerRadius = LauncherThemeParts.dp(context, 9f).toFloat()
        return drawable
    }

    internal fun circle(context: Context): GradientDrawable {
        if (LauncherActivity.isXinhaitianTheme(context)) {
            return LauncherThemeParts.xinhaitianGradient(context, 0f, true)
        }
        return circle(context, LauncherThemeColors.primary(context))
    }

    internal fun circle(context: Context, color: Int): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.OVAL
        drawable.setColor(color)
        return drawable
    }

    internal fun circleWithSoftShadow(context: Context): Drawable {
        val ring = circle(context, ColorUtils.setAlphaComponent(LauncherThemeColors.primary(context), 0x99))
        val center = circle(context)
        val inset = LauncherThemeParts.dp(context, 3f)
        return LayerDrawable(arrayOf<Drawable>(ring, center)).apply {
            setLayerInset(1, inset, inset, inset, inset)
        }
    }

    internal fun cardCircle(context: Context): GradientDrawable =
        circle(context, LauncherThemeColors.card(context))

    internal fun applyCardCircleIcon(view: ImageView?, context: Context) {
        if (view == null) return
        view.background = cardCircle(context)
        if (LauncherActivity.isLauncherDarkMode(context)) {
            view.setColorFilter(Color.WHITE)
        } else {
            view.clearColorFilter()
        }
    }

    internal fun xinhaitianCircle(context: Context): GradientDrawable =
        LauncherThemeParts.xinhaitianGradient(context, 0f, true)

    internal fun statsScrim(context: Context): GradientDrawable =
        statsScrim(LauncherThemeColors.primary(context))

    internal fun statsScrim(baseColor: Int): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(
                Color.argb(230, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)),
                Color.argb(179, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)),
                Color.argb(0, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
            )
        )
    }
}
