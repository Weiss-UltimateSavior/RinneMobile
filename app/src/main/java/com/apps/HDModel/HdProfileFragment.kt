package com.apps.HDModel

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import com.apps.account.LauncherAccountSettingsFragment
import com.apps.chat.LauncherChatSelectFragment
import com.apps.leaderboard.LauncherLeaderboardFragment
import com.apps.profile.LauncherModuleCompatibilityFragment
import com.apps.profile.LauncherProfileEditFragment
import com.apps.profile.LauncherProfileFragment
import com.apps.translation.TranslationSettingFragment
import com.core.R

/**
 * HD 个人页：复用账户资料业务，以双栏内容适配大屏容器。
 *
 * 重构计划 9.9 阶段 111/129：5 个设置目标（资料编辑/账号设置/模块兼容/翻译设置/排行榜）
 * 与聊天流（[LauncherChatSelectFragment] 选择 → 聊天子 Fragment）全部迁子 Fragment 承载，
 * embeddedHost（LocalActivityManager 嵌入）已整体移除（阶段 129，W-3 收官）。
 */
class HdProfileFragment : LauncherProfileFragment(), HdEmbeddedActivityOwner {
    private var detailContainer: FrameLayout? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val root = inflater.inflate(R.layout.fragment_hd_profile, container, false)
        bindProfileRoot(root)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        detailContainer = view.findViewById(R.id.hdProfileDetailContainer)
        super.onViewCreated(view, savedInstanceState)
        openAccountSettings()
    }

    override fun onDestroyView() {
        detailContainer = null
        super.onDestroyView()
    }

    override fun usePortraitProfileScaler(): Boolean = false

    override fun applyProfileSystemBarInsets(): Boolean = false

    override fun profileFragmentContainerId(): Int = R.id.hdFragmentContainer

    override fun createAccountFragment(): Fragment = HdAccountFragment()

    override fun openProfileEdit() {
        showChildFragment(CHILD_PROFILE_EDIT_TAG, LauncherProfileEditFragment())
    }

    override fun openAccountSettings() {
        showChildFragment(CHILD_ACCOUNT_SETTINGS_TAG, LauncherAccountSettingsFragment())
    }

    override fun openChatRoom() {
        showChildFragment(CHILD_CHAT_SELECT_TAG, LauncherChatSelectFragment())
    }

    override fun openModuleCompatibility() {
        showChildFragment(CHILD_MODULE_COMPAT_TAG, LauncherModuleCompatibilityFragment())
    }

    override fun openTranslationSettings() {
        showChildFragment(CHILD_TRANSLATION_TAG, TranslationSettingFragment())
    }

    override fun openLeaderboard() {
        showChildFragment(CHILD_LEADERBOARD_TAG, LauncherLeaderboardFragment())
    }

    private fun showChildFragment(tag: String, fragment: Fragment) {
        if (!isAdded || detailContainer == null) return
        childFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.launcher_fragment_enter,
                R.anim.launcher_fragment_exit,
            )
            .replace(R.id.hdProfileDetailContainer, fragment, tag)
            .commit()
    }

    override fun closeEmbeddedActivity(child: Activity?): Boolean {
        // 子 Fragment 路径（8 个目标：资料编辑/账号设置/模块兼容/翻译设置/排行榜/聊天选择/AI 聊天/公共聊天）
        val existing = childFragmentManager.findFragmentByTag(CHILD_PROFILE_EDIT_TAG)
            ?: childFragmentManager.findFragmentByTag(CHILD_ACCOUNT_SETTINGS_TAG)
            ?: childFragmentManager.findFragmentByTag(CHILD_MODULE_COMPAT_TAG)
            ?: childFragmentManager.findFragmentByTag(CHILD_TRANSLATION_TAG)
            ?: childFragmentManager.findFragmentByTag(CHILD_LEADERBOARD_TAG)
            ?: childFragmentManager.findFragmentByTag(CHILD_CHAT_SELECT_TAG)
            ?: childFragmentManager.findFragmentByTag(LauncherChatSelectFragment.CHILD_CHAT_AI_TAG)
            ?: childFragmentManager.findFragmentByTag(LauncherChatSelectFragment.CHILD_CHAT_PUBLIC_TAG)
        if (existing != null) {
            childFragmentManager.beginTransaction()
                .setCustomAnimations(R.anim.launcher_fragment_enter, R.anim.launcher_fragment_exit)
                .remove(existing)
                .commit()
            return true
        }
        return false
    }

    companion object {
        private const val CHILD_PROFILE_EDIT_TAG = "hd_profile_edit"
        private const val CHILD_ACCOUNT_SETTINGS_TAG = "hd_account_settings"
        private const val CHILD_MODULE_COMPAT_TAG = "hd_module_compatibility"
        private const val CHILD_TRANSLATION_TAG = "hd_translation_settings"
        private const val CHILD_LEADERBOARD_TAG = "hd_leaderboard"
        private const val CHILD_CHAT_SELECT_TAG = "hd_chat_select"
    }
}
