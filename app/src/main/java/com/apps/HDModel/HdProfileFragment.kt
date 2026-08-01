package com.apps.HDModel

import android.Manifest
import android.app.LocalActivityManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.apps.account.LauncherAccountSettingsActivity
import com.apps.leaderboard.LauncherLeaderboardActivity
import com.apps.profile.LauncherModuleCompatibilityActivity
import com.apps.profile.LauncherProfileEditActivity
import com.apps.profile.LauncherProfileFragment
import com.core.R
import com.apps.translation.TranslationSettingActivity

/** HD 个人页：复用账户资料业务，以双栏内容适配大屏容器。 */
@Suppress("DEPRECATION")
class HdProfileFragment : LauncherProfileFragment(), HdEmbeddedActivityOwner {
    private var detailContainer: FrameLayout? = null
    private var localActivityManager: LocalActivityManager? = null
    private var embeddedActivityId: String? = null
    private var pendingProjectionCallback: ((resultCode: Int, data: Intent?) -> Unit)? = null
    private var pendingNotificationPermissionCallback: ((Boolean) -> Unit)? = null

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = pendingProjectionCallback
            pendingProjectionCallback = null
            callback?.invoke(result.resultCode, result.data)
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val callback = pendingNotificationPermissionCallback
            pendingNotificationPermissionCallback = null
            callback?.invoke(granted)
        }

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
        pendingProjectionCallback = null
        pendingNotificationPermissionCallback = null
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

    override fun launchTranslationProjection(callback: (resultCode: Int, data: Intent?) -> Unit): Boolean {
        if (!isAdded) return false
        val mediaProjectionManager = requireContext()
            .getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        pendingProjectionCallback = callback
        projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        return true
    }

    override fun requestTranslationNotificationPermission(callback: (Boolean) -> Unit): Boolean {
        if (!isAdded) return false
        pendingNotificationPermissionCallback = callback
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        return true
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
