package com.apps.game

import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.apps.theme.LauncherDialogFactory
import com.apps.theme.LauncherMotion
import com.apps.theme.LauncherTheme
import com.core.R
import com.core.importer.ImportGameData

/**
 * 跨端导入预览专用弹窗。
 *
 * Controller 只负责业务流转，本类集中处理带多选列表的复杂 UI 模板。
 */
internal object ExternalImportPreviewDialog {

    interface Callback {
        fun onImport()

        fun onCancel()
    }

    @JvmStatic
    fun show(host: ManageHost, games: List<ImportGameData>, callback: Callback) {
        val dialog = AlertDialog.Builder(host.requireContext()).create()
        dialog.setOnCancelListener { callback.onCancel() }
        dialog.show()
        LauncherMotion.applyDialogMotion(dialog)

        val window = dialog.window
        if (window == null) {
            callback.onCancel()
            if (dialog.isShowing) dialog.dismiss()
            return
        }
        window.setBackgroundDrawableResource(android.R.color.transparent)
        // 宽度按 300dp 指定，并通过 LauncherDialogFactory 做屏幕宽度兜底（左右各留 16dp + 平板竖屏缩放），避免小屏溢出
        window.setLayout(LauncherDialogFactory.dialogWidthPx(host.requireContext(), 300),
                WindowManager.LayoutParams.WRAP_CONTENT)

        val root = LinearLayout(host.requireContext())
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(host.dp(22), host.dp(18), host.dp(22), host.dp(15))
        root.setBackgroundResource(R.drawable.launcher_dialog_bg)

        root.addView(createTitle(host, host.getString(R.string.game_import_preview)),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val totalCount = games.size
        val existCount = games.count { it.exists }
        val info = TextView(host.requireContext())
        info.text = host.getString(R.string.game_import_preview_count, totalCount, existCount)
        info.gravity = Gravity.CENTER
        info.setTextColor(LauncherTheme.textMuted(host.requireContext()))
        host.setResponsiveTextSize(info, 12f)
        val infoLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        infoLp.setMargins(0, host.dp(10), 0, 0)
        root.addView(info, infoLp)

        val listContainer = LinearLayout(host.requireContext())
        listContainer.orientation = LinearLayout.VERTICAL

        val checkBoxes = ArrayList<CheckBox>()
        for (game in games) {
            listContainer.addView(createImportPreviewRow(host, game, checkBoxes))
        }

        val scroll = ScrollView(host.requireContext())
        scroll.addView(listContainer)
        val scrollHeight = minOf(host.dp(280), host.dp(8) + totalCount * host.dp(54))
        val scrollLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, scrollHeight)
        scrollLp.setMargins(0, host.dp(4), 0, 0)
        root.addView(scroll, scrollLp)

        val buttons = LinearLayout(host.requireContext())
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.weightSum = 3f
        val buttonsLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, host.dp(36))
        buttonsLp.setMargins(0, host.dp(12), 0, 0)

        val toggleAll = TextView(host.requireContext())
        toggleAll.setText(R.string.game_import_select_all)
        toggleAll.gravity = Gravity.CENTER
        host.setResponsiveTextSize(toggleAll, 13f)
        toggleAll.setTypeface(null, Typeface.BOLD)
        LauncherTheme.secondaryButton(toggleAll)
        toggleAll.setOnClickListener {
            val anyUnchecked = games.indices.any { !games[it].exists && !checkBoxes[it].isChecked }
            for (i in games.indices) {
                if (!games[i].exists) checkBoxes[i].isChecked = anyUnchecked
            }
        }
        buttons.addView(toggleAll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        val importButton = TextView(host.requireContext())
        importButton.setText(R.string.game_common_import)
        importButton.gravity = Gravity.CENTER
        host.setResponsiveTextSize(importButton, 13f)
        importButton.setTypeface(null, Typeface.BOLD)
        LauncherTheme.primaryButton(importButton)
        val importLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        importLp.setMargins(host.dp(6), 0, 0, 0)
        importButton.setOnClickListener {
            dialog.dismiss()
            for (i in games.indices) {
                games[i].selected = checkBoxes[i].isChecked && !games[i].exists
            }
            callback.onImport()
        }
        buttons.addView(importButton, importLp)

        val cancel = TextView(host.requireContext())
        cancel.setText(R.string.game_common_cancel)
        cancel.gravity = Gravity.CENTER
        host.setResponsiveTextSize(cancel, 13f)
        cancel.setTypeface(null, Typeface.BOLD)
        LauncherTheme.secondaryButton(cancel)
        val cancelLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        cancelLp.setMargins(host.dp(6), 0, 0, 0)
        cancel.setOnClickListener {
            dialog.dismiss()
            callback.onCancel()
        }
        buttons.addView(cancel, cancelLp)

        root.addView(buttons, buttonsLp)
        window.setContentView(root)
    }

    private fun createTitle(host: ManageHost, text: String): TextView {
        val title = TextView(host.requireContext())
        title.text = text
        title.gravity = Gravity.CENTER
        title.setTextColor(LauncherTheme.text(host.requireContext()))
        host.setResponsiveTextSize(title, 16f)
        title.setTypeface(null, Typeface.BOLD)
        return title
    }

    private fun createImportPreviewRow(host: ManageHost, game: ImportGameData, outCheckBoxes: MutableList<CheckBox>): View {
        val row = LinearLayout(host.requireContext())
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(host.dp(2), host.dp(7), host.dp(2), host.dp(7))

        val checkBox = CheckBox(host.requireContext())
        checkBox.isChecked = game.selected
        checkBox.isEnabled = !game.exists
        checkBox.isClickable = !game.exists
        val checkBoxLp = LinearLayout.LayoutParams(host.dp(28), host.dp(28))
        row.addView(checkBox, checkBoxLp)
        outCheckBoxes.add(checkBox)

        val textColumn = LinearLayout(host.requireContext())
        textColumn.orientation = LinearLayout.VERTICAL
        val textColumnLp = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        textColumnLp.setMargins(host.dp(6), 0, 0, 0)
        textColumn.layoutParams = textColumnLp

        val name = TextView(host.requireContext())
        name.text = game.name
        name.setSingleLine(true)
        name.ellipsize = TextUtils.TruncateAt.END
        name.setTextColor(if (game.exists)
            LauncherTheme.textMuted(host.requireContext())
        else
            LauncherTheme.text(host.requireContext()))
        host.setResponsiveTextSize(name, 13f)
        textColumn.addView(name, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val status = TextView(host.requireContext())
        status.text = buildStatusText(host, game)
        status.setSingleLine(true)
        status.ellipsize = TextUtils.TruncateAt.END
        status.setTextColor(LauncherTheme.textMuted(host.requireContext()))
        host.setResponsiveTextSize(status, 10f)
        val statusLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        statusLp.setMargins(0, host.dp(2), 0, 0)
        textColumn.addView(status, statusLp)

        row.addView(textColumn)
        return row
    }

    private fun buildStatusText(host: ManageHost, game: ImportGameData): String {
        val statusText = StringBuilder()
        if (game.exists) {
            statusText.append(host.getString(R.string.game_import_exists_skip))
        } else {
            statusText.append(host.getString(R.string.game_import_new_game))
            if (game.totalPlayTime > 0) {
                statusText.append(host.getString(
                        R.string.game_import_duration, formatSeconds(host, game.totalPlayTime)))
            }
            val lunaBoxSessions = game.lunaBoxSessions
            if (!lunaBoxSessions.isNullOrEmpty()) {
                statusText.append(host.getString(R.string.game_import_records, lunaBoxSessions.size))
            } else {
                val vniteTimers = game.vniteTimers
                if (!vniteTimers.isNullOrEmpty()) {
                    statusText.append(host.getString(R.string.game_import_records, vniteTimers.size))
                } else {
                    val playedTimeMap = game.playedTimeMap
                    if (!playedTimeMap.isNullOrEmpty()) {
                        statusText.append(host.getString(R.string.game_import_records, playedTimeMap.size))
                    }
                }
            }
        }
        return statusText.toString()
    }

    private fun formatSeconds(host: ManageHost, seconds: Long): String {
        if (seconds <= 0) return host.getString(R.string.game_duration_minutes, 0)
        val minutes = seconds / 60
        if (minutes < 60) return host.getString(R.string.game_duration_minutes, minutes)
        val hours = minutes / 60
        val remainMinutes = minutes % 60
        return host.getString(R.string.game_duration_hours_minutes, hours,
                if (remainMinutes > 0) host.getString(R.string.game_duration_remaining_minutes, remainMinutes) else "")
    }
}
