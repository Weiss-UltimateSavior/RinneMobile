package com.apps.HDModel

import android.app.LocalActivityManager
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import com.apps.account.LauncherAccountSettingsActivity
import com.apps.leaderboard.LauncherLeaderboardActivity
import com.apps.profile.LauncherModuleCompatibilityActivity
import com.apps.profile.LauncherProfileEditActivity
import com.apps.profile.LauncherProfileFragment
import com.core.R
import com.core.translation.TranslationSettingActivity

/** HD 个人页：复用账户资料业务，以双栏内容适配大屏容器。 */
@Suppress("DEPRECATION")
class HdProfileFragment : LauncherProfileFragment(), HdEmbeddedActivityOwner {
    private var detailContainer: FrameLayout? = null
    private var localActivityManager: LocalActivityManager? = null
    private var embeddedActivityId: String? = null

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
        localActivityManager = LocalActivityManager(requireActivity(), false).apply {
            dispatchCreate(savedInstanceState)
        }
        detailContainer = view.findViewById(R.id.hdProfileDetailContainer)
        super.onViewCreated(view, savedInstanceState)
        openAccountSettings()
    }

    override fun onResume() {
        super.onResume()
        localActivityManager?.dispatchResume()
    }

    override fun onPause() {
        localActivityManager?.dispatchPause(requireActivity().isFinishing)
        super.onPause()
    }

    override fun onStop() {
        localActivityManager?.dispatchStop()
        super.onStop()
    }

    override fun onDestroyView() {
        localActivityManager?.dispatchDestroy(requireActivity().isFinishing)
        localActivityManager = null
        detailContainer = null
        embeddedActivityId = null
        super.onDestroyView()
    }

    override fun usePortraitProfileScaler(): Boolean = false

    override fun applyProfileSystemBarInsets(): Boolean = false

    override fun profileFragmentContainerId(): Int = R.id.hdFragmentContainer

    override fun createAccountFragment(): Fragment = HdAccountFragment()

    override fun openProfileEdit() {
        showEmbeddedActivity("hd_profile_edit", LauncherProfileEditActivity::class.java)
    }

    override fun openAccountSettings() {
        showEmbeddedActivity("hd_account_settings", LauncherAccountSettingsActivity::class.java)
    }

    override fun openChatRoom() {
        showEmbeddedActivity("hd_chat_room", HdChatSelectActivity::class.java)
    }

    override fun openModuleCompatibility() {
        showEmbeddedActivity(
            "hd_module_compatibility",
            LauncherModuleCompatibilityActivity::class.java,
        )
    }

    override fun openTranslationSettings() {
        showEmbeddedActivity("hd_translation_settings", TranslationSettingActivity::class.java)
    }

    override fun openLeaderboard() {
        showEmbeddedActivity("hd_leaderboard", LauncherLeaderboardActivity::class.java)
    }

    private fun showEmbeddedActivity(id: String, activityClass: Class<*>) {
        showEmbeddedActivity(id, Intent(requireContext(), activityClass))
    }

    internal fun showEmbeddedActivity(id: String, intent: Intent) {
        val manager = localActivityManager ?: return
        val container = detailContainer ?: return
        embeddedActivityId = id
        val window = manager.startActivity(
            id,
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        ) ?: return
        val content = window.decorView
        HdPageMotion.showEmbedded(container, content)
    }

    override fun closeEmbeddedActivity(child: Activity?): Boolean {
        val manager = localActivityManager ?: return false
        val id = embeddedActivityId ?: return false
        val current = manager.currentActivity
        if (child != null && current != null && current !== child) return false
        embeddedActivityId = null
        detailContainer?.apply {
            HdPageMotion.closeEmbedded(this) {
                post { manager.destroyActivity(id, true) }
            }
        }
        return true
    }
}
