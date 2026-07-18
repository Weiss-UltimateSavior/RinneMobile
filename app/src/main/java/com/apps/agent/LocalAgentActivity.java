package com.apps.agent;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
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
import com.yuki.yukihub.R;
import com.yuki.yukihub.databinding.ActivityLocalAgentBinding;
import com.yuki.yukihub.util.AppExecutors;
import com.yuki.yukihub.util.RxMainScheduler;

import java.util.ArrayList;
import java.util.List;

/** Independent local agent surface. It does not use account login, personas or /ai/chat. */
public class LocalAgentActivity extends AppCompatActivity {
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
    private AlertDialog activeApprovalDialog;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherActivity.applySavedToneMode(this);
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();
        binding = ActivityLocalAgentBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
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
        applySolidPrimaryChip(binding.agentQuickInspect);
        applySolidPrimaryChip(binding.agentQuickLocate);
        applySolidPrimaryChip(binding.agentQuickHistory);
        binding.agentInput.setTextColor(LauncherTheme.text(this));
        binding.agentInput.setHintTextColor(LauncherTheme.textMuted(this));
        LauncherTheme.styleTextInput(binding.agentInput);
        binding.agentSend.setBackground(null);
        binding.agentSend.setImageTintList(ColorStateList.valueOf(LauncherTheme.primary(this)));
    }

    private void applySolidPrimaryChip(TextView view) {
        view.setTextColor(LauncherTheme.onPrimary(this));
        view.setBackground(LauncherTheme.solidPrimary(this, 999f));
    }

    private void bindActions() {
        binding.agentSend.setOnClickListener(view -> {
            if (runtime.isRunning()) runtime.cancel(); else send();
        });
        binding.agentQuickInspect.setOnClickListener(view -> showConfigDialog());
        binding.agentStateIcon.setOnClickListener(view -> showAgentSettingsDialog());
        binding.agentQuickLocate.setOnClickListener(view -> showSnapshotHistory());
        binding.agentQuickHistory.setOnClickListener(view -> confirmClearHistory());
        binding.agentTopOverlay.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateListPadding());
        binding.agentComposerOverlay.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateListPadding());
    }

    private void send() {
        if (clearingHistory) return;
        String text = binding.agentInput.getText() == null ? "" : binding.agentInput.getText().toString().trim();
        if (text.isEmpty()) return;
        if (text.length() > 4000) {
            binding.agentInput.setError("单次输入不能超过 4000 个字符");
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
        setWorkbenchStatus("正在规划任务", "分析指令并选择本地工具", "02");
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
                    setWorkbenchStatus("正在执行本地操作", name, "03");
                }
            }
            @Override public void onToolFinished(String name, boolean success) {
                if (!unavailable()) setWorkbenchStatus(success ? "本地操作已完成" : "本地操作未完成",
                        success ? "正在整理结果" : "正在分析失败原因", "04");
            }
            @Override public void onApprovalRequired(LocalAgentRuntime.ApprovalRequest request,
                                                     LocalAgentRuntime.ApprovalResponder responder) {
                if (unavailable()) { responder.resolve(false); return; }
                setWorkbenchStatus("等待人工确认", "确认后才能继续当前任务", "05");
                activeApprovalDialog = LauncherDialogFactory.showLongMessageConfirm(
                        LocalAgentActivity.this, request.title, request.preview, request.confirmText,
                        () -> { activeApprovalDialog = null; responder.resolve(true); },
                        () -> { activeApprovalDialog = null; responder.resolve(false); });
            }
            @Override public void onCriticalWarning(String title, String message) {
                if (!unavailable()) LauncherDialogFactory.showLongMessageConfirm(
                        LocalAgentActivity.this, title, message, "知道了", () -> { }, () -> { });
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
                setWorkbenchStatus("任务已完成", "结果与修改记录已保存在本机", "06");
                updateEmptyState();
            }
            @Override public void onError(String message) {
                if (unavailable()) return;
                dismissApprovalDialog();
                removePendingUiMessages();
                renderRunning(false);
                setWorkbenchStatus("任务未完成", message, "!");
                LauncherDialogFactory.showInfo(LocalAgentActivity.this, "智能体未完成", message);
            }
        });
    }

    private void renderRunning(boolean running) {
        binding.agentInput.setEnabled(historyLoaded && !running && !clearingHistory);
        binding.agentSend.setEnabled(historyLoaded && !clearingHistory);
        binding.agentSend.setAlpha(1f);
        binding.agentSend.setRotation(running ? 45f : 0f);
        binding.agentSend.setContentDescription(running ? "停止任务" : "执行任务");
    }

    private void loadHistory() {
        AppExecutors.runOnIo(() -> {
            List<AgentConversationRepository.Message> loaded = null;
            String loadError = null;
            try { loaded = repository.recent(100); }
            catch (Throwable error) { loadError = error.getMessage() == null ? "本地会话读取失败" : error.getMessage(); }
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
            Toast.makeText(this, "请先停止当前任务", Toast.LENGTH_SHORT).show();
            return;
        }
        LauncherDialogFactory.showConfirm(this, "清空本地会话", "只会删除智能体本地消息，不影响游戏库和角色聊天。",
                "清空", () -> {
                    clearingHistory = true;
                    renderRunning(false);
                    AppExecutors.runOnIo(() -> {
                        try {
                            repository.clear();
                        } catch (Throwable error) {
                            String message = error.getMessage() == null ? "清空本地会话失败" : error.getMessage();
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
            catch (Throwable error) { value = "读取修改记录失败。"; }
            String delivered = value;
            RxMainScheduler.post(() -> {
                if (!unavailable()) LauncherDialogFactory.showLongMessageConfirm(
                        this, "修改记录与快照", delivered, "知道了", () -> { }, () -> { });
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
        TextView title = text("模型 API 配置", 16, true);
        root.addView(title);
        TextView note = text("配置仅保存在本机。对话和必要的游戏信息会发送给所选模型服务。API Key 使用 Android Keystore 加密。", 11, false);
        note.setTextColor(LauncherTheme.textMuted(this));
        LinearLayout.LayoutParams noteLp = wrap(); noteLp.setMargins(0, dp(9), 0, 0); root.addView(note, noteLp);
        EditText baseUrl = input(root, "API 地址", "https://api.example.com/v1",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        baseUrl.setText(config.baseUrl);
        EditText model = input(root, "模型名称", "支持工具调用的模型", InputType.TYPE_CLASS_TEXT);
        model.setText(config.model);
        EditText apiKey = input(root, "API Key", config.hasApiKey ? "已保存；留空保持不变" : "请输入 API Key",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText temperature = input(root, "温度", "0.0 - 2.0", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        temperature.setText(String.valueOf(config.temperature));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = text("取消", 13, true); LauncherTheme.secondaryButton(cancel); cancel.setGravity(Gravity.CENTER);
        TextView save = text("保存", 13, true); LauncherTheme.primaryButton(save); save.setGravity(Gravity.CENTER);
        cancel.setOnClickListener(view -> dialog.dismiss());
        save.setOnClickListener(view -> {
            try {
                float temp = Float.parseFloat(valueOf(temperature));
                boolean replaceKey = !valueOf(apiKey).isEmpty();
                AgentConfigStore.save(this, valueOf(baseUrl), valueOf(model), temp, valueOf(apiKey), replaceKey,
                        config.toolCallLimit, config.taskPlanEnabled, config.permissionMode);
                dialog.dismiss();
                renderConfigState();
                Toast.makeText(this, "智能体模型配置已保存", Toast.LENGTH_SHORT).show();
            } catch (Throwable error) {
                Toast.makeText(this, error.getMessage() == null ? "配置保存失败" : error.getMessage(), Toast.LENGTH_LONG).show();
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
        root.addView(text("智能体执行设置", 16, true));
        TextView note = text("这些设置只控制任务执行方式，不会修改模型 API 配置。", 11, false);
        note.setTextColor(LauncherTheme.textMuted(this));
        LinearLayout.LayoutParams noteLp = wrap(); noteLp.setMargins(0, dp(8), 0, 0); root.addView(note, noteLp);

        EditText toolCallLimit = input(root, "最大工具调用次数", "1 - 50", InputType.TYPE_CLASS_NUMBER);
        toolCallLimit.setText(String.valueOf(config.toolCallLimit));
        EditText contextBudget = input(root, "上下文大小（K 字符）", "16 - 1024", InputType.TYPE_CLASS_NUMBER);
        contextBudget.setText(String.valueOf(config.contextBudgetKb));
        TextView contextNote = text("默认 72K。超过预算或模型服务报告窗口不足时，必须确认压缩后才能继续。", 10, false);
        contextNote.setTextColor(LauncherTheme.textMuted(this));
        LinearLayout.LayoutParams contextNoteLp = wrap(); contextNoteLp.setMargins(0, dp(5), 0, 0);
        root.addView(contextNote, contextNoteLp);

        SwitchCompat taskPlan = settingSwitch("生成任务计划与检查清单", config.taskPlanEnabled);
        LinearLayout.LayoutParams planLp = wrap(); planLp.setMargins(0, dp(10), 0, 0); root.addView(taskPlan, planLp);
        SwitchCompat fullPermission = settingSwitch("完全权限模式", config.isFullPermission());
        LinearLayout.LayoutParams permissionLp = wrap(); permissionLp.setMargins(0, dp(4), 0, 0); root.addView(fullPermission, permissionLp);

        TextView warning = text("开启完全权限后，目录访问、文件修改和 MCP 操作将不再弹窗确认。路径隔离、修改快照、工具白名单与审计仍然启用。", 10, false);
        warning.setTextColor(LauncherTheme.textMuted(this));
        LinearLayout.LayoutParams warningLp = wrap(); warningLp.setMargins(0, dp(5), 0, 0); root.addView(warning, warningLp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = text("取消", 13, true); LauncherTheme.secondaryButton(cancel); cancel.setGravity(Gravity.CENTER);
        TextView save = text("保存", 13, true); LauncherTheme.primaryButton(save); save.setGravity(Gravity.CENTER);
        cancel.setOnClickListener(view -> dialog.dismiss());
        save.setOnClickListener(view -> {
            try {
                int calls = Integer.parseInt(valueOf(toolCallLimit));
                int contextKb = Integer.parseInt(valueOf(contextBudget));
                AgentConfigStore.saveExecutionSettings(this, calls, contextKb,
                        taskPlan.isChecked(), fullPermission.isChecked());
                dialog.dismiss();
                renderConfigState();
                Toast.makeText(this, "智能体执行设置已保存", Toast.LENGTH_SHORT).show();
            } catch (Throwable error) {
                Toast.makeText(this, error.getMessage() == null ? "设置保存失败" : error.getMessage(), Toast.LENGTH_LONG).show();
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
        LauncherTheme.styleSwitch(view);
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
        setWorkbenchStatus(config.isReady() ? "工作台待命" : "需要配置模型",
                config.isReady() ? "当前模型 · " + config.model + " · "
                        + (config.isFullPermission() ? "完全权限" : "受限权限")
                        : "点击右上角设置模型 API", "01");
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
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
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
