package com.apps.agent;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.apps.LauncherActivity;
import com.apps.theme.LauncherDialogFactory;
import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherEditText;
import com.apps.widget.LauncherTabletPortraitScaler;
import com.core.R;
import com.core.agent.runtime.LocalAgentRuntime;
import com.core.agent.store.AgentConfigStore;
import com.core.agent.store.AgentConversationRepository;
import com.core.agent.store.AgentSnapshotStore;
import com.core.databinding.ActivityLocalAgentBinding;
import com.core.util.AppExecutors;
import com.core.util.RxMainScheduler;

import java.util.ArrayList;
import java.util.List;

/** Independent local agent surface. It does not use account login, personas or /ai/chat. */
public class LocalAgentActivity extends AppCompatActivity {
    private static final String TAG = "LocalAgentActivity";
    private ActivityLocalAgentBinding binding;
    private final List<AgentConversationRepository.Message> messages = new ArrayList<>();
    private AgentConversationRepository repository;
    private LocalAgentMessageAdapter adapter;
    private LocalAgentRuntime runtime;
    private AgentConversationRepository.Message streamingMessage;
    private AgentConversationRepository.Message reasoningMessage;
    private AgentConversationRepository.Message pendingUserMessage;
    private final StringBuilder committedReasoning = new StringBuilder();
    private final StringBuilder currentRoundText = new StringBuilder();
    private int baseBottomPadding;
    private boolean historyLoaded;
    private boolean clearingHistory;
    private boolean userTouchedInput;
    private AlertDialog activeApprovalDialog;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherActivity.applySavedToneMode(this);
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();
        binding = ActivityLocalAgentBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        // 防止 EditText 自动获焦弹起键盘：根布局已设 focusable+focusableInTouchMode 抢占焦点。
        // loadHistory 异步回调会让 EditText 从 disabled 切到 enabled，那才是真正触发自动获焦
        // 的时机，由 renderRunning() 中的 clearFocus + hideSoftInput 兜底处理。
        LauncherTabletPortraitScaler.applyActivityContent(this);
        repository = new AgentConversationRepository(this);
        runtime = new LocalAgentRuntime(this);
        adapter = new LocalAgentMessageAdapter(messages);
        binding.agentMessages.setLayoutManager(new LinearLayoutManager(this));
        binding.agentMessages.setAdapter(adapter);
        // Default change animations cross-fade old/new TextViews. Token-rate updates otherwise
        // leave several text layers visible at once and look like content is overlapping.
        binding.agentMessages.setItemAnimator(null);
        binding.agentInput.setEnabled(false);
        binding.agentSend.setEnabled(false);
        baseBottomPadding = binding.agentMessages.getPaddingBottom();
        bindInsets();
        bindTheme();
        bindActions();
        loadHistory();
        renderConfigState();
        LauncherMotion.applyActivityOpen(this);
    }

    private void bindTheme() {
        LauncherTheme.applyPrimaryTone(binding.getRoot());
        binding.agentTitleBar.setBackground(LauncherTheme.solidPrimary(this, 0f));
        binding.agentInfoBar.setBackground(LauncherTheme.secondaryButton(this, 18f));
        binding.agentInputThemeBar.setBackground(LauncherTheme.secondaryButton(this, 22f));
        binding.agentEmptyState.setBackground(null);
        binding.agentStateIcon.setBackground(LauncherTheme.solidPrimary(this, 999f));
        binding.agentStateIcon.setImageTintList(ColorStateList.valueOf(LauncherTheme.onPrimary(this)));
        int primary = LauncherTheme.primary(this);
        binding.agentInput.setTextColor(primary);
        binding.agentInput.setHintTextColor(ContextCompat.getColor(this, com.core.R.color.launcher_text_muted_color));
        LauncherTheme.styleTextInput(binding.agentInput);
        binding.agentSend.setBackground(null);
        binding.agentSend.setImageTintList(ColorStateList.valueOf(primary));
    }

    private void bindActions() {
        binding.agentSend.setOnClickListener(view -> {
            if (runtime.isRunning()) runtime.cancel(); else send();
        });
        binding.agentStateIcon.setOnClickListener(view -> showFeatureMenu());
        binding.agentTopOverlay.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateListPadding());
        binding.agentComposerOverlay.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateListPadding());
        // 用户主动触摸 EditText 时设置标志位，renderRunning 中的 clearFocus + hideSoftInput
        // 逻辑据此跳过，避免与用户已主动唤起的输入状态冲突。
        // 同时主动 requestFocus 并通过 WindowInsetsController 唤起 IME —— edge-to-edge 模式
        // 下 setSoftInputMode 已失效（Android 11+ 弃用），系统自动唤起在某些机型/系统版本
        // 上不可靠（典型复现：Lenovo TB323FU / Android 16）。
        // 不使用 OnFocusChangeListener 主动唤起，避免 IME inset 派发引起焦点抖动。
        binding.agentInput.setOnTouchListener((v, event) -> {
            userTouchedInput = true;
            if (!v.hasFocus()) {
                v.requestFocus();
            }
            showImeExplicit(v);
            return false;
        });
    }

    /** 主动唤起 IME，优先使用 API 30+ 的 WindowInsetsController，低版本回退到 IMM。 */
    private void showImeExplicit(View view) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.view.WindowInsetsController controller = view.getWindowInsetsController();
                if (controller != null) {
                    controller.show(android.view.WindowInsets.Type.ime());
                    return;
                }
            }
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(view, 0);
        } catch (Exception e) { Log.d(TAG, "show IME failed", e); }
    }

    private void send() {
        if (clearingHistory) return;
        String text = binding.agentInput.getText() == null ? "" : binding.agentInput.getText().toString().trim();
        if (text.isEmpty()) return;
        if (text.length() > 4000) {
            binding.agentInput.setError(getString(R.string.social_agent_input_too_long));
            return;
        }
        binding.agentInput.setText("");
        pendingUserMessage = new AgentConversationRepository.Message(-1, "user", text, "", System.currentTimeMillis());
        messages.add(pendingUserMessage);
        reasoningMessage = null;
        committedReasoning.setLength(0);
        currentRoundText.setLength(0);
        streamingMessage = new AgentConversationRepository.Message(-1, "assistant", "", "", System.currentTimeMillis());
        messages.add(streamingMessage);
        adapter.notifyItemRangeInserted(messages.size() - 2, 2);
        updateEmptyState();
        scrollToEnd();
        setWorkbenchStatus(getString(R.string.social_agent_planning),
                getString(R.string.social_agent_planning_detail), "02");
        renderRunning(true);
        runtime.send(text, new LocalAgentRuntime.Callback() {
            @Override public void onTextDelta(String delta) {
                if (unavailable() || streamingMessage == null || delta == null || delta.isEmpty()) return;
                currentRoundText.append(delta);
                renderReasoningMessage();
                scrollToEndIfFollowing();
            }
            @Override public void onReasoningDelta(String delta) {
                if (unavailable() || streamingMessage == null || delta == null || delta.isEmpty()) return;
                committedReasoning.append(delta);
                renderReasoningMessage();
                scrollToEndIfFollowing();
            }
            @Override public void onModelRoundFinished(boolean toolRound) {
                if (unavailable()) return;
                if (toolRound && currentRoundText.length() > 0) {
                    if (committedReasoning.length() > 0) committedReasoning.append("\n\n");
                    committedReasoning.append(currentRoundText);
                }
                currentRoundText.setLength(0);
                renderReasoningMessage();
            }
            @Override public void onToolStarted(String name) {
                if (!unavailable()) {
                    setWorkbenchStatus(getString(R.string.social_agent_executing), name, "03");
                }
            }
            @Override public void onToolFinished(String name, boolean success) {
                if (!unavailable()) setWorkbenchStatus(
                        getString(success ? R.string.social_agent_operation_complete
                                : R.string.social_agent_operation_incomplete),
                        getString(success ? R.string.social_agent_preparing_result
                                : R.string.social_agent_analyzing_failure), "04");
            }
            @Override public void onApprovalRequired(LocalAgentRuntime.ApprovalRequest request,
                                                     LocalAgentRuntime.ApprovalResponder responder) {
                if (unavailable()) { responder.resolve(false); return; }
                setWorkbenchStatus(getString(R.string.social_agent_waiting_confirmation),
                        getString(R.string.social_agent_confirmation_detail), "05");
                activeApprovalDialog = LauncherDialogFactory.showLongMessageConfirm(
                        LocalAgentActivity.this, request.title, request.preview, request.confirmText,
                        () -> { activeApprovalDialog = null; responder.resolve(true); },
                        () -> { activeApprovalDialog = null; responder.resolve(false); });
            }
            @Override public void onCriticalWarning(String title, String message) {
                if (!unavailable()) LauncherDialogFactory.showLongMessageConfirm(
                        LocalAgentActivity.this, title, message,
                        getString(R.string.social_action_got_it), () -> { }, () -> { });
            }
            @Override public void onComplete(String finalText) {
                if (unavailable()) return;
                dismissApprovalDialog();
                if (streamingMessage != null) {
                    streamingMessage.content = finalText;
                    int index = messages.indexOf(streamingMessage);
                    if (index >= 0) adapter.notifyItemChanged(index);
                }
                if (reasoningMessage != null) {
                    reasoningMessage.name = "complete";
                    int reasoningIndex = messages.indexOf(reasoningMessage);
                    if (reasoningIndex >= 0) adapter.notifyItemChanged(reasoningIndex);
                }
                streamingMessage = null;
                reasoningMessage = null;
                pendingUserMessage = null;
                renderRunning(false);
                setWorkbenchStatus(getString(R.string.social_agent_task_complete),
                        getString(R.string.social_agent_task_complete_detail), "06");
                updateEmptyState();
            }
            @Override public void onError(String message) {
                if (unavailable()) return;
                dismissApprovalDialog();
                removePendingUiMessages();
                renderRunning(false);
                setWorkbenchStatus(getString(R.string.social_agent_task_incomplete), message, "!");
                LauncherDialogFactory.showInfo(LocalAgentActivity.this,
                        getString(R.string.social_agent_incomplete_title), message);
            }
        });
    }

    private void renderRunning(boolean running) {
        boolean inputEnabled = historyLoaded && !running && !clearingHistory;
        binding.agentInput.setEnabled(inputEnabled);
        binding.agentSend.setEnabled(historyLoaded && !clearingHistory);
        binding.agentSend.setAlpha(1f);
        binding.agentSend.setRotation(running ? 45f : 0f);
        binding.agentSend.setContentDescription(getString(running
                ? R.string.social_agent_stop_task : R.string.social_agent_run_task));
        // loadHistory 异步回调中 setEnabled(true) 会触发 EditText 自动获焦并弹起 IME。
        // 用 userTouchedInput 区分"用户主动点击"与"系统自动获焦"——只有用户未主动操作时
        // 才清除焦点并隐藏 IME，避免影响用户已主动唤起的输入状态。
        if (inputEnabled && !userTouchedInput) {
            binding.agentInput.clearFocus();
            View rootView = binding.getRoot();
            if (rootView != null) rootView.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && imm.isAcceptingText()) {
                imm.hideSoftInputFromWindow(binding.agentInput.getWindowToken(), 0);
            }
        }
    }

    private void loadHistory() {
        AppExecutors.runOnIo(() -> {
            List<AgentConversationRepository.Message> loaded = null;
            String loadError = null;
            try { loaded = repository.recent(100); }
            catch (Throwable error) { loadError = error.getMessage() == null
                    ? getString(R.string.social_agent_session_load_failed) : error.getMessage(); }
            List<AgentConversationRepository.Message> delivered = loaded;
            String deliveredError = loadError;
            RxMainScheduler.post(() -> {
                if (isFinishing() || binding == null) return;
                if (delivered != null) {
                    messages.clear();
                    messages.addAll(delivered);
                    adapter.notifyDataSetChanged();
                }
                historyLoaded = true;
                clearingHistory = false;
                renderRunning(runtime.isRunning());
                scrollToEnd();
                updateEmptyState();
                if (!runtime.isRunning()) renderConfigState();
                if (deliveredError != null) Toast.makeText(this, deliveredError, Toast.LENGTH_LONG).show();
            });
        });
    }

    private void confirmClearHistory() {
        if (runtime.isRunning()) {
            Toast.makeText(this, R.string.social_agent_stop_first, Toast.LENGTH_SHORT).show();
            return;
        }
        LauncherDialogFactory.showConfirm(this, getString(R.string.social_agent_clear_session),
                getString(R.string.social_agent_clear_session_message),
                getString(R.string.social_action_clear), () -> {
                    clearingHistory = true;
                    renderRunning(false);
                    AppExecutors.runOnIo(() -> {
                        try {
                            repository.clear();
                        } catch (Throwable error) {
                            String message = error.getMessage() == null
                                    ? getString(R.string.social_agent_clear_failed) : error.getMessage();
                            RxMainScheduler.post(() -> {
                                if (!unavailable()) Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                            });
                        } finally {
                            RxMainScheduler.post(this::loadHistory);
                        }
                    });
                });
    }

    private void showSnapshotHistory() {
        AppExecutors.runOnIo(() -> {
            String value;
            try { value = AgentSnapshotStore.recentDisplay(this, 20); }
            catch (Throwable error) { value = getString(R.string.social_agent_read_mutations_failed); }
            String delivered = value;
            RxMainScheduler.post(() -> {
                if (!unavailable()) LauncherDialogFactory.showLongMessageConfirm(
                        this, getString(R.string.social_agent_mutations_title), delivered,
                        getString(R.string.social_action_got_it), () -> { }, () -> { });
            });
        });
    }

    private void showConfigDialog() {
        AgentConfigStore.Config config = AgentConfigStore.get(this);
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(18), dp(22), dp(15));
        root.setBackgroundResource(R.drawable.launcher_dialog_bg);
        TextView title = text(getString(R.string.social_agent_api_config), 16, true);
        root.addView(title);
        TextView note = text(getString(R.string.social_agent_api_note), 11, false);
        note.setTextColor(LauncherTheme.textMuted(this));
        LinearLayout.LayoutParams noteLp = wrap(); noteLp.setMargins(0, dp(9), 0, 0); root.addView(note, noteLp);
        EditText baseUrl = input(root, getString(R.string.social_agent_api_address), "https://api.example.com/v1",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        baseUrl.setText(config.baseUrl);
        EditText model = input(root, getString(R.string.social_model_name),
                getString(R.string.social_agent_model_support_hint), InputType.TYPE_CLASS_TEXT);
        model.setText(config.model);
        EditText apiKey = input(root, getString(R.string.social_api_key),
                config.hasApiKey ? getString(R.string.social_agent_api_key_saved_hint)
                        : getString(R.string.social_agent_api_key_hint),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText temperature = input(root, getString(R.string.social_temperature), "0.0 - 2.0",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        temperature.setText(String.valueOf(config.temperature));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = text(getString(R.string.social_action_cancel), 13, true); LauncherTheme.secondaryButton(cancel); cancel.setGravity(Gravity.CENTER);
        TextView save = text(getString(R.string.social_action_save), 13, true); LauncherTheme.primaryButton(save); save.setGravity(Gravity.CENTER);
        cancel.setOnClickListener(view -> dialog.dismiss());
        save.setOnClickListener(view -> {
            try {
                float temp = Float.parseFloat(valueOf(temperature));
                boolean replaceKey = !valueOf(apiKey).isEmpty();
                AgentConfigStore.save(this, valueOf(baseUrl), valueOf(model), temp, valueOf(apiKey), replaceKey,
                        config.toolCallLimit, config.taskPlanEnabled, config.permissionMode);
                dialog.dismiss();
                renderConfigState();
                Toast.makeText(this, R.string.social_agent_config_saved, Toast.LENGTH_SHORT).show();
            } catch (Throwable error) {
                Toast.makeText(this, error.getMessage() == null
                        ? getString(R.string.social_agent_config_save_failed) : error.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, dp(36), 1f));
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0, dp(36), 1f); saveLp.setMargins(dp(8), 0, 0, 0);
        buttons.addView(save, saveLp);
        LinearLayout.LayoutParams buttonsLp = wrap(); buttonsLp.setMargins(0, dp(12), 0, 0); root.addView(buttons, buttonsLp);
        ScrollView scroll = new ScrollView(this); scroll.addView(root); window.setContentView(scroll);
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);
        window.setLayout(dp(288), WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private void showFeatureMenu() {
        String[] items = {getString(R.string.social_agent_api_menu),
                getString(R.string.social_agent_mutation_log),
                getString(R.string.social_agent_clear_menu),
                getString(R.string.social_agent_execution_settings)};
        LauncherDialogFactory.showStandardActionChoices(this,
                getString(R.string.social_agent_features), items, index -> {
            if (index == 0) showConfigDialog();
            else if (index == 1) showSnapshotHistory();
            else if (index == 2) confirmClearHistory();
            else if (index == 3) showAgentSettingsDialog();
        });
    }

    private void showAgentSettingsDialog() {
        AgentConfigStore.Config config = AgentConfigStore.get(this);
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(18), dp(22), dp(15));
        root.setBackgroundResource(R.drawable.launcher_dialog_bg);
        root.addView(text(getString(R.string.social_agent_execution_title), 16, true));
        TextView note = text(getString(R.string.social_agent_execution_note), 11, false);
        note.setTextColor(LauncherTheme.textMuted(this));
        LinearLayout.LayoutParams noteLp = wrap(); noteLp.setMargins(0, dp(8), 0, 0); root.addView(note, noteLp);

        EditText toolCallLimit = input(root, getString(R.string.social_agent_tool_limit),
                "1 - 50", InputType.TYPE_CLASS_NUMBER);
        toolCallLimit.setText(String.valueOf(config.toolCallLimit));
        EditText contextBudget = input(root, getString(R.string.social_agent_context_budget),
                "16 - 1024", InputType.TYPE_CLASS_NUMBER);
        contextBudget.setText(String.valueOf(config.contextBudgetKb));
        TextView contextNote = text(getString(R.string.social_agent_context_note), 10, false);
        contextNote.setTextColor(LauncherTheme.textMuted(this));
        LinearLayout.LayoutParams contextNoteLp = wrap(); contextNoteLp.setMargins(0, dp(5), 0, 0);
        root.addView(contextNote, contextNoteLp);

        SwitchCompat taskPlan = settingSwitch(getString(R.string.social_agent_task_plan), config.taskPlanEnabled);
        LinearLayout.LayoutParams planLp = wrap(); planLp.setMargins(0, dp(10), 0, 0); root.addView(taskPlan, planLp);
        SwitchCompat fullPermission = settingSwitch(getString(R.string.social_agent_full_permission), config.isFullPermission());
        LinearLayout.LayoutParams permissionLp = wrap(); permissionLp.setMargins(0, dp(4), 0, 0); root.addView(fullPermission, permissionLp);

        TextView warning = text(getString(R.string.social_agent_permission_warning), 10, false);
        warning.setTextColor(LauncherTheme.textMuted(this));
        LinearLayout.LayoutParams warningLp = wrap(); warningLp.setMargins(0, dp(5), 0, 0); root.addView(warning, warningLp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = text(getString(R.string.social_action_cancel), 13, true); LauncherTheme.secondaryButton(cancel); cancel.setGravity(Gravity.CENTER);
        TextView save = text(getString(R.string.social_action_save), 13, true); LauncherTheme.primaryButton(save); save.setGravity(Gravity.CENTER);
        cancel.setOnClickListener(view -> dialog.dismiss());
        save.setOnClickListener(view -> {
            try {
                int calls = Integer.parseInt(valueOf(toolCallLimit));
                int contextKb = Integer.parseInt(valueOf(contextBudget));
                AgentConfigStore.saveExecutionSettings(this, calls, contextKb,
                        taskPlan.isChecked(), fullPermission.isChecked());
                dialog.dismiss();
                renderConfigState();
                Toast.makeText(this, R.string.social_agent_settings_saved, Toast.LENGTH_SHORT).show();
            } catch (Throwable error) {
                Toast.makeText(this, error.getMessage() == null
                        ? getString(R.string.social_agent_settings_save_failed) : error.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, dp(36), 1f));
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0, dp(36), 1f); saveLp.setMargins(dp(8), 0, 0, 0);
        buttons.addView(save, saveLp);
        LinearLayout.LayoutParams buttonsLp = wrap(); buttonsLp.setMargins(0, dp(12), 0, 0); root.addView(buttons, buttonsLp);

        ScrollView scroll = new ScrollView(this); scroll.addView(root); window.setContentView(scroll);
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);
        window.setLayout(dp(288), WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private SwitchCompat settingSwitch(String label, boolean checked) {
        SwitchCompat view = new SwitchCompat(this);
        view.setText(label); view.setTextSize(12); view.setTextColor(LauncherTheme.text(this));
        view.setGravity(Gravity.CENTER_VERTICAL); view.setChecked(checked);
        LauncherTheme.styleMaterialSwitch(view);
        return view;
    }

    private EditText input(LinearLayout root, String label, String hint, int type) {
        TextView labelView = text(label, 12, true);
        LinearLayout.LayoutParams labelLp = wrap(); labelLp.setMargins(0, dp(10), 0, dp(5)); root.addView(labelView, labelLp);
        EditText input = new LauncherEditText(this);
        input.setSingleLine(true); input.setInputType(type); input.setHint(hint); input.setTextSize(12);
        input.setTextColor(LauncherTheme.text(this)); input.setHintTextColor(LauncherTheme.textMuted(this));
        input.setPadding(dp(13), 0, dp(13), 0); input.setBackground(LauncherTheme.secondaryButton(this, 20f));
        LauncherTheme.styleTextInput(input);
        root.addView(input, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));
        return input;
    }

    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(LauncherTheme.text(this));
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD); return view;
    }

    private void renderConfigState() {
        AgentConfigStore.Config config = AgentConfigStore.get(this);
        setWorkbenchStatus(
                getString(config.isReady() ? R.string.social_agent_ready : R.string.social_agent_need_model),
                config.isReady()
                        ? getString(R.string.social_agent_current_model, config.model,
                                getString(config.isFullPermission()
                                        ? R.string.social_agent_full_permission_label
                                        : R.string.social_agent_restricted_permission_label))
                        : getString(R.string.social_agent_configure_api), "01");
    }

    private void bindInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            int safeLeft = Math.max(bars.left, cutout.left);
            int safeTop = Math.max(bars.top, cutout.top);
            int safeRight = Math.max(bars.right, cutout.right);
            int safeBottom = Math.max(bars.bottom, cutout.bottom);
            boolean keyboardVisible = ime.bottom > safeBottom;

            binding.agentTitleBar.setPaddingRelative(safeLeft + dp(13), safeTop + dp(10),
                    safeRight + dp(13), dp(13));
            setHorizontalMargins(binding.agentInfoBar, safeLeft + dp(16), safeRight + dp(16));
            setHorizontalMargins(binding.agentEmptyState, safeLeft + dp(24), safeRight + dp(24));
            binding.agentComposerOverlay.setPaddingRelative(safeLeft + dp(16), 0,
                    safeRight + dp(16), (keyboardVisible ? 0 : safeBottom) + dp(10));
            setBottomMargin(binding.agentComposerOverlay, keyboardVisible ? ime.bottom : 0);
            binding.agentMessages.setPadding(safeLeft + dp(16), binding.agentMessages.getPaddingTop(),
                    safeRight + dp(16), binding.agentMessages.getPaddingBottom());
            updateListPadding();
            return insets;
        });
        ViewCompat.requestApplyInsets(binding.getRoot());
    }

    private void setHorizontalMargins(View view, int left, int right) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
        if (margins.leftMargin == left && margins.rightMargin == right) return;
        margins.leftMargin = left;
        margins.rightMargin = right;
        view.setLayoutParams(margins);
    }

    private void setBottomMargin(View view, int bottom) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
        if (margins.bottomMargin == bottom) return;
        margins.bottomMargin = bottom;
        view.setLayoutParams(margins);
    }

    private void updateListPadding() {
        if (binding == null) return;
        binding.agentMessages.setPadding(binding.agentMessages.getPaddingLeft(),
                binding.agentTopOverlay.getHeight() + dp(8), binding.agentMessages.getPaddingRight(),
                baseBottomPadding + binding.agentComposerOverlay.getHeight() + dp(8));
    }

    private void scrollToEnd() {
        if (!messages.isEmpty()) binding.agentMessages.scrollToPosition(messages.size() - 1);
    }

    private void scrollToEndIfFollowing() {
        if (messages.isEmpty() || !(binding.agentMessages.getLayoutManager() instanceof LinearLayoutManager)) return;
        LinearLayoutManager manager = (LinearLayoutManager) binding.agentMessages.getLayoutManager();
        if (manager.findLastVisibleItemPosition() >= messages.size() - 2) {
            binding.agentMessages.scrollToPosition(messages.size() - 1);
        }
    }

    private void renderReasoningMessage() {
        if (binding == null || streamingMessage == null) return;
        String combined = committedReasoning.toString();
        if (currentRoundText.length() > 0) {
            if (!combined.isEmpty()) combined += "\n\n";
            combined += currentRoundText;
        }
        if (combined.isEmpty()) {
            if (reasoningMessage != null) {
                int index = messages.indexOf(reasoningMessage);
                if (index >= 0) { messages.remove(index); adapter.notifyItemRemoved(index); }
                reasoningMessage = null;
            }
            return;
        }
        if (reasoningMessage == null) {
            reasoningMessage = new AgentConversationRepository.Message(
                    -1, "reasoning", combined, "streaming", System.currentTimeMillis());
            int answerIndex = messages.indexOf(streamingMessage);
            int insertion = answerIndex < 0 ? messages.size() : answerIndex;
            messages.add(insertion, reasoningMessage);
            adapter.notifyItemInserted(insertion);
        } else {
            reasoningMessage.content = combined;
            int index = messages.indexOf(reasoningMessage);
            if (index >= 0) adapter.notifyItemChanged(index);
        }
    }

    private void setWorkbenchStatus(String title, String hint, String phase) {
        if (binding == null) return;
        binding.agentStateLabel.setText(title);
        binding.agentHint.setText(hint);
    }

    private void updateEmptyState() {
        if (binding != null) binding.agentEmptyState.setVisibility(messages.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void close() { runtime.cancel(); LauncherMotion.finish(this); }
    @Override public void onBackPressed() { close(); }
    @Override protected void onDestroy() {
        dismissApprovalDialog();
        if (runtime != null) runtime.close();
        binding = null;
        super.onDestroy();
    }

    private void configureEdgeToEdgeWindow() {
        boolean darkMode = LauncherActivity.isLauncherDarkMode(this);
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        // edge-to-edge 模式下 setSoftInputMode(SOFT_INPUT_ADJUST_RESIZE) 已失效
        //（Android 11+ 弃用），IME inset 改由 bindInsets() 手动处理。
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(ContextCompat.getColor(this, R.color.launcher_bg_color));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attributes);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        if (ColorUtils.calculateLuminance(LauncherTheme.primary(this)) > 0.5d) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (!darkMode) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private String valueOf(EditText view) { return view.getText() == null ? "" : view.getText().toString().trim(); }
    private boolean unavailable() { return isFinishing() || isDestroyed() || binding == null; }
    private void dismissApprovalDialog() {
        AlertDialog dialog = activeApprovalDialog;
        activeApprovalDialog = null;
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }
    private void removePendingUiMessages() {
        int streamIndex = messages.indexOf(streamingMessage);
        if (streamIndex >= 0) { messages.remove(streamIndex); adapter.notifyItemRemoved(streamIndex); }
        int reasoningIndex = messages.indexOf(reasoningMessage);
        if (reasoningIndex >= 0) { messages.remove(reasoningIndex); adapter.notifyItemRemoved(reasoningIndex); }
        int userIndex = messages.indexOf(pendingUserMessage);
        if (userIndex >= 0) { messages.remove(userIndex); adapter.notifyItemRemoved(userIndex); }
        streamingMessage = null;
        reasoningMessage = null;
        committedReasoning.setLength(0);
        currentRoundText.setLength(0);
        pendingUserMessage = null;
        updateEmptyState();
    }
    private LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase));
    }
}
