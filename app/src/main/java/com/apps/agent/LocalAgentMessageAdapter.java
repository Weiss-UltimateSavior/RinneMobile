package com.apps.agent;

import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.apps.theme.LauncherTheme;
import com.yuki.yukihub.databinding.ItemLocalAgentEventBinding;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Left/right message flow used by the independent agent workbench. */
final class LocalAgentMessageAdapter extends RecyclerView.Adapter<LocalAgentMessageAdapter.Holder> {
    private final List<AgentConversationRepository.Message> messages;

    LocalAgentMessageAdapter(List<AgentConversationRepository.Message> messages) { this.messages = messages; }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Holder holder = new Holder(ItemLocalAgentEventBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
        // Some Honor framework builds dispatch android:nestedScrollingEnabled from View's base
        // constructor before NestedScrollView initializes its helper. Enable it only after inflate.
        holder.binding.agentReasoningScroll.setNestedScrollingEnabled(true);
        return holder;
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        AgentConversationRepository.Message message = messages.get(position);
        boolean user = "user".equals(message.role);
        boolean tool = "tool".equals(message.role);
        boolean reasoning = "reasoning".equals(message.role);
        int horizontalInset = dp(holder, 4);
        int oppositeInset = reasoning ? dp(holder, 18) : dp(holder, 54);
        FrameLayout.LayoutParams cardParams = (FrameLayout.LayoutParams) holder.binding.agentEventCard.getLayoutParams();
        cardParams.gravity = user ? Gravity.END : Gravity.START;
        cardParams.width = reasoning ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT;
        cardParams.leftMargin = user ? oppositeInset : horizontalInset;
        cardParams.rightMargin = user ? horizontalInset : oppositeInset;
        holder.binding.agentEventCard.setLayoutParams(cardParams);
        holder.binding.agentEventCard.setBackground(user
                ? LauncherTheme.solidPrimary(holder.itemView.getContext(), 18f)
                : new ColorDrawable(android.graphics.Color.TRANSPARENT));
        int cardPadding = user ? dp(holder, 14) : 0;
        holder.binding.agentEventCard.setPadding(cardPadding, user ? dp(holder, 11) : dp(holder, 4),
                cardPadding, user ? dp(holder, 11) : dp(holder, 4));
        holder.binding.agentEventHeader.setVisibility(user || reasoning ? View.GONE : View.VISIBLE);
        holder.binding.agentEventContent.setVisibility(reasoning ? View.GONE : View.VISIBLE);
        holder.binding.agentReasoningContainer.setVisibility(reasoning ? View.VISIBLE : View.GONE);
        if (reasoning) {
            boolean follow = holder.binding.agentReasoningScroll.getChildCount() == 0
                    || holder.binding.agentReasoningScroll.getScrollY()
                    + holder.binding.agentReasoningScroll.getHeight() + dp(holder, 24)
                    >= holder.binding.agentReasoningScroll.getChildAt(0).getHeight();
            holder.binding.agentReasoningContainer.setBackground(null);
            holder.binding.agentReasoningState.setText("streaming".equals(message.name)
                    ? "思考中…" : "已完成 · 可滑动查看");
            holder.binding.agentReasoningState.setTextColor(LauncherTheme.textMuted(holder.itemView.getContext()));
            holder.binding.agentReasoningContent.setText(message.content.isEmpty() ? "正在分析…" : message.content);
            holder.binding.agentReasoningContent.setTextColor(LauncherTheme.textMuted(holder.itemView.getContext()));
            if (follow && "streaming".equals(message.name)) {
                holder.binding.agentReasoningScroll.post(() ->
                        holder.binding.agentReasoningScroll.fullScroll(View.FOCUS_DOWN));
            }
        }
        holder.binding.agentEventTitle.setText(tool ? "本地操作" : "智能体");
        String meta = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(message.createdAt));
        if (tool && !message.name.isEmpty()) meta += "  ·  " + message.name;
        holder.binding.agentEventMeta.setText(meta);
        holder.binding.agentEventContent.setText(message.content.isEmpty() && !user && !tool ? "正在生成结果…" : message.content);
        holder.binding.agentEventContent.setMaxWidth(Math.max(dp(holder, 220),
                holder.itemView.getResources().getDisplayMetrics().widthPixels - dp(holder, 100)));
        LinearLayout.LayoutParams contentParams = (LinearLayout.LayoutParams) holder.binding.agentEventContent.getLayoutParams();
        contentParams.topMargin = user ? 0 : dp(holder, 7);
        holder.binding.agentEventContent.setLayoutParams(contentParams);
        holder.binding.agentEventContent.setTextColor(user
                ? LauncherTheme.onPrimary(holder.itemView.getContext())
                : LauncherTheme.text(holder.itemView.getContext()));
        holder.binding.agentEventTitle.setTextColor(LauncherTheme.text(holder.itemView.getContext()));
        holder.binding.agentEventMeta.setTextColor(LauncherTheme.textMuted(holder.itemView.getContext()));
    }

    @Override public int getItemCount() { return messages.size(); }

    static final class Holder extends RecyclerView.ViewHolder {
        final ItemLocalAgentEventBinding binding;
        Holder(ItemLocalAgentEventBinding binding) { super(binding.getRoot()); this.binding = binding; }
    }

    private static int dp(Holder holder, int value) {
        return Math.round(value * holder.itemView.getResources().getDisplayMetrics().density);
    }
}
