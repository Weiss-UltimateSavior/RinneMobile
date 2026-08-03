package com.apps.theme

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.R

/**
 * Shared dialog-shell helpers used by [LauncherDialogFactory]'s split dialog builders
 * (Confirm / Choice / Loading / Update). Kept internal to the module.
 */
internal object LauncherDialogParts {

    internal fun open(context: Context, widthDp: Int): AlertDialog {
        return open(context, widthDp, true)
    }

    internal fun open(context: Context, widthDp: Int, cancelable: Boolean): AlertDialog {
        return open(context, widthDp, cancelable, null)
    }

    internal fun open(context: Context, widthDp: Int, cancelable: Boolean,
                      windowType: Int?): AlertDialog {
        val dialog = AlertDialog.Builder(context).create()
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(cancelable)
        if (windowType != null) {
            dialog.window?.setType(windowType)
        }
        dialog.show()
        LauncherMotion.applyDialogMotion(dialog)
        val window = dialog.window
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setLayout(dialogWidthPx(context, widthDp), WindowManager.LayoutParams.WRAP_CONTENT)
        }
        return dialog
    }

    internal fun setContent(dialog: AlertDialog, content: View, widthDp: Int) {
        val window: Window? = dialog.window
        if (window == null) return
        content.background = LauncherTheme.secondaryButton(content.context, 20f)
        LauncherTheme.applyPrimaryTone(content)
        LauncherTabletPortraitScaler.apply(content)
        window.setContentView(content)
        window.setLayout(dialogWidthPx(content.context, widthDp), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    internal fun root(context: Context, scrollable: Boolean): LinearLayout {
        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(
            LauncherTheme.dp(context, 22), LauncherTheme.dp(context, if (scrollable) 18 else 20),
            LauncherTheme.dp(context, 22), LauncherTheme.dp(context, if (scrollable) 15 else 16))
        root.background = LauncherTheme.secondaryButton(context, 20f)
        return root
    }

    internal fun title(context: Context, value: String?): TextView {
        val view = TextView(context)
        view.text = value
        view.setTextColor(LauncherTheme.text(context))
        view.textSize = 16f
        view.setTypeface(null, Typeface.BOLD)
        return view
    }

    internal fun standardTitle(context: Context, value: String?): TextView {
        val view = title(context, value)
        view.gravity = Gravity.CENTER
        return view
    }

    internal fun message(context: Context, value: String?): TextView {
        val view = TextView(context)
        view.text = value
        view.setTextColor(LauncherTheme.textMuted(context))
        view.textSize = 13f
        return view
    }

    internal fun standardMessage(context: Context, value: String?): TextView {
        val view = message(context, value)
        view.gravity = Gravity.CENTER
        view.textSize = 12f
        view.setLineSpacing(LauncherTheme.dp(context, 2).toFloat(), 1.05f)
        return view
    }

    internal fun button(context: Context, value: CharSequence?, primary: Boolean): TextView {
        val view = TextView(context)
        view.text = value
        view.gravity = Gravity.CENTER
        view.textSize = 13f
        view.setTypeface(null, Typeface.BOLD)
        if (primary) LauncherTheme.primaryButton(view) else LauncherTheme.secondaryButton(view)
        return view
    }

    internal fun compactChoice(context: Context, value: CharSequence?, selected: Boolean): TextView {
        val view = TextView(context)
        view.text = value
        view.gravity = Gravity.CENTER
        view.isSingleLine = true
        view.ellipsize = TextUtils.TruncateAt.MIDDLE
        view.textSize = 13f
        view.setTextColor(if (selected) LauncherTheme.primary(context) else LauncherTheme.text(context))
        view.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        view.background = LauncherTheme.cancelChip(context)
        return view
    }

    internal fun cancelButton(context: Context): TextView {
        val view = TextView(context)
        view.text = context.getString(R.string.launcher_dialog_cancel)
        view.gravity = Gravity.CENTER
        view.setTextColor(LauncherTheme.primary(context))
        view.textSize = 13f
        view.setTypeface(null, Typeface.BOLD)
        view.background = LauncherTheme.cancelChip(context)
        return view
    }

    internal fun topMargin(context: Context, marginDp: Int): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        params.setMargins(0, LauncherTheme.dp(context, marginDp), 0, 0)
        return params
    }

    internal fun fixedHeightTopMargin(context: Context, marginDp: Int, heightDp: Int): LinearLayout.LayoutParams {
        val params = topMargin(context, marginDp)
        params.height = LauncherTheme.dp(context, heightDp)
        return params
    }

    internal fun dialogWidthPx(context: Context, widthDp: Int): Int {
        val desiredWidth = LauncherTabletPortraitScaler.dp(context, widthDp)
        val horizontalMargin = LauncherTheme.dp(context, 16) * 2
        val maxWidth = Math.max(0, context.resources.displayMetrics.widthPixels - horizontalMargin)
        return Math.min(desiredWidth, maxWidth)
    }
}
