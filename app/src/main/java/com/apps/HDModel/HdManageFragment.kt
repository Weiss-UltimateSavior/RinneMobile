package com.apps.HDModel

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
    private var embeddedHost: HdEmbeddedActivityHost? = null

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
        embeddedHost = HdEmbeddedActivityHost(requireActivity()).also { it.onCreate(savedInstanceState) }
        detailContainer = view.findViewById(R.id.hdManageDetailContainer)
        super.onViewCreated(view, savedInstanceState)
        openAddGame()
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
        val host = embeddedHost ?: return
        val container = detailContainer ?: return
        val content = host.start(
            id,
            Intent(requireContext(), activityClass).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        ) ?: return
        HdPageMotion.showEmbedded(container, content)
    }

    override fun closeEmbeddedActivity(child: Activity?): Boolean {
        val host = embeddedHost ?: return false
        val id = host.beginClose(child) ?: return false
        detailContainer?.apply {
            HdPageMotion.closeEmbedded(this) {
                post { host.destroy(id) }
            }
        }
        return true
    }
}
