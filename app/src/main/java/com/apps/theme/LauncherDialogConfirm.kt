package com.apps.theme

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.core.R

/** Confirm-style dialog builders for [LauncherDialogFactory]. */
internal object LauncherDialogConfirm {

    internal fun showInfo(context: Context, title: String?, message: String?,
                          onAcknowledge: Runnable?) {
        val dialog = LauncherDialogParts.open(context, LauncherDialogFactory.WIDTH_STANDARD_DP)
        val root = LauncherDialogParts.root(context, false)
        root.addView(LauncherDialogParts.standardTitle(context, title))
        root.addView(LauncherDialogParts.standardMessage(context, message), LauncherDialogParts.topMargin(context, 13))
        val confirm = LauncherDialogParts.button(context, context.getString(R.string.settings_got_it), true)
        confirm.setOnClickListener {
            dialog.dismiss()
            onAcknowledge?.run()
        }
        root.addView(confirm, LauncherDialogParts.fixedHeightTopMargin(context, 11, 36))
        LauncherDialogParts.setContent(dialog, root, LauncherDialogFactory.WIDTH_STANDARD_DP)
    }

    internal fun showConfirm(context: Context, title: String?, message: String?,
                             confirmText: String?, onConfirm: Runnable?,
                             cancelText: CharSequence?, onDismiss: Runnable?): AlertDialog {
        val dialog = LauncherDialogParts.open(context, LauncherDialogFactory.WIDTH_COMPACT_DP)
        val root = LayoutInflater.from(context).inflate(R.layout.dialog_launcher_confirm, null)
        val titleView = root.findViewById<TextView>(R.id.dialogTitle)
        val messageView = root.findViewById<TextView>(R.id.dialogMessage)
        val cancel = root.findViewById<TextView>(R.id.dialogBtnCancel)
        val confirm = root.findViewById<TextView>(R.id.dialogBtnConfirm)
        titleView.text = title
        messageView.text = message
        cancel.text = cancelText ?: context.getString(R.string.launcher_dialog_cancel)
        confirm.text = confirmText
        LauncherTheme.dialogButtons(cancel, confirm)
        cancel.setOnClickListener { dialog.dismiss() }
        confirm.setOnClickListener {
            dialog.dismiss()
            onConfirm?.run()
        }
        if (onDismiss != null) {
            dialog.setOnDismissListener { onDismiss.run() }
        }
        LauncherDialogParts.setContent(dialog, root, LauncherDialogFactory.WIDTH_COMPACT_DP)
        return dialog
    }

    /** Compact confirmation rendered with the standard Launcher shell in an overlay window. */
    internal fun showOverlayConfirm(context: Context, title: String?, message: String?,
                                    confirmText: String?, onConfirm: Runnable?,
                                    windowType: Int): AlertDialog {
        val dialog = LauncherDialogParts.open(context, LauncherDialogFactory.WIDTH_COMPACT_DP, true, windowType)
        val root = LayoutInflater.from(context).inflate(R.layout.dialog_launcher_confirm, null)
        val titleView = root.findViewById<TextView>(R.id.dialogTitle)
        val messageView = root.findViewById<TextView>(R.id.dialogMessage)
        val cancel = root.findViewById<TextView>(R.id.dialogBtnCancel)
        val confirm = root.findViewById<TextView>(R.id.dialogBtnConfirm)
        titleView.text = title
        messageView.text = message
        confirm.text = confirmText
        LauncherTheme.dialogButtons(cancel, confirm)
        cancel.setOnClickListener { dialog.dismiss() }
        confirm.setOnClickListener {
            dialog.dismiss()
            onConfirm?.run()
        }
        LauncherDialogParts.setContent(dialog, root, LauncherDialogFactory.WIDTH_COMPACT_DP)
        return dialog
    }

    /** Standard-width confirmation used by settings and account flows. */
    internal fun showStandardConfirm(context: Context, title: String?, message: String?,
                                     confirmText: String?, onConfirm: Runnable?) {
        val dialog = LauncherDialogParts.open(context, LauncherDialogFactory.WIDTH_STANDARD_DP)
        val root = LauncherDialogParts.root(context, false)
        root.addView(LauncherDialogParts.standardTitle(context, title))
        root.addView(LauncherDialogParts.standardMessage(context, message), LauncherDialogParts.topMargin(context, 13))

        val confirm = LauncherDialogParts.button(context, confirmText, true)
        confirm.setOnClickListener {
            dialog.dismiss()
            onConfirm?.run()
        }
        root.addView(confirm, LauncherDialogParts.fixedHeightTopMargin(context, 11, 36))

        val cancel = LauncherDialogParts.cancelButton(context)
        cancel.setOnClickListener { dialog.dismiss() }
        root.addView(cancel, LauncherDialogParts.fixedHeightTopMargin(context, 9, 36))
        LauncherDialogParts.setContent(dialog, root, LauncherDialogFactory.WIDTH_STANDARD_DP)
    }

    /** Scrollable long-message confirmation for content that cannot safely fit the compact shell. */
    internal fun showLongMessageConfirm(context: Context, title: String?, message: String?,
                                        confirmText: String?, onConfirm: Runnable?,
                                        onCancel: Runnable?): AlertDialog {
        val dialog = LauncherDialogParts.open(context, LauncherDialogFactory.WIDTH_FORM_DP)
        var resolved = false
        val root = LauncherDialogParts.root(context, false)
        root.addView(LauncherDialogParts.standardTitle(context, title))

        val scroll = ScrollView(context)
        val messageView = LauncherDialogParts.standardMessage(context, message)
        scroll.addView(messageView)
        val scrollParams = LauncherDialogParts.topMargin(context, 13)
        scrollParams.height = LauncherTheme.dp(context, 220)
        root.addView(scroll, scrollParams)
        // 消息短于预留高度时收紧到内容高度，避免短消息时在描述与按钮之间出现大片空白
        LauncherDialogParts.shrinkScrollToContent(scroll)

        val confirm = LauncherDialogParts.button(context, confirmText, true)
        confirm.setOnClickListener {
            resolved = true
            dialog.dismiss()
            onConfirm?.run()
        }
        root.addView(confirm, LauncherDialogParts.fixedHeightTopMargin(context, 11, 36))

        val cancel = LauncherDialogParts.cancelButton(context)
        cancel.setOnClickListener {
            resolved = true
            dialog.dismiss()
            onCancel?.run()
        }
        dialog.setOnCancelListener {
            if (!resolved && onCancel != null) onCancel.run()
            resolved = true
        }
        root.addView(cancel, LauncherDialogParts.fixedHeightTopMargin(context, 9, 36))
        LauncherDialogParts.setContent(dialog, root, LauncherDialogFactory.WIDTH_FORM_DP)
        return dialog
    }

    /** Standard-width destructive confirmation with a horizontal action row. */
    internal fun showDangerConfirm(context: Context, title: String?, message: String?,
                                   dangerText: String?, onConfirm: Runnable?) {
        val dialog = LauncherDialogParts.open(context, LauncherDialogFactory.WIDTH_STANDARD_DP)
        val root = LauncherDialogParts.root(context, false)
        root.addView(LauncherDialogParts.standardTitle(context, title))
        root.addView(LauncherDialogParts.standardMessage(context, message), LauncherDialogParts.topMargin(context, 13))

        val actions = LinearLayout(context)
        actions.orientation = LinearLayout.HORIZONTAL
        val cancel = LauncherDialogParts.button(context, context.getString(R.string.launcher_dialog_cancel), false)
        cancel.setOnClickListener { dialog.dismiss() }
        actions.addView(cancel, LinearLayout.LayoutParams(0, LauncherTheme.dp(context, 38), 1f))

        val danger = TextView(context)
        danger.text = dangerText
        danger.gravity = Gravity.CENTER
        danger.textSize = 13f
        danger.setTypeface(null, Typeface.BOLD)
        LauncherTheme.dangerButton(danger)
        danger.setOnClickListener {
            dialog.dismiss()
            onConfirm?.run()
        }
        val dangerParams = LinearLayout.LayoutParams(0, LauncherTheme.dp(context, 38), 1f)
        dangerParams.setMargins(LauncherTheme.dp(context, 10), 0, 0, 0)
        actions.addView(danger, dangerParams)
        root.addView(actions, LauncherDialogParts.fixedHeightTopMargin(context, 13, 38))
        LauncherDialogParts.setContent(dialog, root, LauncherDialogFactory.WIDTH_STANDARD_DP)
    }

    /** Android 11+ 全文件访问权限引导对话框，GO 按钮由调用方处理跳转。 */
    internal fun showStoragePermissionRequest(
        context: Context,
        onGo: Runnable,
        onCancel: Runnable,
    ) {
        val dialog = LauncherDialogParts.open(context, LauncherDialogFactory.WIDTH_FORM_DP)
        val root = LauncherDialogParts.root(context, false)
        root.addView(LauncherDialogParts.standardTitle(context, context.getString(R.string.core_file_access_title)))
        root.addView(LauncherDialogParts.standardMessage(context, context.getString(R.string.core_file_access_message)), LauncherDialogParts.topMargin(context, 13))

        val goBtn = LauncherDialogParts.button(context, context.getString(R.string.core_go), true)
        goBtn.setOnClickListener {
            dialog.dismiss()
            onGo.run()
        }
        root.addView(goBtn, LauncherDialogParts.fixedHeightTopMargin(context, 9, 38))

        val cancelBtn = LauncherDialogParts.cancelButton(context)
        cancelBtn.setOnClickListener {
            dialog.dismiss()
            onCancel.run()
        }
        root.addView(cancelBtn, LauncherDialogParts.fixedHeightTopMargin(context, 9, 38))

        LauncherDialogParts.setContent(dialog, root, LauncherDialogFactory.WIDTH_FORM_DP)
    }
}
