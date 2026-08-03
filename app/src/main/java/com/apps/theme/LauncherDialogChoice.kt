package com.apps.theme

import android.content.Context
import android.text.TextUtils
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.core.R

/** Choice-style dialog builders (action menus / pickers) for [LauncherDialogFactory]. */
internal object LauncherDialogChoice {

    internal fun showActionChoices(context: Context, title: String?, choices: Array<CharSequence>?,
                                   dangerIndex: Int, listener: LauncherDialogFactory.ChoiceListener?) {
        val dialog = LauncherDialogParts.open(context, LauncherDialogFactory.WIDTH_ACTION_MENU_DP)
        val root = LauncherDialogParts.root(context, true)
        root.addView(LauncherDialogParts.title(context, title))
        val scroll = ScrollView(context)
        val list = LinearLayout(context)
        list.orientation = LinearLayout.VERTICAL
        if (choices != null) {
            for (i in choices.indices) {
                val option = if (i == dangerIndex) {
                    TextView(context).apply {
                        text = choices[i]
                        textSize = 13f
                        setTypeface(null, Typeface.BOLD)
                        LauncherTheme.dangerMenuItem(this)
                    }
                } else {
                    LauncherDialogParts.button(context, choices[i], false)
                }
                option.gravity = Gravity.CENTER_VERTICAL
                option.setPadding(LauncherTheme.dp(context, 13), 0, LauncherTheme.dp(context, 13), 0)
                option.maxLines = 2
                option.setOnClickListener {
                    dialog.dismiss()
                    listener?.onChoice(i)
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
        val scrollParams = LauncherDialogParts.topMargin(context, 12)
        scrollParams.height = Math.min(LauncherTheme.dp(context, 252), LauncherTheme.dp(context, listHeight))
        root.addView(scroll, scrollParams)
        val cancel = LauncherDialogParts.button(context, context.getString(R.string.launcher_dialog_cancel), false)
        cancel.setOnClickListener { dialog.dismiss() }
        root.addView(cancel, LauncherDialogParts.fixedHeightTopMargin(context, 12, 36))
        LauncherDialogParts.setContent(dialog, root, LauncherDialogFactory.WIDTH_ACTION_MENU_DP)
    }

    /** Standard-width compact action menu for a small number of short operations. */
    internal fun showStandardActionChoices(context: Context, title: String?, choices: Array<CharSequence>?,
                                           dangerIndex: Int, listener: LauncherDialogFactory.ChoiceListener?) {
        val dialog = LauncherDialogParts.open(context, LauncherDialogFactory.WIDTH_STANDARD_DP)
        val root = LauncherDialogParts.root(context, false)
        root.addView(LauncherDialogParts.standardTitle(context, title))
        if (choices != null) {
            for (i in choices.indices) {
                val option = TextView(context)
                option.text = choices[i]
                option.gravity = Gravity.CENTER
                option.isSingleLine = true
                option.textSize = 13f
                option.setTypeface(null, Typeface.BOLD)
                if (i == dangerIndex) {
                    LauncherTheme.dangerMenuItem(option)
                } else {
                    LauncherTheme.menuItem(option)
                }
                option.setOnClickListener {
                    dialog.dismiss()
                    listener?.onChoice(i)
                }
                root.addView(option, LauncherDialogParts.fixedHeightTopMargin(context, 11, 36))
            }
        }
        val cancel = LauncherDialogParts.cancelButton(context)
        cancel.setOnClickListener { dialog.dismiss() }
        root.addView(cancel, LauncherDialogParts.fixedHeightTopMargin(context, 9, 36))
        LauncherDialogParts.setContent(dialog, root, LauncherDialogFactory.WIDTH_STANDARD_DP)
    }

    /** Standard action choices with a short explanatory message above the actions. */
    internal fun showMessageActionChoices(
        context: Context,
        title: String?,
        message: String?,
        choices: Array<CharSequence>?,
        cancelText: CharSequence?,
        listener: LauncherDialogFactory.ChoiceListener?
    ) {
        val dialog = LauncherDialogParts.open(context, LauncherDialogFactory.WIDTH_STANDARD_DP)
        val root = LauncherDialogParts.root(context, false)
        root.addView(LauncherDialogParts.standardTitle(context, title))
        root.addView(LauncherDialogParts.standardMessage(context, message), LauncherDialogParts.topMargin(context, 13))
        if (choices != null) {
            for (i in choices.indices) {
                val option = TextView(context)
                option.text = choices[i]
                option.gravity = Gravity.CENTER
                option.isSingleLine = true
                option.textSize = 13f
                option.setTypeface(null, Typeface.BOLD)
                LauncherTheme.menuItem(option)
                option.setOnClickListener {
                    dialog.dismiss()
                    listener?.onChoice(i)
                }
                root.addView(option, LauncherDialogParts.fixedHeightTopMargin(context, 11, 36))
            }
        }
        val cancel = LauncherDialogParts.cancelButton(context)
        if (cancelText != null) {
            cancel.text = cancelText
        }
        cancel.setOnClickListener { dialog.dismiss() }
        root.addView(cancel, LauncherDialogParts.fixedHeightTopMargin(context, 9, 36))
        LauncherDialogParts.setContent(dialog, root, LauncherDialogFactory.WIDTH_STANDARD_DP)
    }

    /** Compact single-choice picker matching the add-game launch-target selector. */
    internal fun showSingleChoice(context: Context, title: String?, choices: Array<CharSequence>?,
                                  checkedIndex: Int, listener: LauncherDialogFactory.ChoiceListener?) {
        val dialog = LauncherDialogParts.open(context, LauncherDialogFactory.WIDTH_COMPACT_DP)
        val root = LauncherDialogParts.root(context, false)
        root.addView(LauncherDialogParts.standardTitle(context, title))

        val scroll = ScrollView(context)
        val list = LinearLayout(context)
        list.orientation = LinearLayout.VERTICAL
        val optionCount = choices?.size ?: 0
        if (choices != null) {
            for (i in choices.indices) {
                val option = LauncherDialogParts.compactChoice(context, choices[i], i == checkedIndex)
                option.gravity = Gravity.CENTER
                val optionParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LauncherTheme.dp(context, 38))
                optionParams.setMargins(0, LauncherTheme.dp(context, 7), 0, 0)
                option.setOnClickListener {
                    dialog.dismiss()
                    listener?.onChoice(i)
                }
                list.addView(option, optionParams)
            }
        }
        scroll.addView(list)
        val listHeight = optionCount * (38 + 7)
        val scrollParams = LauncherDialogParts.topMargin(context, 7)
        scrollParams.height = Math.min(LauncherTheme.dp(context, 280), LauncherTheme.dp(context, listHeight))
        root.addView(scroll, scrollParams)

        val cancel = LauncherDialogParts.cancelButton(context)
        cancel.setOnClickListener { dialog.dismiss() }
        root.addView(cancel, LauncherDialogParts.fixedHeightTopMargin(context, 9, 36))
        LauncherDialogParts.setContent(dialog, root, LauncherDialogFactory.WIDTH_COMPACT_DP)
    }

    internal fun showScanDepthChoices(
        context: Context,
        title: String?,
        quickModeText: String?,
        fullModeText: String?,
        labels: Array<CharSequence>?,
        depthValues: IntArray?,
        currentDepth: Int,
        listener: LauncherDialogFactory.ScanDepthListener?
    ) {
        val dialog = LauncherDialogParts.open(context, LauncherDialogFactory.WIDTH_COMPACT_DP)
        val root = LauncherDialogParts.root(context, false)
        root.addView(LauncherDialogParts.standardTitle(context, title))
        val fullRefresh = booleanArrayOf(false)
        val scanMode = LauncherDialogParts.button(context, quickModeText, false)
        scanMode.setOnClickListener {
            fullRefresh[0] = !fullRefresh[0]
            scanMode.text = if (fullRefresh[0]) fullModeText else quickModeText
        }
        root.addView(scanMode, LauncherDialogParts.fixedHeightTopMargin(context, 11, 36))
        if (labels != null && depthValues != null) {
            val count = Math.min(labels.size, depthValues.size)
            for (i in 0 until count) {
                val depth = depthValues[i]
                val selected = depth == currentDepth
                val option = LauncherDialogParts.compactChoice(context, labels[i], selected)
                option.setOnClickListener {
                    dialog.dismiss()
                    listener?.onChoice(depth, fullRefresh[0])
                }
                root.addView(option, LauncherDialogParts.fixedHeightTopMargin(context, 11, 36))
            }
        }
        val cancel = LauncherDialogParts.cancelButton(context)
        cancel.setOnClickListener { dialog.dismiss() }
        root.addView(cancel, LauncherDialogParts.fixedHeightTopMargin(context, 9, 36))
        LauncherDialogParts.setContent(dialog, root, LauncherDialogFactory.WIDTH_COMPACT_DP)
    }

    internal fun showTextChoicesWithSkip(
        context: Context,
        title: String?,
        message: String?,
        choices: List<String>?,
        skipText: String?,
        cancelText: String?,
        listener: LauncherDialogFactory.TextChoiceListener?,
        onSkip: Runnable?,
        onCancel: Runnable?
    ) {
        val dialog = LauncherDialogParts.open(context, LauncherDialogFactory.WIDTH_COMPACT_DP, false)
        val root = LauncherDialogParts.root(context, false)
        root.addView(LauncherDialogParts.standardTitle(context, title))
        root.addView(LauncherDialogParts.standardMessage(context, message), LauncherDialogParts.topMargin(context, 10))
        val scroll = ScrollView(context)
        val list = LinearLayout(context)
        list.orientation = LinearLayout.VERTICAL
        choices?.forEach { candidate ->
            val option = LauncherDialogParts.compactChoice(context, candidate, false)
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
        val scrollParams = LauncherDialogParts.topMargin(context, 4)
        val count = choices?.size ?: 0
        scrollParams.height = Math.min(LauncherTheme.dp(context, 250), LauncherTheme.dp(context, 8 + count * 46))
        root.addView(scroll, scrollParams)
        val buttons = LinearLayout(context)
        buttons.orientation = LinearLayout.HORIZONTAL
        val skip = LauncherDialogParts.button(context, skipText, false)
        skip.setOnClickListener {
            dialog.dismiss()
            onSkip?.run()
        }
        buttons.addView(skip, LinearLayout.LayoutParams(0, LauncherTheme.dp(context, 36), 1f))
        val cancel = LauncherDialogParts.button(context, cancelText, false)
        cancel.setOnClickListener {
            dialog.dismiss()
            onCancel?.run()
        }
        val cancelParams = LinearLayout.LayoutParams(0, LauncherTheme.dp(context, 36), 1f)
        cancelParams.setMargins(LauncherTheme.dp(context, 8), 0, 0, 0)
        buttons.addView(cancel, cancelParams)
        root.addView(buttons, LauncherDialogParts.fixedHeightTopMargin(context, 12, 36))
        LauncherDialogParts.setContent(dialog, root, LauncherDialogFactory.WIDTH_COMPACT_DP)
    }
}
