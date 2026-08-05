package com.apps.HDModel

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import com.apps.account.LauncherAccountSettingsFragment
import com.apps.leaderboard.LauncherLeaderboardFragment
import com.apps.profile.LauncherModuleCompatibilityFragment
import com.apps.profile.LauncherProfileEditFragment
import com.apps.profile.LauncherProfileFragment
import com.apps.translation.TranslationSettingFragment
import com.core.R

/**
 * HD 个人页：复用账户资料业务，以双栏内容适配大屏容器。
 *
 * 重构计划 9.9 阶段 111：5 个设置目标（资料编辑/账号设置/模块兼容/翻译设置/排行榜）
 * 迁子 Fragment 承载；聊天流（[HdChatSelectActivity] 路由到任意聊天 Activity）暂保留
 * embeddedHost 承载（聊天目标为大型 Activity，迁子 Fragment 留待后续阶段）。
 */
@Suppress("DEPRECATION")
class HdProfileFragment : LauncherProfileFragment(), HdEmbeddedActivityOwner {
    private var detailContainer: FrameLayout? = null
    private var embeddedHost: HdEmbeddedActivityHost? = null

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
        embeddedHost = HdEmbeddedActivityHost(requireActivity()).also { it.onCreate(savedInstanceState) }
        detailContainer = view.findViewById(R.id.hdProfileDetailContainer)
        super.onViewCreated(view, savedInstanceState)
        openAccountSettings()
    }

    override fun onResume() {
        super.onResume()
        embeddedHost?.onResume()
    }

    override fun onPause() {
        embeddedHost?.onPause()
        super.onPause()
    }

    override fun onStop() {
        embeddedHost?.onStop()
        super.onStop()
    }

    override fun onDestroyView() {
        embeddedHost?.onDestroyView()
        embeddedHost = null
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
        showEmbeddedActivity("hd_chat_room", HdChatSelectActivity::class.java)
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

    private fun showEmbeddedActivity(id: String, activityClass: Class<*>) {
        showEmbeddedActivity(id, Intent(requireContext(), activityClass))
    }

    internal fun showEmbeddedActivity(id: String, intent: Intent) {
        val host = embeddedHost ?: return
        val container = detailContainer ?: return
        val content = host.start(
            id,
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        ) ?: return
        HdPageMotion.showEmbedded(container, content)
    }

    override fun closeEmbeddedActivity(child: Activity?): Boolean {
        val existing = childFragmentManager.findFragmentByTag(CHILD_PROFILE_EDIT_TAG)
            ?: childFragmentManager.findFragmentByTag(CHILD_ACCOUNT_SETTINGS_TAG)
            ?: childFragmentManager.findFragmentByTag(CHILD_MODULE_COMPAT_TAG)
            ?: childFragmentManager.findFragmentByTag(CHILD_TRANSLATION_TAG)
            ?: childFragmentManager.findFragmentByTag(CHILD_LEADERBOARD_TAG)
        if (existing != null) {
            childFragmentManager.beginTransaction()
                .setCustomAnimations(R.anim.launcher_fragment_enter, R.anim.launcher_fragment_exit)
                .remove(existing)
                .commit()
            return true
        }
        // 聊天流仍由 embeddedHost 承载（HdChatSelectActivity 路由到任意聊天 Activity）。
        val host = embeddedHost
        val id = host?.beginClose(child) ?: return false
        detailContainer?.apply {
            HdPageMotion.closeEmbedded(this) {
                post { host.destroy(id) }
            }
        }
        return true
    }

    companion object {
        private const val CHILD_PROFILE_EDIT_TAG = "hd_profile_edit"
        private const val CHILD_ACCOUNT_SETTINGS_TAG = "hd_account_settings"
        private const val CHILD_MODULE_COMPAT_TAG = "hd_module_compatibility"
        private const val CHILD_TRANSLATION_TAG = "hd_translation_settings"
        private const val CHILD_LEADERBOARD_TAG = "hd_leaderboard"
    }
}
