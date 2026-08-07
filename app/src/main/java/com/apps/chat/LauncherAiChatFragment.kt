package com.apps.chat

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.apps.HDModel.HdEmbeddedActivityOwner
import com.apps.HDModel.HdModeActivity
import com.apps.HDModel.LauncherDialogRouter
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.R
import com.core.databinding.ActivityLauncherAiChatBinding
import com.core.launcherbridge.LauncherAiChatBridge
import com.core.launcherbridge.LauncherAuthBridge

/**
 * AI 聊天页（重构计划 9.9 W-3，阶段 127）：自 [LauncherAiChatActivity] 抽取全部逻辑，
 * 竖屏由薄宿主 [LauncherAiChatActivity] 承载，HD 由 HdProfileFragment 以子 Fragment 承载
 *（阶段 129 接入）。
 *
 * edge-to-edge 窗口配置保留在薄宿主 Activity；本 Fragment 仅保留 View 级 insets/IME
 * 处理（经 [ChatInsetsHelper]）与聊天交互逻辑（原 Activity 逐字等价迁移）。
 */
class LauncherAiChatFragment : Fragment() {
    private var binding: ActivityLauncherAiChatBinding? = null
    private val messages = ArrayList<LauncherAiChatBridge.Message>()
    private var adapter: LauncherAiChatMessageAdapter? = null
    private var persona: String? = null
    private var threadId: String? = null
    private var characterName: String? = null
    private var sending = false

    /** 原 Activity onCreate 校验失败时置位：跳过界面渲染，直接按宿主关闭。 */
    private var closedEarly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        persona = arguments?.getString(EXTRA_PERSONA)
        threadId = arguments?.getString(EXTRA_THREAD_ID)
        // 登录与参数校验（原 Activity onCreate 行为）：不合法时提示并关闭，不渲染聊天界面。
        if (!LauncherAuthBridge.isLoggedIn(requireContext())) {
            Toast.makeText(requireContext(), R.string.social_ai_login_required, Toast.LENGTH_SHORT).show()
            closedEarly = true
            requestClose()
            return
        }
        val currentPersona = persona
        val currentThreadId = threadId
        if (currentPersona == null || !PERSONA_PATTERN.matches(currentPersona) ||
            currentThreadId == null || !THREAD_PATTERN.matches(currentThreadId)
        ) {
            Toast.makeText(requireContext(), R.string.social_invalid_chat_character, Toast.LENGTH_SHORT).show()
            closedEarly = true
            requestClose()
            return
        }
        val title = arguments?.getString(EXTRA_TITLE)
        characterName = if (title == null || title.trim().isEmpty()) {
            getString(R.string.social_ai_chat)
        } else {
            title.replace("（AI）", "")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val currentBinding = ActivityLauncherAiChatBinding.inflate(inflater, container, false)
        binding = currentBinding
        return currentBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (activity !is HdModeActivity) LauncherTabletPortraitScaler.apply(view)
        if (closedEarly) return
        val currentBinding = binding ?: return
        val rawTitle = arguments?.getString(EXTRA_TITLE)
        currentBinding.aiChatTitle.text = if (rawTitle == null || rawTitle.trim().isEmpty()) {
            getString(R.string.social_ai_chat)
        } else {
            rawTitle
        }
        ChatInsetsHelper.install(
            currentBinding.aiChatRoot,
            object : ChatInsetsLayout {
                override val topOverlay: View get() = currentBinding.aiChatTopOverlay
                override val titleBar: View get() = currentBinding.aiChatTitleBar
                override val composerOverlay: View get() = currentBinding.aiChatComposerOverlay
                override val inputThemeBar: View get() = currentBinding.aiChatInputThemeBar
                override val messages: View get() = currentBinding.aiChatMessages
            },
            currentBinding.aiChatMessages.paddingBottom,
        )
        val currentAdapter = LauncherAiChatMessageAdapter(
            messages,
            characterName ?: getString(R.string.social_ai_chat),
        )
        adapter = currentAdapter
        currentBinding.aiChatMessages.layoutManager = LinearLayoutManager(requireContext())
        currentBinding.aiChatMessages.adapter = currentAdapter
        LauncherTheme.applyPrimaryTone(currentBinding.aiChatRoot)
        currentBinding.aiChatTitleBar.background = LauncherTheme.primaryButton(requireContext(), 0f)
        currentBinding.aiChatTitle.setTextColor(LauncherTheme.onPrimary(requireContext()))
        currentBinding.aiChatMore.setTextColor(LauncherTheme.onPrimary(requireContext()))
        currentBinding.aiChatInputThemeBar.background = LauncherTheme.primaryButton(requireContext(), 0f)
        currentBinding.aiChatInput.setTextColor(LauncherTheme.text(requireContext()))
        currentBinding.aiChatInput.setHintTextColor(LauncherTheme.textMuted(requireContext()))
        currentBinding.aiChatCharacterIcon.background = LauncherTheme.circle(requireContext())
        currentBinding.aiChatSend.imageTintList = ColorStateList.valueOf(LauncherTheme.primary(requireContext()))
        currentBinding.aiChatMore.setOnClickListener { showMoreMenu(it) }
        currentBinding.aiChatSend.setOnClickListener { sendMessage() }
        currentBinding.aiChatInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderInputState() }
            override fun afterTextChanged(s: Editable?) {}
        })
        renderInputState()
        loadHistory()
        // 硬件返回：按承载宿主关闭（原 onBackPressed → LauncherMotion.finish 语义，双上下文统一）。
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { requestClose() }
            },
        )
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun showMoreMenu(anchor: View) {
        val menu = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.launcher_white_card)
            setPadding(
                LauncherTheme.dp(requireContext(), 7),
                LauncherTheme.dp(requireContext(), 7),
                LauncherTheme.dp(requireContext(), 7),
                LauncherTheme.dp(requireContext(), 7),
            )
        }
        // 菜单宽度带屏幕兜底：小屏设备上不超过屏幕宽度减去两侧 48dp 边距，禁止裸固定 dp 宽度
        val menuWidth = minOf(
            LauncherTheme.dp(requireContext(), 119),
            resources.displayMetrics.widthPixels - LauncherTheme.dp(requireContext(), 48),
        )
        val popupWindow = PopupWindow(menu, menuWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popupWindow.isOutsideTouchable = true
        // 纯透明遮罩：PopupWindow 必须设置背景才能拦截外部点击并触发关闭，此处仅作遮罩不参与绘制
        popupWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popupWindow.animationStyle = R.style.LauncherDialogAnimation

        addMoreMenuItem(menu, getString(R.string.social_custom_model), popupWindow) {
            // LLM 配置对话框强依赖 Activity（Dialog 宿主/IME/生命周期），传 Activity 上下文。
            AgentLlmConfigDialog(requireActivity()).show()
        }
        addMoreMenuItem(menu, getString(R.string.social_clear_history), popupWindow) {
            showClearConfirmDialog()
        }
        popupWindow.showAsDropDown(
            anchor,
            anchor.width - menuWidth,
            LauncherTheme.dp(requireContext(), 5),
            Gravity.NO_GRAVITY,
        )
    }

    private fun addMoreMenuItem(menu: LinearLayout, label: String, popupWindow: PopupWindow, action: () -> Unit) {
        val item = TextView(requireContext()).apply {
            text = label
            setTextSize(13f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            isSingleLine = true
            setPadding(LauncherTheme.dp(requireContext(), 13), 0, LauncherTheme.dp(requireContext(), 13), 0)
            setTextColor(LauncherTheme.primary(requireContext()))
            // 菜单项透明背景：容器 menu 已绘制 launcher_white_card 圆角卡片，菜单项不再叠加背景
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                popupWindow.dismiss()
                action()
            }
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LauncherTheme.dp(requireContext(), 34),
        )
        params.setMargins(0, 0, 0, LauncherTheme.dp(requireContext(), 5))
        menu.addView(item, params)
    }

    private fun loadHistory() {
        val currentBinding = binding ?: return
        val currentThreadId = threadId ?: return
        currentBinding.aiChatHint.setText(R.string.social_loading_chat_history)
        LauncherAiChatBridge.loadHistory(requireContext(), currentThreadId, object : LauncherAiChatBridge.HistoryCallback {
            override fun onSuccess(loaded: List<LauncherAiChatBridge.Message>) {
                if (unavailable()) return
                messages.clear()
                loaded.forEach { item ->
                    if (item.role == "user" || item.role == "assistant" || item.role == "tool") {
                        messages.add(item)
                    }
                }
                adapter?.notifyDataSetChanged()
                currentBinding.aiChatHint.setText(
                    if (messages.isEmpty()) R.string.social_start_chatting else R.string.social_history_loaded,
                )
                scrollToEnd()
            }

            override fun onError(message: String) {
                if (unavailable()) return
                currentBinding.aiChatHint.setText(R.string.social_history_load_failed)
                showError(message)
            }
        })
    }

    private fun sendMessage() {
        val currentBinding = binding ?: return
        val currentPersona = persona ?: return
        val currentThreadId = threadId ?: return
        val text = currentBinding.aiChatInput.text?.toString()?.trim().orEmpty()
        val length = weightedLength(text)
        if (sending || text.isEmpty() || length > 100) return
        sending = true
        currentBinding.aiChatInput.setText("")
        messages.add(LauncherAiChatBridge.Message("user", text, ""))
        adapter?.notifyItemInserted(messages.size - 1)
        scrollToEnd()
        renderInputState()
        currentBinding.aiChatHint.setText(R.string.social_replying)
        LauncherAiChatBridge.send(requireContext(), text, currentPersona, currentThreadId, object : LauncherAiChatBridge.ReplyCallback {
            override fun onSuccess(reply: String) {
                if (unavailable()) return
                sending = false
                messages.add(LauncherAiChatBridge.Message("assistant", reply, ""))
                adapter?.notifyItemInserted(messages.size - 1)
                currentBinding.aiChatHint.setText(R.string.social_reply_complete)
                scrollToEnd()
                renderInputState()
            }

            override fun onError(message: String) {
                if (unavailable()) return
                sending = false
                currentBinding.aiChatHint.setText(R.string.social_reply_failed)
                renderInputState()
                showError(message)
            }
        })
    }

    private fun renderInputState() {
        val currentBinding = binding ?: return
        val length = weightedLength(currentBinding.aiChatInput.text?.toString().orEmpty())
        currentBinding.aiChatSend.isEnabled = !sending && length > 0 && length <= 100
        currentBinding.aiChatSend.alpha = if (currentBinding.aiChatSend.isEnabled) 1f else .45f
    }

    private fun showClearConfirmDialog() {
        val currentThreadId = threadId ?: return
        LauncherDialogRouter.showDangerConfirm(
            requireContext(),
            getString(R.string.social_clear_history_title),
            getString(R.string.social_clear_history_message),
            getString(R.string.social_action_clear),
        ) {
            LauncherAiChatBridge.clearHistory(requireContext(), currentThreadId, object : LauncherAiChatBridge.Callback {
                override fun onSuccess() {
                    if (unavailable()) return
                    messages.clear()
                    adapter?.notifyDataSetChanged()
                    binding?.aiChatHint?.setText(R.string.social_history_cleared)
                }

                override fun onError(error: String) {
                    if (unavailable()) return
                    showError(error)
                }
            })
        }
    }

    private fun weightedLength(value: String): Int {
        if (value.isEmpty()) return 0
        var halfUnits = 0
        for (i in value.indices) {
            halfUnits += if (value[i] <= '\u007f') 1 else 2
        }
        return (halfUnits + 1) / 2
    }

    private fun scrollToEnd() {
        if (!messages.isEmpty()) binding?.aiChatMessages?.scrollToPosition(messages.size - 1)
    }

    private fun showError(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    /** 按承载宿主分派关闭：竖屏薄宿主 finish，HD 由父 Fragment 关闭子 Fragment。 */
    private fun requestClose() {
        when (val host = activity) {
            is LauncherAiChatActivity -> host.finishAiChat()
            is HdModeActivity -> (parentFragment as? HdEmbeddedActivityOwner)?.closeEmbeddedActivity()
            else -> Unit
        }
    }

    private fun unavailable(): Boolean = !isAdded || binding == null

    companion object {
        /** 聊天参数键（Activity intent extra 与 Fragment arg 共用，阶段 127 单源化）。 */
        const val EXTRA_PERSONA = "persona"
        const val EXTRA_THREAD_ID = "thread_id"
        const val EXTRA_TITLE = "title"

        private val PERSONA_PATTERN = Regex("persona_[A-Za-z0-9_]+")
        private val THREAD_PATTERN = Regex("[A-Za-z0-9_.:-]{1,128}")

        @JvmStatic
        fun newInstance(persona: String?, threadId: String?, title: String?): LauncherAiChatFragment =
            LauncherAiChatFragment().apply {
                arguments = Bundle().apply {
                    putString(EXTRA_PERSONA, persona)
                    putString(EXTRA_THREAD_ID, threadId)
                    putString(EXTRA_TITLE, title)
                }
            }
    }
}
