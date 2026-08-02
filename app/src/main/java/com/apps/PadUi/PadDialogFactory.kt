package com.apps.PadUi

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.apps.theme.LauncherMotion
import com.apps.theme.LauncherTheme
import com.core.R
import kotlin.math.max
import kotlin.math.min

/** Shared dialog shell for the landscape Pad surfaces. */
object PadDialogFactory {
    const val WIDTH_COMPACT_DP: Int = 270
    const val WIDTH_CONFIRM_DP: Int = 288
    const val WIDTH_FORM_DP: Int = 288

    fun interface ChoiceListener {
        fun onChoice(index: Int)
    }

    @JvmStatic
    fun showConfirm(
        context: Context,
        title: String?,
        message: String?,
        confirmText: String?,
        onConfirm: Runnable?
    ) {
        val dialog = open(context, WIDTH_CONFIRM_DP, true)
        val content = LayoutInflater.from(context).inflate(R.layout.dialog_launcher_confirm, null)
        val titleView = content.findViewById<TextView>(R.id.dialogTitle)
        val messageView = content.findViewById<TextView>(R.id.dialogMessage)
        val cancel = content.findViewById<TextView>(R.id.dialogBtnCancel)
        val confirm = content.findViewById<TextView>(R.id.dialogBtnConfirm)
        titleView.text = title
        messageView.text = message
        confirm.text = confirmText
        LauncherTheme.dialogButtons(cancel, confirm)
        cancel.setOnClickListener { dialog.dismiss() }
        confirm.setOnClickListener {
            dialog.dismiss()
            onConfirm?.run()
        }
        setContent(dialog, content, WIDTH_CONFIRM_DP)
    }

    @JvmStatic
    fun showStandardConfirm(
        context: Context,
        title: String?,
        message: String?,
        confirmText: String?,
        onConfirm: Runnable?
    ) {
        val dialog = open(context, WIDTH_COMPACT_DP, true)
        val root = root(context)
        root.addView(title(context, title))
        root.addView(message(context, message), topMargin(context, 13))

        val confirm = button(context, confirmText, true)
        confirm.setOnClickListener {
            dialog.dismiss()
            onConfirm?.run()
        }
        root.addView(confirm, fixedHeightTopMargin(context, 11, 36))

        val cancel = cancelButton(context)
        cancel.setOnClickListener { dialog.dismiss() }
        root.addView(cancel, fixedHeightTopMargin(context, 9, 36))
        setContent(dialog, root, WIDTH_COMPACT_DP)
    }

    @JvmStatic
    fun showInfo(context: Context, title: String?, message: String?) {
        val dialog = open(context, WIDTH_COMPACT_DP, true)
        val root = root(context)
        root.addView(title(context, title))
        root.addView(message(context, message), topMargin(context, 13))

        val acknowledge = button(context, context.getString(R.string.pad_acknowledge), true)
        acknowledge.setOnClickListener { dialog.dismiss() }
        root.addView(acknowledge, fixedHeightTopMargin(context, 11, 36))
        setContent(dialog, root, WIDTH_COMPACT_DP)
    }

    /** Non-cancelable compact loading dialog; the caller owns its lifecycle. */
    @JvmStatic
    fun showLoading(context: Context, title: String?, hint: String?): AlertDialog {
        val dialog = open(context, WIDTH_COMPACT_DP, false)
        val root = root(context)
        root.addView(title(context, title))

        val progress = ProgressBar(context)
        progress.isIndeterminate = true
        progress.indeterminateDrawable?.setColorFilter(
            LauncherTheme.primary(context), PorterDuff.Mode.SRC_IN
        )
        val progressParams = LinearLayout.LayoutParams(dp(context, 32), dp(context, 32))
        progressParams.gravity = android.view.Gravity.CENTER_HORIZONTAL
        progressParams.setMargins(0, dp(context, 14), 0, 0)
        root.addView(progress, progressParams)

        val hintView = message(context, hint)
        hintView.textSize = 11f
        root.addView(hintView, topMargin(context, 10))
        setContent(dialog, root, WIDTH_COMPACT_DP)
        return dialog
    }

    /** Non-cancelable Pad loading shell with a tagged progress TextView for sync updates. */
    @JvmStatic
    fun showProgressLoading(
        context: Context,
        title: String?,
        progressText: String?,
        hint: String?,
        progressTag: String?
    ): AlertDialog {
        val dialog = open(context, WIDTH_COMPACT_DP, false)
        val root = root(context)
        root.addView(title(context, title))

        val progress = ProgressBar(context)
        progress.isIndeterminate = true
        progress.indeterminateDrawable?.setColorFilter(
            LauncherTheme.primary(context), PorterDuff.Mode.SRC_IN
        )
        val progressParams = LinearLayout.LayoutParams(dp(context, 32), dp(context, 32))
        progressParams.gravity = android.view.Gravity.CENTER_HORIZONTAL
        progressParams.setMargins(0, dp(context, 14), 0, 0)
        root.addView(progress, progressParams)

        val progressView = message(context, progressText)
        progressView.tag = progressTag
        root.addView(progressView, topMargin(context, 6))

        val hintView = message(context, hint)
        hintView.textSize = 11f
        root.addView(hintView, topMargin(context, 10))
        setContent(dialog, root, WIDTH_COMPACT_DP)
        return dialog
    }

    @JvmStatic
    fun showActionChoices(
        context: Context,
        title: String?,
        choices: Array<CharSequence>?,
        dangerIndex: Int,
        listener: ChoiceListener?
    ) {
        val dialog = open(context, WIDTH_COMPACT_DP, true)
        val root = root(context)
        val titleView = title(context, title)
        titleView.isSingleLine = true
        titleView.ellipsize = android.text.TextUtils.TruncateAt.END
        root.addView(titleView)

        val scroll = ScrollView(context)
        val actions = LinearLayout(context)
        actions.orientation = LinearLayout.VERTICAL
        if (choices != null) {
            for (i in choices.indices) {
                val index = i
                val option = TextView(context)
                option.text = choices[i]
                option.gravity = android.view.Gravity.CENTER
                option.isSingleLine = true
                option.textSize = 13f
                option.setTypeface(null, Typeface.BOLD)
                if (index == dangerIndex) {
                    LauncherTheme.dangerMenuItem(option)
                } else {
                    LauncherTheme.menuItem(option)
                }
                option.setOnClickListener {
                    dialog.dismiss()
                    listener?.onChoice(index)
                }
                val optionParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 36)
                )
                optionParams.setMargins(0, if (index == 0) 0 else dp(context, 11), 0, 0)
                actions.addView(option, optionParams)
            }
        }
        scroll.addView(actions)
        val choiceCount = choices?.size ?: 0
        val listHeight = choiceCount * 36 + max(0, choiceCount - 1) * 11
        val scrollParams = topMargin(context, 11)
        val maxScrollHeight = min(dp(context, 252), dp(context, listHeight))
        val screenHeight = context.resources.displayMetrics.heightPixels
        val reservedHeight = dp(context, 160)
        val availableHeight = max(0, screenHeight - reservedHeight)
        scrollParams.height = min(maxScrollHeight, availableHeight)
        root.addView(scroll, scrollParams)

        val cancel = cancelButton(context)
        cancel.setOnClickListener { dialog.dismiss() }
        root.addView(cancel, fixedHeightTopMargin(context, 9, 36))
        setContent(dialog, root, WIDTH_COMPACT_DP)
    }

    @JvmStatic
    fun showSingleChoice(
        context: Context,
        title: String?,
        choices: Array<CharSequence>?,
        checkedIndex: Int,
        listener: ChoiceListener?
    ) {
        val dialog = open(context, WIDTH_COMPACT_DP, true)
        val root = root(context)
        root.addView(title(context, title))

        val scroll = ScrollView(context)
        val list = LinearLayout(context)
        list.orientation = LinearLayout.VERTICAL
        val optionCount = choices?.size ?: 0
        if (choices != null) {
            for (i in choices.indices) {
                val index = i
                val option = compactChoice(context, choices[i], index == checkedIndex)
                option.setOnClickListener {
                    dialog.dismiss()
                    listener?.onChoice(index)
                }
                val optionParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 38)
                )
                optionParams.setMargins(0, dp(context, 7), 0, 0)
                list.addView(option, optionParams)
            }
        }
        scroll.addView(list)
        val listHeight = optionCount * (38 + 7)
        val scrollParams = topMargin(context, 7)
        val maxScrollHeight = min(dp(context, 280), dp(context, listHeight))
        val screenHeight = context.resources.displayMetrics.heightPixels
        val reservedHeight = dp(context, 160)
        val availableHeight = max(0, screenHeight - reservedHeight)
        scrollParams.height = min(maxScrollHeight, availableHeight)
        root.addView(scroll, scrollParams)
        val cancel = cancelButton(context)
        cancel.setOnClickListener { dialog.dismiss() }
        root.addView(cancel, fixedHeightTopMargin(context, 9, 36))
        setContent(dialog, root, WIDTH_COMPACT_DP)
    }

    @JvmStatic
    fun showDangerConfirm(
        context: Context,
        title: String?,
        message: String?,
        dangerText: String?,
        onConfirm: Runnable?
    ) {
        val dialog = open(context, WIDTH_COMPACT_DP, true)
        val root = root(context)
        root.addView(title(context, title))
        root.addView(message(context, message), topMargin(context, 13))

        val actions = LinearLayout(context)
        actions.orientation = LinearLayout.HORIZONTAL
        val cancel = button(context, context.getString(R.string.core_cancel), false)
        cancel.setOnClickListener { dialog.dismiss() }
        actions.addView(cancel, LinearLayout.LayoutParams(0, dp(context, 38), 1f))

        val danger = TextView(context)
        danger.text = dangerText
        danger.gravity = android.view.Gravity.CENTER
        danger.textSize = 13f
        danger.setTypeface(null, Typeface.BOLD)
        LauncherTheme.dangerButton(danger)
        danger.setOnClickListener {
            dialog.dismiss()
            onConfirm?.run()
        }
        val dangerParams = LinearLayout.LayoutParams(0, dp(context, 38), 1f)
        dangerParams.setMargins(dp(context, 8), 0, 0, 0)
        actions.addView(danger, dangerParams)
        root.addView(actions, fixedHeightTopMargin(context, 13, 38))
        setContent(dialog, root, WIDTH_COMPACT_DP)
    }

    @JvmStatic
    fun primaryInlineAction(view: TextView) {
        styleInlineAction(view)
        LauncherTheme.primaryButton(view)
    }

    @JvmStatic
    fun secondaryInlineAction(view: TextView) {
        styleInlineAction(view)
        LauncherTheme.secondaryButton(view)
    }

    @JvmStatic
    fun dialogWidthPx(context: Context, widthDp: Int): Int {
        val densityWidth = dp(context, widthDp)
        val horizontalMargin = dp(context, 48)
        val availableWidth = context.resources.displayMetrics.widthPixels - horizontalMargin
        return max(0, min(densityWidth, availableWidth))
    }

    private fun open(context: Context, widthDp: Int, cancelable: Boolean): AlertDialog {
        val dialog = AlertDialog.Builder(context).create()
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(cancelable)
        dialog.show()
        LauncherMotion.applyDialogMotion(dialog)
        val window = dialog.window
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setLayout(dialogWidthPx(context, widthDp), WindowManager.LayoutParams.WRAP_CONTENT)
        }
        return dialog
    }

    private fun setContent(dialog: AlertDialog, content: View, widthDp: Int) {
        val window = dialog.window ?: return
        content.background = LauncherTheme.secondaryButton(content.context, 20f)
        LauncherTheme.applyPrimaryTone(content)
        window.setContentView(content)
        window.setLayout(dialogWidthPx(content.context, widthDp), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun root(context: Context): LinearLayout {
        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(context, 22), dp(context, 20), dp(context, 22), dp(context, 16))
        return root
    }

    private fun title(context: Context, text: String?): TextView {
        val view = TextView(context)
        view.text = text
        view.gravity = android.view.Gravity.CENTER
        view.setTextColor(LauncherTheme.text(context))
        view.textSize = 16f
        view.setTypeface(null, Typeface.BOLD)
        return view
    }

    private fun message(context: Context, text: String?): TextView {
        val view = TextView(context)
        view.text = text
        view.gravity = android.view.Gravity.CENTER
        view.setTextColor(LauncherTheme.textMuted(context))
        view.textSize = 12f
        view.setLineSpacing(dp(context, 4).toFloat(), 1f)
        return view
    }

    private fun button(context: Context, text: CharSequence?, primary: Boolean): TextView {
        val view = TextView(context)
        view.text = text
        view.gravity = android.view.Gravity.CENTER
        view.textSize = 13f
        view.setTypeface(null, Typeface.BOLD)
        if (primary) {
            LauncherTheme.primaryButton(view)
        } else {
            LauncherTheme.secondaryButton(view)
        }
        return view
    }

    private fun compactChoice(context: Context, text: CharSequence?, selected: Boolean): TextView {
        val view = TextView(context)
        view.text = text
        view.gravity = android.view.Gravity.CENTER
        view.isSingleLine = true
        view.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        view.textSize = 13f
        view.setTextColor(if (selected) LauncherTheme.primary(context) else LauncherTheme.text(context))
        view.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        view.background = LauncherTheme.cancelChip(context)
        return view
    }

    private fun cancelButton(context: Context): TextView =
        button(context, context.getString(R.string.core_cancel), false)

    private fun styleInlineAction(view: TextView) {
        view.gravity = android.view.Gravity.CENTER
        view.textSize = 13f
        view.setTypeface(null, Typeface.BOLD)
        view.minHeight = dp(view.context, 38)
    }

    private fun topMargin(context: Context, topMarginDp: Int): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, dp(context, topMarginDp), 0, 0)
        return params
    }

    private fun fixedHeightTopMargin(
        context: Context,
        topMarginDp: Int,
        heightDp: Int
    ): LinearLayout.LayoutParams {
        val params = topMargin(context, topMarginDp)
        params.height = dp(context, heightDp)
        return params
    }

    private fun dp(context: Context, value: Int): Int =
        LauncherTheme.dp(context, value)
}
