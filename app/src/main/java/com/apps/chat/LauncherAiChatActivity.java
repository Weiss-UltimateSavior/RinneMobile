package com.apps.chat;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.core.R;
import com.core.databinding.ActivityLauncherAiChatBinding;
import com.core.launcherbridge.LauncherAiChatBridge;
import com.core.launcherbridge.LauncherAuthBridge;

import java.util.ArrayList;
import java.util.List;
import com.apps.LauncherActivity;
import com.apps.theme.LauncherDialogFactory;
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
        com.apps.LauncherEdgeToEdgeHelper.apply(this, true, true);
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
        characterName = title == null || title.trim().isEmpty() ? getString(R.string.social_ai_chat) : title.replace("（AI）", "");
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
        menu.setPadding(LauncherTheme.dp(this, 7), LauncherTheme.dp(this, 7), LauncherTheme.dp(this, 7), LauncherTheme.dp(this, 7));

        // 菜单宽度带屏幕兜底：小屏设备上不超过屏幕宽度减去两侧 48dp 边距，禁止裸固定 dp 宽度
        int menuWidth = Math.min(LauncherTheme.dp(this, 119),
                getResources().getDisplayMetrics().widthPixels - LauncherTheme.dp(this, 48));
        PopupWindow popupWindow = new PopupWindow(menu, menuWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popupWindow.setOutsideTouchable(true);
        // 纯透明遮罩：PopupWindow 必须设置背景才能拦截外部点击并触发关闭，此处仅作遮罩不参与绘制
        popupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popupWindow.setAnimationStyle(R.style.LauncherDialogAnimation);

        addMoreMenuItem(menu, getString(R.string.social_custom_model), popupWindow, () -> new AgentLlmConfigDialog(this).show());
        addMoreMenuItem(menu, getString(R.string.social_clear_history), popupWindow, this::showClearConfirmDialog);
        popupWindow.showAsDropDown(anchor, anchor.getWidth() - menuWidth, LauncherTheme.dp(this, 5), android.view.Gravity.NO_GRAVITY);
    }

    private void addMoreMenuItem(LinearLayout menu, String label, PopupWindow popupWindow, Runnable action) {
        TextView item = new TextView(this);
        item.setText(label);
        item.setTextSize(13);
        item.setTypeface(null, android.graphics.Typeface.BOLD);
        item.setGravity(android.view.Gravity.CENTER);
        item.setSingleLine(true);
        item.setPadding(LauncherTheme.dp(this, 13), 0, LauncherTheme.dp(this, 13), 0);
        item.setTextColor(LauncherTheme.primary(this));
        // 菜单项透明背景：容器 menu 已绘制 launcher_white_card 圆角卡片，菜单项不再叠加背景
        item.setBackgroundColor(Color.TRANSPARENT);
        item.setOnClickListener(view -> {
            popupWindow.dismiss();
            action.run();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LauncherTheme.dp(this, 34));
        params.setMargins(0, 0, 0, LauncherTheme.dp(this, 5));
        menu.addView(item, params);
    }

    private void loadHistory() {
        binding.aiChatHint.setText(R.string.social_loading_chat_history);
        LauncherAiChatBridge.loadHistory(this, threadId, new LauncherAiChatBridge.HistoryCallback() {
            @Override public void onSuccess(List<LauncherAiChatBridge.Message> loaded) {
                if (isFinishing() || isDestroyed() || binding == null) return;
                messages.clear();
                for (LauncherAiChatBridge.Message item : loaded) {
                    if ("user".equals(item.role) || "assistant".equals(item.role) || "tool".equals(item.role)) messages.add(item);
                }
                adapter.notifyDataSetChanged();
                binding.aiChatHint.setText(messages.isEmpty()
                        ? R.string.social_start_chatting : R.string.social_history_loaded);
                scrollToEnd();
            }
            @Override public void onError(String message) {
                if (isFinishing() || isDestroyed() || binding == null) return;
                binding.aiChatHint.setText(R.string.social_history_load_failed);
                showError(message);
            }
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
                if (isFinishing() || isDestroyed() || binding == null) return;
                sending = false;
                messages.add(new LauncherAiChatBridge.Message("assistant", reply, ""));
                adapter.notifyItemInserted(messages.size() - 1);
                binding.aiChatHint.setText(R.string.social_reply_complete);
                scrollToEnd();
                renderInputState();
            }
            @Override public void onError(String message) {
                if (isFinishing() || isDestroyed() || binding == null) return;
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

    private void showClearConfirmDialog() {
        LauncherDialogFactory.showDangerConfirm(
                this,
                getString(R.string.social_clear_history_title),
                getString(R.string.social_clear_history_message),
                getString(R.string.social_action_clear),
                () -> {
            LauncherAiChatBridge.clearHistory(this, threadId, new LauncherAiChatBridge.Callback() {
                @Override public void onSuccess() { if (!isFinishing() && !isDestroyed() && binding != null) { messages.clear(); adapter.notifyDataSetChanged(); binding.aiChatHint.setText(R.string.social_history_cleared); } }
                @Override public void onError(String error) { if (!isFinishing() && !isDestroyed() && binding != null) showError(error); }
            });
        });
    }

    private int weightedLength(String value) {
        if (value == null || value.isEmpty()) return 0;
        int halfUnits = 0;
        for (int i = 0; i < value.length(); i++) halfUnits += value.charAt(i) <= 0x7f ? 1 : 2;
        return (halfUnits + 1) / 2;
    }

    private void scrollToEnd() { if (!messages.isEmpty()) binding.aiChatMessages.scrollToPosition(messages.size() - 1); }
    private void showError(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    private void applyInsets() {
        View root = binding.aiChatRoot;
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            int topInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
            int systemBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            int imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            setOverlayMargins(binding.aiChatTopOverlay, 0, 0);
            binding.aiChatTitleBar.setPaddingRelative(LauncherTheme.dp(this, 13), topInset + LauncherTheme.dp(this, 12), LauncherTheme.dp(this, 13), LauncherTheme.dp(this, 15));
            boolean keyboardVisible = imeBottom > systemBottom;
            setOverlayMargins(binding.aiChatComposerOverlay, 0, keyboardVisible ? imeBottom : 0);
            View inputThemeBar = binding.aiChatInputThemeBar;
            inputThemeBar.setPaddingRelative(
                    inputThemeBar.getPaddingStart(),
                    LauncherTheme.dp(this, 13),
                    inputThemeBar.getPaddingEnd(),
                    keyboardVisible ? LauncherTheme.dp(this, 14) : systemBottom + LauncherTheme.dp(this, 14));
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
                : Math.max(0, binding.aiChatMessages.getBottom() - binding.aiChatComposerOverlay.getTop()) + LauncherTheme.dp(this, 8);
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

    private void applySavedToneMode() { LauncherActivity.applySavedToneMode(this); }
    @Override protected void attachBaseContext(android.content.Context context) { super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(context)); }
    @Override public void onBackPressed() { LauncherMotion.finish(this); }
}
