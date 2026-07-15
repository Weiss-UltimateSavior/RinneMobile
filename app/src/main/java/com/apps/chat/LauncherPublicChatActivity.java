package com.apps.chat;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.animation.LinearInterpolator;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.yuki.yukihub.R;
import com.yuki.yukihub.launcherbridge.LauncherAuthBridge;
import com.yuki.yukihub.launcherbridge.LauncherPublicChatBridge;
import com.yuki.yukihub.util.RxMainScheduler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import okhttp3.WebSocket;
import io.reactivex.disposables.Disposable;
import com.apps.LauncherActivity;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherTabletPortraitScaler;

/** User-facing public channel. Moderation remains server/admin-only. */
public class LauncherPublicChatActivity extends AppCompatActivity {
    private final List<LauncherPublicChatBridge.Message> messages = new ArrayList<>();
    private LauncherChatMessageAdapter adapter;
    private RecyclerView messageList;
    private TextView noticeView;
    private View announcementBar;
    private View topOverlay;
    private View titleBar;
    private View composerOverlay;
    private TextView connectionView;
    private EditText inputView;
    private ImageView sendView;
    private int messageListBaseBottomPadding;
    private Integer nextBeforeId;
    private boolean loadingOlder;
    private boolean readonly;
    private boolean muted;
    private String muteReason = "";
    private String connectionState = "连接中";
    private WebSocket socket;
    private ObjectAnimator sendAnimator;
    private Disposable heartbeatDisposable;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        applySavedToneMode();
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();
        setContentView(R.layout.activity_launcher_public_chat);
        LauncherTabletPortraitScaler.applyActivityContent(this);

        messageList = findViewById(R.id.publicChatMessages);
        noticeView = findViewById(R.id.publicChatNotice);
        announcementBar = findViewById(R.id.publicChatAnnouncementBar);
        topOverlay = findViewById(R.id.publicChatTopOverlay);
        titleBar = findViewById(R.id.publicChatTitleBar);
        composerOverlay = findViewById(R.id.publicChatComposerOverlay);
        connectionView = findViewById(R.id.publicChatConnection);
        inputView = findViewById(R.id.publicChatInput);
        sendView = findViewById(R.id.publicChatSend);
        messageListBaseBottomPadding = messageList.getPaddingBottom();
        topOverlay.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> updateMessageListOverlayPadding());
        titleBar.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> updateMessageListOverlayPadding());
        composerOverlay.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> updateMessageListOverlayPadding());
        applyInsets();
        adapter = new LauncherChatMessageAdapter(messages, LauncherAuthBridge.getNickname(this));
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        messageList.setLayoutManager(layoutManager);
        messageList.setAdapter(adapter);
        messageList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (!recyclerView.canScrollVertically(-1)) loadOlder();
            }
        });
        sendView.setOnClickListener(view -> sendMessage());
        LauncherTheme.applyPrimaryTone(findViewById(R.id.publicChatRoot));
        titleBar.setBackground(LauncherTheme.primaryButton(this, 0f));
        ((TextView) findViewById(R.id.publicChatTitle)).setTextColor(LauncherTheme.onPrimary(this));
        connectionView.setTextColor(ColorUtils.setAlphaComponent(LauncherTheme.onPrimary(this), 190));
        findViewById(R.id.publicChatAnnouncementIcon).setBackground(LauncherTheme.circle(this));
        findViewById(R.id.publicChatInputThemeBar).setBackground(LauncherTheme.primaryButton(this, 0f));
        inputView.setTextColor(LauncherTheme.text(this));
        inputView.setHintTextColor(LauncherTheme.textMuted(this));
        sendView.setImageTintList(ColorStateList.valueOf(LauncherTheme.primary(this)));
        renderStatus();
        loadChannel();
    }

    private void loadChannel() {
        LauncherPublicChatBridge.loadInitial(this, new LauncherPublicChatBridge.ChatCallback() {
            @Override public void onSuccess(List<LauncherPublicChatBridge.Message> loaded, Integer cursor) {
                messages.clear(); messages.addAll(loaded); sortMessages(); nextBeforeId = cursor; adapter.notifyDataSetChanged();
                if (!messages.isEmpty()) messageList.scrollToPosition(messages.size() - 1);
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
                if (messages.size() > previousCount) messageList.scrollToPosition(messages.size() - previousCount);
            }
            @Override public void onError(String message) { loadingOlder = false; showError(message); }
        });
    }

    private void sendMessage() {
        String content = inputView.getText().toString().trim();
        if (content.isEmpty()) return;
        if (readonly || muted) { renderStatus(); return; }
        inputView.setText("");
        sendView.setEnabled(false);
        startSendAnimation();
        LauncherPublicChatBridge.send(this, content, new LauncherPublicChatBridge.MessageCallback() {
            @Override public void onSuccess(LauncherPublicChatBridge.Message message) {
                stopSendAnimation(); sendView.setEnabled(true); upsert(message, true);
            }
            @Override public void onError(String message) {
                stopSendAnimation();
                inputView.setText(content);
                sendView.setEnabled(true);
                showError(message);
            }
        });
    }

    private void startSendAnimation() {
        stopSendAnimation();
        sendAnimator = ObjectAnimator.ofFloat(sendView, View.ROTATION, 0f, 360f);
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
        sendView.setRotation(0f);
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
        if (scrollToEnd) messageList.scrollToPosition(messages.size() - 1);
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
        noticeView.setText(text.length() == 0 ? "暂无公告" : text);
        announcementBar.setVisibility(View.VISIBLE);
    }

    private void renderStatus() {
        String text = "";
        if (readonly) text = "只读";
        else if (muted) text = TextUtils.isEmpty(muteReason) ? "已禁言" : "已禁言：" + muteReason;
        boolean canSend = !readonly && !muted;
        inputView.setEnabled(canSend); sendView.setEnabled(canSend);
        inputView.setHint(canSend ? "输入消息…" : text);
        renderConnectionStatus(text);
    }

    private void renderConnectionStatus(String channelState) {
        connectionView.setText(TextUtils.isEmpty(channelState)
                ? connectionState
                : connectionState + " · " + channelState);
    }

    private void showError(String message) { if (!isFinishing()) Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    private final class RealtimeCallbacks implements LauncherPublicChatBridge.RealtimeListener {
        @Override public void onConnected() { runOnUiThread(() -> { connectionState = "已连接"; renderStatus(); scheduleHeartbeat(); }); }
        @Override public void onMessageCreated(LauncherPublicChatBridge.Message message) { runOnUiThread(() -> upsert(message, true)); }
        @Override public void onMessageDeleted(int messageId) { runOnUiThread(() -> removeMessage(messageId)); }
        @Override public void onMessagePinned(LauncherPublicChatBridge.Message message) { runOnUiThread(() -> upsert(message, false)); }
        @Override public void onReadonlyChanged(boolean value) { runOnUiThread(() -> { readonly = value; renderStatus(); }); }
        @Override public void onMuted(boolean value, Long until, String reason) { runOnUiThread(() -> { muted = value; muteReason = reason; renderStatus(); }); }
        @Override public void onAnnouncementChanged(LauncherPublicChatBridge.Announcement announcement) { LauncherPublicChatBridge.loadAnnouncements(LauncherPublicChatActivity.this, new LauncherPublicChatBridge.AnnouncementsCallback() { @Override public void onSuccess(List<LauncherPublicChatBridge.Announcement> list) { renderAnnouncements(list); } @Override public void onError(String message) { showError(message); } }); }
        @Override public void onError(String message) { runOnUiThread(() -> { connectionState = "连接已断开"; renderStatus(); }); }
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
        View root = findViewById(R.id.publicChatRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            int topInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
            int systemBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            int imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            setOverlayMargins(topOverlay, 0, 0);
            titleBar.setPaddingRelative(dp(13), topInset + dp(12), dp(13), dp(15));
            boolean keyboardVisible = imeBottom > systemBottom;
            setOverlayMargins(composerOverlay, 0, keyboardVisible ? imeBottom : 0);
            View inputThemeBar = findViewById(R.id.publicChatInputThemeBar);
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
        if (messageList == null || titleBar == null || composerOverlay == null) return;
        int listTop = topOverlay.getVisibility() == View.GONE
                ? 0
                : Math.max(0, topOverlay.getBottom());
        setMessageListTopMargin(listTop);
        int bottomSpace = composerOverlay.getVisibility() == View.GONE
                ? 0
                : Math.max(0, messageList.getBottom() - composerOverlay.getTop()) + dp(8);
        messageList.setPadding(
                messageList.getPaddingLeft(),
                messageList.getPaddingTop(),
                messageList.getPaddingRight(),
                messageListBaseBottomPadding + bottomSpace);
    }

    private void setMessageListTopMargin(int topMargin) {
        ViewGroup.LayoutParams params = messageList.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
        if (margins.topMargin == topMargin) return;
        margins.topMargin = topMargin;
        messageList.setLayoutParams(margins);
    }

    private void configureEdgeToEdgeWindow() { boolean dark = LauncherActivity.isLauncherDarkMode(this); Window window = getWindow(); window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN); window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS); window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE); window.setStatusBarColor(Color.TRANSPARENT); window.setNavigationBarColor(ContextCompat.getColor(this, R.color.launcher_bg_color)); int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN; if (ColorUtils.calculateLuminance(LauncherTheme.primary(this)) > 0.5d) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR; if (!dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR; window.getDecorView().setSystemUiVisibility(flags); }
    private void applySavedToneMode() { LauncherActivity.applySavedToneMode(this); }
    @Override protected void attachBaseContext(android.content.Context newBase) { super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase)); }
}
