package com.apps.theme

import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.apps.LauncherThemeStyle
import com.apps.LauncherUiMode
import com.core.R
import kotlin.math.max
import kotlin.math.min

/** 底层主题工具：UI mode 包装、颜色资源解析、dp 换算、渐变/选中手柄构建与按钮 id 判定。 */
internal object LauncherThemeParts {

    internal fun uiContext(context: Context): Context {
        val wrapped = LauncherUiMode.wrap(context)
        return wrapped ?: context
    }

    internal fun color(context: Context, colorResId: Int): Int {
        return ContextCompat.getColor(uiContext(context), colorResId)
    }

    internal fun xinhaitianGradient(context: Context, radiusDp: Float, oval: Boolean): GradientDrawable {
        val drawable = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                LauncherThemeStyle.XINHAITIAN_PRIMARY_COLOR,
                LauncherThemeStyle.XINHAITIAN_ACCENT_COLOR
            )
        )
        drawable.shape = if (oval) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
        if (!oval) drawable.cornerRadius = dp(context, radiusDp).toFloat()
        return drawable
    }

    internal fun selectionHandle(context: Context, color: Int): GradientDrawable {
        val handle = GradientDrawable()
        handle.shape = GradientDrawable.OVAL
        handle.setColor(color)
        val size = dp(context, 18f)
        handle.setSize(size, size)
        return handle
    }

    internal fun blend(color1: Int, color2: Int, ratio: Float): Int {
        val r = (Color.red(color1) * (1 - ratio) + Color.red(color2) * ratio).toInt()
        val g = (Color.green(color1) * (1 - ratio) + Color.green(color2) * ratio).toInt()
        val b = (Color.blue(color1) * (1 - ratio) + Color.blue(color2) * ratio).toInt()
        return Color.rgb(r, g, b)
    }

    internal fun isPrimaryButtonId(idName: String?): Boolean {
        if (idName == null) return false
        return "btnSubmit" == idName
            || "addGameSave" == idName
            || "aiDetailClose" == idName
            || "aiGenerateSubmit" == idName
            || "aiHistoryClear" == idName
            || "aiReviewGenerate" == idName
            || "aiReviewSave" == idName
            || "btnSave" == idName
            || "registerCreate" == idName
            || "chatSelectContinue" == idName
            || "disclaimerClose" == idName
            || "imagePreviewShare" == idName
            || "themeMenuApply" == idName
            || "pendingClose" == idName
    }

    internal fun isSecondaryButtonId(idName: String?): Boolean {
        if (idName == null) return false
        return "aiReviewHistory" == idName
            || "aiGenerateHistory" == idName
            || "btnCancel" == idName
            || "btnPickCover" == idName
            || "imagePreviewClose" == idName
            || "imagePreviewSave" == idName
    }

    internal fun isDangerButtonId(idName: String?): Boolean {
        return "dialogDangerButton" == idName
    }

    internal fun styleSpinnerItemView(view: View, dropdown: Boolean) {
        if (view !is TextView) return
        val textView = view
        val context = textView.context
        textView.setTextColor(color(context, R.color.launcher_text_color))
        if (dropdown) {
            // dropdown item 透明背景，让 popup 容器的圆角背景统一显示
            textView.setBackgroundColor(Color.TRANSPARENT)
            textView.setPadding(dp(context, 13f), 0, dp(context, 13f), 0)
        } else {
            textView.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    internal fun shiftColor(color: Int, factor: Float): Int {
        return Color.rgb(
            clamp(Math.round(Color.red(color) * factor)),
            clamp(Math.round(Color.green(color) * factor)),
            clamp(Math.round(Color.blue(color) * factor))
        )
    }

    internal fun clamp(value: Int): Int {
        return max(0, min(255, value))
    }

    internal fun dp(context: Context, value: Float): Int {
        return (value * context.resources.displayMetrics.density + 0.5f).toInt()
    }

    internal fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density + 0.5f).toInt()
    }

    internal fun dpFloat(context: Context, value: Float): Float {
        return value * context.resources.displayMetrics.density
    }

    internal fun idName(view: View?): String {
        if (view == null || view.id == View.NO_ID) return ""
        return try {
            view.resources.getResourceEntryName(view.id)
        } catch (ignored: Resources.NotFoundException) {
            ""
        }
    }
}
