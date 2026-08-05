package com.apps.chat

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.apps.HDModel.HdEmbeddedActivityOwner
import com.apps.HDModel.HdModeActivity
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.R
import com.core.databinding.ActivityLauncherPublicChatBinding
import com.core.launcherbridge.LauncherAuthBridge
import com.core.launcherbridge.LauncherPublicChatBridge
import com.core.util.Disposable
import com.core.util.RxMainScheduler
import okhttp3.WebSocket

/**
 * 公共聊天页（重构计划 9.9 W-3，阶段 128）：自 [LauncherPublicChatActivity] 抽取全部逻辑，
 * 竖屏由薄宿主 [LauncherPublicChatActivity] 承载，HD 由 HdProfileFragment 以子 Fragment 承载
 *（阶段 129 接入）。
 *
 * WebSocket 实时回调经 [runOnUiIfAlive] 回到 UI 线程并守卫 detach；心跳/发送动画/
 * socket 在 onDestroyView 释放（§8 postDelayed 清理约定）。
 */
class LauncherPublicChatFragment : Fragment() {
    private var binding: ActivityLauncherPublicChatBinding? = null
    private val messages = ArrayList<LauncherPublicChatBridge.Message>()
    private var adapter: LauncherChatMessageAdapter? = null
    /** insets 重排回调（ChatInsetsHelper.install 返回），公告栏显隐等布局变化后调用。 */
    private var relayoutOverlay: (() -> Unit)? = null
    private var nextBeforeId: Int? = null
    private var loadingOlder = false
    private var sending = false
    private var readonly = false
    private var muted = false
    private var muteReason = ""
    private var connectionState = ""
    private var socket: WebSocket? = null
    private var sendAnimator: ObjectAnimator? = null
    private var heartbeatDisposable: Disposable? = null

    /** 后台线程（WebSocket 回调）场景下安全使用的上下文快照。 */
    private var appContext: Context? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appContext = requireContext().applicationContext
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val currentBinding = ActivityLauncherPublicChatBinding.inflate(inflater, container, false)
        binding = currentBinding
        return currentBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        LauncherTabletPortraitScaler.apply(view)
        val currentBinding = binding ?: return
        connectionState = getString(R.string.social_connecting)
        relayoutOverlay = ChatInsetsHelper.install(
            currentBinding.publicChatRoot,
            object : ChatInsetsLayout {
                override val topOverlay: View get() = currentBinding.publicChatTopOverlay
                override val titleBar: View get() = currentBinding.publicChatTitleBar
                override val composerOverlay: View get() = currentBinding.publicChatComposerOverlay
                override val inputThemeBar: View get() = currentBinding.publicChatInputThemeBar
                override val messages: View get() = currentBinding.publicChatMessages
            },
            currentBinding.publicChatMessages.paddingBottom,
        )
        val currentAdapter = LauncherChatMessageAdapter(messages, LauncherAuthBridge.getNickname(requireContext()))
        adapter = currentAdapter
        currentBinding.publicChatMessages.layoutManager = LinearLayoutManager(requireContext())
        currentBinding.publicChatMessages.adapter = currentAdapter
        currentBinding.publicChatMessages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (!recyclerView.canScrollVertically(-1)) loadOlder()
            }
        })
        currentBinding.publicChatSend.setOnClickListener { sendMessage() }
        currentBinding.publicChatInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateSendState() }
            override fun afterTextChanged(s: Editable?) {}
        })
        LauncherTheme.applyPrimaryTone(currentBinding.publicChatRoot)
        currentBinding.publicChatTitleBar.background = LauncherTheme.primaryButton(requireContext(), 0f)
        currentBinding.publicChatTitle.setTextColor(LauncherTheme.onPrimary(requireContext()))
        currentBinding.publicChatConnection.setTextColor(
            ColorUtils.setAlphaComponent(LauncherTheme.onPrimary(requireContext()), 190),
        )
        currentBinding.publicChatAnnouncementIcon.background = LauncherTheme.circle(requireContext())
        currentBinding.publicChatInputThemeBar.background = LauncherTheme.primaryButton(requireContext(), 0f)
        currentBinding.publicChatInput.setTextColor(LauncherTheme.text(requireContext()))
        currentBinding.publicChatInput.setHintTextColor(LauncherTheme.textMuted(requireContext()))
        currentBinding.publicChatSend.imageTintList = ColorStateList.valueOf(LauncherTheme.primary(requireContext()))
        renderStatus()
        loadChannel()
        // 硬件返回：按承载宿主关闭（原默认 back=finish 语义，双上下文统一）。
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { requestClose() }
            },
        )
    }

    override fun onDestroyView() {
        cancelHeartbeat()
        stopSendAnimation()
        socket?.close(1000, "页面关闭")
        socket = null
        relayoutOverlay = null
        binding = null
        super.onDestroyView()
    }

    private fun loadChannel() {
        LauncherPublicChatBridge.loadInitial(requireContext(), object : LauncherPublicChatBridge.ChatCallback {
            override fun onSuccess(loaded: List<LauncherPublicChatBridge.Message>, cursor: Int?) {
                if (unavailable()) return
                messages.clear()
                messages.addAll(loaded)
                sortMessages()
                nextBeforeId = cursor
                adapter?.notifyDataSetChanged()
                if (!messages.isEmpty()) binding?.publicChatMessages?.scrollToPosition(messages.size - 1)
            }

            override fun onError(message: String) { showError(message) }
        })
        LauncherPublicChatBridge.loadStatus(requireContext(), object : LauncherPublicChatBridge.StatusCallback {
            override fun onSuccess(state: LauncherPublicChatBridge.Status) {
                if (unavailable()) return
                readonly = state.readonly
                muted = state.muted
                muteReason = state.muteReason
                renderStatus()
            }

            override fun onError(message: String) { showError(message) }
        })
        LauncherPublicChatBridge.loadAnnouncements(requireContext(), object : LauncherPublicChatBridge.AnnouncementsCallback {
            override fun onSuccess(announcements: List<LauncherPublicChatBridge.Announcement>) {
                if (unavailable()) return
                renderAnnouncements(announcements)
            }

            override fun onError(message: String) { showError(message) }
        })
        socket = LauncherPublicChatBridge.connect(requireContext(), RealtimeCallbacks())
    }

    private fun loadOlder() {
        if (loadingOlder || nextBeforeId == null) return
        val beforeId = nextBeforeId ?: return
        loadingOlder = true
        LauncherPublicChatBridge.loadOlder(requireContext(), beforeId, object : LauncherPublicChatBridge.ChatCallback {
            override fun onSuccess(loaded: List<LauncherPublicChatBridge.Message>, cursor: Int?) {
                if (unavailable()) return
                val previousCount = messages.size
                for (i in loaded.indices.reversed()) upsert(loaded[i], false)
                nextBeforeId = cursor
                loadingOlder = false
                adapter?.notifyDataSetChanged()
                if (messages.size > previousCount) {
                    binding?.publicChatMessages?.scrollToPosition(messages.size - previousCount)
                }
            }

            override fun onError(message: String) {
                loadingOlder = false
                showError(message)
            }
        })
    }

    private fun sendMessage() {
        val currentBinding = binding ?: return
        val content = currentBinding.publicChatInput.text?.toString()?.trim().orEmpty()
        if (content.isEmpty()) return
        if (readonly || muted) {
            renderStatus()
            return
        }
        sending = true
        currentBinding.publicChatInput.setText("")
        updateSendState()
        startSendAnimation()
        LauncherPublicChatBridge.send(requireContext(), content, object : LauncherPublicChatBridge.MessageCallback {
            override fun onSuccess(message: LauncherPublicChatBridge.Message) {
                if (unavailable()) return
                sending = false
                stopSendAnimation()
                updateSendState()
                upsert(message, true)
            }

            override fun onError(message: String) {
                if (unavailable()) return
                sending = false
                stopSendAnimation()
                binding?.publicChatInput?.setText(content)
                updateSendState()
                showError(message)
            }
        })
    }

    private fun startSendAnimation() {
        stopSendAnimation()
        val currentBinding = binding ?: return
        sendAnimator = ObjectAnimator.ofFloat(currentBinding.publicChatSend, View.ROTATION, 0f, 360f).apply {
            duration = 700L
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopSendAnimation() {
        sendAnimator?.cancel()
        sendAnimator = null
        binding?.publicChatSend?.rotation = 0f
    }

    private fun upsert(message: LauncherPublicChatBridge.Message, scrollToEnd: Boolean) {
        for (i in messages.indices) {
            if (messages[i].id == message.id) {
                messages[i] = message
                sortMessages()
                adapter?.notifyDataSetChanged()
                return
            }
        }
        messages.add(message)
        sortMessages()
        adapter?.notifyDataSetChanged()
        if (scrollToEnd) binding?.publicChatMessages?.scrollToPosition(messages.size - 1)
    }

    private fun sortMessages() {
        messages.sortWith(compareBy<LauncherPublicChatBridge.Message> { it.createdAt }.thenBy { it.id })
    }

    private fun removeMessage(id: Int) {
        for (i in messages.indices) {
            if (messages[i].id == id) {
                messages.removeAt(i)
                adapter?.notifyItemRemoved(i)
                return
            }
        }
    }

    private fun renderAnnouncements(announcements: List<LauncherPublicChatBridge.Announcement>) {
        val currentBinding = binding ?: return
        val text = StringBuilder()
        announcements.forEach { item ->
            if (item.active) {
                if (text.isNotEmpty()) text.append("\n\n")
                text.append(item.title).append("\n").append(item.content)
            }
        }
        val hasAnnouncement = text.isNotEmpty()
        currentBinding.publicChatAnnouncementBar.visibility = if (hasAnnouncement) View.VISIBLE else View.GONE
        if (hasAnnouncement) currentBinding.publicChatNotice.text = text.toString()
        relayoutOverlay?.invoke()
    }

    private fun renderStatus() {
        val currentBinding = binding ?: return
        var text = ""
        if (readonly) {
            text = getString(R.string.social_read_only)
        } else if (muted) {
            text = if (muteReason.isEmpty()) {
                getString(R.string.social_muted)
            } else {
                getString(R.string.social_muted_reason, muteReason)
            }
        }
        val canSend = !readonly && !muted
        currentBinding.publicChatInput.isEnabled = canSend
        currentBinding.publicChatInput.hint = if (canSend) getString(R.string.social_input_message) else text
        updateSendState()
        renderConnectionStatus(text)
    }

    private fun updateSendState() {
        if (unavailable()) return
        val currentBinding = binding ?: return
        val hasContent = currentBinding.publicChatInput.text != null &&
            currentBinding.publicChatInput.text.toString().trim().isNotEmpty()
        val enabled = !sending && !readonly && !muted && hasContent
        currentBinding.publicChatSend.isEnabled = enabled
        currentBinding.publicChatSend.alpha = if (enabled) 1f else .45f
    }

    private fun renderConnectionStatus(channelState: String) {
        val currentBinding = binding ?: return
        currentBinding.publicChatConnection.text = if (channelState.isEmpty()) {
            connectionState
        } else {
            "$connectionState · $channelState"
        }
    }

    private fun showError(message: String) {
        if (unavailable()) return
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    /** WebSocket 回调经主线程 post 守卫执行；detach 后丢弃。 */
    private fun runOnUiIfAlive(action: () -> Unit) {
        view?.post {
            if (unavailable()) return@post
            action()
        }
    }

    private inner class RealtimeCallbacks : LauncherPublicChatBridge.RealtimeListener {
        override fun onConnected() {
            runOnUiIfAlive {
                connectionState = getString(R.string.social_connected)
                renderStatus()
                scheduleHeartbeat()
            }
        }

        override fun onMessageCreated(message: LauncherPublicChatBridge.Message) {
            runOnUiIfAlive { upsert(message, true) }
        }

        override fun onMessageDeleted(messageId: Int) {
            runOnUiIfAlive { removeMessage(messageId) }
        }

        override fun onMessagePinned(message: LauncherPublicChatBridge.Message) {
            runOnUiIfAlive { upsert(message, false) }
        }

        override fun onReadonlyChanged(value: Boolean) {
            runOnUiIfAlive {
                readonly = value
                renderStatus()
            }
        }

        override fun onMuted(value: Boolean, mutedUntil: Long?, reason: String) {
            runOnUiIfAlive {
                muted = value
                muteReason = reason
                renderStatus()
            }
        }

        override fun onAnnouncementChanged(announcement: LauncherPublicChatBridge.Announcement) {
            // WebSocket 回调线程，用 applicationContext 快照发起重载，避免 requireContext 跨线程。
            val app = appContext ?: return
            LauncherPublicChatBridge.loadAnnouncements(app, object : LauncherPublicChatBridge.AnnouncementsCallback {
                override fun onSuccess(list: List<LauncherPublicChatBridge.Announcement>) {
                    if (unavailable()) return
                    renderAnnouncements(list)
                }

                override fun onError(message: String) { showError(message) }
            })
        }

        override fun onError(message: String) {
            runOnUiIfAlive {
                connectionState = getString(R.string.social_disconnected)
                renderStatus()
            }
        }
    }

    private fun scheduleHeartbeat() {
        cancelHeartbeat()
        heartbeatDisposable = RxMainScheduler.postDelayed(
            Runnable {
                if (unavailable()) return@Runnable
                if (socket != null) socket?.send("ping")
                scheduleHeartbeat()
            },
            25_000L,
        )
    }

    private fun cancelHeartbeat() {
        heartbeatDisposable?.dispose()
        heartbeatDisposable = null
    }

    /** 按承载宿主分派关闭：竖屏薄宿主 finish，HD 由父 Fragment 关闭子 Fragment。 */
    private fun requestClose() {
        when (val host = activity) {
            is LauncherPublicChatActivity -> host.finishPublicChat()
            is HdModeActivity -> (parentFragment as? HdEmbeddedActivityOwner)?.closeEmbeddedActivity()
            else -> Unit
        }
    }

    private fun unavailable(): Boolean = !isAdded || binding == null
}
