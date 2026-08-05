package com.apps.agent

import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.apps.HDModel.HdEmbeddedActivityOwner
import com.apps.HDModel.HdModeActivity
import com.apps.HDModel.LauncherDialogRouter
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.R
import com.core.agent.runtime.LocalAgentRuntime
import com.core.agent.store.AgentConfigStore
import com.core.agent.store.AgentConversationRepository
import com.core.agent.store.AgentSnapshotStore
import com.core.databinding.ActivityLocalAgentBinding
import com.core.util.AppExecutors
import com.core.util.DevLogger
import com.core.util.RxMainScheduler

/**
 * 本地智能体页（重构计划 9.9 阶段 114）：自 [LocalAgentActivity] 抽取全部逻辑，
 * HD 由 [com.apps.HDModel.HdHomeFragment] 以子 Fragment 承载。
 *
 * 原 Activity 的独立 edge-to-edge 窗口配置保留在薄宿主 [LocalAgentActivity]；
 * 本 Fragment 仅保留 View 级 insets/IME 处理（bindInsets/showImeExplicit）。
 */
class LocalAgentFragment : Fragment() {
    private val logTag = "LocalAgentActivity"
    private var binding: ActivityLocalAgentBinding? = null
    private val messages = ArrayList<AgentConversationRepository.Message>()
    private lateinit var repository: AgentConversationRepository
    private lateinit var adapter: LocalAgentMessageAdapter
    private lateinit var runtime: LocalAgentRuntime
    private var streamingMessage: AgentConversationRepository.Message? = null
    private var reasoningMessage: AgentConversationRepository.Message? = null
    private var pendingUserMessage: AgentConversationRepository.Message? = null
    private val committedReasoning = StringBuilder()
    private val currentRoundText = StringBuilder()
    private var baseBottomPadding = 0
    private var historyLoaded = false
    private var clearingHistory = false
    private var userTouchedInput = false
    private var activeApprovalDialog: AlertDialog? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val currentBinding = ActivityLocalAgentBinding.inflate(inflater, container, false)
        binding = currentBinding
        return currentBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // 防止 EditText 自动获焦弹起键盘：根布局已设 focusable+focusableInTouchMode 抢占焦点。
        // loadHistory 异步回调会让 EditText 从 disabled 切到 enabled，那才是真正触发自动获焦
        // 的时机，由 renderRunning() 中的 clearFocus + hideSoftInput 兜底处理。
        LauncherTabletPortraitScaler.apply(view)
        repository = AgentConversationRepository(requireContext())
        runtime = LocalAgentRuntime(requireContext())
        adapter = LocalAgentMessageAdapter(messages)
        val currentBinding = binding ?: return
        currentBinding.agentMessages.layoutManager = LinearLayoutManager(requireContext())
        currentBinding.agentMessages.adapter = adapter
        // Default change animations cross-fade old/new TextViews. Token-rate updates otherwise
        // leave several text layers visible at once and look like content is overlapping.
        currentBinding.agentMessages.itemAnimator = null
        currentBinding.agentInput.isEnabled = false
        currentBinding.agentSend.isEnabled = false
        baseBottomPadding = currentBinding.agentMessages.paddingBottom
        bindInsets()
        bindTheme()
        bindActions()
        loadHistory()
        renderConfigState()
        // 硬件返回：按承载宿主关闭（对齐薄宿主模式；原 LocalAgentActivity 废弃 onBackPressed 已移除）。
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { requestClose() }
            },
        )
    }

    override fun onDestroyView() {
        dismissApprovalDialog()
        if (::runtime.isInitialized) runtime.close()
        binding = null
        super.onDestroyView()
    }

    /** 按承载宿主分派关闭：竖屏薄宿主 finish，HD 由父 Fragment 关闭子 Fragment。 */
    private fun requestClose() {
        when (val host = activity) {
            is LocalAgentActivity -> host.finishLocalAgent()
            is HdModeActivity -> (parentFragment as? HdEmbeddedActivityOwner)?.closeEmbeddedActivity()
            else -> Unit
        }
    }

    private fun bindTheme() {
        val currentBinding = binding ?: return
        LauncherTheme.applyPrimaryTone(currentBinding.root)
        currentBinding.agentTitleBar.background = LauncherTheme.solidPrimary(requireContext(), 0f)
        currentBinding.agentInfoBar.background = LauncherTheme.secondaryButton(requireContext(), 18f)
        currentBinding.agentInputThemeBar.background = LauncherTheme.secondaryButton(requireContext(), 22f)
        currentBinding.agentEmptyState.background = null
        currentBinding.agentStateIcon.background = LauncherTheme.solidPrimary(requireContext(), 999f)
        currentBinding.agentStateIcon.imageTintList = ColorStateList.valueOf(LauncherTheme.onPrimary(requireContext()))
        val primary = LauncherTheme.primary(requireContext())
        currentBinding.agentInput.setTextColor(primary)
        currentBinding.agentInput.setHintTextColor(LauncherTheme.textMuted(requireContext()))
        LauncherTheme.styleTextInput(currentBinding.agentInput)
        currentBinding.agentSend.background = null
        currentBinding.agentSend.imageTintList = ColorStateList.valueOf(primary)
    }

    private fun bindActions() {
        val currentBinding = binding ?: return
        currentBinding.agentSend.setOnClickListener {
            if (runtime.isRunning()) runtime.cancel() else send()
        }
        currentBinding.agentStateIcon.setOnClickListener { showFeatureMenu() }
        currentBinding.agentTopOverlay.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateListPadding() }
        currentBinding.agentComposerOverlay.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateListPadding() }
        // 用户主动触摸 EditText 时设置标志位，renderRunning 中的 clearFocus + hideSoftInput
        // 逻辑据此跳过，避免与用户已主动唤起的输入状态冲突。
        // 同时主动 requestFocus 并通过 WindowInsetsController 唤起 IME —— edge-to-edge 模式
        // 下 setSoftInputMode 已失效（Android 11+ 弃用），系统自动唤起在某些机型/系统版本
        // 上不可靠（典型复现：Lenovo TB323FU / Android 16）。
        // 不使用 OnFocusChangeListener 主动唤起，避免 IME inset 派发引起焦点抖动。
        currentBinding.agentInput.setOnTouchListener { view, _ ->
            userTouchedInput = true
            if (!view.hasFocus()) {
                view.requestFocus()
            }
            showImeExplicit(view)
            false
        }
    }

    /** 主动唤起 IME，优先使用 API 30+ 的 WindowInsetsController，低版本回退到 IMM。 */
    private fun showImeExplicit(view: View) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val controller = view.windowInsetsController
                if (controller != null) {
                    controller.show(android.view.WindowInsets.Type.ime())
                    return
                }
            }
            val imm = requireContext().getSystemService(InputMethodManager::class.java)
            if (imm != null) imm.showSoftInput(view, 0)
        } catch (error: Exception) {
            Log.d(logTag, "show IME failed", error)
        }
    }

    private fun send() {
        if (clearingHistory) return
        val currentBinding = binding ?: return
        val text = currentBinding.agentInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        if (text.length > 4000) {
            currentBinding.agentInput.error = getString(R.string.social_agent_input_too_long)
            return
        }
        currentBinding.agentInput.setText("")
        val userMessage = AgentConversationRepository.Message(
            -1, "user", text, "", System.currentTimeMillis(),
        )
        pendingUserMessage = userMessage
        messages.add(userMessage)
        reasoningMessage = null
        committedReasoning.setLength(0)
        currentRoundText.setLength(0)
        val streamedMessage = AgentConversationRepository.Message(
            -1, "assistant", "", "", System.currentTimeMillis(),
        )
        streamingMessage = streamedMessage
        messages.add(streamedMessage)
        adapter.notifyItemRangeInserted(messages.size - 2, 2)
        updateEmptyState()
        scrollToEnd()
        setWorkbenchStatus(
            getString(R.string.social_agent_planning),
            getString(R.string.social_agent_planning_detail),
            "02",
        )
        renderRunning(true)
        runtime.send(text, localAgentCallback)
    }

    /** 本地智能体运行时回调：流式文本/推理/工具/审批/完成/错误 的统一处理。 */
    private val localAgentCallback = object : LocalAgentRuntime.Callback {
        override fun onTextDelta(delta: String?) {
            if (unavailable() || streamingMessage == null || delta == null || delta.isEmpty()) return
            currentRoundText.append(delta)
            renderReasoningMessage()
            scrollToEndIfFollowing()
        }

        override fun onReasoningDelta(delta: String?) {
            if (unavailable() || streamingMessage == null || delta == null || delta.isEmpty()) return
            committedReasoning.append(delta)
            renderReasoningMessage()
            scrollToEndIfFollowing()
        }

        override fun onModelRoundFinished(toolRound: Boolean) {
            if (unavailable()) return
            if (toolRound && currentRoundText.length > 0) {
                if (committedReasoning.length > 0) committedReasoning.append("\n\n")
                committedReasoning.append(currentRoundText)
            }
            currentRoundText.setLength(0)
            renderReasoningMessage()
        }

        override fun onToolStarted(name: String) {
            if (!unavailable()) {
                setWorkbenchStatus(getString(R.string.social_agent_executing), name, "03")
            }
        }

        override fun onToolFinished(name: String, success: Boolean) {
            if (!unavailable()) setWorkbenchStatus(
                getString(
                    if (success) R.string.social_agent_operation_complete
                    else R.string.social_agent_operation_incomplete,
                ),
                getString(
                    if (success) R.string.social_agent_preparing_result
                    else R.string.social_agent_analyzing_failure,
                ),
                "04",
            )
        }

        override fun onApprovalRequired(
            request: LocalAgentRuntime.ApprovalRequest,
            responder: LocalAgentRuntime.ApprovalResponder,
        ) {
            if (unavailable()) {
                responder.resolve(false)
                return
            }
            setWorkbenchStatus(
                getString(R.string.social_agent_waiting_confirmation),
                getString(R.string.social_agent_confirmation_detail),
                "05",
            )
            activeApprovalDialog = LauncherDialogRouter.showLongMessageConfirm(
                requireContext(), request.title, request.preview, request.confirmText,
                {
                    activeApprovalDialog = null
                    responder.resolve(true)
                },
                {
                    activeApprovalDialog = null
                    responder.resolve(false)
                },
            )
        }

        override fun onCriticalWarning(title: String, message: String) {
            if (!unavailable()) LauncherDialogRouter.showLongMessageConfirm(
                requireContext(), title, message,
                getString(R.string.social_action_got_it), { }, { },
            )
        }

        override fun onComplete(finalText: String?) {
            if (unavailable()) return
            dismissApprovalDialog()
            streamingMessage?.let { message ->
                // 原 Java 字段可为 null；Kotlin 非空字段用 orEmpty() 等价（null 显示为空）。
                message.content = finalText.orEmpty()
                val index = messages.indexOf(message)
                if (index >= 0) adapter.notifyItemChanged(index)
            }
            reasoningMessage?.let { message ->
                message.name = "complete"
                val index = messages.indexOf(message)
                if (index >= 0) adapter.notifyItemChanged(index)
            }
            streamingMessage = null
            reasoningMessage = null
            pendingUserMessage = null
            renderRunning(false)
            setWorkbenchStatus(
                getString(R.string.social_agent_task_complete),
                getString(R.string.social_agent_task_complete_detail),
                "06",
            )
            updateEmptyState()
        }

        override fun onError(message: String?) {
            if (unavailable()) return
            dismissApprovalDialog()
            removePendingUiMessages()
            renderRunning(false)
            setWorkbenchStatus(getString(R.string.social_agent_task_incomplete), message, "!")
            LauncherDialogRouter.showInfo(
                requireContext(),
                getString(R.string.social_agent_incomplete_title),
                message,
            )
        }
    }

    private fun renderRunning(running: Boolean) {
        val currentBinding = binding ?: return
        val inputEnabled = historyLoaded && !running && !clearingHistory
        currentBinding.agentInput.isEnabled = inputEnabled
        currentBinding.agentSend.isEnabled = historyLoaded && !clearingHistory
        currentBinding.agentSend.alpha = 1f
        currentBinding.agentSend.rotation = if (running) 45f else 0f
        currentBinding.agentSend.contentDescription = getString(
            if (running) R.string.social_agent_stop_task else R.string.social_agent_run_task,
        )
        // loadHistory 异步回调中 setEnabled(true) 会触发 EditText 自动获焦并弹起 IME。
        // 用 userTouchedInput 区分"用户主动点击"与"系统自动获焦"——只有用户未主动操作时
        // 才清除焦点并隐藏 IME，避免影响用户已主动唤起的输入状态。
        if (inputEnabled && !userTouchedInput) {
            currentBinding.agentInput.clearFocus()
            val rootView = currentBinding.root
            if (rootView != null) rootView.requestFocus()
            val imm = requireContext().getSystemService(InputMethodManager::class.java)
            if (imm != null && imm.isAcceptingText) {
                imm.hideSoftInputFromWindow(currentBinding.agentInput.windowToken, 0)
            }
        }
    }

    private fun loadHistory() {
        val loadFailedText = getString(R.string.social_agent_session_load_failed)
        AppExecutors.runOnIo {
            var loaded: List<AgentConversationRepository.Message>? = null
            var loadError: String? = null
            try {
                loaded = repository.recent(100)
            } catch (error: RuntimeException) {
                DevLogger.w(logTag, "Failed to load local agent history", error)
                loadError = error.message ?: loadFailedText
            }
            val delivered = loaded
            val deliveredError = loadError
            RxMainScheduler.post {
                if (!isAdded || binding == null) return@post
                if (delivered != null) {
                    messages.clear()
                    messages.addAll(delivered)
                    adapter.notifyDataSetChanged()
                }
                historyLoaded = true
                clearingHistory = false
                renderRunning(runtime.isRunning())
                scrollToEnd()
                updateEmptyState()
                if (!runtime.isRunning()) renderConfigState()
                if (deliveredError != null) {
                    Toast.makeText(requireContext(), deliveredError, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun confirmClearHistory() {
        if (runtime.isRunning()) {
            Toast.makeText(requireContext(), R.string.social_agent_stop_first, Toast.LENGTH_SHORT).show()
            return
        }
        LauncherDialogRouter.showConfirm(
            requireContext(),
            getString(R.string.social_agent_clear_session),
            getString(R.string.social_agent_clear_session_message),
            getString(R.string.social_action_clear),
        ) {
            clearingHistory = true
            renderRunning(false)
            val clearFailedText = getString(R.string.social_agent_clear_failed)
            AppExecutors.runOnIo {
                try {
                    repository.clear()
                } catch (error: RuntimeException) {
                    DevLogger.w(logTag, "Failed to clear local agent history", error)
                    val message = error.message ?: clearFailedText
                    RxMainScheduler.post {
                        if (!unavailable()) {
                            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                        }
                    }
                } finally {
                    RxMainScheduler.post {
                        if (!unavailable()) loadHistory()
                    }
                }
            }
        }
    }

    private fun showSnapshotHistory() {
        val appContext = requireContext().applicationContext
        val readFailedText = getString(R.string.social_agent_read_mutations_failed)
        AppExecutors.runOnIo {
            var value: String
            try {
                value = AgentSnapshotStore.recentDisplay(appContext, 20)
            } catch (error: Exception) {
                DevLogger.w(logTag, "Failed to load local agent snapshot history", error)
                value = readFailedText
            }
            val delivered = value
            RxMainScheduler.post {
                if (!unavailable()) LauncherDialogRouter.showLongMessageConfirm(
                    requireContext(),
                    getString(R.string.social_agent_mutations_title),
                    delivered,
                    getString(R.string.social_action_got_it),
                    { },
                    { },
                )
            }
        }
    }

    private fun showConfigDialog() {
        AgentConfigDialog.showApiConfig(requireActivity()) { renderConfigState() }
    }

    private fun showFeatureMenu() {
        val items: Array<CharSequence> = arrayOf(
            getString(R.string.social_agent_api_menu),
            getString(R.string.social_agent_mutation_log),
            getString(R.string.social_agent_clear_menu),
            getString(R.string.social_agent_execution_settings),
        )
        LauncherDialogRouter.showStandardActionChoices(
            requireContext(),
            getString(R.string.social_agent_features),
            items,
        ) { index ->
            when (index) {
                0 -> showConfigDialog()
                1 -> showSnapshotHistory()
                2 -> confirmClearHistory()
                3 -> showAgentSettingsDialog()
            }
        }
    }

    private fun showAgentSettingsDialog() {
        AgentConfigDialog.showExecutionSettings(requireActivity()) { renderConfigState() }
    }

    private fun renderConfigState() {
        val config = AgentConfigStore.get(requireContext())
        setWorkbenchStatus(
            getString(if (config.isReady()) R.string.social_agent_ready else R.string.social_agent_need_model),
            if (config.isReady()) {
                getString(
                    R.string.social_agent_current_model,
                    config.model,
                    getString(
                        if (config.isFullPermission()) R.string.social_agent_full_permission_label
                        else R.string.social_agent_restricted_permission_label,
                    ),
                )
            } else {
                getString(R.string.social_agent_configure_api)
            },
            "01",
        )
    }

    private fun bindInsets() {
        val currentBinding = binding ?: return
        ViewCompat.setOnApplyWindowInsetsListener(currentBinding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val safeLeft = maxOf(bars.left, cutout.left)
            val safeTop = maxOf(bars.top, cutout.top)
            val safeRight = maxOf(bars.right, cutout.right)
            val safeBottom = maxOf(bars.bottom, cutout.bottom)
            val keyboardVisible = ime.bottom > safeBottom

            currentBinding.agentTitleBar.setPaddingRelative(
                safeLeft + LauncherTheme.dp(requireContext(), 13),
                safeTop + LauncherTheme.dp(requireContext(), 10),
                safeRight + LauncherTheme.dp(requireContext(), 13),
                LauncherTheme.dp(requireContext(), 13),
            )
            setHorizontalMargins(
                currentBinding.agentInfoBar,
                safeLeft + LauncherTheme.dp(requireContext(), 16),
                safeRight + LauncherTheme.dp(requireContext(), 16),
            )
            setHorizontalMargins(
                currentBinding.agentEmptyState,
                safeLeft + LauncherTheme.dp(requireContext(), 24),
                safeRight + LauncherTheme.dp(requireContext(), 24),
            )
            currentBinding.agentComposerOverlay.setPaddingRelative(
                safeLeft + LauncherTheme.dp(requireContext(), 16),
                0,
                safeRight + LauncherTheme.dp(requireContext(), 16),
                (if (keyboardVisible) 0 else safeBottom) + LauncherTheme.dp(requireContext(), 10),
            )
            setBottomMargin(currentBinding.agentComposerOverlay, if (keyboardVisible) ime.bottom else 0)
            currentBinding.agentMessages.setPadding(
                safeLeft + LauncherTheme.dp(requireContext(), 16),
                currentBinding.agentMessages.paddingTop,
                safeRight + LauncherTheme.dp(requireContext(), 16),
                currentBinding.agentMessages.paddingBottom,
            )
            updateListPadding()
            insets
        }
        ViewCompat.requestApplyInsets(currentBinding.root)
    }

    private fun setHorizontalMargins(view: View, left: Int, right: Int) {
        val params = view.layoutParams
        if (params !is ViewGroup.MarginLayoutParams) return
        if (params.leftMargin == left && params.rightMargin == right) return
        params.leftMargin = left
        params.rightMargin = right
        view.layoutParams = params
    }

    private fun setBottomMargin(view: View, bottom: Int) {
        val params = view.layoutParams
        if (params !is ViewGroup.MarginLayoutParams) return
        if (params.bottomMargin == bottom) return
        params.bottomMargin = bottom
        view.layoutParams = params
    }

    private fun updateListPadding() {
        val currentBinding = binding ?: return
        currentBinding.agentMessages.setPadding(
            currentBinding.agentMessages.paddingLeft,
            currentBinding.agentTopOverlay.height + LauncherTheme.dp(requireContext(), 8),
            currentBinding.agentMessages.paddingRight,
            baseBottomPadding + currentBinding.agentComposerOverlay.height + LauncherTheme.dp(requireContext(), 8),
        )
    }

    private fun scrollToEnd() {
        if (messages.isNotEmpty()) binding?.agentMessages?.scrollToPosition(messages.size - 1)
    }

    private fun scrollToEndIfFollowing() {
        val currentBinding = binding ?: return
        if (messages.isEmpty() || currentBinding.agentMessages.layoutManager !is LinearLayoutManager) return
        val manager = currentBinding.agentMessages.layoutManager as LinearLayoutManager
        if (manager.findLastVisibleItemPosition() >= messages.size - 2) {
            currentBinding.agentMessages.scrollToPosition(messages.size - 1)
        }
    }

    private fun renderReasoningMessage() {
        val currentBinding = binding ?: return
        val streamedMessage = streamingMessage ?: return
        var combined = committedReasoning.toString()
        if (currentRoundText.length > 0) {
            if (combined.isNotEmpty()) combined += "\n\n"
            combined += currentRoundText
        }
        if (combined.isEmpty()) {
            reasoningMessage?.let { message ->
                val index = messages.indexOf(message)
                if (index >= 0) {
                    messages.removeAt(index)
                    adapter.notifyItemRemoved(index)
                }
            }
            reasoningMessage = null
            return
        }
        if (reasoningMessage == null) {
            val newReasoningMessage = AgentConversationRepository.Message(
                -1, "reasoning", combined, "streaming", System.currentTimeMillis(),
            )
            reasoningMessage = newReasoningMessage
            val answerIndex = messages.indexOf(streamedMessage)
            val insertion = if (answerIndex < 0) messages.size else answerIndex
            messages.add(insertion, newReasoningMessage)
            adapter.notifyItemInserted(insertion)
        } else {
            reasoningMessage?.content = combined
            val index = messages.indexOf(reasoningMessage)
            if (index >= 0) adapter.notifyItemChanged(index)
        }
    }

    private fun setWorkbenchStatus(title: String, hint: String?, phase: String) {
        val currentBinding = binding ?: return
        currentBinding.agentStateLabel.text = title
        currentBinding.agentHint.text = hint
    }

    private fun updateEmptyState() {
        val currentBinding = binding ?: return
        currentBinding.agentEmptyState.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun unavailable(): Boolean = !isAdded || binding == null

    private fun dismissApprovalDialog() {
        val dialog = activeApprovalDialog
        activeApprovalDialog = null
        if (dialog != null && dialog.isShowing) dialog.dismiss()
    }

    private fun removePendingUiMessages() {
        streamingMessage?.let { message ->
            val index = messages.indexOf(message)
            if (index >= 0) {
                messages.removeAt(index)
                adapter.notifyItemRemoved(index)
            }
        }
        reasoningMessage?.let { message ->
            val index = messages.indexOf(message)
            if (index >= 0) {
                messages.removeAt(index)
                adapter.notifyItemRemoved(index)
            }
        }
        pendingUserMessage?.let { message ->
            val index = messages.indexOf(message)
            if (index >= 0) {
                messages.removeAt(index)
                adapter.notifyItemRemoved(index)
            }
        }
        streamingMessage = null
        reasoningMessage = null
        committedReasoning.setLength(0)
        currentRoundText.setLength(0)
        pendingUserMessage = null
        updateEmptyState()
    }
}
