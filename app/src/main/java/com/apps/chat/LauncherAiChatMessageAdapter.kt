package com.apps.chat

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherTabletPortraitScaler
import com.yuki.yukihub.R
import com.yuki.yukihub.launcherbridge.LauncherAiChatBridge
import kotlin.math.max

class LauncherAiChatMessageAdapter(
    private val messages: List<LauncherAiChatBridge.Message>,
    assistantName: String?
) : RecyclerView.Adapter<LauncherAiChatMessageAdapter.Holder>() {
    private val assistantName = assistantName ?: "AI"

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_launcher_chat_message, parent, false)
        LauncherTabletPortraitScaler.apply(view)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val message = messages[position]
        val user = message.role == "user"
        val tool = message.role == "tool"
        val content = if (message.content.trim().isEmpty() && tool) "已使用工具：${message.name}" else message.content
        holder.content.text = content
        (holder.bubble.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = if (user) Gravity.END else Gravity.START
            holder.bubble.layoutParams = this
        }
        holder.bubble.background = LauncherTheme.chatBubble(holder.bubble.context, user)
        holder.author.text = if (user) "我" else if (tool) "工具 · ${message.name}" else assistantName
        holder.time.visibility = View.GONE
        holder.pinned.visibility = View.GONE
        val contentColor = if (user) LauncherTheme.onPrimary(holder.bubble.context) else LauncherTheme.text(holder.bubble.context)
        holder.author.setTextColor(contentColor)
        holder.content.setTextColor(contentColor)
    }

    override fun getItemCount(): Int = messages.size

    class Holder(root: View) : RecyclerView.ViewHolder(root) {
        val bubble: LinearLayout = root.findViewById(R.id.chatMessageBubble)
        val author: TextView = root.findViewById(R.id.chatMessageAuthor)
        val time: TextView = root.findViewById(R.id.chatMessageTime)
        val pinned: TextView = root.findViewById(R.id.chatMessagePinned)
        val content: TextView = root.findViewById(R.id.chatMessageContent)

        init {
            root.addOnLayoutChangeListener { view, left, _, right, _, _, _, _, _ ->
                content.maxWidth = max(0, (right - left) * 76 / 100 - LauncherTheme.dp(view.context, 24f))
            }
        }
    }
}
