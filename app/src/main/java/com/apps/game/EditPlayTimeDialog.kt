package com.apps.game

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.util.Log
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.apps.theme.LauncherDialogFactory
import com.apps.theme.LauncherMotion
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherEditText
import com.core.R
import com.core.launcherbridge.LauncherRepositoryBridge
import com.core.model.Game
import com.core.util.AppExecutors
import com.core.util.RxMainScheduler
import com.core.util.TimeFormatUtil

/**
 * 修改游玩时长对话框（从 GameActionMenuFactory 拆分，见 com_apps_refactor_plan.md §5.1）。
 */
object EditPlayTimeDialog {

    /** 显示修改游玩时长对话框，包含总时长与追加时长两个输入框。 */
    @JvmStatic
    fun show(
        fragment: Fragment, game: Game?,
        callback: GameActionMenuFactory.GameUpdateCallback
    ) {
        if (game == null) return
        val ctx = fragment.requireContext()
        // 使用 Dialog 而非 AlertDialog，避免 FLAG_NOT_FOCUSABLE 导致输入法无法唤醒
        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = GameActionMenuFactory.createDialogRoot(ctx)
        root.addView(GameActionMenuFactory.createDialogTitle(ctx, ctx.getString(R.string.game_action_edit_duration)))

        val info = TextView(ctx)
        val lastPlayedText = if (game.lastPlayedAt > 0) {
            TimeFormatUtil.date(game.lastPlayedAt)
        } else {
            ctx.getString(R.string.game_action_none)
        }
        info.text = ctx.getString(R.string.game_action_current_duration,
            TimeFormatUtil.playTime(game.totalPlayTime), lastPlayedText)
        info.setTextColor(LauncherTheme.textMuted(ctx))
        info.textSize = 12f
        info.setLineSpacing(LauncherTheme.dp(ctx, 4).toFloat(), 1f)
        val infoLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        infoLp.setMargins(0, LauncherTheme.dp(ctx, 13), 0, 0)
        root.addView(info, infoLp)

        val totalLabel = TextView(ctx)
        totalLabel.setText(R.string.game_action_set_total_duration)
        totalLabel.setTextColor(LauncherTheme.text(ctx))
        totalLabel.textSize = 12f
        totalLabel.setTypeface(null, Typeface.BOLD)
        val tlLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        tlLp.setMargins(0, LauncherTheme.dp(ctx, 13), 0, 0)
        root.addView(totalLabel, tlLp)

        val totalInput = LauncherEditText(ctx)
        totalInput.setHint(R.string.game_action_total_duration_hint)
        totalInput.setTextColor(LauncherTheme.text(ctx))
        totalInput.setHintTextColor(ContextCompat.getColor(ctx, R.color.launcher_input_hint_color))
        totalInput.textSize = 13f
        totalInput.setPadding(LauncherTheme.dp(ctx, 13), LauncherTheme.dp(ctx, 9), LauncherTheme.dp(ctx, 13), LauncherTheme.dp(ctx, 9))
        totalInput.background = LauncherTheme.cancelChip(ctx)
        LauncherTheme.styleTextInput(totalInput)
        val tiLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        tiLp.setMargins(0, LauncherTheme.dp(ctx, 5), 0, 0)
        root.addView(totalInput, tiLp)

        val addLabel = TextView(ctx)
        addLabel.setText(R.string.game_action_add_duration)
        addLabel.setTextColor(LauncherTheme.text(ctx))
        addLabel.textSize = 12f
        addLabel.setTypeface(null, Typeface.BOLD)
        val alLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        alLp.setMargins(0, LauncherTheme.dp(ctx, 11), 0, 0)
        root.addView(addLabel, alLp)

        val addInput = LauncherEditText(ctx)
        addInput.setHint(R.string.game_action_add_duration_hint)
        addInput.setTextColor(LauncherTheme.text(ctx))
        addInput.setHintTextColor(ContextCompat.getColor(ctx, R.color.launcher_input_hint_color))
        addInput.textSize = 13f
        addInput.setPadding(LauncherTheme.dp(ctx, 13), LauncherTheme.dp(ctx, 9), LauncherTheme.dp(ctx, 13), LauncherTheme.dp(ctx, 9))
        addInput.background = LauncherTheme.cancelChip(ctx)
        LauncherTheme.styleTextInput(addInput)
        val aiLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        aiLp.setMargins(0, LauncherTheme.dp(ctx, 5), 0, 0)
        root.addView(addInput, aiLp)

        val hint = TextView(ctx)
        hint.setText(R.string.game_action_duration_units_hint)
        hint.setTextColor(LauncherTheme.textMuted(ctx))
        hint.textSize = 11f
        val hLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        hLp.setMargins(0, LauncherTheme.dp(ctx, 7), 0, 0)
        root.addView(hint, hLp)

        val btnRow = LinearLayout(ctx)
        btnRow.orientation = LinearLayout.HORIZONTAL
        btnRow.weightSum = 2f
        val brLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        brLp.setMargins(0, LauncherTheme.dp(ctx, 13), 0, 0)
        btnRow.layoutParams = brLp

        val cancelBtn = TextView(ctx)
        cancelBtn.setText(R.string.game_common_cancel)
        cancelBtn.gravity = Gravity.CENTER
        cancelBtn.textSize = 13f
        cancelBtn.setTypeface(null, Typeface.BOLD)
        LauncherTheme.secondaryButton(cancelBtn)
        val cancelLp = LinearLayout.LayoutParams(0, LauncherTheme.dp(ctx, 38), 1f)
        cancelLp.setMargins(0, 0, LauncherTheme.dp(ctx, 5), 0)
        cancelBtn.layoutParams = cancelLp
        cancelBtn.setOnClickListener { dialog.dismiss() }
        btnRow.addView(cancelBtn)

        val saveBtn = TextView(ctx)
        saveBtn.setText(R.string.game_common_save)
        saveBtn.gravity = Gravity.CENTER
        saveBtn.textSize = 13f
        saveBtn.setTypeface(null, Typeface.BOLD)
        LauncherTheme.primaryButton(saveBtn)
        val saveLp = LinearLayout.LayoutParams(0, LauncherTheme.dp(ctx, 38), 1f)
        saveLp.setMargins(LauncherTheme.dp(ctx, 5), 0, 0, 0)
        saveBtn.layoutParams = saveLp
        saveBtn.setOnClickListener {
            val totalMinutes = GameMetadataFormatter.parseDuration(totalInput.text.toString().trim { it <= ' ' })
            val addMinutes = GameMetadataFormatter.parseDuration(addInput.text.toString().trim { it <= ' ' })
            if (totalMinutes == null && addMinutes == null) {
                Toast.makeText(ctx, R.string.game_action_invalid_duration, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            updatePlayTime(fragment, game, totalMinutes, addMinutes, callback)
        }
        btnRow.addView(saveBtn)
        root.addView(btnRow)

        val window = dialog.window
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dialog.setContentView(root)
        dialog.show()
        LauncherMotion.applyDialogMotion(dialog)
        window?.setLayout(LauncherDialogFactory.dialogWidthPx(ctx, 288), WindowManager.LayoutParams.WRAP_CONTENT)

        totalInput.requestFocus()
        totalInput.post {
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(totalInput, 0)
        }
    }

    /** 异步写入游玩时长，完成后通过 callback 回传最新 Game。 */
    @JvmStatic
    fun updatePlayTime(
        fragment: Fragment, game: Game, totalMinutes: Long?,
        addMinutes: Long?, callback: GameActionMenuFactory.GameUpdateCallback
    ) {
        val appContext = fragment.requireContext().applicationContext
        AppExecutors.runOnSingle {
            var updated: Game? = null
            try {
                val latest = LauncherRepositoryBridge.findGameById(appContext, game.id)
                if (latest != null) {
                    var finalDuration = latest.totalPlayTime
                    if (totalMinutes != null) finalDuration = totalMinutes * 60_000L
                    if (addMinutes != null) finalDuration += addMinutes * 60_000L
                    val clamped = Math.max(0, finalDuration)
                    LauncherRepositoryBridge.setManualPlayTimeForGame(appContext, latest.id, clamped)
                    latest.totalPlayTime = clamped
                    updated = latest
                }
            } catch (e: Exception) {
                Log.w("EditPlayTimeDialog", "Failed to update play time", e)
            }
            val result = updated
            RxMainScheduler.post {
                if (!fragment.isAdded || fragment.view == null) return@post
                if (result != null) callback.onGameUpdated(result)
            }
        }
    }
}
