package com.apps.PadUi

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.apps.theme.LauncherDialogFactory
import com.apps.theme.LauncherDialogParts
import com.apps.theme.LauncherMotion
import com.apps.theme.LauncherTheme
import com.core.R
import com.core.launcherbridge.LauncherUpdateBridge
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

    /** 双按钮确认（带取消文案与关闭回调）：会话过期等需区分「稍后」与「关闭」的场景。 */
    @JvmStatic
    fun showConfirm(
        context: Context,
        title: String?,
        message: String?,
        confirmText: String?,
        onConfirm: Runnable?,
        cancelText: CharSequence?,
        onDismiss: Runnable?,
    ): AlertDialog {
        val dialog = open(context, WIDTH_CONFIRM_DP, true)
        val content = LayoutInflater.from(context).inflate(R.layout.dialog_launcher_confirm, null)
        val titleView = content.findViewById<TextView>(R.id.dialogTitle)
        val messageView = content.findViewById<TextView>(R.id.dialogMessage)
        val cancel = content.findViewById<TextView>(R.id.dialogBtnCancel)
        val confirm = content.findViewById<TextView>(R.id.dialogBtnConfirm)
        titleView.text = title
        messageView.text = message
        confirm.text = confirmText
        // cancelText 为 null 时显式设默认，与 Launcher 版 showConfirm 对齐（LauncherDialogConfirm.kt:42）。
        cancel.text = cancelText ?: context.getString(R.string.core_cancel)
        LauncherTheme.dialogButtons(cancel, confirm)
        // onDismiss 语义与 Launcher 版对齐：任何 dismiss（含 confirm 点击）都触发。
        dialog.setOnDismissListener { onDismiss?.run() }
        cancel.setOnClickListener { dialog.dismiss() }
        confirm.setOnClickListener {
            dialog.dismiss()
            onConfirm?.run()
        }
        setContent(dialog, content, WIDTH_CONFIRM_DP)
        return dialog
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

    /** 信息提示 + 确认回调（onAcknowledge）。 */
    @JvmStatic
    fun showInfo(context: Context, title: String?, message: String?, onAcknowledge: Runnable?) {
        val dialog = open(context, WIDTH_COMPACT_DP, true)
        val root = root(context)
        root.addView(title(context, title))
        root.addView(message(context, message), topMargin(context, 13))

        val acknowledge = button(context, context.getString(R.string.pad_acknowledge), true)
        acknowledge.setOnClickListener {
            dialog.dismiss()
            onAcknowledge?.run()
        }
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
        val progressParams = LinearLayout.LayoutParams(LauncherTheme.dp(context, 32), LauncherTheme.dp(context, 32))
        progressParams.gravity = android.view.Gravity.CENTER_HORIZONTAL
        progressParams.setMargins(0, LauncherTheme.dp(context, 14), 0, 0)
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
        val progressParams = LinearLayout.LayoutParams(LauncherTheme.dp(context, 32), LauncherTheme.dp(context, 32))
        progressParams.gravity = android.view.Gravity.CENTER_HORIZONTAL
        progressParams.setMargins(0, LauncherTheme.dp(context, 14), 0, 0)
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
                    LinearLayout.LayoutParams.MATCH_PARENT, LauncherTheme.dp(context, 36)
                )
                optionParams.setMargins(0, if (index == 0) 0 else LauncherTheme.dp(context, 11), 0, 0)
                actions.addView(option, optionParams)
            }
        }
        scroll.addView(actions)
        val choiceCount = choices?.size ?: 0
        val listHeight = choiceCount * 36 + max(0, choiceCount - 1) * 11
        val scrollParams = topMargin(context, 11)
        val maxScrollHeight = min(LauncherTheme.dp(context, 252), LauncherTheme.dp(context, listHeight))
        val screenHeight = context.resources.displayMetrics.heightPixels
        val reservedHeight = LauncherTheme.dp(context, 160)
        val availableHeight = max(0, screenHeight - reservedHeight)
        scrollParams.height = min(maxScrollHeight, availableHeight)
        root.addView(scroll, scrollParams)

        val cancel = cancelButton(context)
        cancel.setOnClickListener { dialog.dismiss() }
        root.addView(cancel, fixedHeightTopMargin(context, 9, 36))
        setContent(dialog, root, WIDTH_COMPACT_DP)
    }

    /** 动作选项（无 danger 标记）：等价 dangerIndex=-1，供 LauncherDialogRouter 4 参路由。 */
    @JvmStatic
    fun showActionChoices(
        context: Context,
        title: String?,
        choices: Array<CharSequence>?,
        listener: ChoiceListener?
    ) {
        showActionChoices(context, title, choices, -1, listener)
    }

    /** 滚动长消息确认：对齐 LauncherDialogConfirm.showLongMessageConfirm 语义（resolved 防重 + 外部取消回调 onCancel）。 */
    @JvmStatic
    fun showLongMessageConfirm(
        context: Context,
        title: String?,
        message: String?,
        confirmText: String?,
        onConfirm: Runnable?,
        onCancel: Runnable?
    ): AlertDialog {
        return PadLongMessageDialog.showLongMessageConfirm(context, title, message, confirmText, onConfirm, onCancel)
    }

    @JvmStatic
    fun showMessageActionChoices(
        context: Context,
        title: String?,
        message: String?,
        choices: Array<CharSequence>?,
        cancelText: CharSequence?,
        listener: ChoiceListener?
    ) {
        val dialog = open(context, WIDTH_CONFIRM_DP, true)
        val root = root(context)
        root.addView(title(context, title))

        val scroll = ScrollView(context)
        scroll.addView(message(context, message))
        val scrollParams = topMargin(context, 13)
        val screenHeight = context.resources.displayMetrics.heightPixels
        scrollParams.height = min(LauncherTheme.dp(context, 220), max(LauncherTheme.dp(context, 80), screenHeight - LauncherTheme.dp(context, 210)))
        root.addView(scroll, scrollParams)
        // 消息短于预留高度时收紧到内容高度，避免短消息时在描述与选项之间出现大片空白
        LauncherDialogParts.shrinkScrollToContent(scroll)

        if (choices != null) {
            for (i in choices.indices) {
                val index = i
                val option = button(context, choices[i], true)
                option.setOnClickListener {
                    dialog.dismiss()
                    listener?.onChoice(index)
                }
                root.addView(option, fixedHeightTopMargin(context, if (index == 0) 13 else 9, 36))
            }
        }

        val cancel = button(context, cancelText ?: context.getString(R.string.core_cancel), false)
        cancel.setOnClickListener { dialog.dismiss() }
        root.addView(cancel, fixedHeightTopMargin(context, 9, 36))
        setContent(dialog, root, WIDTH_CONFIRM_DP)
    }

    /** 扫描深度选择（快速/完整切换 + 深度选项）：HD 下 Pad 视觉对应 Launcher 版。 */
    @JvmStatic
    fun showScanDepthChoices(
        context: Context,
        title: String?,
        quickModeText: String?,
        fullModeText: String?,
        labels: Array<CharSequence>?,
        depthValues: IntArray?,
        currentDepth: Int,
        listener: LauncherDialogFactory.ScanDepthListener?,
    ) {
        val dialog = open(context, WIDTH_COMPACT_DP, true)
        val root = root(context)
        root.addView(title(context, title))
        val fullRefresh = booleanArrayOf(false)
        val scanMode = button(context, quickModeText, false)
        scanMode.setOnClickListener {
            fullRefresh[0] = !fullRefresh[0]
            scanMode.text = if (fullRefresh[0]) fullModeText else quickModeText
        }
        root.addView(scanMode, fixedHeightTopMargin(context, 11, 36))
        if (labels != null && depthValues != null) {
            val count = Math.min(labels.size, depthValues.size)
            for (i in 0 until count) {
                val depth = depthValues[i]
                val selected = depth == currentDepth
                val option = compactChoice(context, labels[i], selected)
                option.setOnClickListener {
                    dialog.dismiss()
                    listener?.onChoice(depth, fullRefresh[0])
                }
                root.addView(option, fixedHeightTopMargin(context, 11, 36))
            }
        }
        val cancel = cancelButton(context)
        cancel.setOnClickListener { dialog.dismiss() }
        root.addView(cancel, fixedHeightTopMargin(context, 9, 36))
        setContent(dialog, root, WIDTH_COMPACT_DP)
    }

    /** 文本选择 + 跳过/取消（xp3 目标解析等）：HD 下 Pad 视觉对应 Launcher 版。 */
    @JvmStatic
    fun showTextChoicesWithSkip(
        context: Context,
        title: String?,
        message: String?,
        choices: List<String>?,
        skipText: String?,
        cancelText: String?,
        listener: LauncherDialogFactory.TextChoiceListener?,
        onSkip: Runnable?,
        onCancel: Runnable?,
    ) {
        val dialog = open(context, WIDTH_COMPACT_DP, false)
        val root = root(context)
        root.addView(title(context, title))
        root.addView(message(context, message), topMargin(context, 10))
        val scroll = ScrollView(context)
        val list = LinearLayout(context)
        list.orientation = LinearLayout.VERTICAL
        choices?.forEach { candidate ->
            val option = compactChoice(context, candidate, false)
            option.ellipsize = TextUtils.TruncateAt.MIDDLE
            option.setOnClickListener {
                dialog.dismiss()
                listener?.onChoice(candidate)
            }
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LauncherTheme.dp(context, 38))
            params.setMargins(0, LauncherTheme.dp(context, 8), 0, 0)
            list.addView(option, params)
        }
        scroll.addView(list)
        val scrollParams = topMargin(context, 4)
        val count = choices?.size ?: 0
        scrollParams.height = Math.min(LauncherTheme.dp(context, 250), LauncherTheme.dp(context, 8 + count * 46))
        root.addView(scroll, scrollParams)
        val buttons = LinearLayout(context)
        buttons.orientation = LinearLayout.HORIZONTAL
        val skip = button(context, skipText, false)
        skip.setOnClickListener {
            dialog.dismiss()
            onSkip?.run()
        }
        buttons.addView(skip, LinearLayout.LayoutParams(0, LauncherTheme.dp(context, 36), 1f))
        val cancel = button(context, cancelText, false)
        cancel.setOnClickListener {
            dialog.dismiss()
            onCancel?.run()
        }
        val cancelParams = LinearLayout.LayoutParams(0, LauncherTheme.dp(context, 36), 1f)
        cancelParams.setMargins(LauncherTheme.dp(context, 8), 0, 0, 0)
        buttons.addView(cancel, cancelParams)
        root.addView(buttons, fixedHeightTopMargin(context, 12, 36))
        setContent(dialog, root, WIDTH_COMPACT_DP)
    }

    @JvmStatic
    fun showUpdateResult(
        context: Context,
        info: LauncherUpdateBridge.UpdateInfo?,
        currentVersion: String?,
        hasUpdate: Boolean,
        error: String?
    ) {
        // 完整实现已拆分至 PadUpdateDialog，此处保留转发以兼容既有调用方，避免 PadDialogFactory 超过行数限制
        PadUpdateDialog.showUpdateResult(context, info, currentVersion, hasUpdate, error)
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
                    LinearLayout.LayoutParams.MATCH_PARENT, LauncherTheme.dp(context, 38)
                )
                optionParams.setMargins(0, LauncherTheme.dp(context, 7), 0, 0)
                list.addView(option, optionParams)
            }
        }
        scroll.addView(list)
        val listHeight = optionCount * (38 + 7)
        val scrollParams = topMargin(context, 7)
        val maxScrollHeight = min(LauncherTheme.dp(context, 280), LauncherTheme.dp(context, listHeight))
        val screenHeight = context.resources.displayMetrics.heightPixels
        val reservedHeight = LauncherTheme.dp(context, 160)
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
        actions.addView(cancel, LinearLayout.LayoutParams(0, LauncherTheme.dp(context, 38), 1f))

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
        val dangerParams = LinearLayout.LayoutParams(0, LauncherTheme.dp(context, 38), 1f)
        dangerParams.setMargins(LauncherTheme.dp(context, 8), 0, 0, 0)
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
        val densityWidth = LauncherTheme.dp(context, widthDp)
        val horizontalMargin = LauncherTheme.dp(context, 48)
        val availableWidth = context.resources.displayMetrics.widthPixels - horizontalMargin
        return max(0, min(densityWidth, availableWidth))
    }

    internal fun open(context: Context, widthDp: Int, cancelable: Boolean): AlertDialog {
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

    internal fun setContent(dialog: AlertDialog, content: View, widthDp: Int) {
        val window = dialog.window ?: return
        content.background = LauncherTheme.secondaryButton(content.context, 20f)
        LauncherTheme.applyPrimaryTone(content)
        window.setContentView(content)
        window.setLayout(dialogWidthPx(content.context, widthDp), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    internal fun root(context: Context): LinearLayout {
        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(LauncherTheme.dp(context, 22), LauncherTheme.dp(context, 20), LauncherTheme.dp(context, 22), LauncherTheme.dp(context, 16))
        return root
    }

    internal fun title(context: Context, text: String?): TextView {
        val view = TextView(context)
        view.text = text
        view.gravity = android.view.Gravity.CENTER
        view.setTextColor(LauncherTheme.text(context))
        view.textSize = 16f
        view.setTypeface(null, Typeface.BOLD)
        return view
    }

    internal fun message(context: Context, text: String?): TextView {
        val view = TextView(context)
        view.text = text
        view.gravity = android.view.Gravity.CENTER
        view.setTextColor(LauncherTheme.textMuted(context))
        view.textSize = 12f
        view.setLineSpacing(LauncherTheme.dp(context, 4).toFloat(), 1f)
        return view
    }

    internal fun button(context: Context, text: CharSequence?, primary: Boolean): TextView {
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

    internal fun cancelButton(context: Context): TextView =
        button(context, context.getString(R.string.core_cancel), false)

    private fun styleInlineAction(view: TextView) {
        view.gravity = android.view.Gravity.CENTER
        view.textSize = 13f
        view.setTypeface(null, Typeface.BOLD)
        view.minHeight = LauncherTheme.dp(view.context, 38)
    }

    internal fun topMargin(context: Context, topMarginDp: Int): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, LauncherTheme.dp(context, topMarginDp), 0, 0)
        return params
    }

    internal fun fixedHeightTopMargin(
        context: Context,
        topMarginDp: Int,
        heightDp: Int
    ): LinearLayout.LayoutParams {
        val params = topMargin(context, topMarginDp)
        params.height = LauncherTheme.dp(context, heightDp)
        return params
    }
}
