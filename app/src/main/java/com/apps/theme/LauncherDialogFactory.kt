package com.apps.theme

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.apps.util.LauncherUrlOpener
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.R
import com.core.launcherbridge.LauncherUpdateBridge

/** Shared non-engine Launcher dialog shell. */
object LauncherDialogFactory {
    /** Visual baseline from the Launcher center-navigation confirmation dialog. */
    const val WIDTH_COMPACT_DP: Int = 252
    const val WIDTH_STANDARD_DP: Int = WIDTH_COMPACT_DP
    const val WIDTH_FORM_DP: Int = 288
    const val WIDTH_ACTION_MENU_DP: Int = 340

    fun interface ChoiceListener {
        fun onChoice(index: Int)
    }

    fun interface ScanDepthListener {
        fun onChoice(depth: Int, fullRefresh: Boolean)
    }

    fun interface TextChoiceListener {
        fun onChoice(value: String)
    }

    @JvmStatic
    fun showInfo(context: Context, title: String?, message: String?) {
        showInfo(context, title, message, null)
    }

    /** Standard-width information prompt with an optional acknowledgement callback. */
    @JvmStatic
    fun showInfo(context: Context, title: String?, message: String?,
                 onAcknowledge: Runnable?) {
        val dialog = open(context, WIDTH_STANDARD_DP)
        val root = root(context, false)
        root.addView(standardTitle(context, title))
        root.addView(standardMessage(context, message), topMargin(context, 13))
        val confirm = button(context, context.getString(R.string.settings_got_it), true)
        confirm.setOnClickListener {
            dialog.dismiss()
            onAcknowledge?.run()
        }
        root.addView(confirm, fixedHeightTopMargin(context, 11, 36))
        setContent(dialog, root, WIDTH_STANDARD_DP)
    }

    @JvmStatic
    fun showConfirm(context: Context, title: String?, message: String?,
                    confirmText: String?, onConfirm: Runnable?) {
        showConfirm(context, title, message, confirmText, onConfirm, null, null)
    }

    @JvmStatic
    fun showConfirm(context: Context, title: String?, message: String?,
                    confirmText: String?, onConfirm: Runnable?,
                    onDismiss: Runnable?): AlertDialog {
        return showConfirm(context, title, message, confirmText, onConfirm, null, onDismiss)
    }

    @JvmStatic
    fun showConfirm(context: Context, title: String?, message: String?,
                    confirmText: String?, onConfirm: Runnable?,
                    cancelText: CharSequence?, onDismiss: Runnable?): AlertDialog {
        val dialog = open(context, WIDTH_COMPACT_DP)
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
        setContent(dialog, root, WIDTH_COMPACT_DP)
        return dialog
    }

    /** Compact confirmation rendered with the standard Launcher shell in an overlay window. */
    @JvmStatic
    fun showOverlayConfirm(context: Context, title: String?, message: String?,
                           confirmText: String?, onConfirm: Runnable?,
                           windowType: Int): AlertDialog {
        val dialog = open(context, WIDTH_COMPACT_DP, true, windowType)
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
        setContent(dialog, root, WIDTH_COMPACT_DP)
        return dialog
    }

    /** Standard-width confirmation used by settings and account flows. */
    @JvmStatic
    fun showStandardConfirm(context: Context, title: String?, message: String?,
                            confirmText: String?, onConfirm: Runnable?) {
        val dialog = open(context, WIDTH_STANDARD_DP)
        val root = root(context, false)
        root.addView(standardTitle(context, title))
        root.addView(standardMessage(context, message), topMargin(context, 13))

        val confirm = button(context, confirmText, true)
        confirm.setOnClickListener {
            dialog.dismiss()
            onConfirm?.run()
        }
        root.addView(confirm, fixedHeightTopMargin(context, 11, 36))

        val cancel = cancelButton(context)
        cancel.setOnClickListener { dialog.dismiss() }
        root.addView(cancel, fixedHeightTopMargin(context, 9, 36))
        setContent(dialog, root, WIDTH_STANDARD_DP)
    }

    /** Scrollable long-message confirmation for content that cannot safely fit the compact shell. */
    @JvmStatic
    fun showLongMessageConfirm(context: Context, title: String?, message: String?,
                               confirmText: String?, onConfirm: Runnable?): AlertDialog {
        return showLongMessageConfirm(context, title, message, confirmText, onConfirm, null)
    }

    @JvmStatic
    fun showLongMessageConfirm(context: Context, title: String?, message: String?,
                               confirmText: String?, onConfirm: Runnable?,
                               onCancel: Runnable?): AlertDialog {
        val dialog = open(context, WIDTH_FORM_DP)
        var resolved = false
        val root = root(context, false)
        root.addView(standardTitle(context, title))

        val scroll = ScrollView(context)
        val messageView = standardMessage(context, message)
        scroll.addView(messageView)
        val scrollParams = topMargin(context, 13)
        scrollParams.height = LauncherTheme.dp(context, 220)
        root.addView(scroll, scrollParams)

        val confirm = button(context, confirmText, true)
        confirm.setOnClickListener {
            resolved = true
            dialog.dismiss()
            onConfirm?.run()
        }
        root.addView(confirm, fixedHeightTopMargin(context, 11, 36))

        val cancel = cancelButton(context)
        cancel.setOnClickListener {
            resolved = true
            dialog.dismiss()
            onCancel?.run()
        }
        dialog.setOnCancelListener {
            if (!resolved && onCancel != null) onCancel.run()
            resolved = true
        }
        root.addView(cancel, fixedHeightTopMargin(context, 9, 36))
        setContent(dialog, root, WIDTH_FORM_DP)
        return dialog
    }

    /** Standard-width destructive confirmation with a horizontal action row. */
    @JvmStatic
    fun showDangerConfirm(context: Context, title: String?, message: String?,
                          dangerText: String?, onConfirm: Runnable?) {
        val dialog = open(context, WIDTH_STANDARD_DP)
        val root = root(context, false)
        root.addView(standardTitle(context, title))
        root.addView(standardMessage(context, message), topMargin(context, 13))

        val actions = LinearLayout(context)
        actions.orientation = LinearLayout.HORIZONTAL
        val cancel = button(context, context.getString(R.string.launcher_dialog_cancel), false)
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
        root.addView(actions, fixedHeightTopMargin(context, 13, 38))
        setContent(dialog, root, WIDTH_STANDARD_DP)
    }

    /** Non-cancelable indeterminate loading shell. The caller owns its lifecycle. */
    @JvmStatic
    fun showLoading(context: Context, title: String?, hint: String?): AlertDialog {
        val dialog = open(context, WIDTH_STANDARD_DP, false)
        val root = root(context, false)
        root.addView(standardTitle(context, title))

        val progress = ProgressBar(context)
        progress.isIndeterminate = true
        progress.indeterminateDrawable?.setColorFilter(
            LauncherTheme.primary(context), PorterDuff.Mode.SRC_IN)
        val progressParams = LinearLayout.LayoutParams(LauncherTheme.dp(context, 32), LauncherTheme.dp(context, 32))
        progressParams.gravity = Gravity.CENTER_HORIZONTAL
        progressParams.setMargins(0, LauncherTheme.dp(context, 14), 0, 0)
        root.addView(progress, progressParams)

        val hintView = standardMessage(context, hint)
        hintView.textSize = 11f
        root.addView(hintView, topMargin(context, 10))
        setContent(dialog, root, WIDTH_STANDARD_DP)
        return dialog
    }

    /**
     * Non-cancelable loading shell with an additional progress TextView tagged as [progressTag].
     * Sync flows update that tagged view while keeping all dialog construction inside the factory.
     */
    @JvmStatic
    fun showProgressLoading(
        context: Context,
        title: String?,
        progressText: String?,
        hint: String?,
        progressTag: String?
    ): AlertDialog {
        val dialog = open(context, WIDTH_STANDARD_DP, false)
        val root = root(context, false)
        root.addView(standardTitle(context, title))

        val progress = ProgressBar(context)
        progress.isIndeterminate = true
        progress.indeterminateDrawable?.setColorFilter(
            LauncherTheme.primary(context), PorterDuff.Mode.SRC_IN)
        val progressParams = LinearLayout.LayoutParams(LauncherTheme.dp(context, 32), LauncherTheme.dp(context, 32))
        progressParams.gravity = Gravity.CENTER_HORIZONTAL
        progressParams.setMargins(0, LauncherTheme.dp(context, 14), 0, 0)
        root.addView(progress, progressParams)

        val progressView = standardMessage(context, progressText)
        progressView.tag = progressTag
        root.addView(progressView, topMargin(context, 6))

        val hintView = standardMessage(context, hint)
        hintView.textSize = 11f
        root.addView(hintView, topMargin(context, 10))
        setContent(dialog, root, WIDTH_STANDARD_DP)
        return dialog
    }

    @JvmStatic
    fun showActionChoices(context: Context, title: String?, choices: Array<CharSequence>?,
                          listener: ChoiceListener?) {
        showActionChoices(context, title, choices, -1, listener)
    }

    @JvmStatic
    fun showActionChoices(context: Context, title: String?, choices: Array<CharSequence>?,
                          dangerIndex: Int, listener: ChoiceListener?) {
        val dialog = open(context, WIDTH_ACTION_MENU_DP)
        val root = root(context, true)
        root.addView(title(context, title))
        val scroll = ScrollView(context)
        val list = LinearLayout(context)
        list.orientation = LinearLayout.VERTICAL
        if (choices != null) {
            for (i in choices.indices) {
                val index = i
                val option = if (index == dangerIndex) {
                    TextView(context).apply {
                        text = choices[i]
                        textSize = 13f
                        setTypeface(null, Typeface.BOLD)
                        LauncherTheme.dangerMenuItem(this)
                    }
                } else {
                    button(context, choices[i], false)
                }
                option.gravity = Gravity.CENTER_VERTICAL
                option.setPadding(LauncherTheme.dp(context, 13), 0, LauncherTheme.dp(context, 13), 0)
                option.maxLines = 2
                option.setOnClickListener {
                    dialog.dismiss()
                    listener?.onChoice(index)
                }
                val optionParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LauncherTheme.dp(context, 42))
                if (i > 0) optionParams.setMargins(0, LauncherTheme.dp(context, 7), 0, 0)
                list.addView(option, optionParams)
            }
        }
        scroll.addView(list)
        val optionCount = choices?.size ?: 0
        val listHeight = optionCount * 42 + Math.max(0, optionCount - 1) * 7
        val scrollParams = topMargin(context, 12)
        scrollParams.height = Math.min(LauncherTheme.dp(context, 252), LauncherTheme.dp(context, listHeight))
        root.addView(scroll, scrollParams)
        val cancel = button(context, context.getString(R.string.launcher_dialog_cancel), false)
        cancel.setOnClickListener { dialog.dismiss() }
        root.addView(cancel, fixedHeightTopMargin(context, 12, 36))
        setContent(dialog, root, WIDTH_ACTION_MENU_DP)
    }

    /** Standard-width compact action menu for a small number of short operations. */
    @JvmStatic
    fun showStandardActionChoices(context: Context, title: String?, choices: Array<CharSequence>?,
                                  listener: ChoiceListener?) {
        showStandardActionChoices(context, title, choices, -1, listener)
    }

    @JvmStatic
    fun showStandardActionChoices(context: Context, title: String?, choices: Array<CharSequence>?,
                                  dangerIndex: Int, listener: ChoiceListener?) {
        val dialog = open(context, WIDTH_STANDARD_DP)
        val root = root(context, false)
        root.addView(standardTitle(context, title))
        if (choices != null) {
            for (i in choices.indices) {
                val index = i
                val option = TextView(context)
                option.text = choices[i]
                option.gravity = Gravity.CENTER
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
                root.addView(option, fixedHeightTopMargin(context, 11, 36))
            }
        }
        val cancel = cancelButton(context)
        cancel.setOnClickListener { dialog.dismiss() }
        root.addView(cancel, fixedHeightTopMargin(context, 9, 36))
        setContent(dialog, root, WIDTH_STANDARD_DP)
    }

    /** Standard action choices with a short explanatory message above the actions. */
    @JvmStatic
    fun showMessageActionChoices(
        context: Context,
        title: String?,
        message: String?,
        choices: Array<CharSequence>?,
        listener: ChoiceListener?
    ) {
        showMessageActionChoices(context, title, message, choices, null, listener)
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
        val dialog = open(context, WIDTH_STANDARD_DP)
        val root = root(context, false)
        root.addView(standardTitle(context, title))
        root.addView(standardMessage(context, message), topMargin(context, 13))
        if (choices != null) {
            for (i in choices.indices) {
                val index = i
                val option = TextView(context)
                option.text = choices[i]
                option.gravity = Gravity.CENTER
                option.isSingleLine = true
                option.textSize = 13f
                option.setTypeface(null, Typeface.BOLD)
                LauncherTheme.menuItem(option)
                option.setOnClickListener {
                    dialog.dismiss()
                    listener?.onChoice(index)
                }
                root.addView(option, fixedHeightTopMargin(context, 11, 36))
            }
        }
        val cancel = cancelButton(context)
        if (cancelText != null) {
            cancel.text = cancelText
        }
        cancel.setOnClickListener { dialog.dismiss() }
        root.addView(cancel, fixedHeightTopMargin(context, 9, 36))
        setContent(dialog, root, WIDTH_STANDARD_DP)
    }

    @JvmStatic
    fun showUpdateResult(
        context: Context,
        info: LauncherUpdateBridge.UpdateInfo?,
        currentVersion: String?,
        hasUpdate: Boolean,
        error: String?
    ) {
        val title = context.getString(
            if (hasUpdate) R.string.theme_update_available else R.string.theme_check_for_updates
        )
        when {
            error != null -> showInfo(context, title, error)
            hasUpdate && info != null -> showUpdateAvailable(context, title, info, currentVersion)
            else -> showInfo(
                context,
                title,
                context.getString(
                    R.string.theme_already_latest,
                    LauncherUpdateFormatter.emptyOr(
                        currentVersion,
                        context.getString(R.string.settings_unknown)
                    )
                )
            )
        }
    }

    /** Compact single-choice picker matching the add-game launch-target selector. */
    @JvmStatic
    fun showSingleChoice(context: Context, title: String?, choices: Array<CharSequence>?,
                         checkedIndex: Int, listener: ChoiceListener?) {
        val dialog = open(context, WIDTH_COMPACT_DP)
        val root = root(context, false)
        root.addView(standardTitle(context, title))

        val scroll = ScrollView(context)
        val list = LinearLayout(context)
        list.orientation = LinearLayout.VERTICAL
        val optionCount = choices?.size ?: 0
        for (i in 0 until optionCount) {
            val index = i
            val option = compactChoice(context, choices!![i], index == checkedIndex)
            option.gravity = Gravity.CENTER
            val optionParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LauncherTheme.dp(context, 38))
            optionParams.setMargins(0, LauncherTheme.dp(context, 7), 0, 0)
            option.setOnClickListener {
                dialog.dismiss()
                listener?.onChoice(index)
            }
            list.addView(option, optionParams)
        }
        scroll.addView(list)
        val listHeight = optionCount * (38 + 7)
        val scrollParams = topMargin(context, 7)
        scrollParams.height = Math.min(LauncherTheme.dp(context, 280), LauncherTheme.dp(context, listHeight))
        root.addView(scroll, scrollParams)

        val cancel = cancelButton(context)
        cancel.setOnClickListener { dialog.dismiss() }
        root.addView(cancel, fixedHeightTopMargin(context, 9, 36))
        setContent(dialog, root, WIDTH_COMPACT_DP)
    }

    @JvmStatic
    fun showScanDepthChoices(
        context: Context,
        title: String?,
        quickModeText: String?,
        fullModeText: String?,
        labels: Array<CharSequence>?,
        depthValues: IntArray?,
        currentDepth: Int,
        listener: ScanDepthListener?
    ) {
        val dialog = open(context, WIDTH_COMPACT_DP)
        val root = root(context, false)
        root.addView(standardTitle(context, title))
        val fullRefresh = booleanArrayOf(false)
        val scanMode = button(context, quickModeText, false)
        scanMode.setOnClickListener {
            fullRefresh[0] = !fullRefresh[0]
            scanMode.text = if (fullRefresh[0]) fullModeText else quickModeText
        }
        root.addView(scanMode, fixedHeightTopMargin(context, 11, 36))
        val count = Math.min(labels?.size ?: 0, depthValues?.size ?: 0)
        for (i in 0 until count) {
            val depth = depthValues!![i]
            val selected = depth == currentDepth
            val option = compactChoice(context, labels!![i], selected)
            option.setOnClickListener {
                dialog.dismiss()
                listener?.onChoice(depth, fullRefresh[0])
            }
            root.addView(option, fixedHeightTopMargin(context, 11, 36))
        }
        val cancel = cancelButton(context)
        cancel.setOnClickListener { dialog.dismiss() }
        root.addView(cancel, fixedHeightTopMargin(context, 9, 36))
        setContent(dialog, root, WIDTH_COMPACT_DP)
    }

    @JvmStatic
    fun showTextChoicesWithSkip(
        context: Context,
        title: String?,
        message: String?,
        choices: List<String>?,
        skipText: String?,
        cancelText: String?,
        listener: TextChoiceListener?,
        onSkip: Runnable?,
        onCancel: Runnable?
    ) {
        val dialog = open(context, WIDTH_COMPACT_DP, false)
        val root = root(context, false)
        root.addView(standardTitle(context, title))
        root.addView(standardMessage(context, message), topMargin(context, 10))
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

    /** Android 11+ 全文件访问权限引导对话框，GO 按钮由调用方处理跳转。 */
    @JvmStatic
    fun showStoragePermissionRequest(
        context: Context,
        onGo: Runnable,
        onCancel: Runnable,
    ) {
        val dialog = open(context, WIDTH_FORM_DP)
        val root = root(context, false)
        root.addView(standardTitle(context, context.getString(R.string.core_file_access_title)))
        root.addView(standardMessage(context, context.getString(R.string.core_file_access_message)), topMargin(context, 13))

        val goBtn = button(context, context.getString(R.string.core_go), true)
        goBtn.setOnClickListener {
            dialog.dismiss()
            onGo.run()
        }
        root.addView(goBtn, fixedHeightTopMargin(context, 9, 38))

        val cancelBtn = cancelButton(context)
        cancelBtn.setOnClickListener {
            dialog.dismiss()
            onCancel.run()
        }
        root.addView(cancelBtn, fixedHeightTopMargin(context, 9, 38))

        setContent(dialog, root, WIDTH_FORM_DP)
    }

    private fun open(context: Context, widthDp: Int): AlertDialog {
        return open(context, widthDp, true)
    }

    private fun open(context: Context, widthDp: Int, cancelable: Boolean): AlertDialog {
        return open(context, widthDp, cancelable, null)
    }

    private fun open(context: Context, widthDp: Int, cancelable: Boolean,
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

    private fun setContent(dialog: AlertDialog, content: View, widthDp: Int) {
        val window: Window? = dialog.window
        if (window == null) return
        content.background = LauncherTheme.secondaryButton(content.context, 20f)
        LauncherTheme.applyPrimaryTone(content)
        LauncherTabletPortraitScaler.apply(content)
        window.setContentView(content)
        window.setLayout(dialogWidthPx(content.context, widthDp), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun root(context: Context, scrollable: Boolean): LinearLayout {
        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(
            LauncherTheme.dp(context, 22), LauncherTheme.dp(context, if (scrollable) 18 else 20),
            LauncherTheme.dp(context, 22), LauncherTheme.dp(context, if (scrollable) 15 else 16))
        root.background = LauncherTheme.secondaryButton(context, 20f)
        return root
    }

    private fun title(context: Context, value: String?): TextView {
        val view = TextView(context)
        view.text = value
        view.setTextColor(LauncherTheme.text(context))
        view.textSize = 16f
        view.setTypeface(null, Typeface.BOLD)
        return view
    }

    private fun standardTitle(context: Context, value: String?): TextView {
        val view = title(context, value)
        view.gravity = Gravity.CENTER
        return view
    }

    private fun message(context: Context, value: String?): TextView {
        val view = TextView(context)
        view.text = value
        view.setTextColor(LauncherTheme.textMuted(context))
        view.textSize = 13f
        return view
    }

    private fun standardMessage(context: Context, value: String?): TextView {
        val view = message(context, value)
        view.gravity = Gravity.CENTER
        view.textSize = 12f
        view.setLineSpacing(LauncherTheme.dp(context, 2).toFloat(), 1.05f)
        return view
    }

    private fun button(context: Context, value: CharSequence?, primary: Boolean): TextView {
        val view = TextView(context)
        view.text = value
        view.gravity = Gravity.CENTER
        view.textSize = 13f
        view.setTypeface(null, Typeface.BOLD)
        if (primary) LauncherTheme.primaryButton(view) else LauncherTheme.secondaryButton(view)
        return view
    }

    private fun compactChoice(context: Context, value: CharSequence?, selected: Boolean): TextView {
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

    private fun cancelButton(context: Context): TextView {
        val view = TextView(context)
        view.text = context.getString(R.string.launcher_dialog_cancel)
        view.gravity = Gravity.CENTER
        view.setTextColor(LauncherTheme.primary(context))
        view.textSize = 13f
        view.setTypeface(null, Typeface.BOLD)
        view.background = LauncherTheme.cancelChip(context)
        return view
    }

    private fun topMargin(context: Context, marginDp: Int): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        params.setMargins(0, LauncherTheme.dp(context, marginDp), 0, 0)
        return params
    }

    private fun fixedHeightTopMargin(context: Context, marginDp: Int, heightDp: Int): LinearLayout.LayoutParams {
        val params = topMargin(context, marginDp)
        params.height = LauncherTheme.dp(context, heightDp)
        return params
    }

    /**
     * 弹窗宽度兜底（px）：期望宽度不超过屏幕宽度减去两侧 16dp 边距，平板竖屏走 [LauncherTabletPortraitScaler.dp] 缩放。
     *
     * 提升为公开 @JvmStatic 以消除各处私有副本（如 AgentLlmConfigDialog），统一弹窗宽度算法单一来源。
     */
    @JvmStatic
    fun dialogWidthPx(context: Context, widthDp: Int): Int {
        val desiredWidth = LauncherTabletPortraitScaler.dp(context, widthDp)
        val horizontalMargin = LauncherTheme.dp(context, 16) * 2
        val maxWidth = Math.max(0, context.resources.displayMetrics.widthPixels - horizontalMargin)
        return Math.min(desiredWidth, maxWidth)
    }

    private fun showUpdateAvailable(
        context: Context,
        title: String,
        info: LauncherUpdateBridge.UpdateInfo,
        currentVersion: String?
    ) {
        // 消息拼接与 URL 解析统一复用 LauncherUpdateFormatter，避免与 PadDialogFactory 重复实现
        val message = LauncherUpdateFormatter.buildUpdateMessage(context, info, currentVersion)
        showMessageActionChoices(
            context,
            title,
            message,
            arrayOf(
                context.getString(R.string.theme_go_to_download),
                context.getString(R.string.theme_release_page),
            ),
            context.getString(R.string.theme_later)
        ) { index ->
            LauncherUrlOpener.open(context, LauncherUpdateFormatter.resolveUpdateUrl(info, index))
        }
    }
}
