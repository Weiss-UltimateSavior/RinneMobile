package com.apps.agent

import android.app.Activity
import android.app.Dialog
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import com.apps.theme.LauncherDialogFactory
import com.apps.theme.LauncherMotion
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherEditText
import com.core.R
import com.core.agent.store.AgentConfigStore
import com.core.util.DevLogger
import java.security.GeneralSecurityException

/** Dedicated Launcher-shell dialogs for the local agent configuration forms. */
internal object AgentConfigDialog {

    @JvmStatic
    fun showApiConfig(activity: Activity, onSaved: Runnable?) {
        val config = AgentConfigStore.get(activity)
        val dialog = open(activity)
        val root = root(activity)
        root.addView(text(activity, activity.getString(R.string.social_agent_api_config), 16, true))
        val note = text(activity, activity.getString(R.string.social_agent_api_note), 11, false)
        note.setTextColor(LauncherTheme.textMuted(activity))
        val noteLp = wrap()
        noteLp.setMargins(0, LauncherTheme.dp(activity, 9), 0, 0)
        root.addView(note, noteLp)
        val baseUrl = input(
            activity, root, activity.getString(R.string.social_agent_api_address),
            activity.getString(R.string.social_agent_api_address_hint),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )
        baseUrl.setText(config.baseUrl)
        val model = input(
            activity, root, activity.getString(R.string.social_model_name),
            activity.getString(R.string.social_agent_model_support_hint), InputType.TYPE_CLASS_TEXT
        )
        model.setText(config.model)
        val apiKey = input(
            activity, root, activity.getString(R.string.social_api_key),
            if (config.hasApiKey) activity.getString(R.string.social_agent_api_key_saved_hint)
            else activity.getString(R.string.social_agent_api_key_hint),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        val temperature = input(
            activity, root, activity.getString(R.string.social_temperature),
            activity.getString(R.string.social_agent_temperature_hint),
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        temperature.setText(config.temperature.toString())
        addButtons(activity, root, dialog, Runnable {
            try {
                val temp = valueOf(temperature).toFloat()
                val replaceKey = valueOf(apiKey).isNotEmpty()
                AgentConfigStore.save(
                    activity, valueOf(baseUrl), valueOf(model), temp, valueOf(apiKey), replaceKey,
                    config.toolCallLimit, config.taskPlanEnabled, config.permissionMode
                )
                dialog.dismiss()
                onSaved?.run()
                Toast.makeText(activity, R.string.social_agent_config_saved, Toast.LENGTH_SHORT).show()
            } catch (error: NumberFormatException) {
                showSaveError(activity, error, "Invalid number in agent API config", R.string.social_agent_config_save_failed)
            } catch (error: GeneralSecurityException) {
                showSaveError(activity, error, "Failed to save agent API config", R.string.social_agent_config_save_failed)
            } catch (error: IllegalArgumentException) {
                showSaveError(activity, error, "Failed to save agent API config", R.string.social_agent_config_save_failed)
            }
        })
        show(activity, dialog, root)
    }

    @JvmStatic
    fun showExecutionSettings(activity: Activity, onSaved: Runnable?) {
        val config = AgentConfigStore.get(activity)
        val dialog = open(activity)
        val root = root(activity)
        root.addView(text(activity, activity.getString(R.string.social_agent_execution_title), 16, true))
        val note = text(activity, activity.getString(R.string.social_agent_execution_note), 11, false)
        note.setTextColor(LauncherTheme.textMuted(activity))
        val noteLp = wrap()
        noteLp.setMargins(0, LauncherTheme.dp(activity, 8), 0, 0)
        root.addView(note, noteLp)
        val toolCallLimit = input(
            activity, root, activity.getString(R.string.social_agent_tool_limit),
            activity.getString(R.string.social_agent_tool_limit_hint), InputType.TYPE_CLASS_NUMBER
        )
        toolCallLimit.setText(config.toolCallLimit.toString())
        val contextBudget = input(
            activity, root, activity.getString(R.string.social_agent_context_budget),
            activity.getString(R.string.social_agent_context_budget_hint), InputType.TYPE_CLASS_NUMBER
        )
        contextBudget.setText(config.contextBudgetKb.toString())
        val contextNote = text(activity, activity.getString(R.string.social_agent_context_note), 10, false)
        contextNote.setTextColor(LauncherTheme.textMuted(activity))
        val contextNoteLp = wrap()
        contextNoteLp.setMargins(0, LauncherTheme.dp(activity, 5), 0, 0)
        root.addView(contextNote, contextNoteLp)
        val taskPlan = settingSwitch(activity, activity.getString(R.string.social_agent_task_plan), config.taskPlanEnabled)
        val planLp = wrap()
        planLp.setMargins(0, LauncherTheme.dp(activity, 10), 0, 0)
        root.addView(taskPlan, planLp)
        val fullPermission = settingSwitch(
            activity, activity.getString(R.string.social_agent_full_permission), config.isFullPermission()
        )
        val permissionLp = wrap()
        permissionLp.setMargins(0, LauncherTheme.dp(activity, 4), 0, 0)
        root.addView(fullPermission, permissionLp)
        val warning = text(activity, activity.getString(R.string.social_agent_permission_warning), 10, false)
        warning.setTextColor(LauncherTheme.textMuted(activity))
        val warningLp = wrap()
        warningLp.setMargins(0, LauncherTheme.dp(activity, 5), 0, 0)
        root.addView(warning, warningLp)
        addButtons(activity, root, dialog, Runnable {
            try {
                val calls = valueOf(toolCallLimit).toInt()
                val contextKb = valueOf(contextBudget).toInt()
                AgentConfigStore.saveExecutionSettings(
                    activity, calls, contextKb,
                    taskPlan.isChecked, fullPermission.isChecked
                )
                dialog.dismiss()
                onSaved?.run()
                Toast.makeText(activity, R.string.social_agent_settings_saved, Toast.LENGTH_SHORT).show()
            } catch (error: NumberFormatException) {
                showSaveError(activity, error, "Invalid number in agent execution settings", R.string.social_agent_settings_save_failed)
            } catch (error: GeneralSecurityException) {
                showSaveError(activity, error, "Failed to save agent execution settings", R.string.social_agent_settings_save_failed)
            } catch (error: IllegalArgumentException) {
                showSaveError(activity, error, "Failed to save agent execution settings", R.string.social_agent_settings_save_failed)
            }
        })
        show(activity, dialog, root)
    }

    private fun open(activity: Activity): Dialog {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val window = dialog.window
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        return dialog
    }

    private fun root(activity: Activity): LinearLayout {
        val root = LinearLayout(activity)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(
            LauncherTheme.dp(activity, 22), LauncherTheme.dp(activity, 18),
            LauncherTheme.dp(activity, 22), LauncherTheme.dp(activity, 15)
        )
        root.setBackgroundResource(R.drawable.launcher_dialog_bg)
        return root
    }

    private fun show(activity: Activity, dialog: Dialog, root: LinearLayout) {
        val scroll = ScrollView(activity)
        scroll.addView(root)
        val window = dialog.window
        if (window == null) return
        window.setContentView(scroll)
        dialog.show()
        LauncherMotion.applyDialogMotion(dialog)
        window.setLayout(LauncherDialogFactory.dialogWidthPx(activity, 288), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun addButtons(activity: Activity, root: LinearLayout, dialog: Dialog, onSave: Runnable) {
        val buttons = LinearLayout(activity)
        buttons.orientation = LinearLayout.HORIZONTAL
        val cancel = text(activity, activity.getString(R.string.social_action_cancel), 13, true)
        LauncherTheme.secondaryButton(cancel)
        cancel.gravity = Gravity.CENTER
        val save = text(activity, activity.getString(R.string.social_action_save), 13, true)
        LauncherTheme.primaryButton(save)
        save.gravity = Gravity.CENTER
        cancel.setOnClickListener { dialog.dismiss() }
        save.setOnClickListener { onSave.run() }
        buttons.addView(cancel, LinearLayout.LayoutParams(0, LauncherTheme.dp(activity, 36), 1f))
        val saveLp = LinearLayout.LayoutParams(0, LauncherTheme.dp(activity, 36), 1f)
        saveLp.setMargins(LauncherTheme.dp(activity, 8), 0, 0, 0)
        buttons.addView(save, saveLp)
        val buttonsLp = wrap()
        buttonsLp.setMargins(0, LauncherTheme.dp(activity, 12), 0, 0)
        root.addView(buttons, buttonsLp)
    }

    private fun settingSwitch(activity: Activity, label: String, checked: Boolean): SwitchCompat {
        val view = SwitchCompat(activity)
        view.text = label
        view.setTextSize(12f)
        view.setTextColor(LauncherTheme.text(activity))
        view.gravity = Gravity.CENTER_VERTICAL
        view.isChecked = checked
        LauncherTheme.styleMaterialSwitch(view)
        return view
    }

    private fun input(activity: Activity, root: LinearLayout, label: String, hint: String, type: Int): EditText {
        val labelView = text(activity, label, 12, true)
        val labelLp = wrap()
        labelLp.setMargins(0, LauncherTheme.dp(activity, 10), 0, LauncherTheme.dp(activity, 5))
        root.addView(labelView, labelLp)
        val input = LauncherEditText(activity)
        input.setSingleLine(true)
        input.inputType = type
        input.hint = hint
        input.setTextSize(12f)
        input.setTextColor(LauncherTheme.text(activity))
        input.setHintTextColor(LauncherTheme.textMuted(activity))
        input.setPadding(LauncherTheme.dp(activity, 13), 0, LauncherTheme.dp(activity, 13), 0)
        input.background = LauncherTheme.secondaryButton(activity, 20f)
        LauncherTheme.styleTextInput(input)
        root.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LauncherTheme.dp(activity, 40)))
        return input
    }

    private fun text(activity: Activity, value: String, size: Int, bold: Boolean): TextView {
        val view = TextView(activity)
        view.text = value
        view.setTextSize(size.toFloat())
        view.setTextColor(LauncherTheme.text(activity))
        if (bold) view.setTypeface(null, Typeface.BOLD)
        return view
    }

    private fun valueOf(view: EditText): String = view.text?.toString()?.trim() ?: ""

    private fun wrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun showSaveError(activity: Activity, error: Exception, message: String, fallbackRes: Int) {
        DevLogger.w("AgentConfigDialog", message, error)
        Toast.makeText(activity, error.message ?: activity.getString(fallbackRes), Toast.LENGTH_LONG).show()
    }
}
