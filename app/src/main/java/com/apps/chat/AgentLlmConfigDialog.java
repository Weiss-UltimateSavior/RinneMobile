package com.apps.chat;

import android.app.Activity;
import android.app.Dialog;
import android.text.InputType;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.apps.theme.LauncherDialogFactory;
import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherEditText;
import com.core.R;
import com.core.launcherbridge.LauncherAuthBridge;
import com.core.launcherbridge.LlmConfig;
import com.core.launcherbridge.LlmConfigCallback;

import java.net.URISyntaxException;
import java.util.Locale;

/**
 * AI 聊天页的 LLM 配置弹窗。
 * 弹窗外壳、表单构建与保存/重置行为统一收纳在此，调用方仅一行 {@code new AgentLlmConfigDialog(...).show()}。
 */
public class AgentLlmConfigDialog {
    private final Activity activity;
    @Nullable private final Runnable onSaveConfig;
    @Nullable private final Runnable onResetConfig;

    public AgentLlmConfigDialog(Activity activity) {
        this(activity, null, null);
    }

    /**
     * @param onSaveConfig  保存成功后回调（可选；原行为仅关闭弹窗并 Toast，无需回调时传 null）
     * @param onResetConfig 恢复默认成功后回调（可选，同上）
     */
    public AgentLlmConfigDialog(Activity activity, @Nullable Runnable onSaveConfig, @Nullable Runnable onResetConfig) {
        this.activity = activity;
        this.onSaveConfig = onSaveConfig;
        this.onResetConfig = onResetConfig;
    }

    /**
     * 构建并展示 LLM 配置弹窗。
     *
     * <p>透明 window 外壳 + 表单（baseUrl / apiKey / model / temperature）+ 重置 / 保存按钮；
     * 弹窗宽度统一走 {@link LauncherDialogFactory#dialogWidthPx}，主题色调经 LauncherTheme.applyPrimaryTone 注入。
     * 异步回填当前配置（LauncherAuthBridge.fetchLlmConfig）；保存 / 重置经 LauncherAuthBridge.updateLlmConfig，
     * 成功后分别触发 {@code onSaveConfig} / {@code onResetConfig} 回调（若构造时提供）。
     */
    public void show() {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);
        Window window = dialog.getWindow();
        if (window == null) return;
        // 透明 window 外壳 + 表单宽度兜底，与 LauncherDialogFactory 弹窗规范一致（禁止裸固定 dp 宽度）
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(LauncherDialogFactory.dialogWidthPx(activity, LauncherDialogFactory.WIDTH_FORM_DP), WindowManager.LayoutParams.WRAP_CONTENT);
        window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(LauncherTheme.dp(activity, 22), LauncherTheme.dp(activity, 18), LauncherTheme.dp(activity, 22), LauncherTheme.dp(activity, 15));
        root.setBackgroundResource(R.drawable.launcher_dialog_bg);
        TextView title = dialogText(activity.getString(R.string.social_custom_llm_title), 16, R.color.launcher_text_color);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);
        TextView info = dialogText(activity.getString(R.string.social_custom_llm_note), 11, R.color.launcher_text_muted_color);
        info.setLineSpacing(LauncherTheme.dp(activity, 3), 1f);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        infoLp.setMargins(0, LauncherTheme.dp(activity, 9), 0, 0);
        root.addView(info, infoLp);
        EditText baseUrl = llmInput(root, activity.getString(R.string.social_api_endpoint), activity.getString(R.string.social_agent_api_address_hint), InputType.TYPE_TEXT_VARIATION_URI);
        EditText apiKey = llmInput(root, activity.getString(R.string.social_api_key), activity.getString(R.string.social_api_key_default_hint), InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText model = llmInput(root, activity.getString(R.string.social_model_name), activity.getString(R.string.social_model_example), InputType.TYPE_CLASS_TEXT);
        EditText temperature = llmInput(root, activity.getString(R.string.social_temperature), activity.getString(R.string.social_temperature_hint), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        TextView loading = dialogText(activity.getString(R.string.social_reading_config), 11, R.color.launcher_text_muted_color);
        LinearLayout.LayoutParams loadingLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        loadingLp.setMargins(0, LauncherTheme.dp(activity, 8), 0, 0);
        root.addView(loading, loadingLp);
        TextView reset = dialogText(activity.getString(R.string.social_restore_default), 12, R.color.launcher_text_color);
        LauncherTheme.secondaryButton(reset);
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LauncherTheme.dp(activity, 34));
        resetLp.setMargins(0, LauncherTheme.dp(activity, 11), 0, 0);
        root.addView(reset, resetLp);
        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LauncherTheme.dp(activity, 36));
        buttonsLp.setMargins(0, LauncherTheme.dp(activity, 8), 0, 0);
        TextView cancel = dialogText(activity.getString(R.string.social_action_cancel), 13, R.color.launcher_text_color);
        LauncherTheme.secondaryButton(cancel);
        cancel.setOnClickListener(view -> dialog.dismiss());
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        TextView save = dialogText(activity.getString(R.string.social_action_save), 13, R.color.launcher_on_primary_color);
        LauncherTheme.primaryButton(save);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        saveLp.setMargins(LauncherTheme.dp(activity, 8), 0, 0, 0);
        buttons.addView(save, saveLp);
        root.addView(buttons, buttonsLp);
        ScrollView dialogScroll = new ScrollView(activity);
        dialogScroll.setFillViewport(true);
        dialogScroll.addView(root);
        // 主题色调统一（LauncherDialogFactory.setContent 同款处理）
        LauncherTheme.applyPrimaryTone(root);
        window.setContentView(dialogScroll);

        baseUrl.setFocusableInTouchMode(true);
        baseUrl.requestFocus();
        baseUrl.postDelayed(() -> {
            if (!dialog.isShowing()) return;
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(baseUrl, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }, 180L);
        baseUrl.postDelayed(() -> {
            if (!dialog.isShowing()) return;
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(baseUrl, android.view.inputmethod.InputMethodManager.SHOW_FORCED);
        }, 420L);

        LauncherAuthBridge.fetchLlmConfig(activity, new LlmConfigCallback() {
            @Override public void onSuccess(LlmConfig config) {
                if (!dialog.isShowing()) return;
                baseUrl.setText(config.baseUrl); apiKey.setText(config.apiKey); model.setText(config.model); temperature.setText(config.temperature);
                loading.setText(R.string.social_default_model_hint);
            }
            @Override public void onError(String message) { if (dialog.isShowing()) loading.setText(activity.getString(R.string.social_read_failed, message)); }
        });
        reset.setOnClickListener(view -> saveLlmConfig(dialog, new LlmConfig(), reset,
                activity.getString(R.string.social_restoring), activity.getString(R.string.social_restore_default)));
        save.setOnClickListener(view -> {
            String baseUrlValue = textOf(baseUrl);
            String baseUrlError = validatePublicBaseUrl(baseUrlValue);
            if (baseUrlError != null) { baseUrl.setError(baseUrlError); return; }
            String temp = textOf(temperature);
            if (!temp.isEmpty()) {
                try {
                    double value = Double.parseDouble(temp);
                    if (value < 0d || value > 2d) throw new NumberFormatException();
                } catch (NumberFormatException error) { temperature.setError(activity.getString(R.string.social_temperature_error)); return; }
            }
            LlmConfig config = new LlmConfig();
            config.baseUrl = baseUrlValue; config.apiKey = textOf(apiKey); config.model = textOf(model); config.temperature = temp;
            saveLlmConfig(dialog, config, save, activity.getString(R.string.social_validating_and_saving),
                    activity.getString(R.string.social_action_save));
        });
    }

    private EditText llmInput(LinearLayout root, String label, String hintText, int inputType) {
        TextView labelView = dialogText(label, 12, R.color.launcher_text_color);
        labelView.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelLp.setMargins(0, LauncherTheme.dp(activity, 10), 0, 0);
        root.addView(labelView, labelLp);
        EditText field = new LauncherEditText(activity);
        field.setSingleLine(true);
        field.setTextSize(12);
        field.setInputType(inputType);
        field.setHint(hintText);
        field.setTextColor(ContextCompat.getColor(activity, R.color.launcher_text_color));
        field.setHintTextColor(ContextCompat.getColor(activity, R.color.launcher_input_hint_color));
        field.setBackgroundResource(R.drawable.launcher_account_input);
        LauncherTheme.styleTextInput(field);
        field.setPadding(LauncherTheme.dp(activity, 13), 0, LauncherTheme.dp(activity, 13), 0);
        root.addView(field, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LauncherTheme.dp(activity, 38)));
        return field;
    }

    private void saveLlmConfig(Dialog dialog, LlmConfig config, TextView action,
                               String loadingText, String idleText) {
        action.setEnabled(false);
        action.setText(loadingText);
        boolean restoresDefault = (config.baseUrl == null || config.baseUrl.trim().isEmpty())
                && (config.apiKey == null || config.apiKey.trim().isEmpty())
                && (config.model == null || config.model.trim().isEmpty())
                && (config.temperature == null || config.temperature.trim().isEmpty());
        LauncherAuthBridge.updateLlmConfig(activity, config, new LlmConfigCallback() {
            @Override public void onSuccess(LlmConfig saved) {
                if (dialog.isShowing()) dialog.dismiss();
                Toast.makeText(activity,
                        restoresDefault ? R.string.social_default_model_restored
                                : R.string.social_model_saved, Toast.LENGTH_SHORT).show();
                if (restoresDefault) {
                    if (onResetConfig != null) onResetConfig.run();
                } else {
                    if (onSaveConfig != null) onSaveConfig.run();
                }
            }
            @Override public void onError(String message) {
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    action.setEnabled(true);
                    action.setText(idleText);
                    Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    /** Client-side UX guard only; the server remains the authoritative URL security boundary. */
    private String validatePublicBaseUrl(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try {
            java.net.URI uri = new java.net.URI(value.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return activity.getString(R.string.social_error_http_only);
            if (uri.getUserInfo() != null) return activity.getString(R.string.social_error_endpoint_credentials);
            if (host == null || host.trim().isEmpty()) return activity.getString(R.string.social_error_public_endpoint);
            String normalized = host.toLowerCase(Locale.ROOT);
            if (normalized.equals("localhost") || normalized.endsWith(".localhost") || normalized.endsWith(".local")
                    || normalized.equals("0.0.0.0") || normalized.equals("::1") || normalized.startsWith("fe80:")
                    || normalized.startsWith("fc") || normalized.startsWith("fd")) return activity.getString(R.string.social_error_private_endpoint);
            String[] parts = normalized.split("\\.");
            boolean ipv4Literal = parts.length == 4;
            for (String part : parts) if (!part.matches("\\d+")) ipv4Literal = false;
            if (ipv4Literal) {
                int first = Integer.parseInt(parts[0]);
                int second = Integer.parseInt(parts[1]);
                if (first == 10 || first == 127 || first == 0 || first == 169 && second == 254
                        || first == 172 && second >= 16 && second <= 31 || first == 192 && second == 168) {
                    return activity.getString(R.string.social_error_private_endpoint);
                }
            }
            return null;
        } catch (URISyntaxException | NumberFormatException ignored) {
            return activity.getString(R.string.social_error_http_endpoint);
        }
    }

    private TextView dialogText(String text, int size, int colorRes) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setGravity(android.view.Gravity.CENTER);
        view.setTextColor(ContextCompat.getColor(activity, colorRes));
        view.setTextSize(size);
        return view;
    }

    private String textOf(TextView view) { return view.getText() == null ? "" : view.getText().toString().trim(); }
}
