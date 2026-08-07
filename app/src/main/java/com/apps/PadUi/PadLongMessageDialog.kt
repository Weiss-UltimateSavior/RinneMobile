package com.apps.PadUi

import android.content.Context
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import com.apps.theme.LauncherDialogParts
import com.apps.theme.LauncherTheme

/**
 * Pad 滚动长消息确认对话框（W8 阶段 118）。
 *
 * 语义对齐 LauncherDialogConfirm.showLongMessageConfirm：ScrollView 滚动长消息 +
 * 确认/取消按钮 + resolved 防重复回调 + setOnCancelListener（back/外部点击取消时回调 onCancel）。
 * 为控制 PadDialogFactory 行数拆分（参照 PadUpdateDialog 先例），复用其 internal 构建 helper。
 */
object PadLongMessageDialog {

    @JvmStatic
    fun showLongMessageConfirm(
        context: Context,
        title: String?,
        message: String?,
        confirmText: String?,
        onConfirm: Runnable?,
        onCancel: Runnable?
    ): AlertDialog {
        val dialog = PadDialogFactory.open(context, PadDialogFactory.WIDTH_FORM_DP, true)
        var resolved = false
        val root = PadDialogFactory.root(context)
        root.addView(PadDialogFactory.title(context, title))

        val scroll = ScrollView(context)
        scroll.addView(PadDialogFactory.message(context, message))
        val scrollParams = PadDialogFactory.topMargin(context, 13)
        scrollParams.height = LauncherTheme.dp(context, 220)
        root.addView(scroll, scrollParams)
        // 消息短于预留高度时收紧到内容高度，避免短消息时在描述与按钮之间出现大片空白
        LauncherDialogParts.shrinkScrollToContent(scroll)

        val confirm = PadDialogFactory.button(context, confirmText, true)
        confirm.setOnClickListener {
            resolved = true
            dialog.dismiss()
            onConfirm?.run()
        }
        root.addView(confirm, PadDialogFactory.fixedHeightTopMargin(context, 11, 36))

        val cancel = PadDialogFactory.cancelButton(context)
        cancel.setOnClickListener {
            resolved = true
            dialog.dismiss()
            onCancel?.run()
        }
        dialog.setOnCancelListener {
            if (!resolved && onCancel != null) onCancel.run()
            resolved = true
        }
        root.addView(cancel, PadDialogFactory.fixedHeightTopMargin(context, 9, 36))
        PadDialogFactory.setContent(dialog, root, PadDialogFactory.WIDTH_FORM_DP)
        return dialog
    }
}
