package com.apps.theme

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils

/** TextView/EditText/视图树等控件样式应用。 */
internal object LauncherThemeViews {

    internal fun textPrimary(view: TextView?) {
        if (view != null) view.setTextColor(LauncherThemeColors.primary(view.context))
    }

    internal fun textOnPrimary(view: TextView?) {
        if (view != null) view.setTextColor(LauncherThemeColors.onPrimary(view.context))
    }

    internal fun chip(view: TextView?, selected: Boolean) {
        if (view == null) return
        view.setTextColor(if (selected) LauncherThemeColors.onPrimary(view.context) else LauncherThemeColors.primary(view.context))
        view.background = if (selected) LauncherThemeDrawables.selectedChip(view.context) else LauncherThemeDrawables.secondaryButton(view.context, 999f)
    }

    internal fun primaryButton(view: TextView?) {
        if (view == null) return
        view.setTextColor(LauncherThemeColors.onPrimary(view.context))
        view.background = LauncherThemeDrawables.primaryButton(view.context, 20f)
    }

    internal fun longActionButton(view: TextView?) {
        if (view == null) return
        view.gravity = Gravity.CENTER
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        view.setTypeface(null, Typeface.BOLD)
        primaryButton(view)
    }

    internal fun shortActionButton(view: TextView?) {
        longActionButton(view)
    }

    internal fun shortSecondaryActionButton(view: TextView?) {
        if (view == null) return
        view.gravity = Gravity.CENTER
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        view.setTypeface(null, Typeface.BOLD)
        secondaryButton(view)
    }

    internal fun formInputs(vararg views: EditText?) {
        for (view in views) {
            if (view == null) continue
            val context = view.context
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            view.setPaddingRelative(LauncherThemeParts.dp(context, 13f), view.paddingTop, LauncherThemeParts.dp(context, 13f), view.paddingBottom)
            view.background = LauncherThemeDrawables.secondaryButton(context, 20f)
            val inputType = view.inputType
            val multiline = (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
            if (!multiline && view.layoutParams != null) {
                view.layoutParams.height = LauncherThemeParts.dp(context, 45f)
                view.requestLayout()
            }
            styleTextInput(view)
        }
    }

    internal fun secondaryButton(view: TextView?) {
        if (view == null) return
        view.setTextColor(LauncherThemeColors.primary(view.context))
        view.background = LauncherThemeDrawables.secondaryButton(view.context, 20f)
    }

    internal fun dangerButton(view: TextView?) {
        if (view == null) return
        view.setTextColor(LauncherThemeColors.onDanger(view.context))
        view.background = LauncherThemeDrawables.dangerButton(view.context, 20f)
    }

    internal fun menuItem(view: TextView?) {
        if (view == null) return
        view.setTextColor(LauncherThemeColors.primary(view.context))
        view.background = LauncherThemeDrawables.secondaryButton(view.context, 999f)
    }

    internal fun dangerMenuItem(view: TextView?) {
        if (view == null) return
        view.setTextColor(LauncherThemeColors.danger(view.context))
        view.background = LauncherThemeDrawables.secondaryButton(view.context, 999f)
    }

    internal fun styleTextInput(input: EditText?) {
        if (input == null) return
        val primary = LauncherThemeColors.primary(input.context)
        input.highlightColor = ColorUtils.setAlphaComponent(primary, 82)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val cursor = GradientDrawable()
        cursor.setColor(primary)
        cursor.setSize(LauncherThemeParts.dp(input.context, 2f), -1)
        input.setTextCursorDrawable(cursor)
        input.setTextSelectHandle(LauncherThemeParts.selectionHandle(input.context, primary))
        input.setTextSelectHandleLeft(LauncherThemeParts.selectionHandle(input.context, primary))
        input.setTextSelectHandleRight(LauncherThemeParts.selectionHandle(input.context, primary))
    }

    internal fun dialogButtons(cancel: TextView?, confirm: TextView?) {
        if (cancel != null) {
            secondaryButton(cancel)
        }
        primaryButton(confirm)
    }

    internal fun applyPrimaryTone(root: View?) {
        if (root == null) return
        val context = root.context
        val defaultPrimary = LauncherThemeColors.primaryText(context)
        val themedPrimary = LauncherThemeColors.primary(context)

        if (root is TextView) {
            if (root.currentTextColor == defaultPrimary) {
                root.setTextColor(themedPrimary)
            }
        }
        if (root is CompoundButton) {
            root.buttonTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(themedPrimary, LauncherThemeColors.textMuted(context))
            )
        }
        if (root is EditText) {
            styleTextInput(root)
        }

        val idName = LauncherThemeParts.idName(root)
        if (LauncherThemeParts.isPrimaryButtonId(idName) && root is TextView) {
            primaryButton(root)
        } else if (LauncherThemeParts.isSecondaryButtonId(idName) && root is TextView) {
            secondaryButton(root)
        } else if (LauncherThemeParts.isDangerButtonId(idName) && root is TextView) {
            dangerButton(root)
        }

        if (root is ViewGroup) {
            val group = root
            for (i in 0 until group.childCount) {
                applyPrimaryTone(group.getChildAt(i))
            }
        }
    }

    internal fun styleManageRow(row: View?) {
        if (row !is ViewGroup) return
        val context = row.context
        val group: ViewGroup = row
        if (group.childCount > 0 && group.getChildAt(0) is TextView) {
            val icon = group.getChildAt(0) as TextView
            icon.background = LauncherThemeDrawables.circle(context)
            icon.setTextColor(LauncherThemeColors.onPrimary(context))
        } else if (group.childCount > 0 && group.getChildAt(0) is ImageView) {
            val icon = group.getChildAt(0) as ImageView
            icon.background = null
            icon.imageTintList = ColorStateList.valueOf(LauncherThemeColors.primary(context))
        }
        if (group.childCount > 2 && group.getChildAt(2) is ImageView) {
            (group.getChildAt(2) as ImageView).imageTintList =
                ColorStateList.valueOf(LauncherThemeColors.primary(context))
        }
    }
}
