package com.apps.HDModel

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
    private var embeddedHost: HdEmbeddedActivityHost? = null

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
        embeddedHost = HdEmbeddedActivityHost(requireActivity()).also { it.onCreate(savedInstanceState) }
        detailContainer = view.findViewById(R.id.hdAccountDetailContainer)
        super.onViewCreated(view, savedInstanceState)
        openRegister()
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
