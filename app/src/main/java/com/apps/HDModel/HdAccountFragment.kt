package com.apps.HDModel

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import com.apps.account.LauncherAccountFragment
import com.apps.account.LauncherPasswordResetFragment
import com.apps.account.LauncherRegisterFragment
import com.core.R

/**
 * HD 登录页：复用登录业务，以双栏表单适配大屏内容容器。
 *
 * 重构计划 9.9 阶段 108：嵌入 Activity 迁子 Fragment（注册/重置密码），
 * 不再使用 LocalActivityManager；ActivityResult 由子 Fragment 自身注册。
 */
class HdAccountFragment : LauncherAccountFragment(), HdEmbeddedActivityOwner {
    private var detailContainer: FrameLayout? = null

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
        detailContainer = view.findViewById(R.id.hdAccountDetailContainer)
        super.onViewCreated(view, savedInstanceState)
        openRegister()
    }

    override fun onDestroyView() {
        detailContainer = null
        super.onDestroyView()
    }

    override fun usePortraitAccountScaler(): Boolean = false

    override fun applyAccountSystemBarInsets(): Boolean = false

    override fun accountFragmentContainerId(): Int = R.id.hdFragmentContainer

    override fun createProfileFragment(): Fragment = HdProfileFragment()

    override fun openRegister() {
        showChildFragment(CHILD_REGISTER_TAG, LauncherRegisterFragment())
    }

    override fun openPasswordReset() {
        showChildFragment(CHILD_PASSWORD_RESET_TAG, LauncherPasswordResetFragment())
    }

    private fun showChildFragment(tag: String, fragment: Fragment) {
        if (!isAdded || detailContainer == null) return
        childFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.launcher_fragment_enter,
                R.anim.launcher_fragment_exit,
            )
            .replace(R.id.hdAccountDetailContainer, fragment, tag)
            .commit()
    }

    override fun closeEmbeddedActivity(child: Activity?): Boolean {
        val existing = childFragmentManager.findFragmentByTag(CHILD_REGISTER_TAG)
            ?: childFragmentManager.findFragmentByTag(CHILD_PASSWORD_RESET_TAG)
            ?: return false
        childFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.launcher_fragment_enter, R.anim.launcher_fragment_exit)
            .remove(existing)
            .commit()
        return true
    }

    companion object {
        private const val CHILD_REGISTER_TAG = "hd_account_register"
        private const val CHILD_PASSWORD_RESET_TAG = "hd_account_password_reset"
    }
}
