package com.apps;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.yuki.yukihub.R;
import com.yuki.yukihub.launcherbridge.LauncherAiChatBridge;
import com.yuki.yukihub.launcherbridge.LauncherAuthBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared chat surface for the registered persona-based AI characters. */
public class LauncherAiChatActivity extends AppCompatActivity {
    public static final String EXTRA_PERSONA = "persona";
    public static final String EXTRA_THREAD_ID = "thread_id";
    public static final String EXTRA_TITLE = "title";

    private final List<LauncherAiChatBridge.Message> messages = new ArrayList<>();
    private LauncherAiChatMessageAdapter adapter;
    private RecyclerView messageList;
    private EditText input;
    private ImageView send;
    private TextView hint;
    private String persona;
    private String threadId;
    private String characterName;
    private boolean sending;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        applySavedToneMode();
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();
        if (!LauncherAuthBridge.isLoggedIn(this)) {
            Toast.makeText(this, "请先登录后再使用 AI 聊天", Toast.LENGTH_SHORT).show();
            LauncherMotion.finish(this);
            return;
        }
        persona = getIntent().getStringExtra(EXTRA_PERSONA);
        threadId = getIntent().getStringExtra(EXTRA_THREAD_ID);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (persona == null || !persona.matches("persona_[A-Za-z0-9_]+") || threadId == null || !threadId.matches("[A-Za-z0-9_.:-]{1,128}")) {
            Toast.makeText(this, "聊天角色参数无效", Toast.LENGTH_SHORT).show();
            LauncherMotion.finish(this);
            return;
        }
        setContentView(R.layout.activity_launcher_ai_chat);
        applyInsets();
        characterName = title == null || title.trim().isEmpty() ? "AI" : title.replace("（AI）", "");
        ((TextView) findViewById(R.id.aiChatTitle)).setText(title == null || title.trim().isEmpty() ? "AI 聊天" : title);
        hint = findViewById(R.id.aiChatHint);
        input = findViewById(R.id.aiChatInput);
        send = findViewById(R.id.aiChatSend);
        messageList = findViewById(R.id.aiChatMessages);
        adapter = new LauncherAiChatMessageAdapter(messages, characterName);
        messageList.setLayoutManager(new LinearLayoutManager(this));
        messageList.setAdapter(adapter);
        LauncherTheme.applyPrimaryTone(findViewById(R.id.aiChatRoot));
        LauncherTheme.primaryButton((TextView) findViewById(R.id.aiChatClear));
        LauncherTheme.primaryButton((TextView) findViewById(R.id.aiChatCustomModel));
        findViewById(R.id.aiChatCharacterIcon).setBackground(LauncherTheme.circle(this));
        send.setImageTintList(ColorStateList.valueOf(LauncherTheme.primary(this)));
        findViewById(R.id.aiChatClear).setOnClickListener(view -> showClearConfirmDialog());
        findViewById(R.id.aiChatCustomModel).setOnClickListener(view -> showCustomLlmDialog());
        send.setOnClickListener(view -> sendMessage());
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderInputState(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        renderInputState();
        loadHistory();
        LauncherMotion.applyActivityOpen(this);
    }

    private void loadHistory() {
        hint.setText("加载聊天记录中…");
        LauncherAiChatBridge.loadHistory(this, threadId, new LauncherAiChatBridge.HistoryCallback() {
            @Override public void onSuccess(List<LauncherAiChatBridge.Message> loaded) {
                if (isFinishing()) return;
                messages.clear();
                for (LauncherAiChatBridge.Message item : loaded) {
                    if ("user".equals(item.role) || "assistant".equals(item.role) || "tool".equals(item.role)) messages.add(item);
                }
                adapter.notifyDataSetChanged();
                hint.setText(messages.isEmpty() ? "开始和我聊天吧" : "历史消息已加载");
                scrollToEnd();
            }
            @Override public void onError(String message) { if (!isFinishing()) { hint.setText("聊天记录加载失败"); showError(message); } }
        });
    }

    private void sendMessage() {
        String text = input.getText() == null ? "" : input.getText().toString().trim();
        int length = weightedLength(text);
        if (sending || text.isEmpty() || length > 100) return;
        sending = true;
        input.setText("");
        messages.add(new LauncherAiChatBridge.Message("user", text, ""));
        adapter.notifyItemInserted(messages.size() - 1);
        scrollToEnd();
        renderInputState();
        hint.setText("正在回复…");
        LauncherAiChatBridge.send(this, text, persona, threadId, new LauncherAiChatBridge.ReplyCallback() {
            @Override public void onSuccess(String reply) {
                if (isFinishing()) return;
                sending = false;
                messages.add(new LauncherAiChatBridge.Message("assistant", reply, ""));
                adapter.notifyItemInserted(messages.size() - 1);
                hint.setText("AI 回复完成");
                scrollToEnd();
                renderInputState();
            }
            @Override public void onError(String message) {
                if (isFinishing()) return;
                sending = false;
                hint.setText("回复失败，请重试");
                renderInputState();
                showError(message);
            }
        });
    }

    private void renderInputState() {
        int length = weightedLength(input.getText() == null ? "" : input.getText().toString());
        send.setEnabled(!sending && length > 0 && length <= 100);
        send.setAlpha(send.isEnabled() ? 1f : .45f);
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
        TextView title = dialogText("自定义 LLM 模型", 16, R.color.launcher_text_color);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);
        TextView info = dialogText("留空的字段将使用系统默认配置。保存后仅影响你的 AI 聊天。", 11, R.color.launcher_text_muted_color);
        info.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        infoLp.setMargins(0, dp(9), 0, 0);
        root.addView(info, infoLp);
        EditText baseUrl = llmInput(root, "接口地址", "https://api.example.com/v1", android.text.InputType.TYPE_TEXT_VARIATION_URI);
        EditText apiKey = llmInput(root, "API Key", "留空则使用系统默认", android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText model = llmInput(root, "模型名称", "例如 gpt-4o-mini", android.text.InputType.TYPE_CLASS_TEXT);
        EditText temperature = llmInput(root, "温度", "0.0 - 2.0，可留空", android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        TextView loading = dialogText("正在读取当前配置…", 11, R.color.launcher_text_muted_color);
        LinearLayout.LayoutParams loadingLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        loadingLp.setMargins(0, dp(8), 0, 0);
        root.addView(loading, loadingLp);
        TextView reset = dialogText("恢复系统默认", 12, R.color.launcher_text_color);
        LauncherTheme.secondaryButton(reset);
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34));
        resetLp.setMargins(0, dp(11), 0, 0);
        root.addView(reset, resetLp);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        buttonsLp.setMargins(0, dp(8), 0, 0);
        TextView cancel = dialogText("取消", 13, R.color.launcher_text_color);
        LauncherTheme.secondaryButton(cancel);
        cancel.setOnClickListener(view -> dialog.dismiss());
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        TextView save = dialogText("保存", 13, R.color.launcher_on_primary_color);
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

        LauncherAuthBridge.fetchLlmConfig(this, new LauncherAuthBridge.LlmConfigCallback() {
            @Override public void onSuccess(LauncherAuthBridge.LlmConfig config) {
                if (!dialog.isShowing()) return;
                baseUrl.setText(config.baseUrl); apiKey.setText(config.apiKey); model.setText(config.model); temperature.setText(config.temperature);
                loading.setText("留空即回退到系统默认模型");
            }
            @Override public void onError(String message) { if (dialog.isShowing()) loading.setText("读取失败：" + message); }
        });
        reset.setOnClickListener(view -> saveLlmConfig(dialog, new LauncherAuthBridge.LlmConfig(), reset, "恢复中...", "恢复系统默认"));
        save.setOnClickListener(view -> {
            String baseUrlValue = textOf(baseUrl);
            String baseUrlError = validatePublicBaseUrl(baseUrlValue);
            if (baseUrlError != null) { baseUrl.setError(baseUrlError); return; }
            String temp = textOf(temperature);
            if (!temp.isEmpty()) {
                try {
                    double value = Double.parseDouble(temp);
                    if (value < 0d || value > 2d) throw new NumberFormatException();
                } catch (NumberFormatException error) { temperature.setError("温度需在 0.0 到 2.0 之间"); return; }
            }
            LauncherAuthBridge.LlmConfig config = new LauncherAuthBridge.LlmConfig();
            config.baseUrl = baseUrlValue; config.apiKey = textOf(apiKey); config.model = textOf(model); config.temperature = temp;
            saveLlmConfig(dialog, config, save, "验证并保存中...", "保存");
        });
    }

    private EditText llmInput(LinearLayout root, String label, String hintText, int inputType) {
        TextView labelView = dialogText(label, 12, R.color.launcher_text_color);
        labelView.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelLp.setMargins(0, dp(10), 0, 0);
        root.addView(labelView, labelLp);
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setTextSize(12);
        field.setInputType(inputType);
        field.setHint(hintText);
        field.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_color));
        field.setHintTextColor(ContextCompat.getColor(this, R.color.launcher_input_hint_color));
        field.setBackgroundResource(R.drawable.launcher_account_input);
        field.setPadding(dp(13), 0, dp(13), 0);
        root.addView(field, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)));
        return field;
    }

    private void saveLlmConfig(android.app.Dialog dialog, LauncherAuthBridge.LlmConfig config, TextView action,
                               String loadingText, String idleText) {
        action.setEnabled(false);
        action.setText(loadingText);
        boolean restoresDefault = (config.baseUrl == null || config.baseUrl.trim().isEmpty())
                && (config.apiKey == null || config.apiKey.trim().isEmpty())
                && (config.model == null || config.model.trim().isEmpty())
                && (config.temperature == null || config.temperature.trim().isEmpty());
        LauncherAuthBridge.updateLlmConfig(this, config, new LauncherAuthBridge.LlmConfigCallback() {
            @Override public void onSuccess(LauncherAuthBridge.LlmConfig saved) {
                if (dialog.isShowing()) dialog.dismiss();
                Toast.makeText(LauncherAiChatActivity.this,
                        restoresDefault ? "已恢复系统默认模型" : "模型连通性验证通过，配置已保存", Toast.LENGTH_SHORT).show();
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
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return "仅支持 http 或 https 地址";
            if (uri.getUserInfo() != null) return "接口地址不能包含账号或密码";
            if (host == null || host.trim().isEmpty()) return "请输入有效的公网接口地址";
            String normalized = host.toLowerCase(Locale.ROOT);
            if (normalized.equals("localhost") || normalized.endsWith(".localhost") || normalized.endsWith(".local")
                    || normalized.equals("0.0.0.0") || normalized.equals("::1") || normalized.startsWith("fe80:")
                    || normalized.startsWith("fc") || normalized.startsWith("fd")) return "不支持本机或内网接口地址";
            String[] parts = normalized.split("\\.");
            boolean ipv4Literal = parts.length == 4;
            for (String part : parts) if (!part.matches("\\d+")) ipv4Literal = false;
            if (ipv4Literal) {
                int first = Integer.parseInt(parts[0]);
                int second = Integer.parseInt(parts[1]);
                if (first == 10 || first == 127 || first == 0 || first == 169 && second == 254
                        || first == 172 && second >= 16 && second <= 31 || first == 192 && second == 168) {
                    return "不支持本机或内网接口地址";
                }
            }
            return null;
        } catch (Throwable ignored) {
            return "请输入有效的公网 http/https 接口地址";
        }
    }

    private void showClearConfirmDialog() {
        AlertDialog dialog = new AlertDialog.Builder(this).create();
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(270), WindowManager.LayoutParams.WRAP_CONTENT);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(20), dp(22), dp(16));
        root.setBackgroundResource(R.drawable.launcher_dialog_bg);
        TextView title = dialogText("清空聊天记录", 16, R.color.launcher_text_color);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);
        TextView message = dialogText("将清除该角色的全部聊天记录，此操作无法撤销。", 12, R.color.launcher_text_muted_color);
        LinearLayout.LayoutParams messageLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        messageLp.setMargins(0, dp(13), 0, 0);
        root.addView(message, messageLp);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        buttonsLp.setMargins(0, dp(14), 0, 0);
        TextView cancel = dialogText("取消", 13, R.color.launcher_text_color);
        LauncherTheme.secondaryButton(cancel);
        cancel.setOnClickListener(view -> dialog.dismiss());
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        TextView confirm = dialogText("清空", 13, R.color.launcher_on_primary_color);
        LauncherTheme.dangerButton(confirm);
        confirm.setOnClickListener(view -> {
            dialog.dismiss();
            LauncherAiChatBridge.clearHistory(this, threadId, new LauncherAiChatBridge.Callback() {
                @Override public void onSuccess() { if (!isFinishing()) { messages.clear(); adapter.notifyDataSetChanged(); hint.setText("聊天记录已清空"); } }
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

    private void scrollToEnd() { if (!messages.isEmpty()) messageList.scrollToPosition(messages.size() - 1); }
    private void showError(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private void applyInsets() { View root = findViewById(R.id.aiChatRoot); root.setOnApplyWindowInsetsListener((view, insets) -> { view.setPadding(view.getPaddingLeft(), insets.getSystemWindowInsetTop(), view.getPaddingRight(), insets.getSystemWindowInsetBottom()); return insets; }); root.requestApplyInsets(); }
    private void configureEdgeToEdgeWindow() { boolean dark = LauncherActivity.isLauncherDarkMode(this); Window window = getWindow(); window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN); window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS); window.setStatusBarColor(Color.TRANSPARENT); window.setNavigationBarColor(ContextCompat.getColor(this, R.color.launcher_bg_color)); int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN; if (!dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR; window.getDecorView().setSystemUiVisibility(flags); }
    private void applySavedToneMode() { LauncherActivity.applySavedToneMode(this); }
    @Override protected void attachBaseContext(android.content.Context context) { super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(context)); }
    @Override public void onBackPressed() { LauncherMotion.finish(this); }
}
