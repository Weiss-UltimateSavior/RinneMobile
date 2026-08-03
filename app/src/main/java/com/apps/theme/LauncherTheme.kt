package com.apps.theme

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.core.launcherbridge.LauncherUpdateBridge

/**
 * 统一的 Launcher 主题入口（薄协调层）。
 * 全部公共 API 委托给同包子 object（Colors / Drawables / Views / Switch / Spinner / Parts）实现。
 */
object LauncherTheme {

    @JvmStatic
    fun primary(context: Context): Int = LauncherThemeColors.primary(context)

    @JvmStatic
    fun onPrimary(context: Context): Int = LauncherThemeColors.onPrimary(context)

    @JvmStatic
    fun card(context: Context): Int = LauncherThemeColors.card(context)

    @JvmStatic
    fun bg(context: Context): Int = LauncherThemeColors.bg(context)

    @JvmStatic
    fun line(context: Context): Int = LauncherThemeColors.line(context)

    @JvmStatic
    fun text(context: Context): Int = LauncherThemeColors.text(context)

    @JvmStatic
    fun textMuted(context: Context): Int = LauncherThemeColors.textMuted(context)

    @JvmStatic
    fun primaryText(context: Context): Int = LauncherThemeColors.primaryText(context)

    @JvmStatic
    fun danger(context: Context): Int = LauncherThemeColors.danger(context)

    @JvmStatic
    fun onDanger(context: Context): Int = LauncherThemeColors.onDanger(context)

    @JvmStatic
    fun primaryButton(context: Context, radiusDp: Float): GradientDrawable =
        LauncherThemeDrawables.primaryButton(context, radiusDp)

    /** Primary tone without theme-specific gradients. */
    @JvmStatic
    fun solidPrimary(context: Context, radiusDp: Float): GradientDrawable =
        LauncherThemeDrawables.solidPrimary(context, radiusDp)

    /** Theme-colored card copy overlay with the same opacity as launcher_game_text_overlay. */
    @JvmStatic
    fun primaryTextOverlay(context: Context): GradientDrawable =
        LauncherThemeDrawables.primaryTextOverlay(context)

    @JvmStatic
    fun secondaryButton(context: Context, radiusDp: Float): GradientDrawable =
        LauncherThemeDrawables.secondaryButton(context, radiusDp)

    @JvmStatic
    fun dangerButton(context: Context, radiusDp: Float): GradientDrawable =
        LauncherThemeDrawables.dangerButton(context, radiusDp)

    @JvmStatic
    fun primaryGradientCard(context: Context, radiusDp: Float): GradientDrawable =
        LauncherThemeDrawables.primaryGradientCard(context, radiusDp)

    /** Outgoing messages use the active tone; incoming messages use the neutral card surface. */
    @JvmStatic
    fun chatBubble(context: Context, outgoing: Boolean): GradientDrawable =
        LauncherThemeDrawables.chatBubble(context, outgoing)

    @JvmStatic
    fun selectedChip(context: Context): GradientDrawable = LauncherThemeDrawables.selectedChip(context)

    @JvmStatic
    fun cancelChip(context: Context): GradientDrawable = LauncherThemeDrawables.cancelChip(context)

    @JvmStatic
    fun selectedOption(context: Context): GradientDrawable =
        LauncherThemeDrawables.selectedOption(context)

    @JvmStatic
    fun circle(context: Context): GradientDrawable = LauncherThemeDrawables.circle(context)

    @JvmStatic
    fun circle(context: Context, color: Int): GradientDrawable =
        LauncherThemeDrawables.circle(context, color)

    /** Navigation launch button with a 3dp, 60%-opaque ring in the active primary tone. */
    @JvmStatic
    fun circleWithSoftShadow(context: Context): Drawable =
        LauncherThemeDrawables.circleWithSoftShadow(context)

    /** Circle with card background color, matching the white-card style of manage rows. */
    @JvmStatic
    fun cardCircle(context: Context): GradientDrawable = LauncherThemeDrawables.cardCircle(context)

    /**
     * 统一应用右上角圆形按钮样式：cardCircle 背景 + 深色模式白色 tint / 浅色模式原色。
     * 供 LauncherHomeFragment.actionProfileMenu 和 LauncherProfileFragment.actionChangeCover 复用。
     */
    @JvmStatic
    fun applyCardCircleIcon(view: ImageView?, context: Context) {
        LauncherThemeDrawables.applyCardCircleIcon(view, context)
    }

    @JvmStatic
    fun xinhaitianCircle(context: Context): GradientDrawable =
        LauncherThemeDrawables.xinhaitianCircle(context)

    @JvmStatic
    fun statsScrim(context: Context): GradientDrawable = LauncherThemeDrawables.statsScrim(context)

    @JvmStatic
    fun statsScrim(baseColor: Int): GradientDrawable = LauncherThemeDrawables.statsScrim(baseColor)

    @JvmStatic
    fun textPrimary(view: TextView?) = LauncherThemeViews.textPrimary(view)

    @JvmStatic
    fun textOnPrimary(view: TextView?) = LauncherThemeViews.textOnPrimary(view)

    @JvmStatic
    fun chip(view: TextView?, selected: Boolean) = LauncherThemeViews.chip(view, selected)

    @JvmStatic
    fun primaryButton(view: TextView?) = LauncherThemeViews.primaryButton(view)

    /** Applies the common full-width action treatment used by Launcher setting pages. */
    @JvmStatic
    fun longActionButton(view: TextView?) = LauncherThemeViews.longActionButton(view)

    /** Applies the compact form of the shared Launcher action treatment. */
    @JvmStatic
    fun shortActionButton(view: TextView?) = LauncherThemeViews.shortActionButton(view)

    /** Applies the compact secondary action treatment while preserving shared button metrics. */
    @JvmStatic
    fun shortSecondaryActionButton(view: TextView?) = LauncherThemeViews.shortSecondaryActionButton(view)

    /** Normalizes ordinary page form fields; call only from non-dialog page roots. */
    @JvmStatic
    fun formInputs(vararg views: EditText?) = LauncherThemeViews.formInputs(*views)

    @JvmStatic
    fun secondaryButton(view: TextView?) = LauncherThemeViews.secondaryButton(view)

    @JvmStatic
    fun dangerButton(view: TextView?) = LauncherThemeViews.dangerButton(view)

    @JvmStatic
    fun menuItem(view: TextView?) = LauncherThemeViews.menuItem(view)

    @JvmStatic
    fun dangerMenuItem(view: TextView?) = LauncherThemeViews.dangerMenuItem(view)

    @JvmStatic
    fun styleSpinner(spinner: Spinner?) = LauncherThemeSpinner.styleSpinner(spinner)

    /**
     * 统一 SwitchCompat 启停按钮的色调：开启时使用主题主色，关闭时使用中性灰。
     * 必须在 Activity 创建后调用，确保主题已加载。
     */
    @JvmStatic
    fun styleSwitch(switchCompat: SwitchCompat?) = LauncherThemeSwitch.styleSwitch(switchCompat)

    /**
     * Material 3 风格开关：开启时为实色轨道与白色圆点，关闭时使用描边轨道。
     * 仅用于需要较大、醒目的设置页开关。
     */
    @JvmStatic
    fun styleMaterialSwitch(switchCompat: SwitchCompat?) =
        LauncherThemeSwitch.styleMaterialSwitch(switchCompat)

    /** Applies the active Launcher tone to a text input's insertion cursor. */
    @JvmStatic
    fun styleTextInput(input: EditText?) = LauncherThemeViews.styleTextInput(input)

    @JvmStatic
    fun <T> spinnerAdapter(context: Context, items: Array<T>): ArrayAdapter<T> =
        LauncherThemeSpinner.spinnerAdapter(context, items)

    @JvmStatic
    fun dialogButtons(cancel: TextView?, confirm: TextView?) =
        LauncherThemeViews.dialogButtons(cancel, confirm)

    @JvmStatic
    fun applyPrimaryTone(root: View?) = LauncherThemeViews.applyPrimaryTone(root)

    /** Applies the shared icon and arrow treatment used by Launcher action rows. */
    @JvmStatic
    fun styleManageRow(row: View?) = LauncherThemeViews.styleManageRow(row)

    @JvmStatic
    fun idName(view: View?): String = LauncherThemeParts.idName(view)

    @JvmStatic
    fun dp(context: Context, value: Float): Int = LauncherThemeParts.dp(context, value)

    /**
     * dp 转 px 的浮点版本（无舍入）：返回 value * density。
     * 与 [dp]（四舍五入返回 Int px）的语义不同，保留浮点精度，
     * 供粒子动画速度/半径等浮点物理量与绘制坐标使用，避免舍入造成的行为差异。
     */
    @JvmStatic
    fun dpFloat(context: Context, value: Float): Float = LauncherThemeParts.dpFloat(context, value)

    @JvmStatic
    fun dp(context: Context, value: Int): Int = LauncherThemeParts.dp(context, value)

    @JvmStatic
    fun showUpdateResultDialog(
        context: Context, info: LauncherUpdateBridge.UpdateInfo?,
        currentVersion: String?, hasUpdate: Boolean, error: String?
    ) {
        LauncherDialogFactory.showUpdateResult(context, info, currentVersion, hasUpdate, error)
    }
}
