package com.apps.widget

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.roundToInt

/** Applies Launcher portrait-tablet dimensions to phone-first XML layouts. */
object LauncherTabletPortraitScaler {
    private const val SMALL_TABLET_SCALE = 1.35f
    private const val LARGE_TABLET_SCALE = 1.55f

    @JvmStatic fun applyActivityContent(activity: Activity?) { activity?.findViewById<View>(android.R.id.content)?.let(::apply) }
    @JvmStatic fun apply(root: View?) {
        val scale = scaleFor(root)
        if (root == null || scale <= 1f || root.getTag(com.yuki.yukihub.R.id.launcher_tablet_portrait_scaled) != null) return
        applyToTree(root, scale)
        root.setTag(com.yuki.yukihub.R.id.launcher_tablet_portrait_scaled, true)
    }
    @JvmStatic fun scaleFor(view: View?): Float = scaleFor(view?.resources)
    @JvmStatic fun scaleFor(resources: Resources?): Float {
        resources ?: return 1f
        val config = resources.configuration
        if (config.orientation != Configuration.ORIENTATION_PORTRAIT || config.smallestScreenWidthDp < 600) return 1f
        return if (config.smallestScreenWidthDp >= 720) LARGE_TABLET_SCALE else SMALL_TABLET_SCALE
    }
    @JvmStatic fun isTabletPortrait(resources: Resources?): Boolean = scaleFor(resources) > 1f
    @JvmStatic fun libraryGridColumns(resources: Resources?): Int = if (!isTabletPortrait(resources)) 2 else if (resources!!.configuration.smallestScreenWidthDp >= 720) 4 else 3
    @JvmStatic fun libraryPageSize(resources: Resources?): Int = libraryGridColumns(resources) * 4
    @JvmStatic fun dp(context: Context?, baseDp: Int): Int = if (context == null) baseDp else (baseDp * context.resources.displayMetrics.density * scaleFor(context.resources)).roundToInt()

    private fun applyToTree(view: View, scale: Float) {
        view.layoutParams?.let { params ->
            if (params.width > 0) params.width = scalePx(params.width, scale)
            if (params.height > 0) params.height = scalePx(params.height, scale)
            (params as? ViewGroup.MarginLayoutParams)?.let { margins ->
                margins.leftMargin = scalePx(margins.leftMargin, scale); margins.topMargin = scalePx(margins.topMargin, scale)
                margins.rightMargin = scalePx(margins.rightMargin, scale); margins.bottomMargin = scalePx(margins.bottomMargin, scale)
            }; view.layoutParams = params
        }
        view.setPaddingRelative(scalePx(view.paddingStart, scale), scalePx(view.paddingTop, scale), scalePx(view.paddingEnd, scale), scalePx(view.paddingBottom, scale))
        if (view.minimumWidth > 0) view.minimumWidth = scalePx(view.minimumWidth, scale)
        if (view.minimumHeight > 0) view.minimumHeight = scalePx(view.minimumHeight, scale)
        if (view.elevation > 0f) view.elevation *= scale
        if (view is TextView) { view.setTextSize(TypedValue.COMPLEX_UNIT_PX, view.textSize * scale); view.compoundDrawablePadding = scalePx(view.compoundDrawablePadding, scale); view.setLineSpacing(view.lineSpacingExtra * scale, view.lineSpacingMultiplier) }
        if (view is ImageView) { if (view.maxWidth > 0) view.maxWidth = scalePx(view.maxWidth, scale); if (view.maxHeight > 0) view.maxHeight = scalePx(view.maxHeight, scale) }
        (view as? ViewGroup)?.let { group -> repeat(group.childCount) { applyToTree(group.getChildAt(it), scale) } }
    }
    private fun scalePx(value: Int, scale: Float): Int = if (value == 0) 0 else (value * scale).roundToInt()
}
