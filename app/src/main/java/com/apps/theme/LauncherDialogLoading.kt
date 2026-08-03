package com.apps.theme

import android.content.Context
import android.graphics.PorterDuff
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog

/** Loading-shell dialog builders for [LauncherDialogFactory]. */
internal object LauncherDialogLoading {

    /** Non-cancelable indeterminate loading shell. The caller owns its lifecycle. */
    internal fun showLoading(context: Context, title: String?, hint: String?): AlertDialog {
        val dialog = LauncherDialogParts.open(context, LauncherDialogFactory.WIDTH_STANDARD_DP, false)
        val root = LauncherDialogParts.root(context, false)
        root.addView(LauncherDialogParts.standardTitle(context, title))

        val progress = ProgressBar(context)
        progress.isIndeterminate = true
        progress.indeterminateDrawable?.setColorFilter(
            LauncherTheme.primary(context), PorterDuff.Mode.SRC_IN)
        val progressParams = LinearLayout.LayoutParams(LauncherTheme.dp(context, 32), LauncherTheme.dp(context, 32))
        progressParams.gravity = Gravity.CENTER_HORIZONTAL
        progressParams.setMargins(0, LauncherTheme.dp(context, 14), 0, 0)
        root.addView(progress, progressParams)

        val hintView = LauncherDialogParts.standardMessage(context, hint)
        hintView.textSize = 11f
        root.addView(hintView, LauncherDialogParts.topMargin(context, 10))
        LauncherDialogParts.setContent(dialog, root, LauncherDialogFactory.WIDTH_STANDARD_DP)
        return dialog
    }

    /**
     * Non-cancelable loading shell with an additional progress TextView tagged as [progressTag].
     * Sync flows update that tagged view while keeping all dialog construction inside the factory.
     */
    internal fun showProgressLoading(
        context: Context,
        title: String?,
        progressText: String?,
        hint: String?,
        progressTag: String?
    ): AlertDialog {
        val dialog = LauncherDialogParts.open(context, LauncherDialogFactory.WIDTH_STANDARD_DP, false)
        val root = LauncherDialogParts.root(context, false)
        root.addView(LauncherDialogParts.standardTitle(context, title))

        val progress = ProgressBar(context)
        progress.isIndeterminate = true
        progress.indeterminateDrawable?.setColorFilter(
            LauncherTheme.primary(context), PorterDuff.Mode.SRC_IN)
        val progressParams = LinearLayout.LayoutParams(LauncherTheme.dp(context, 32), LauncherTheme.dp(context, 32))
        progressParams.gravity = Gravity.CENTER_HORIZONTAL
        progressParams.setMargins(0, LauncherTheme.dp(context, 14), 0, 0)
        root.addView(progress, progressParams)

        val progressView = LauncherDialogParts.standardMessage(context, progressText)
        progressView.tag = progressTag
        root.addView(progressView, LauncherDialogParts.topMargin(context, 6))

        val hintView = LauncherDialogParts.standardMessage(context, hint)
        hintView.textSize = 11f
        root.addView(hintView, LauncherDialogParts.topMargin(context, 10))
        LauncherDialogParts.setContent(dialog, root, LauncherDialogFactory.WIDTH_STANDARD_DP)
        return dialog
    }
}
