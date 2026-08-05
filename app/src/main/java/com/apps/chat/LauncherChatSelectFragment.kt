package com.apps.chat

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.apps.HDModel.HdModeActivity
import com.apps.theme.LauncherMotion
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.R
import com.core.databinding.ActivityLauncherChatSelectBinding
import com.core.launcherbridge.LauncherAuthBridge

/**
 * 聊天选择页（重构计划 9.9 W-3，阶段 129）：自 [LauncherChatSelectActivity] 抽取全部逻辑，
 * 竖屏由薄宿主 [LauncherChatSelectActivity] 承载，HD 由 HdProfileFragment 以子 Fragment 承载
 *（选择确认后经 [openChatDestination] 把自身替换为聊天子 Fragment，替代原 HdChatSelectActivity
 * 的嵌入 Activity 路由）。
 */
class LauncherChatSelectFragment : Fragment() {
    private var binding: ActivityLauncherChatSelectBinding? = null
    private var selectedChat = CHAT_PUBLIC

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val currentBinding = ActivityLauncherChatSelectBinding.inflate(inflater, container, false)
        binding = currentBinding
        return currentBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        LauncherTabletPortraitScaler.apply(view)
        val currentBinding = binding ?: return
        applySystemBarInsets()
        currentBinding.publicChatRow.setOnClickListener { selectChat(CHAT_PUBLIC) }
        currentBinding.yukiAiRow.setOnClickListener { selectChat(CHAT_YUKI) }
        currentBinding.rinmiAiRow.setOnClickListener { selectChat(CHAT_RINNE) }
        currentBinding.chatSelectContinue.setOnClickListener { openSelectedChat() }
        LauncherTheme.applyPrimaryTone(view)
        LauncherTheme.longActionButton(currentBinding.chatSelectContinue)
        applyIconTone()
        renderSelection()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun applySystemBarInsets() {
        val currentBinding = binding ?: return
        val scroll = currentBinding.chatSelectScroll
        val left = scroll.paddingLeft
        val top = scroll.paddingTop
        val right = scroll.paddingRight
        val bottom = scroll.paddingBottom
        currentBinding.root.setOnApplyWindowInsetsListener { _, insets ->
            scroll.setPadding(left, top + insets.systemWindowInsetTop, right, bottom)
            insets
        }
        currentBinding.root.requestApplyInsets()
    }

    private fun applyIconTone() {
        binding?.publicChatRow?.getChildAt(0)?.background = LauncherTheme.circle(requireContext())
    }

    private fun openSelectedChat() {
        if (!LauncherAuthBridge.isLoggedIn(requireContext())) {
            Toast.makeText(requireContext(), R.string.social_chat_login_required, Toast.LENGTH_LONG).show()
            return
        }
        val intent: Intent
        if (CHAT_YUKI == selectedChat) {
            intent = Intent(requireContext(), LauncherAiChatActivity::class.java)
                .putExtra(LauncherAiChatFragment.EXTRA_PERSONA, "persona_yuki")
                .putExtra(LauncherAiChatFragment.EXTRA_THREAD_ID, "launcher-yuki")
                .putExtra(LauncherAiChatFragment.EXTRA_TITLE, getString(R.string.social_yuki_ai))
        } else if (CHAT_RINNE == selectedChat) {
            intent = Intent(requireContext(), LauncherAiChatActivity::class.java)
                .putExtra(LauncherAiChatFragment.EXTRA_PERSONA, "persona_rinne")
                .putExtra(LauncherAiChatFragment.EXTRA_THREAD_ID, "launcher-rinne")
                .putExtra(LauncherAiChatFragment.EXTRA_TITLE, getString(R.string.social_rinne_ai))
        } else {
            intent = Intent(requireContext(), LauncherPublicChatActivity::class.java)
        }
        openChatDestination(intent)
    }

    /**
     * 选择确认后路由：竖屏薄宿主启动聊天 Activity（原 openChatDestination 语义）；
     * HD 子 Fragment 承载时把自身替换为聊天子 Fragment（原 HdChatSelectActivity 路由语义）。
     */
    private fun openChatDestination(intent: Intent) {
        when (val host = activity) {
            is LauncherChatSelectActivity -> {
                host.startActivity(intent)
                LauncherMotion.applyActivityOpen(host)
            }
            is HdModeActivity -> {
                val isAi = intent.component?.className == LauncherAiChatActivity::class.java.name
                val chatFragment: Fragment = if (isAi) {
                    LauncherAiChatFragment.newInstance(
                        persona = intent.getStringExtra(LauncherAiChatFragment.EXTRA_PERSONA),
                        threadId = intent.getStringExtra(LauncherAiChatFragment.EXTRA_THREAD_ID),
                        title = intent.getStringExtra(LauncherAiChatFragment.EXTRA_TITLE),
                    )
                } else {
                    LauncherPublicChatFragment()
                }
                parentFragmentManager.beginTransaction()
                    .setCustomAnimations(R.anim.launcher_fragment_enter, R.anim.launcher_fragment_exit)
                    .replace(R.id.hdProfileDetailContainer, chatFragment, if (isAi) CHILD_CHAT_AI_TAG else CHILD_CHAT_PUBLIC_TAG)
                    .commit()
            }
            else -> Unit
        }
    }

    private fun selectChat(chatName: String) {
        selectedChat = chatName
        renderSelection()
    }

    private fun renderSelection() {
        val currentBinding = binding ?: return
        val publicSelected = CHAT_PUBLIC == selectedChat
        val yukiSelected = CHAT_YUKI == selectedChat
        val rinmiSelected = CHAT_RINNE == selectedChat

        currentBinding.publicChatRow.setBackgroundResource(if (publicSelected) 0 else R.drawable.launcher_chat_option_bg)
        if (publicSelected) currentBinding.publicChatRow.background = LauncherTheme.selectedOption(requireContext())
        currentBinding.yukiAiRow.setBackgroundResource(if (yukiSelected) 0 else R.drawable.launcher_chat_option_bg)
        if (yukiSelected) currentBinding.yukiAiRow.background = LauncherTheme.selectedOption(requireContext())
        currentBinding.rinmiAiRow.setBackgroundResource(if (rinmiSelected) 0 else R.drawable.launcher_chat_option_bg)
        if (rinmiSelected) currentBinding.rinmiAiRow.background = LauncherTheme.selectedOption(requireContext())

        currentBinding.publicChatCheck.visibility = if (publicSelected) View.VISIBLE else View.INVISIBLE
        currentBinding.yukiAiCheck.visibility = if (yukiSelected) View.VISIBLE else View.INVISIBLE
        currentBinding.rinmiAiCheck.visibility = if (rinmiSelected) View.VISIBLE else View.INVISIBLE
    }

    companion object {
        private const val CHAT_PUBLIC = "public"
        private const val CHAT_YUKI = "yuki"
        private const val CHAT_RINNE = "rinne"

        /** HD 聊天子 Fragment tag（HdProfileFragment.closeEmbeddedActivity 按此关闭）。 */
        internal const val CHILD_CHAT_AI_TAG = "hd_chat_ai"
        internal const val CHILD_CHAT_PUBLIC_TAG = "hd_chat_public"
    }
}
