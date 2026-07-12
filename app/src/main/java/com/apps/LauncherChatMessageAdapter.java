package com.apps;

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
import com.yuki.yukihub.launcherbridge.LauncherPublicChatBridge;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/** Reusable Launcher message-bubble renderer for chat-like feeds. */
final class LauncherChatMessageAdapter extends RecyclerView.Adapter<LauncherChatMessageAdapter.Holder> {
    private final List<LauncherPublicChatBridge.Message> messages;
    private final String currentNickname;

    LauncherChatMessageAdapter(List<LauncherPublicChatBridge.Message> messages, String currentNickname) {
        this.messages = messages;
        this.currentNickname = currentNickname == null ? "" : currentNickname;
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_launcher_chat_message, parent, false);
        LauncherTabletPortraitScaler.apply(view);
        return new Holder(view);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        LauncherPublicChatBridge.Message message = messages.get(position);
        boolean outgoing = !currentNickname.isEmpty() && currentNickname.equals(message.senderName)
                && "user".equals(message.senderType);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) holder.bubble.getLayoutParams();
        params.gravity = outgoing ? Gravity.END : Gravity.START;
        holder.bubble.setLayoutParams(params);
        holder.bubble.setBackground(LauncherTheme.chatBubble(holder.bubble.getContext(), outgoing));
        holder.author.setText("admin".equals(message.senderType) ? "管理员 · " + message.senderName : message.senderName);
        holder.time.setText(DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(message.createdAt)));
        holder.pinned.setVisibility(message.pinned ? View.VISIBLE : View.GONE);
        holder.content.setText(message.content);
        int contentColor = outgoing ? LauncherTheme.onPrimary(holder.bubble.getContext()) : LauncherTheme.text(holder.bubble.getContext());
        holder.author.setTextColor(contentColor);
        holder.content.setTextColor(contentColor);
        holder.time.setTextColor(outgoing ? LauncherTheme.onPrimary(holder.bubble.getContext()) : LauncherTheme.textMuted(holder.bubble.getContext()));
        holder.pinned.setTextColor(outgoing ? LauncherTheme.onPrimary(holder.bubble.getContext()) : LauncherTheme.primaryText(holder.bubble.getContext()));
    }

    @Override public int getItemCount() { return messages.size(); }

    static final class Holder extends RecyclerView.ViewHolder {
        final LinearLayout bubble;
        final TextView author, time, pinned, content;
        Holder(@NonNull View view) {
            super(view);
            bubble = view.findViewById(R.id.chatMessageBubble);
            author = view.findViewById(R.id.chatMessageAuthor);
            time = view.findViewById(R.id.chatMessageTime);
            pinned = view.findViewById(R.id.chatMessagePinned);
            content = view.findViewById(R.id.chatMessageContent);
        }
    }
}
