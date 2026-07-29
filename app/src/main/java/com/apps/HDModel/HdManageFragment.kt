package com.apps.HDModel

import android.app.LocalActivityManager
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.apps.game.LauncherManageFragment
import com.apps.game.LauncherAddGameActivity
import com.apps.settings.LauncherKrkrSettingsActivity
import com.apps.settings.LauncherMetadataSourceActivity
import com.apps.sync.LauncherSyncCenterActivity
import com.core.R

/**
 * HD 管理页：复用管理页全部控制器和交互，以左右分栏 XML 适配大屏内容容器。
 */
@Suppress("DEPRECATION")
class HdManageFragment : LauncherManageFragment(), HdEmbeddedActivityOwner {
    private var detailContainer: FrameLayout? = null
    private var localActivityManager: LocalActivityManager? = null
    private var embeddedActivityId: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val root = inflater.inflate(R.layout.fragment_hd_manage, container, false)
        bindManageRoot(root)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        localActivityManager = LocalActivityManager(requireActivity(), false).apply {
            dispatchCreate(savedInstanceState)
        }
        detailContainer = view.findViewById(R.id.hdManageDetailContainer)
        super.onViewCreated(view, savedInstanceState)
        openAddGame()
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

    override fun usePortraitManageScaler(): Boolean = false

    override fun applyManageSystemBarInsets(): Boolean = false

    override fun openAddGame() {
        showEmbeddedActivity("hd_add_game", LauncherAddGameActivity::class.java)
    }

    override fun openMetadataSource() {
        showEmbeddedActivity("hd_metadata_source", LauncherMetadataSourceActivity::class.java)
    }

    override fun openKrkrSettings() {
        showEmbeddedActivity("hd_engine_settings", LauncherKrkrSettingsActivity::class.java)
    }

    override fun openSyncCenter() {
        showEmbeddedActivity("hd_sync_center", LauncherSyncCenterActivity::class.java)
    }

    private fun showEmbeddedActivity(id: String, activityClass: Class<*>) {
        val manager = localActivityManager ?: return
        val container = detailContainer ?: return
        embeddedActivityId = id
        val window = manager.startActivity(
            id,
            Intent(requireContext(), activityClass).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
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
