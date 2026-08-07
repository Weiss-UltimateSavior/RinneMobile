package com.apps.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.apps.HDModel.HdEmbeddedActivityOwner
import com.apps.HDModel.HdModeActivity
import com.apps.HDModel.LauncherDialogRouter
import com.apps.common.LauncherInsetsHelper
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.R
import com.core.databinding.ActivityLauncherProfileEditBinding
import com.core.launcherbridge.AuthCallback
import com.core.launcherbridge.LauncherAuthBridge

/**
 * 个人资料编辑页（重构计划 9.9 阶段 111 自 LauncherProfileEditActivity 抽取）。
 *
 * 竖屏由 [LauncherProfileEditActivity] 薄宿主承载，HD 由
 * [com.apps.HDModel.HdProfileFragment] 作为子 Fragment 承载。
 */
class LauncherProfileEditFragment : Fragment() {
    private var binding: ActivityLauncherProfileEditBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val currentBinding = ActivityLauncherProfileEditBinding.inflate(inflater, container, false)
        binding = currentBinding
        return currentBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (activity !is HdModeActivity) LauncherTabletPortraitScaler.apply(view)
        val currentBinding = binding ?: return
        LauncherInsetsHelper.applyTopInset(currentBinding.root, currentBinding.profileEditScroll)
        bindActions()
        LauncherTheme.applyPrimaryTone(view)
        LauncherTheme.longActionButton(currentBinding.profileEditBack)
        LauncherTheme.longActionButton(currentBinding.btnUpdateUsername)
        LauncherTheme.longActionButton(currentBinding.btnUpdatePassword)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun bindActions() {
        val currentBinding = binding ?: return
        currentBinding.profileEditBack.setOnClickListener { requestClose(resultOk = false) }
        currentBinding.btnUpdateUsername.setOnClickListener { confirmUpdateUsername() }
        currentBinding.btnUpdatePassword.setOnClickListener { confirmUpdatePassword() }
    }

    private fun confirmUpdateUsername() {
        val currentBinding = binding ?: return
        val newUsername = textOf(currentBinding.inputNewUsername)
        if (newUsername.isEmpty()) {
            currentBinding.inputNewUsername.setError(getString(R.string.profile_enter_new_username))
            return
        }
        if (newUsername.length < 3 || newUsername.length > 32) {
            currentBinding.inputNewUsername.setError(getString(R.string.profile_username_length_error))
            return
        }
        if (!Regex("^[a-zA-Z0-9_]+$").matches(newUsername)) {
            currentBinding.inputNewUsername.setError(getString(R.string.profile_username_characters_error))
            return
        }
        showConfirmDialog(
            getString(R.string.profile_change_username),
            getString(R.string.profile_confirm_username_change, newUsername),
        ) { performUpdateUsername() }
    }

    private fun confirmUpdatePassword() {
        val currentBinding = binding ?: return
        val oldPassword = textOf(currentBinding.inputOldPassword)
        val newPassword = textOf(currentBinding.inputNewPassword)
        val confirmPassword = textOf(currentBinding.inputConfirmNewPassword)
        if (oldPassword.isEmpty()) {
            currentBinding.inputOldPassword.setError(getString(R.string.profile_enter_old_password))
            return
        }
        if (newPassword.isEmpty()) {
            currentBinding.inputNewPassword.setError(getString(R.string.profile_enter_new_password))
            return
        }
        if (newPassword.length < 6) {
            currentBinding.inputNewPassword.setError(getString(R.string.profile_password_too_short))
            return
        }
        if (newPassword != confirmPassword) {
            currentBinding.inputConfirmNewPassword.setError(getString(R.string.profile_passwords_do_not_match))
            return
        }
        showConfirmDialog(
            getString(R.string.profile_change_password),
            getString(R.string.profile_confirm_password_change),
        ) { performUpdatePassword() }
    }

    private fun showConfirmDialog(title: String, message: String, onConfirm: () -> Unit) {
        LauncherDialogRouter.showStandardConfirm(
            requireContext(),
            title,
            message,
            getString(R.string.settings_confirm),
            Runnable(onConfirm),
        )
    }

    private fun performUpdateUsername() {
        val currentBinding = binding ?: return
        val newUsername = textOf(currentBinding.inputNewUsername)

        currentBinding.btnUpdateUsername.isEnabled = false
        currentBinding.btnUpdateUsername.setText(R.string.profile_updating)

        LauncherAuthBridge.updateUsername(
            requireContext(),
            newUsername,
            object : AuthCallback {
                override fun onSuccess(token: String) {
                    if (!isAdded) return
                    val current = binding
                    if (current != null) {
                        current.btnUpdateUsername.isEnabled = true
                        current.btnUpdateUsername.setText(R.string.profile_change_username)
                    }
                    Toast.makeText(requireContext(), R.string.profile_username_changed, Toast.LENGTH_SHORT).show()
                }

                override fun onError(message: String) {
                    if (!isAdded) return
                    val current = binding
                    if (current != null) {
                        current.btnUpdateUsername.isEnabled = true
                        current.btnUpdateUsername.setText(R.string.profile_change_username)
                    }
                    showResultDialog(getString(R.string.profile_update_failed), message)
                }
            },
        )
    }

    private fun performUpdatePassword() {
        val currentBinding = binding ?: return
        val oldPassword = textOf(currentBinding.inputOldPassword)
        val newPassword = textOf(currentBinding.inputNewPassword)

        currentBinding.btnUpdatePassword.isEnabled = false
        currentBinding.btnUpdatePassword.setText(R.string.profile_updating)

        LauncherAuthBridge.updatePassword(
            requireContext(),
            oldPassword,
            newPassword,
            object : AuthCallback {
                override fun onSuccess(token: String) {
                    if (!isAdded) return
                    val current = binding
                    if (current != null) {
                        current.btnUpdatePassword.isEnabled = true
                        current.btnUpdatePassword.setText(R.string.profile_change_password)
                    }
                    Toast.makeText(
                        requireContext(),
                        R.string.profile_password_changed_relogin,
                        Toast.LENGTH_SHORT,
                    ).show()
                    // 密码修改后 Token 已吊销，返回登录页
                    requestClose(resultOk = true)
                }

                override fun onError(message: String) {
                    if (!isAdded) return
                    val current = binding
                    if (current != null) {
                        current.btnUpdatePassword.isEnabled = true
                        current.btnUpdatePassword.setText(R.string.profile_change_password)
                    }
                    showResultDialog(getString(R.string.profile_update_failed), message)
                }
            },
        )
    }

    private fun showResultDialog(title: String, message: String) {
        LauncherDialogRouter.showInfo(requireContext(), title, message)
    }

    private fun textOf(view: TextView): String =
        view.text?.toString()?.trim().orEmpty()

    /** 按承载宿主分派关闭：竖屏薄宿主 finish（密码修改成功带 RESULT_OK），HD 由父 Fragment 关闭子 Fragment。 */
    private fun requestClose(resultOk: Boolean) {
        when (val host = activity) {
            is LauncherProfileEditActivity -> host.finishProfileEdit(resultOk)
            is HdModeActivity -> (parentFragment as? HdEmbeddedActivityOwner)?.closeEmbeddedActivity()
            else -> Unit
        }
    }
}
