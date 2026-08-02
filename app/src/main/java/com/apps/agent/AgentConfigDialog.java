package com.apps.agent;

import android.app.Activity;
import android.app.Dialog;
import android.text.InputType;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.SwitchCompat;

import com.apps.widget.LauncherEditText;
import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;
import com.core.R;
import com.core.agent.store.AgentConfigStore;
import com.core.util.DevLogger;

import java.security.GeneralSecurityException;

/** Dedicated Launcher-shell dialogs for the local agent configuration forms. */
final class AgentConfigDialog {
    private AgentConfigDialog() {}

    static void showApiConfig(Activity activity, Runnable onSaved) {
        AgentConfigStore.Config config = AgentConfigStore.get(activity);
        Dialog dialog = open(activity);
        LinearLayout root = root(activity);
        root.addView(text(activity, activity.getString(R.string.social_agent_api_config), 16, true));
        TextView note = text(activity, activity.getString(R.string.social_agent_api_note), 11, false);
        note.setTextColor(LauncherTheme.textMuted(activity));
        LinearLayout.LayoutParams noteLp = wrap(); noteLp.setMargins(0, dp(activity, 9), 0, 0); root.addView(note, noteLp);
        EditText baseUrl = input(activity, root, activity.getString(R.string.social_agent_api_address),
                "https://api.example.com/v1", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        baseUrl.setText(config.baseUrl);
        EditText model = input(activity, root, activity.getString(R.string.social_model_name),
                activity.getString(R.string.social_agent_model_support_hint), InputType.TYPE_CLASS_TEXT);
        model.setText(config.model);
        EditText apiKey = input(activity, root, activity.getString(R.string.social_api_key),
                config.hasApiKey ? activity.getString(R.string.social_agent_api_key_saved_hint)
                        : activity.getString(R.string.social_agent_api_key_hint),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText temperature = input(activity, root, activity.getString(R.string.social_temperature),
                "0.0 - 2.0", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        temperature.setText(String.valueOf(config.temperature));
        addButtons(activity, root, dialog, () -> {
            try {
                float temp = Float.parseFloat(valueOf(temperature));
                boolean replaceKey = !valueOf(apiKey).isEmpty();
                AgentConfigStore.save(activity, valueOf(baseUrl), valueOf(model), temp, valueOf(apiKey), replaceKey,
                        config.toolCallLimit, config.taskPlanEnabled, config.permissionMode);
                dialog.dismiss();
                if (onSaved != null) onSaved.run();
                Toast.makeText(activity, R.string.social_agent_config_saved, Toast.LENGTH_SHORT).show();
            } catch (NumberFormatException error) {
                DevLogger.w("AgentConfigDialog", "Invalid number in agent API config", error);
                Toast.makeText(activity, error.getMessage() == null
                        ? activity.getString(R.string.social_agent_config_save_failed) : error.getMessage(), Toast.LENGTH_LONG).show();
            } catch (GeneralSecurityException | IllegalArgumentException error) {
                DevLogger.w("AgentConfigDialog", "Failed to save agent API config", error);
                Toast.makeText(activity, error.getMessage() == null
                        ? activity.getString(R.string.social_agent_config_save_failed) : error.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        show(activity, dialog, root);
    }

    static void showExecutionSettings(Activity activity, Runnable onSaved) {
        AgentConfigStore.Config config = AgentConfigStore.get(activity);
        Dialog dialog = open(activity);
        LinearLayout root = root(activity);
        root.addView(text(activity, activity.getString(R.string.social_agent_execution_title), 16, true));
        TextView note = text(activity, activity.getString(R.string.social_agent_execution_note), 11, false);
        note.setTextColor(LauncherTheme.textMuted(activity));
        LinearLayout.LayoutParams noteLp = wrap(); noteLp.setMargins(0, dp(activity, 8), 0, 0); root.addView(note, noteLp);
        EditText toolCallLimit = input(activity, root, activity.getString(R.string.social_agent_tool_limit),
                "1 - 50", InputType.TYPE_CLASS_NUMBER);
        toolCallLimit.setText(String.valueOf(config.toolCallLimit));
        EditText contextBudget = input(activity, root, activity.getString(R.string.social_agent_context_budget),
                "16 - 1024", InputType.TYPE_CLASS_NUMBER);
        contextBudget.setText(String.valueOf(config.contextBudgetKb));
        TextView contextNote = text(activity, activity.getString(R.string.social_agent_context_note), 10, false);
        contextNote.setTextColor(LauncherTheme.textMuted(activity));
        LinearLayout.LayoutParams contextNoteLp = wrap(); contextNoteLp.setMargins(0, dp(activity, 5), 0, 0);
        root.addView(contextNote, contextNoteLp);
        SwitchCompat taskPlan = settingSwitch(activity, activity.getString(R.string.social_agent_task_plan), config.taskPlanEnabled);
        LinearLayout.LayoutParams planLp = wrap(); planLp.setMargins(0, dp(activity, 10), 0, 0); root.addView(taskPlan, planLp);
        SwitchCompat fullPermission = settingSwitch(activity, activity.getString(R.string.social_agent_full_permission), config.isFullPermission());
        LinearLayout.LayoutParams permissionLp = wrap(); permissionLp.setMargins(0, dp(activity, 4), 0, 0); root.addView(fullPermission, permissionLp);
        TextView warning = text(activity, activity.getString(R.string.social_agent_permission_warning), 10, false);
        warning.setTextColor(LauncherTheme.textMuted(activity));
        LinearLayout.LayoutParams warningLp = wrap(); warningLp.setMargins(0, dp(activity, 5), 0, 0); root.addView(warning, warningLp);
        addButtons(activity, root, dialog, () -> {
            try {
                int calls = Integer.parseInt(valueOf(toolCallLimit));
                int contextKb = Integer.parseInt(valueOf(contextBudget));
                AgentConfigStore.saveExecutionSettings(activity, calls, contextKb,
                        taskPlan.isChecked(), fullPermission.isChecked());
                dialog.dismiss();
                if (onSaved != null) onSaved.run();
                Toast.makeText(activity, R.string.social_agent_settings_saved, Toast.LENGTH_SHORT).show();
            } catch (NumberFormatException error) {
                DevLogger.w("AgentConfigDialog", "Invalid number in agent execution settings", error);
                Toast.makeText(activity, error.getMessage() == null
                        ? activity.getString(R.string.social_agent_settings_save_failed) : error.getMessage(), Toast.LENGTH_LONG).show();
            } catch (GeneralSecurityException | IllegalArgumentException error) {
                DevLogger.w("AgentConfigDialog", "Failed to save agent execution settings", error);
                Toast.makeText(activity, error.getMessage() == null
                        ? activity.getString(R.string.social_agent_settings_save_failed) : error.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        show(activity, dialog, root);
    }

    private static Dialog open(Activity activity) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        return dialog;
    }

    private static LinearLayout root(Activity activity) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 22), dp(activity, 18), dp(activity, 22), dp(activity, 15));
        root.setBackgroundResource(R.drawable.launcher_dialog_bg);
        return root;
    }

    private static void show(Activity activity, Dialog dialog, LinearLayout root) {
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(root);
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setContentView(scroll);
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);
        window.setLayout(dp(activity, 288), WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private static void addButtons(Activity activity, LinearLayout root, Dialog dialog, Runnable onSave) {
        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = text(activity, activity.getString(R.string.social_action_cancel), 13, true);
        LauncherTheme.secondaryButton(cancel);
        cancel.setGravity(Gravity.CENTER);
        TextView save = text(activity, activity.getString(R.string.social_action_save), 13, true);
        LauncherTheme.primaryButton(save);
        save.setGravity(Gravity.CENTER);
        cancel.setOnClickListener(view -> dialog.dismiss());
        save.setOnClickListener(view -> onSave.run());
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, dp(activity, 36), 1f));
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0, dp(activity, 36), 1f);
        saveLp.setMargins(dp(activity, 8), 0, 0, 0);
        buttons.addView(save, saveLp);
        LinearLayout.LayoutParams buttonsLp = wrap();
        buttonsLp.setMargins(0, dp(activity, 12), 0, 0);
        root.addView(buttons, buttonsLp);
    }

    private static SwitchCompat settingSwitch(Activity activity, String label, boolean checked) {
        SwitchCompat view = new SwitchCompat(activity);
        view.setText(label);
        view.setTextSize(12);
        view.setTextColor(LauncherTheme.text(activity));
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setChecked(checked);
        LauncherTheme.styleMaterialSwitch(view);
        return view;
    }

    private static EditText input(Activity activity, LinearLayout root, String label, String hint, int type) {
        TextView labelView = text(activity, label, 12, true);
        LinearLayout.LayoutParams labelLp = wrap();
        labelLp.setMargins(0, dp(activity, 10), 0, dp(activity, 5));
        root.addView(labelView, labelLp);
        EditText input = new LauncherEditText(activity);
        input.setSingleLine(true);
        input.setInputType(type);
        input.setHint(hint);
        input.setTextSize(12);
        input.setTextColor(LauncherTheme.text(activity));
        input.setHintTextColor(LauncherTheme.textMuted(activity));
        input.setPadding(dp(activity, 13), 0, dp(activity, 13), 0);
        input.setBackground(LauncherTheme.secondaryButton(activity, 20f));
        LauncherTheme.styleTextInput(input);
        root.addView(input, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 40)));
        return input;
    }

    private static TextView text(Activity activity, String value, int size, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(LauncherTheme.text(activity));
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private static String valueOf(EditText view) {
        return view.getText() == null ? "" : view.getText().toString().trim();
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
