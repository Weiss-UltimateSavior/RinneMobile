package com.apps.chat

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherTabletPortraitScaler
import com.yuki.yukihub.R
import com.yuki.yukihub.launcherbridge.LauncherPublicChatBridge
import java.text.DateFormat
import java.util.Date
import kotlin.math.max

/** Reusable Launcher message-bubble renderer for chat-like feeds. */
class LauncherChatMessageAdapter(
    private val messages: List<LauncherPublicChatBridge.Message>,
    currentNickname: String?
) : RecyclerView.Adapter<LauncherChatMessageAdapter.Holder>() {
    private val currentNickname = currentNickname.orEmpty()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_launcher_chat_message, parent, false)
        LauncherTabletPortraitScaler.apply(view)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val message = messages[position]
        val outgoing = currentNickname.isNotEmpty() && currentNickname == message.senderName && message.senderType == "user"
        (holder.bubble.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = if (outgoing) Gravity.END else Gravity.START
            holder.bubble.layoutParams = this
        }
        holder.bubble.background = LauncherTheme.chatBubble(holder.bubble.context, outgoing)
        holder.author.text = if (message.senderType == "admin") "管理员 · ${message.senderName}" else message.senderName
        holder.time.text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.createdAt))
        holder.pinned.visibility = if (message.pinned) View.VISIBLE else View.GONE
        holder.content.text = message.content
        val contentColor = if (outgoing) LauncherTheme.onPrimary(holder.bubble.context) else LauncherTheme.text(holder.bubble.context)
        holder.author.setTextColor(contentColor)
        holder.content.setTextColor(contentColor)
        holder.time.setTextColor(if (outgoing) ColorUtils.setAlphaComponent(contentColor, 190) else LauncherTheme.textMuted(holder.bubble.context))
        holder.pinned.setTextColor(if (outgoing) ColorUtils.setAlphaComponent(contentColor, 220) else LauncherTheme.primary(holder.bubble.context))
    }

    override fun getItemCount(): Int = messages.size

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val bubble: LinearLayout = view.findViewById(R.id.chatMessageBubble)
        val author: TextView = view.findViewById(R.id.chatMessageAuthor)
        val time: TextView = view.findViewById(R.id.chatMessageTime)
        val pinned: TextView = view.findViewById(R.id.chatMessagePinned)
        val content: TextView = view.findViewById(R.id.chatMessageContent)

        init {
            view.addOnLayoutChangeListener { root, left, _, right, _, _, _, _, _ ->
                content.maxWidth = max(0, (right - left) * 76 / 100 - LauncherTheme.dp(root.context, 24f))
            }
        }
    }
}
