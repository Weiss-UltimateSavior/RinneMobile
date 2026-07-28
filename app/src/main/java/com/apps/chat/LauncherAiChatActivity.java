package com.apps.chat;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.core.R;
import com.core.databinding.ActivityLauncherAiChatBinding;
import com.core.launcherbridge.LauncherAiChatBridge;
import com.core.launcherbridge.LauncherAuthBridge;
import com.core.launcherbridge.LlmConfigCallback;
import com.core.launcherbridge.LlmConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.apps.LauncherActivity;
import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherTabletPortraitScaler;

/** Shared chat surface for the registered persona-based AI characters. */
public class LauncherAiChatActivity extends AppCompatActivity {
    public static final String EXTRA_PERSONA = "persona";
    public static final String EXTRA_THREAD_ID = "thread_id";
    public static final String EXTRA_TITLE = "title";

    private final List<LauncherAiChatBridge.Message> messages = new ArrayList<>();
    private LauncherAiChatMessageAdapter adapter;
    private ActivityLauncherAiChatBinding binding;
    private int messageListBaseBottomPadding;
    private String persona;
    private String threadId;
    private String characterName;
    private boolean sending;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        applySavedToneMode();
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();
        if (!LauncherAuthBridge.isLoggedIn(this)) {
            Toast.makeText(this, R.string.social_ai_login_required, Toast.LENGTH_SHORT).show();
            LauncherMotion.finish(this);
            return;
        }
        persona = getIntent().getStringExtra(EXTRA_PERSONA);
        threadId = getIntent().getStringExtra(EXTRA_THREAD_ID);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (persona == null || !persona.matches("persona_[A-Za-z0-9_]+") || threadId == null || !threadId.matches("[A-Za-z0-9_.:-]{1,128}")) {
            Toast.makeText(this, R.string.social_invalid_chat_character, Toast.LENGTH_SHORT).show();
            LauncherMotion.finish(this);
            return;
        }
        binding = ActivityLauncherAiChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        LauncherTabletPortraitScaler.applyActivityContent(this);
        characterName = title == null || title.trim().isEmpty() ? "AI" : title.replace("（AI）", "");
        binding.aiChatTitle.setText(title == null || title.trim().isEmpty()
                ? getString(R.string.social_ai_chat) : title);
        messageListBaseBottomPadding = binding.aiChatMessages.getPaddingBottom();
        binding.aiChatTopOverlay.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> updateMessageListOverlayPadding());
        binding.aiChatTitleBar.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> updateMessageListOverlayPadding());
        binding.aiChatComposerOverlay.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> updateMessageListOverlayPadding());
        applyInsets();
        adapter = new LauncherAiChatMessageAdapter(messages, characterName);
        binding.aiChatMessages.setLayoutManager(new LinearLayoutManager(this));
        binding.aiChatMessages.setAdapter(adapter);
        LauncherTheme.applyPrimaryTone(binding.aiChatRoot);
        binding.aiChatTitleBar.setBackground(LauncherTheme.primaryButton(this, 0f));
        binding.aiChatTitle.setTextColor(LauncherTheme.onPrimary(this));
        binding.aiChatMore.setTextColor(LauncherTheme.onPrimary(this));
        binding.aiChatInputThemeBar.setBackground(LauncherTheme.primaryButton(this, 0f));
        binding.aiChatInput.setTextColor(LauncherTheme.text(this));
        binding.aiChatInput.setHintTextColor(LauncherTheme.textMuted(this));
        binding.aiChatCharacterIcon.setBackground(LauncherTheme.circle(this));
        binding.aiChatSend.setImageTintList(ColorStateList.valueOf(LauncherTheme.primary(this)));
        binding.aiChatMore.setOnClickListener(this::showMoreMenu);
        binding.aiChatSend.setOnClickListener(view -> sendMessage());
        binding.aiChatInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderInputState(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        renderInputState();
        loadHistory();
        LauncherMotion.applyActivityOpen(this);
    }

    private void showMoreMenu(View anchor) {
        if (anchor == null) return;
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setBackgroundResource(R.drawable.launcher_white_card);
        menu.setPadding(dp(7), dp(7), dp(7), dp(7));

        PopupWindow popupWindow = new PopupWindow(menu, dp(119), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popupWindow.setOutsideTouchable(true);
        popupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popupWindow.setAnimationStyle(R.style.LauncherDialogAnimation);

        addMoreMenuItem(menu, getString(R.string.social_custom_model), popupWindow, this::showCustomLlmDialog);
        addMoreMenuItem(menu, getString(R.string.social_clear_history), popupWindow, this::showClearConfirmDialog);
        popupWindow.showAsDropDown(anchor, anchor.getWidth() - dp(119), dp(5), android.view.Gravity.NO_GRAVITY);
    }

    private void addMoreMenuItem(LinearLayout menu, String label, PopupWindow popupWindow, Runnable action) {
        TextView item = new TextView(this);
        item.setText(label);
        item.setTextSize(13);
        item.setTypeface(null, android.graphics.Typeface.BOLD);
        item.setGravity(android.view.Gravity.CENTER);
        item.setSingleLine(true);
        item.setPadding(dp(13), 0, dp(13), 0);
        item.setTextColor(LauncherTheme.primary(this));
        item.setBackgroundColor(Color.TRANSPARENT);
        item.setOnClickListener(view -> {
            popupWindow.dismiss();
            action.run();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34));
        params.setMargins(0, 0, 0, dp(5));
        menu.addView(item, params);
    }

    private void loadHistory() {
        binding.aiChatHint.setText(R.string.social_loading_chat_history);
        LauncherAiChatBridge.loadHistory(this, threadId, new LauncherAiChatBridge.HistoryCallback() {
            @Override public void onSuccess(List<LauncherAiChatBridge.Message> loaded) {
                if (isFinishing()) return;
                messages.clear();
                for (LauncherAiChatBridge.Message item : loaded) {
                    if ("user".equals(item.role) || "assistant".equals(item.role) || "tool".equals(item.role)) messages.add(item);
                }
                adapter.notifyDataSetChanged();
                binding.aiChatHint.setText(messages.isEmpty()
                        ? R.string.social_start_chatting : R.string.social_history_loaded);
                scrollToEnd();
            }
            @Override public void onError(String message) { if (!isFinishing()) { binding.aiChatHint.setText(R.string.social_history_load_failed); showError(message); } }
        });
    }

    private void sendMessage() {
        String text = binding.aiChatInput.getText() == null ? "" : binding.aiChatInput.getText().toString().trim();
        int length = weightedLength(text);
        if (sending || text.isEmpty() || length > 100) return;
        sending = true;
        binding.aiChatInput.setText("");
        messages.add(new LauncherAiChatBridge.Message("user", text, ""));
        adapter.notifyItemInserted(messages.size() - 1);
        scrollToEnd();
        renderInputState();
        binding.aiChatHint.setText(R.string.social_replying);
        LauncherAiChatBridge.send(this, text, persona, threadId, new LauncherAiChatBridge.ReplyCallback() {
            @Override public void onSuccess(String reply) {
                if (isFinishing()) return;
                sending = false;
                messages.add(new LauncherAiChatBridge.Message("assistant", reply, ""));
                adapter.notifyItemInserted(messages.size() - 1);
                binding.aiChatHint.setText(R.string.social_reply_complete);
                scrollToEnd();
                renderInputState();
            }
            @Override public void onError(String message) {
                if (isFinishing()) return;
                sending = false;
                binding.aiChatHint.setText(R.string.social_reply_failed);
                renderInputState();
                showError(message);
            }
        });
    }

    private void renderInputState() {
        int length = weightedLength(binding.aiChatInput.getText() == null ? "" : binding.aiChatInput.getText().toString());
        binding.aiChatSend.setEnabled(!sending && length > 0 && length <= 100);
        binding.aiChatSend.setAlpha(binding.aiChatSend.isEnabled() ? 1f : .45f);
    }

    private void showCustomLlmDialog() {
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(288), WindowManager.LayoutParams.WRAP_CONTENT);
        window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(18), dp(22), dp(15));
        root.setBackgroundResource(R.drawable.launcher_dialog_bg);
        TextView title = dialogText(getString(R.string.social_custom_llm_title), 16, R.color.launcher_text_color);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);
        TextView info = dialogText(getString(R.string.social_custom_llm_note), 11, R.color.launcher_text_muted_color);
        info.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        infoLp.setMargins(0, dp(9), 0, 0);
        root.addView(info, infoLp);
        EditText baseUrl = llmInput(root, getString(R.string.social_api_endpoint), "https://api.example.com/v1", android.text.InputType.TYPE_TEXT_VARIATION_URI);
        EditText apiKey = llmInput(root, getString(R.string.social_api_key), getString(R.string.social_api_key_default_hint), android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText model = llmInput(root, getString(R.string.social_model_name), getString(R.string.social_model_example), android.text.InputType.TYPE_CLASS_TEXT);
        EditText temperature = llmInput(root, getString(R.string.social_temperature), getString(R.string.social_temperature_hint), android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        TextView loading = dialogText(getString(R.string.social_reading_config), 11, R.color.launcher_text_muted_color);
        LinearLayout.LayoutParams loadingLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        loadingLp.setMargins(0, dp(8), 0, 0);
        root.addView(loading, loadingLp);
        TextView reset = dialogText(getString(R.string.social_restore_default), 12, R.color.launcher_text_color);
        LauncherTheme.secondaryButton(reset);
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34));
        resetLp.setMargins(0, dp(11), 0, 0);
        root.addView(reset, resetLp);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        buttonsLp.setMargins(0, dp(8), 0, 0);
        TextView cancel = dialogText(getString(R.string.social_action_cancel), 13, R.color.launcher_text_color);
        LauncherTheme.secondaryButton(cancel);
        cancel.setOnClickListener(view -> dialog.dismiss());
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        TextView save = dialogText(getString(R.string.social_action_save), 13, R.color.launcher_on_primary_color);
        LauncherTheme.primaryButton(save);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        saveLp.setMargins(dp(8), 0, 0, 0);
        buttons.addView(save, saveLp);
        root.addView(buttons, buttonsLp);
        android.widget.ScrollView dialogScroll = new android.widget.ScrollView(this);
        dialogScroll.setFillViewport(true);
        dialogScroll.addView(root);
        window.setContentView(dialogScroll);

        baseUrl.setFocusableInTouchMode(true);
        baseUrl.requestFocus();
        baseUrl.postDelayed(() -> {
            if (!dialog.isShowing()) return;
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(baseUrl, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }, 180L);
        baseUrl.postDelayed(() -> {
            if (!dialog.isShowing()) return;
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(baseUrl, android.view.inputmethod.InputMethodManager.SHOW_FORCED);
        }, 420L);

        LauncherAuthBridge.fetchLlmConfig(this, new LlmConfigCallback() {
            @Override public void onSuccess(LlmConfig config) {
                if (!dialog.isShowing()) return;
                baseUrl.setText(config.baseUrl); apiKey.setText(config.apiKey); model.setText(config.model); temperature.setText(config.temperature);
                loading.setText(R.string.social_default_model_hint);
            }
            @Override public void onError(String message) { if (dialog.isShowing()) loading.setText(getString(R.string.social_read_failed, message)); }
        });
        reset.setOnClickListener(view -> saveLlmConfig(dialog, new LlmConfig(), reset,
                getString(R.string.social_restoring), getString(R.string.social_restore_default)));
        save.setOnClickListener(view -> {
            String baseUrlValue = textOf(baseUrl);
            String baseUrlError = validatePublicBaseUrl(baseUrlValue);
            if (baseUrlError != null) { baseUrl.setError(baseUrlError); return; }
            String temp = textOf(temperature);
            if (!temp.isEmpty()) {
                try {
                    double value = Double.parseDouble(temp);
                    if (value < 0d || value > 2d) throw new NumberFormatException();
                } catch (NumberFormatException error) { temperature.setError(getString(R.string.social_temperature_error)); return; }
            }
            LlmConfig config = new LlmConfig();
            config.baseUrl = baseUrlValue; config.apiKey = textOf(apiKey); config.model = textOf(model); config.temperature = temp;
            saveLlmConfig(dialog, config, save, getString(R.string.social_validating_and_saving),
                    getString(R.string.social_action_save));
        });
    }

    private EditText llmInput(LinearLayout root, String label, String hintText, int inputType) {
        TextView labelView = dialogText(label, 12, R.color.launcher_text_color);
        labelView.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelLp.setMargins(0, dp(10), 0, 0);
        root.addView(labelView, labelLp);
        EditText field = new com.apps.widget.LauncherEditText(this);
        field.setSingleLine(true);
        field.setTextSize(12);
        field.setInputType(inputType);
        field.setHint(hintText);
        field.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_color));
        field.setHintTextColor(ContextCompat.getColor(this, R.color.launcher_input_hint_color));
        field.setBackgroundResource(R.drawable.launcher_account_input);
        LauncherTheme.styleTextInput(field);
        field.setPadding(dp(13), 0, dp(13), 0);
        root.addView(field, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)));
        return field;
    }

    private void saveLlmConfig(android.app.Dialog dialog, LlmConfig config, TextView action,
                               String loadingText, String idleText) {
        action.setEnabled(false);
        action.setText(loadingText);
        boolean restoresDefault = (config.baseUrl == null || config.baseUrl.trim().isEmpty())
                && (config.apiKey == null || config.apiKey.trim().isEmpty())
                && (config.model == null || config.model.trim().isEmpty())
                && (config.temperature == null || config.temperature.trim().isEmpty());
        LauncherAuthBridge.updateLlmConfig(this, config, new LlmConfigCallback() {
            @Override public void onSuccess(LlmConfig saved) {
                if (dialog.isShowing()) dialog.dismiss();
                Toast.makeText(LauncherAiChatActivity.this,
                        restoresDefault ? R.string.social_default_model_restored
                                : R.string.social_model_saved, Toast.LENGTH_SHORT).show();
            }
            @Override public void onError(String message) {
                if (!isFinishing()) {
                    action.setEnabled(true);
                    action.setText(idleText);
                    showError(message);
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
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return getString(R.string.social_error_http_only);
            if (uri.getUserInfo() != null) return getString(R.string.social_error_endpoint_credentials);
            if (host == null || host.trim().isEmpty()) return getString(R.string.social_error_public_endpoint);
            String normalized = host.toLowerCase(Locale.ROOT);
            if (normalized.equals("localhost") || normalized.endsWith(".localhost") || normalized.endsWith(".local")
                    || normalized.equals("0.0.0.0") || normalized.equals("::1") || normalized.startsWith("fe80:")
                    || normalized.startsWith("fc") || normalized.startsWith("fd")) return getString(R.string.social_error_private_endpoint);
            String[] parts = normalized.split("\\.");
            boolean ipv4Literal = parts.length == 4;
            for (String part : parts) if (!part.matches("\\d+")) ipv4Literal = false;
            if (ipv4Literal) {
                int first = Integer.parseInt(parts[0]);
                int second = Integer.parseInt(parts[1]);
                if (first == 10 || first == 127 || first == 0 || first == 169 && second == 254
                        || first == 172 && second >= 16 && second <= 31 || first == 192 && second == 168) {
                    return getString(R.string.social_error_private_endpoint);
                }
            }
            return null;
        } catch (Throwable ignored) {
            return getString(R.string.social_error_http_endpoint);
        }
    }

    private void showClearConfirmDialog() {
        AlertDialog dialog = new AlertDialog.Builder(this).create();
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(252), WindowManager.LayoutParams.WRAP_CONTENT);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(20), dp(22), dp(16));
        root.setBackgroundResource(R.drawable.launcher_dialog_bg);
        TextView title = dialogText(getString(R.string.social_clear_history_title), 16, R.color.launcher_text_color);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);
        TextView message = dialogText(getString(R.string.social_clear_history_message), 12, R.color.launcher_text_muted_color);
        LinearLayout.LayoutParams messageLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        messageLp.setMargins(0, dp(13), 0, 0);
        root.addView(message, messageLp);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        buttonsLp.setMargins(0, dp(14), 0, 0);
        TextView cancel = dialogText(getString(R.string.social_action_cancel), 13, R.color.launcher_text_color);
        LauncherTheme.secondaryButton(cancel);
        cancel.setOnClickListener(view -> dialog.dismiss());
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        TextView confirm = dialogText(getString(R.string.social_action_clear), 13, R.color.launcher_on_primary_color);
        LauncherTheme.dangerButton(confirm);
        confirm.setOnClickListener(view -> {
            dialog.dismiss();
            LauncherAiChatBridge.clearHistory(this, threadId, new LauncherAiChatBridge.Callback() {
                @Override public void onSuccess() { if (!isFinishing()) { messages.clear(); adapter.notifyDataSetChanged(); binding.aiChatHint.setText(R.string.social_history_cleared); } }
                @Override public void onError(String error) { if (!isFinishing()) showError(error); }
            });
        });
        LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        confirmLp.setMargins(dp(8), 0, 0, 0);
        buttons.addView(confirm, confirmLp);
        root.addView(buttons, buttonsLp);
        window.setContentView(root);
    }

    private TextView dialogText(String text, int size, int colorRes) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setGravity(android.view.Gravity.CENTER);
        view.setTextColor(ContextCompat.getColor(this, colorRes));
        view.setTextSize(size);
        return view;
    }

    private String textOf(TextView view) { return view.getText() == null ? "" : view.getText().toString().trim(); }

    private int weightedLength(String value) {
        if (value == null || value.isEmpty()) return 0;
        int halfUnits = 0;
        for (int i = 0; i < value.length(); i++) halfUnits += value.charAt(i) <= 0x7f ? 1 : 2;
        return (halfUnits + 1) / 2;
    }

    private void scrollToEnd() { if (!messages.isEmpty()) binding.aiChatMessages.scrollToPosition(messages.size() - 1); }
    private void showError(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private void applyInsets() {
        View root = binding.aiChatRoot;
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            int topInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
            int systemBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            int imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            setOverlayMargins(binding.aiChatTopOverlay, 0, 0);
            binding.aiChatTitleBar.setPaddingRelative(dp(13), topInset + dp(12), dp(13), dp(15));
            boolean keyboardVisible = imeBottom > systemBottom;
            setOverlayMargins(binding.aiChatComposerOverlay, 0, keyboardVisible ? imeBottom : 0);
            View inputThemeBar = binding.aiChatInputThemeBar;
            inputThemeBar.setPaddingRelative(
                    inputThemeBar.getPaddingStart(),
                    dp(13),
                    inputThemeBar.getPaddingEnd(),
                    keyboardVisible ? dp(14) : systemBottom + dp(14));
            updateMessageListOverlayPadding();
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void setOverlayMargins(View view, int top, int bottom) {
        if (view == null) return;
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
        if (margins.topMargin == top && margins.bottomMargin == bottom) return;
        margins.topMargin = top;
        margins.bottomMargin = bottom;
        view.setLayoutParams(margins);
    }

    private void updateMessageListOverlayPadding() {
        if (binding.aiChatMessages == null || binding.aiChatTopOverlay == null || binding.aiChatComposerOverlay == null) return;
        int listTop = binding.aiChatTopOverlay.getVisibility() == View.GONE
                ? 0
                : Math.max(0, binding.aiChatTopOverlay.getBottom());
        setMessageListTopMargin(listTop);
        int bottomSpace = binding.aiChatComposerOverlay.getVisibility() == View.GONE
                ? 0
                : Math.max(0, binding.aiChatMessages.getBottom() - binding.aiChatComposerOverlay.getTop()) + dp(8);
        binding.aiChatMessages.setPadding(
                binding.aiChatMessages.getPaddingLeft(),
                binding.aiChatMessages.getPaddingTop(),
                binding.aiChatMessages.getPaddingRight(),
                messageListBaseBottomPadding + bottomSpace);
    }

    private void setMessageListTopMargin(int topMargin) {
        ViewGroup.LayoutParams params = binding.aiChatMessages.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
        if (margins.topMargin == topMargin) return;
        margins.topMargin = topMargin;
        binding.aiChatMessages.setLayoutParams(margins);
    }

    private void configureEdgeToEdgeWindow() { boolean dark = LauncherActivity.isLauncherDarkMode(this); Window window = getWindow(); window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN); window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS); window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE); window.setStatusBarColor(Color.TRANSPARENT); window.setNavigationBarColor(ContextCompat.getColor(this, R.color.launcher_bg_color)); int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN; if (ColorUtils.calculateLuminance(LauncherTheme.primary(this)) > 0.5d) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR; if (!dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR; window.getDecorView().setSystemUiVisibility(flags); }
    private void applySavedToneMode() { LauncherActivity.applySavedToneMode(this); }
    @Override protected void attachBaseContext(android.content.Context context) { super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(context)); }
    @Override public void onBackPressed() { LauncherMotion.finish(this); }
}
