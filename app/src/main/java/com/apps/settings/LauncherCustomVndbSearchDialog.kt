package com.apps.settings

import android.app.Dialog
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.apps.theme.LauncherDialogFactory
import com.apps.theme.LauncherMotion
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherEditText
import com.core.R
import com.core.launcherbridge.LauncherMetadataBridge
import com.core.metadata.MetadataController
import com.core.metadata.VnMetadata
import com.core.model.Game

/** Launcher 风格的 VNDB 自定义关键词搜索与候选选择流程。 */
internal object LauncherCustomVndbSearchDialog {

    @JvmStatic
    fun show(fragment: Fragment, game: Game?, onSaved: Runnable?) {
        if (game == null || !fragment.isAdded()) return
        var selectedSource = MetadataController.SOURCE_VNDB
        val dialog = createDialog(fragment)
        val root = createRoot(fragment)
        val titleView = title(fragment, sourceSearchTitle(fragment, selectedSource))
        root.addView(titleView)

        // 数据源选择器
        val sourceRow = LinearLayout(fragment.requireContext())
        sourceRow.orientation = LinearLayout.HORIZONTAL
        sourceRow.weightSum = 3f
        val sources = arrayOf(
            MetadataController.SOURCE_VNDB,
            MetadataController.SOURCE_BANGUMI,
            MetadataController.SOURCE_BANGUMI_MIRROR
        )
        val sourceLabels = arrayOf(
            "VNDB",
            "Bangumi",
            fragment.getString(R.string.settings_bangumi_mirror)
        )
        val sourceChips = arrayOfNulls<TextView>(3)
        for (i in 0 until 3) {
            val idx = i
            val chip = TextView(fragment.requireContext())
            chip.text = sourceLabels[i]
            chip.gravity = Gravity.CENTER
            chip.setTextSize(12f)
            chip.setTypeface(null, Typeface.BOLD)
            chip.setPadding(
                LauncherTheme.dp(fragment.requireContext(), 10),
                LauncherTheme.dp(fragment.requireContext(), 7),
                LauncherTheme.dp(fragment.requireContext(), 10),
                LauncherTheme.dp(fragment.requireContext(), 7)
            )
            LauncherTheme.chip(chip, sources[i] == selectedSource)
            chip.setOnClickListener {
                selectedSource = sources[idx]
                titleView.text = sourceSearchTitle(fragment, selectedSource)
                for (j in 0 until 3) LauncherTheme.chip(sourceChips[j], j == idx)
            }
            val chipParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (i < 2) chipParams.setMarginEnd(LauncherTheme.dp(fragment.requireContext(), 6))
            sourceRow.addView(chip, chipParams)
            sourceChips[i] = chip
        }
        val sourceRowParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        sourceRowParams.setMargins(0, LauncherTheme.dp(fragment.requireContext(), 13), 0, 0)
        root.addView(sourceRow, sourceRowParams)

        val info = info(fragment, fragment.getString(R.string.settings_custom_search_summary))
        val infoParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        infoParams.setMargins(0, LauncherTheme.dp(fragment.requireContext(), 13), 0, 0)
        root.addView(info, infoParams)

        val label = label(fragment, fragment.getString(R.string.settings_search_keywords))
        val labelParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        labelParams.setMargins(0, LauncherTheme.dp(fragment.requireContext(), 13), 0, 0)
        root.addView(label, labelParams)

        val input = LauncherEditText(fragment.requireContext())
        input.setSingleLine(true)
        input.setText(safe(game.title))
        input.setSelectAllOnFocus(true)
        input.setHint(R.string.settings_search_keywords_hint)
        input.setTextColor(LauncherTheme.text(fragment.requireContext()))
        input.setHintTextColor(LauncherTheme.inputHint(fragment.requireContext()))
        input.setTextSize(13f)
        input.background = LauncherTheme.cancelChip(fragment.requireContext())
        LauncherTheme.styleTextInput(input)
        input.setPadding(
            LauncherTheme.dp(fragment.requireContext(), 13),
            LauncherTheme.dp(fragment.requireContext(), 9),
            LauncherTheme.dp(fragment.requireContext(), 13),
            LauncherTheme.dp(fragment.requireContext(), 9)
        )
        val inputParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        inputParams.setMargins(0, LauncherTheme.dp(fragment.requireContext(), 5), 0, 0)
        root.addView(input, inputParams)

        val hint = hint(fragment, fragment.getString(R.string.settings_search_keywords_description))
        val hintParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        hintParams.setMargins(0, LauncherTheme.dp(fragment.requireContext(), 7), 0, 0)
        root.addView(hint, hintParams)

        val btnRow = LinearLayout(fragment.requireContext())
        btnRow.orientation = LinearLayout.HORIZONTAL
        btnRow.weightSum = 2f
        val btnRowParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        btnRowParams.setMargins(0, LauncherTheme.dp(fragment.requireContext(), 13), 0, 0)
        btnRow.layoutParams = btnRowParams

        val cancel = button(fragment, fragment.getString(R.string.settings_cancel), false)
        val cancelParams = LinearLayout.LayoutParams(0, LauncherTheme.dp(fragment.requireContext(), 38), 1f)
        cancelParams.setMargins(0, 0, LauncherTheme.dp(fragment.requireContext(), 5), 0)
        cancel.layoutParams = cancelParams
        cancel.setOnClickListener { dialog.dismiss() }
        btnRow.addView(cancel)

        val search = button(fragment, fragment.getString(R.string.settings_search), true)
        val searchParams = LinearLayout.LayoutParams(0, LauncherTheme.dp(fragment.requireContext(), 38), 1f)
        searchParams.setMargins(LauncherTheme.dp(fragment.requireContext(), 5), 0, 0, 0)
        search.layoutParams = searchParams
        search.setOnClickListener {
            val keyword = input.text?.toString()?.trim() ?: ""
            if (keyword.isEmpty()) {
                Toast.makeText(fragment.requireContext(),
                    R.string.settings_enter_search_keywords, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            search.isEnabled = false
            search.setText(R.string.settings_searching)
            val src = selectedSource
            val cb = object : LauncherMetadataBridge.CandidatesCallback {
                override fun onResult(candidates: List<VnMetadata>, errorMessage: String?) {
                    if (!fragment.isAdded()) return
                    dialog.dismiss()
                    if (errorMessage != null) {
                        Toast.makeText(fragment.requireContext(),
                            fragment.getString(R.string.settings_source_search_failed,
                                sourceLabel(fragment, src), errorMessage), Toast.LENGTH_LONG).show()
                        return
                    }
                    if (candidates.isEmpty()) {
                        Toast.makeText(fragment.requireContext(),
                            fragment.getString(R.string.settings_no_source_results,
                                sourceLabel(fragment, src)), Toast.LENGTH_SHORT).show()
                        return
                    }
                    showCandidates(fragment, game, candidates, onSaved, src)
                }
            }
            if (MetadataController.SOURCE_VNDB == src) {
                LauncherMetadataBridge.searchVndbCandidatesAsync(fragment.requireContext(), keyword, 8, cb)
            } else {
                LauncherMetadataBridge.searchBangumiCandidatesAsync(fragment.requireContext(), keyword, 8, cb)
            }
        }
        btnRow.addView(search)
        root.addView(btnRow)
        setContent(dialog, root, fragment, 288)
        focusAndShowKeyboard(dialog, input, fragment)
    }

    private fun sourceLabel(fragment: Fragment, source: String): String {
        if (MetadataController.SOURCE_BANGUMI == source) return "Bangumi"
        if (MetadataController.SOURCE_BANGUMI_MIRROR == source) {
            return fragment.getString(R.string.settings_bangumi_mirror)
        }
        return "VNDB"
    }

    private fun sourceSearchTitle(fragment: Fragment, source: String): String {
        return fragment.getString(R.string.settings_custom_search_title,
            sourceLabel(fragment, source))
    }

    private fun showCandidates(
        fragment: Fragment, game: Game, candidates: List<VnMetadata>,
        onSaved: Runnable?, source: String
    ) {
        val label = sourceLabel(fragment, source)
        val dialog = createDialog(fragment)
        val root = createRoot(fragment)
        root.addView(title(fragment,
            fragment.getString(R.string.settings_choose_source_result, label)))

        val info = info(fragment,
            fragment.getString(R.string.settings_choose_source_result_summary, label))
        val infoParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        infoParams.setMargins(0, LauncherTheme.dp(fragment.requireContext(), 13), 0, 0)
        root.addView(info, infoParams)

        val list = LinearLayout(fragment.requireContext())
        list.orientation = LinearLayout.VERTICAL
        for (metadata in candidates) {
            val row = TextView(fragment.requireContext())
            val displayTitle = first(metadata.chineseTitle, metadata.romanTitle,
                fragment.getString(R.string.settings_unnamed))
            val original = first(metadata.originalTitle, metadata.id, "")
            val developer = first(metadata.developer,
                fragment.getString(R.string.settings_source_candidate, label))
            row.text = "$displayTitle\n$original\n$developer"
            row.setTextColor(LauncherTheme.text(fragment.requireContext()))
            row.setTextSize(12f)
            row.setLineSpacing(LauncherTheme.dp(fragment.requireContext(), 4).toFloat(), 1f)
            row.setPadding(
                LauncherTheme.dp(fragment.requireContext(), 12),
                LauncherTheme.dp(fragment.requireContext(), 9),
                LauncherTheme.dp(fragment.requireContext(), 12),
                LauncherTheme.dp(fragment.requireContext(), 9)
            )
            row.background = LauncherTheme.cancelChip(fragment.requireContext())
            row.setOnClickListener {
                row.isEnabled = false
                val saveCb = object : LauncherMetadataBridge.Callback {
                    override fun onResult(success: Boolean) {
                        if (!fragment.isAdded()) return
                        dialog.dismiss()
                        Toast.makeText(fragment.requireContext(),
                            fragment.getString(if (success) R.string.settings_metadata_bound
                                else R.string.settings_metadata_save_failed, label),
                            Toast.LENGTH_SHORT).show()
                        if (success && onSaved != null) onSaved.run()
                    }
                }
                if (MetadataController.SOURCE_VNDB == source) {
                    LauncherMetadataBridge.saveSelectedVndbMetadataAsync(fragment.requireContext(), game, metadata, saveCb)
                } else {
                    LauncherMetadataBridge.saveSelectedBangumiMetadataAsync(fragment.requireContext(), game, metadata, saveCb)
                }
            }
            val rowParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            rowParams.setMargins(0, LauncherTheme.dp(fragment.requireContext(), 9), 0, 0)
            list.addView(row, rowParams)
        }

        val scroll = ScrollView(fragment.requireContext())
        scroll.addView(list)
        val scrollParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        scrollParams.setMargins(0, LauncherTheme.dp(fragment.requireContext(), 4), 0, 0)
        root.addView(scroll, scrollParams)

        val cancel = button(fragment, fragment.getString(R.string.settings_cancel), false)
        cancel.setOnClickListener { dialog.dismiss() }
        val cancelParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LauncherTheme.dp(fragment.requireContext(), 38))
        cancelParams.setMargins(0, LauncherTheme.dp(fragment.requireContext(), 13), 0, 0)
        root.addView(cancel, cancelParams)
        setContent(dialog, root, fragment, 288)
        val window = dialog.window
        if (window != null) {
            window.setLayout(LauncherDialogFactory.dialogWidthPx(fragment.requireContext(), 288),
                (fragment.resources.displayMetrics.heightPixels * 0.72f).toInt())
        }
    }

    private fun createDialog(fragment: Fragment): Dialog {
        val dialog = Dialog(fragment.requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    private fun createRoot(fragment: Fragment): LinearLayout {
        val root = LinearLayout(fragment.requireContext())
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(
            LauncherTheme.dp(fragment.requireContext(), 22),
            LauncherTheme.dp(fragment.requireContext(), 18),
            LauncherTheme.dp(fragment.requireContext(), 22),
            LauncherTheme.dp(fragment.requireContext(), 15)
        )
        root.setBackgroundResource(R.drawable.launcher_dialog_bg)
        return root
    }

    private fun title(fragment: Fragment, text: String): TextView {
        val title = TextView(fragment.requireContext())
        title.text = text
        title.gravity = Gravity.CENTER
        title.setSingleLine(true)
        title.ellipsize = TextUtils.TruncateAt.END
        title.setTextColor(LauncherTheme.text(fragment.requireContext()))
        title.setTextSize(16f)
        title.setTypeface(null, Typeface.BOLD)
        return title
    }

    private fun info(fragment: Fragment, text: String): TextView {
        val info = TextView(fragment.requireContext())
        info.text = text
        info.setTextColor(LauncherTheme.textMuted(fragment.requireContext()))
        info.setTextSize(12f)
        info.setLineSpacing(LauncherTheme.dp(fragment.requireContext(), 4).toFloat(), 1f)
        return info
    }

    private fun label(fragment: Fragment, text: String): TextView {
        val label = TextView(fragment.requireContext())
        label.text = text
        label.setTextColor(LauncherTheme.text(fragment.requireContext()))
        label.setTextSize(12f)
        label.setTypeface(null, Typeface.BOLD)
        return label
    }

    private fun hint(fragment: Fragment, text: String): TextView {
        val hint = TextView(fragment.requireContext())
        hint.text = text
        hint.setTextColor(LauncherTheme.textMuted(fragment.requireContext()))
        hint.setTextSize(11f)
        return hint
    }

    private fun button(fragment: Fragment, text: String, primary: Boolean): TextView {
        val btn = TextView(fragment.requireContext())
        btn.text = text
        btn.gravity = Gravity.CENTER
        btn.setTextSize(13f)
        btn.setTypeface(null, Typeface.BOLD)
        if (primary) LauncherTheme.primaryButton(btn) else LauncherTheme.secondaryButton(btn)
        return btn
    }

    private fun setContent(dialog: Dialog, root: LinearLayout, fragment: Fragment, widthDp: Int) {
        dialog.setContentView(root)
        dialog.show()
        LauncherMotion.applyDialogMotion(dialog)
        val window = dialog.window ?: return
        window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.setLayout(LauncherDialogFactory.dialogWidthPx(fragment.requireContext(), widthDp),
            WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun focusAndShowKeyboard(dialog: Dialog, input: EditText, fragment: Fragment) {
        input.isFocusableInTouchMode = true
        input.requestFocus()
        val window = dialog.window
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.setOnDismissListener { hideKeyboard(input, fragment) }
        input.post { showKeyboard(input, fragment, InputMethodManager.SHOW_IMPLICIT) }
        input.postDelayed({ showKeyboard(input, fragment, InputMethodManager.SHOW_FORCED) }, 180)
    }

    private fun showKeyboard(input: EditText, fragment: Fragment, flags: Int) {
        if (!fragment.isAdded()) return
        val manager = fragment.requireContext()
            .getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        if (manager != null) manager.showSoftInput(input, flags)
    }

    private fun hideKeyboard(input: EditText, fragment: Fragment) {
        if (!fragment.isAdded()) return
        val manager = fragment.requireContext()
            .getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        if (manager != null) manager.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun safe(value: String?): String = value?.trim() ?: ""

    private fun first(vararg values: String?): String {
        for (value in values) {
            if (value != null && value.trim().isNotEmpty()) return value.trim()
        }
        return ""
    }
}
