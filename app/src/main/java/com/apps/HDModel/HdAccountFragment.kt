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
import com.apps.account.LauncherAccountFragment
import com.apps.account.LauncherPasswordResetActivity
import com.apps.account.LauncherRegisterActivity
import com.core.R

/** HD 登录页：复用登录业务，以双栏表单适配大屏内容容器。 */
@Suppress("DEPRECATION")
class HdAccountFragment : LauncherAccountFragment(), HdEmbeddedActivityOwner {
    private var detailContainer: FrameLayout? = null
    private var localActivityManager: LocalActivityManager? = null
    private var embeddedActivityId: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val root = inflater.inflate(R.layout.fragment_hd_account, container, false)
        bindAccountRoot(root)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        localActivityManager = LocalActivityManager(requireActivity(), false).apply {
            dispatchCreate(savedInstanceState)
        }
        detailContainer = view.findViewById(R.id.hdAccountDetailContainer)
        super.onViewCreated(view, savedInstanceState)
        openRegister()
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

    override fun usePortraitAccountScaler(): Boolean = false

    override fun applyAccountSystemBarInsets(): Boolean = false

    override fun accountFragmentContainerId(): Int = R.id.hdFragmentContainer

    override fun createProfileFragment(): Fragment = HdProfileFragment()

    override fun openRegister() {
        showEmbeddedActivity("hd_register", LauncherRegisterActivity::class.java)
    }

    override fun openPasswordReset() {
        showEmbeddedActivity("hd_password_reset", LauncherPasswordResetActivity::class.java)
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
