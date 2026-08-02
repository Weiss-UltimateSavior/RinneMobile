package com.apps.chat;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.animation.LinearInterpolator;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.core.R;
import com.core.databinding.ActivityLauncherPublicChatBinding;
import com.core.launcherbridge.LauncherAuthBridge;
import com.core.launcherbridge.LauncherPublicChatBridge;
import com.core.util.RxMainScheduler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import okhttp3.WebSocket;
import com.core.util.Disposable;
import com.apps.LauncherActivity;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherTabletPortraitScaler;

/** User-facing public channel. Moderation remains server/admin-only. */
public class LauncherPublicChatActivity extends AppCompatActivity {
    private final List<LauncherPublicChatBridge.Message> messages = new ArrayList<>();
    private LauncherChatMessageAdapter adapter;
    private ActivityLauncherPublicChatBinding binding;
    private int messageListBaseBottomPadding;
    private Integer nextBeforeId;
    private boolean loadingOlder;
    private boolean sending;
    private boolean readonly;
    private boolean muted;
    private String muteReason = "";
    private String connectionState;
    private WebSocket socket;
    private ObjectAnimator sendAnimator;
    private Disposable heartbeatDisposable;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        applySavedToneMode();
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();
        binding = ActivityLauncherPublicChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        connectionState = getString(R.string.social_connecting);
        LauncherTabletPortraitScaler.applyActivityContent(this);

        messageListBaseBottomPadding = binding.publicChatMessages.getPaddingBottom();
        binding.publicChatTopOverlay.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> updateMessageListOverlayPadding());
        binding.publicChatTitleBar.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> updateMessageListOverlayPadding());
        binding.publicChatComposerOverlay.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> updateMessageListOverlayPadding());
        applyInsets();
        adapter = new LauncherChatMessageAdapter(messages, LauncherAuthBridge.getNickname(this));
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        binding.publicChatMessages.setLayoutManager(layoutManager);
        binding.publicChatMessages.setAdapter(adapter);
        binding.publicChatMessages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (!recyclerView.canScrollVertically(-1)) loadOlder();
            }
        });
        binding.publicChatSend.setOnClickListener(view -> sendMessage());
        binding.publicChatInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateSendState(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        LauncherTheme.applyPrimaryTone(binding.publicChatRoot);
        binding.publicChatTitleBar.setBackground(LauncherTheme.primaryButton(this, 0f));
        binding.publicChatTitle.setTextColor(LauncherTheme.onPrimary(this));
        binding.publicChatConnection.setTextColor(ColorUtils.setAlphaComponent(LauncherTheme.onPrimary(this), 190));
        binding.publicChatAnnouncementIcon.setBackground(LauncherTheme.circle(this));
        binding.publicChatInputThemeBar.setBackground(LauncherTheme.primaryButton(this, 0f));
        binding.publicChatInput.setTextColor(LauncherTheme.text(this));
        binding.publicChatInput.setHintTextColor(LauncherTheme.textMuted(this));
        binding.publicChatSend.setImageTintList(ColorStateList.valueOf(LauncherTheme.primary(this)));
        renderStatus();
        loadChannel();
    }

    private void loadChannel() {
        LauncherPublicChatBridge.loadInitial(this, new LauncherPublicChatBridge.ChatCallback() {
            @Override public void onSuccess(List<LauncherPublicChatBridge.Message> loaded, Integer cursor) {
                messages.clear(); messages.addAll(loaded); sortMessages(); nextBeforeId = cursor; adapter.notifyDataSetChanged();
                if (!messages.isEmpty()) binding.publicChatMessages.scrollToPosition(messages.size() - 1);
            }
            @Override public void onError(String message) { showError(message); }
        });
        LauncherPublicChatBridge.loadStatus(this, new LauncherPublicChatBridge.StatusCallback() {
            @Override public void onSuccess(LauncherPublicChatBridge.Status state) { readonly = state.readonly; muted = state.muted; muteReason = state.muteReason; renderStatus(); }
            @Override public void onError(String message) { showError(message); }
        });
        LauncherPublicChatBridge.loadAnnouncements(this, new LauncherPublicChatBridge.AnnouncementsCallback() {
            @Override public void onSuccess(List<LauncherPublicChatBridge.Announcement> announcements) { renderAnnouncements(announcements); }
            @Override public void onError(String message) { showError(message); }
        });
        socket = LauncherPublicChatBridge.connect(this, new RealtimeCallbacks());
    }

    private void loadOlder() {
        if (loadingOlder || nextBeforeId == null) return;
        loadingOlder = true;
        int beforeId = nextBeforeId;
        LauncherPublicChatBridge.loadOlder(this, beforeId, new LauncherPublicChatBridge.ChatCallback() {
            @Override public void onSuccess(List<LauncherPublicChatBridge.Message> loaded, Integer cursor) {
                int previousCount = messages.size();
                for (int i = loaded.size() - 1; i >= 0; i--) upsert(loaded.get(i), false);
                nextBeforeId = cursor; loadingOlder = false; adapter.notifyDataSetChanged();
                if (messages.size() > previousCount) binding.publicChatMessages.scrollToPosition(messages.size() - previousCount);
            }
            @Override public void onError(String message) { loadingOlder = false; showError(message); }
        });
    }

    private void sendMessage() {
        String content = binding.publicChatInput.getText().toString().trim();
        if (content.isEmpty()) return;
        if (readonly || muted) { renderStatus(); return; }
        sending = true;
        binding.publicChatInput.setText("");
        updateSendState();
        startSendAnimation();
        LauncherPublicChatBridge.send(this, content, new LauncherPublicChatBridge.MessageCallback() {
            @Override public void onSuccess(LauncherPublicChatBridge.Message message) {
                sending = false;
                stopSendAnimation(); updateSendState(); upsert(message, true);
            }
            @Override public void onError(String message) {
                sending = false;
                stopSendAnimation();
                binding.publicChatInput.setText(content);
                updateSendState();
                showError(message);
            }
        });
    }

    private void startSendAnimation() {
        stopSendAnimation();
        sendAnimator = ObjectAnimator.ofFloat(binding.publicChatSend, View.ROTATION, 0f, 360f);
        sendAnimator.setDuration(700L);
        sendAnimator.setInterpolator(new LinearInterpolator());
        sendAnimator.setRepeatCount(ValueAnimator.INFINITE);
        sendAnimator.start();
    }

    private void stopSendAnimation() {
        if (sendAnimator != null) {
            sendAnimator.cancel();
            sendAnimator = null;
        }
        binding.publicChatSend.setRotation(0f);
    }

    private void upsert(LauncherPublicChatBridge.Message message, boolean scrollToEnd) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).id == message.id) {
                messages.set(i, message);
                sortMessages();
                adapter.notifyDataSetChanged();
                return;
            }
        }
        messages.add(message);
        sortMessages();
        adapter.notifyDataSetChanged();
        if (scrollToEnd) binding.publicChatMessages.scrollToPosition(messages.size() - 1);
    }

    private void sortMessages() {
        Collections.sort(messages, (left, right) -> {
            int timeOrder = Long.compare(left.createdAt, right.createdAt);
            return timeOrder != 0 ? timeOrder : Integer.compare(left.id, right.id);
        });
    }

    private void removeMessage(int id) { for (int i = 0; i < messages.size(); i++) if (messages.get(i).id == id) { messages.remove(i); adapter.notifyItemRemoved(i); return; } }

    private void renderAnnouncements(List<LauncherPublicChatBridge.Announcement> announcements) {
        StringBuilder text = new StringBuilder();
        for (LauncherPublicChatBridge.Announcement item : announcements) if (item.active) {
            if (text.length() > 0) text.append("\n\n");
            text.append(item.title).append("\n").append(item.content);
        }
        boolean hasAnnouncement = text.length() > 0;
        binding.publicChatAnnouncementBar.setVisibility(hasAnnouncement ? View.VISIBLE : View.GONE);
        if (hasAnnouncement) binding.publicChatNotice.setText(text);
        updateMessageListOverlayPadding();
    }

    private void renderStatus() {
        String text = "";
        if (readonly) text = getString(R.string.social_read_only);
        else if (muted) text = TextUtils.isEmpty(muteReason)
                ? getString(R.string.social_muted)
                : getString(R.string.social_muted_reason, muteReason);
        boolean canSend = !readonly && !muted;
        binding.publicChatInput.setEnabled(canSend);
        binding.publicChatInput.setHint(canSend ? getString(R.string.social_input_message) : text);
        updateSendState();
        renderConnectionStatus(text);
    }

    private void updateSendState() {
        if (binding == null || isFinishing() || isDestroyed()) return;
        boolean hasContent = binding.publicChatInput.getText() != null && binding.publicChatInput.getText().toString().trim().length() > 0;
        boolean enabled = !sending && !readonly && !muted && hasContent;
        binding.publicChatSend.setEnabled(enabled);
        binding.publicChatSend.setAlpha(enabled ? 1f : .45f);
    }

    private void renderConnectionStatus(String channelState) {
        binding.publicChatConnection.setText(TextUtils.isEmpty(channelState)
                ? connectionState
                : connectionState + " · " + channelState);
    }

    private void showError(String message) { if (!isUiUnavailable()) Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    private void runOnUiIfAlive(Runnable action) {
        runOnUiThread(() -> {
            if (isUiUnavailable()) return;
            action.run();
        });
    }

    private boolean isUiUnavailable() {
        return isFinishing() || isDestroyed() || binding == null;
    }

    private final class RealtimeCallbacks implements LauncherPublicChatBridge.RealtimeListener {
        @Override public void onConnected() { runOnUiIfAlive(() -> { connectionState = getString(R.string.social_connected); renderStatus(); scheduleHeartbeat(); }); }
        @Override public void onMessageCreated(LauncherPublicChatBridge.Message message) { runOnUiIfAlive(() -> upsert(message, true)); }
        @Override public void onMessageDeleted(int messageId) { runOnUiIfAlive(() -> removeMessage(messageId)); }
        @Override public void onMessagePinned(LauncherPublicChatBridge.Message message) { runOnUiIfAlive(() -> upsert(message, false)); }
        @Override public void onReadonlyChanged(boolean value) { runOnUiIfAlive(() -> { readonly = value; renderStatus(); }); }
        @Override public void onMuted(boolean value, Long until, String reason) { runOnUiIfAlive(() -> { muted = value; muteReason = reason; renderStatus(); }); }
        @Override public void onAnnouncementChanged(LauncherPublicChatBridge.Announcement announcement) { LauncherPublicChatBridge.loadAnnouncements(LauncherPublicChatActivity.this, new LauncherPublicChatBridge.AnnouncementsCallback() { @Override public void onSuccess(List<LauncherPublicChatBridge.Announcement> list) { renderAnnouncements(list); } @Override public void onError(String message) { showError(message); } }); }
        @Override public void onError(String message) { runOnUiIfAlive(() -> { connectionState = getString(R.string.social_disconnected); renderStatus(); }); }
    }

    @Override protected void onDestroy() { cancelHeartbeat(); stopSendAnimation(); if (socket != null) socket.close(1000, "页面关闭"); super.onDestroy(); }

    private void scheduleHeartbeat() {
        cancelHeartbeat();
        heartbeatDisposable = RxMainScheduler.postDelayed(new Runnable() {
            @Override public void run() {
                if (socket != null) socket.send("ping");
                scheduleHeartbeat();
            }
        }, 25000L);
    }

    private void cancelHeartbeat() {
        if (heartbeatDisposable != null) {
            heartbeatDisposable.dispose();
            heartbeatDisposable = null;
        }
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private void applyInsets() {
        View root = binding.publicChatRoot;
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            int topInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
            int systemBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            int imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            setOverlayMargins(binding.publicChatTopOverlay, 0, 0);
            binding.publicChatTitleBar.setPaddingRelative(dp(13), topInset + dp(12), dp(13), dp(15));
            boolean keyboardVisible = imeBottom > systemBottom;
            setOverlayMargins(binding.publicChatComposerOverlay, 0, keyboardVisible ? imeBottom : 0);
            View inputThemeBar = binding.publicChatInputThemeBar;
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
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
        if (margins.topMargin == top && margins.bottomMargin == bottom) return;
        margins.topMargin = top;
        margins.bottomMargin = bottom;
        view.setLayoutParams(margins);
    }

    private void updateMessageListOverlayPadding() {
        if (binding == null || isFinishing() || isDestroyed()) return;
        int listTop = binding.publicChatTopOverlay.getVisibility() == View.GONE
                ? 0
                : Math.max(0, binding.publicChatTopOverlay.getBottom());
        setMessageListTopMargin(listTop);
        int bottomSpace = binding.publicChatComposerOverlay.getVisibility() == View.GONE
                ? 0
                : Math.max(0, binding.publicChatMessages.getBottom() - binding.publicChatComposerOverlay.getTop()) + dp(8);
        binding.publicChatMessages.setPadding(
                binding.publicChatMessages.getPaddingLeft(),
                binding.publicChatMessages.getPaddingTop(),
                binding.publicChatMessages.getPaddingRight(),
                messageListBaseBottomPadding + bottomSpace);
    }

    private void setMessageListTopMargin(int topMargin) {
        ViewGroup.LayoutParams params = binding.publicChatMessages.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
        if (margins.topMargin == topMargin) return;
        margins.topMargin = topMargin;
        binding.publicChatMessages.setLayoutParams(margins);
    }

    private void configureEdgeToEdgeWindow() {
        com.apps.LauncherEdgeToEdgeHelper.apply(this, true, true);
    }
    private void applySavedToneMode() { LauncherActivity.applySavedToneMode(this); }
    @Override protected void attachBaseContext(android.content.Context newBase) { super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase)); }
}
