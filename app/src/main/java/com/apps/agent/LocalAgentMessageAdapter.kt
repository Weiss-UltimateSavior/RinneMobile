package com.apps.agent

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.apps.theme.LauncherTheme
import com.yuki.yukihub.databinding.ItemLocalAgentEventBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** Left/right message flow used by the independent agent workbench. */
class LocalAgentMessageAdapter(
    private val messages: List<AgentConversationRepository.Message>
) : RecyclerView.Adapter<LocalAgentMessageAdapter.Holder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemLocalAgentEventBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    ).also {
        // Enable only after inflation: some framework builds dispatch this attribute too early.
        it.binding.agentReasoningScroll.isNestedScrollingEnabled = true
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val message = messages[position]
        val user = message.role == "user"
        val tool = message.role == "tool"
        val reasoning = message.role == "reasoning"
        val horizontalInset = holder.dp(4)
        val oppositeInset = holder.dp(if (reasoning) 18 else 54)
        val binding = holder.binding
        (binding.agentEventCard.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = if (user) Gravity.END else Gravity.START
            width = if (reasoning) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            leftMargin = if (user) oppositeInset else horizontalInset
            rightMargin = if (user) horizontalInset else oppositeInset
            binding.agentEventCard.layoutParams = this
        }
        binding.agentEventCard.background = if (user) {
            LauncherTheme.solidPrimary(holder.itemView.context, 18f)
        } else {
            ColorDrawable(Color.TRANSPARENT)
        }
        val cardPadding = if (user) holder.dp(14) else 0
        binding.agentEventCard.setPadding(
            cardPadding, if (user) holder.dp(11) else holder.dp(4),
            cardPadding, if (user) holder.dp(11) else holder.dp(4)
        )
        binding.agentEventHeader.visibility = if (user || reasoning) View.GONE else View.VISIBLE
        binding.agentEventContent.visibility = if (reasoning) View.GONE else View.VISIBLE
        binding.agentReasoningContainer.visibility = if (reasoning) View.VISIBLE else View.GONE
        if (reasoning) {
            val follow = binding.agentReasoningScroll.childCount == 0 ||
                binding.agentReasoningScroll.scrollY + binding.agentReasoningScroll.height + holder.dp(24) >=
                binding.agentReasoningScroll.getChildAt(0).height
            binding.agentReasoningContainer.background = null
            binding.agentReasoningState.text = if (message.name == "streaming") "思考中…" else "已完成 · 可滑动查看"
            binding.agentReasoningState.setTextColor(LauncherTheme.textMuted(holder.itemView.context))
            binding.agentReasoningContent.text = if (message.content.isEmpty()) "正在分析…" else message.content
            binding.agentReasoningContent.setTextColor(LauncherTheme.textMuted(holder.itemView.context))
            if (follow && message.name == "streaming") {
                binding.agentReasoningScroll.post { binding.agentReasoningScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
        binding.agentEventTitle.text = if (tool) "本地操作" else "智能体"
        var meta = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.createdAt))
        if (tool && message.name.isNotEmpty()) meta += "  ·  ${message.name}"
        binding.agentEventMeta.text = meta
        binding.agentEventContent.text = if (message.content.isEmpty() && !user && !tool) "正在生成结果…" else message.content
        binding.agentEventContent.maxWidth = max(
            holder.dp(220), holder.itemView.resources.displayMetrics.widthPixels - holder.dp(100)
        )
        (binding.agentEventContent.layoutParams as LinearLayout.LayoutParams).apply {
            topMargin = if (user) 0 else holder.dp(7)
            binding.agentEventContent.layoutParams = this
        }
        binding.agentEventContent.setTextColor(
            if (user) LauncherTheme.onPrimary(holder.itemView.context) else LauncherTheme.text(holder.itemView.context)
        )
        binding.agentEventTitle.setTextColor(LauncherTheme.text(holder.itemView.context))
        binding.agentEventMeta.setTextColor(LauncherTheme.textMuted(holder.itemView.context))
    }

    override fun getItemCount(): Int = messages.size

    class Holder(val binding: ItemLocalAgentEventBinding) : RecyclerView.ViewHolder(binding.root) {
        fun dp(value: Int): Int = (value * itemView.resources.displayMetrics.density).roundToInt()
    }
}
