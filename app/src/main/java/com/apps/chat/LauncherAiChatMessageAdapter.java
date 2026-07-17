package com.apps.chat;

import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.yuki.yukihub.R;
import com.yuki.yukihub.launcherbridge.LauncherAiChatBridge;

import java.util.List;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherTabletPortraitScaler;

final class LauncherAiChatMessageAdapter extends RecyclerView.Adapter<LauncherAiChatMessageAdapter.Holder> {
    private final List<LauncherAiChatBridge.Message> messages;
    private final String assistantName;

    LauncherAiChatMessageAdapter(List<LauncherAiChatBridge.Message> messages, String assistantName) {
        this.messages = messages;
        this.assistantName = assistantName == null ? "AI" : assistantName;
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_launcher_chat_message, parent, false);
        LauncherTabletPortraitScaler.apply(view);
        return new Holder(view);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        LauncherAiChatBridge.Message message = messages.get(position);
        boolean user = "user".equals(message.role);
        String content = message.content;
        if (content.trim().isEmpty() && "tool".equals(message.role)) content = "已使用工具：" + message.name;
        holder.content.setText(content);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) holder.bubble.getLayoutParams();
        lp.gravity = user ? Gravity.END : Gravity.START;
        holder.bubble.setLayoutParams(lp);
        holder.bubble.setBackground(LauncherTheme.chatBubble(holder.bubble.getContext(), user));
        holder.author.setText(user ? "我" : ("tool".equals(message.role) ? "工具 · " + message.name : assistantName));
        holder.time.setVisibility(View.GONE);
        holder.pinned.setVisibility(View.GONE);
        int contentColor = user ? LauncherTheme.onPrimary(holder.bubble.getContext())
                : LauncherTheme.text(holder.bubble.getContext());
        holder.author.setTextColor(contentColor);
        holder.content.setTextColor(contentColor);
    }

    @Override public int getItemCount() { return messages.size(); }

    static final class Holder extends RecyclerView.ViewHolder {
        final LinearLayout bubble;
        final TextView author, time, pinned, content;
        Holder(@NonNull View root) {
            super(root);
            bubble = root.findViewById(R.id.chatMessageBubble);
            author = root.findViewById(R.id.chatMessageAuthor);
            time = root.findViewById(R.id.chatMessageTime);
            pinned = root.findViewById(R.id.chatMessagePinned);
            content = root.findViewById(R.id.chatMessageContent);
            root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                int maxContentWidth = Math.max(0, (right - left) * 76 / 100 - LauncherTheme.dp(view.getContext(), 24f));
                content.setMaxWidth(maxContentWidth);
            });
        }
    }

}
