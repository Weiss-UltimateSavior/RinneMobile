package com.apps.account

import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.apps.HDModel.HdEmbeddedActivityOwner
import com.apps.HDModel.HdModeActivity
import com.apps.theme.LauncherDialogFactory
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.R
import com.core.databinding.ActivityLauncherPasswordResetBinding
import com.core.launcherbridge.LauncherAuthBridge
import com.core.launcherbridge.SimpleCallback

/** 通过邮箱验证码重置密码（重构计划 9.9 阶段 108 自 LauncherPasswordResetActivity 抽取）。 */
class LauncherPasswordResetFragment : Fragment() {
    private var binding: ActivityLauncherPasswordResetBinding? = null
    private var verificationCodeTimer: CountDownTimer? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val currentBinding = ActivityLauncherPasswordResetBinding.inflate(inflater, container, false)
        binding = currentBinding
        return currentBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        LauncherTabletPortraitScaler.apply(view)
        val currentBinding = binding ?: return
        applySystemBarInsets()
        LauncherTheme.applyPrimaryTone(view)
        LauncherTheme.formInputs(
            currentBinding.resetEmail,
            currentBinding.resetVerificationCode,
            currentBinding.resetPassword,
            currentBinding.resetConfirmPassword,
        )
        // 获取验证码为内联文字操作：去掉按钮背景，只留跟随主题色的文字。
        currentBinding.resetSendCode.background = null
        currentBinding.resetSendCode.setTextColor(LauncherTheme.primary(requireContext()))
        LauncherTheme.longActionButton(currentBinding.resetSubmit)
        currentBinding.resetSendCode.setOnClickListener { sendVerificationCode() }
        currentBinding.resetSubmit.setOnClickListener { resetPassword() }
    }

    override fun onDestroyView() {
        verificationCodeTimer?.cancel()
        verificationCodeTimer = null
        binding = null
        super.onDestroyView()
    }

    private fun sendVerificationCode() {
        val currentBinding = binding ?: return
        val email = textOf(currentBinding.resetEmail)
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            currentBinding.resetEmail.setError(getString(R.string.social_error_registered_email))
            return
        }
        currentBinding.resetSendCode.isEnabled = false
        currentBinding.resetSendCode.setText(R.string.social_action_sending)
        LauncherAuthBridge.sendPasswordResetCode(
            requireContext(),
            email,
            object : SimpleCallback {
                override fun onSuccess() {
                    if (!isAdded) return
                    Toast.makeText(requireContext(), R.string.social_verification_sent, Toast.LENGTH_SHORT).show()
                    startVerificationCodeCountdown()
                }

                override fun onError(message: String) {
                    if (!isAdded) return
                    val current = binding ?: return
                    current.resetSendCode.isEnabled = true
                    current.resetSendCode.setText(R.string.social_action_get_code)
                    showResultDialog(getString(R.string.social_verification_failed), message)
                }
            },
        )
    }

    private fun resetPassword() {
        val currentBinding = binding ?: return
        val email = textOf(currentBinding.resetEmail)
        val code = textOf(currentBinding.resetVerificationCode)
        val password = textOf(currentBinding.resetPassword)
        val confirmPassword = textOf(currentBinding.resetConfirmPassword)
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            currentBinding.resetEmail.setError(getString(R.string.social_error_registered_email))
            return
        }
        if (!Regex("\\d{6}").matches(code)) {
            currentBinding.resetVerificationCode.setError(getString(R.string.social_error_verification_code))
            return
        }
        if (password.length < 6) {
            currentBinding.resetPassword.setError(getString(R.string.social_error_password_min))
            return
        }
        if (password != confirmPassword) {
            currentBinding.resetConfirmPassword.setError(getString(R.string.social_error_password_mismatch))
            return
        }
        currentBinding.resetSubmit.isEnabled = false
        currentBinding.resetSubmit.setText(R.string.social_resetting_password)
        LauncherAuthBridge.resetPassword(
            requireContext(),
            email,
            code,
            password,
            object : SimpleCallback {
                override fun onSuccess() {
                    if (!isAdded) return
                    Toast.makeText(requireContext(), R.string.social_password_reset_success, Toast.LENGTH_SHORT).show()
                    requestClose(resultOk = true)
                }

                override fun onError(message: String) {
                    if (!isAdded) return
                    val current = binding ?: return
                    current.resetSubmit.isEnabled = true
                    current.resetSubmit.setText(R.string.social_reset_password)
                    showResultDialog(getString(R.string.social_password_reset_failed), message)
                }
            },
        )
    }

    private fun startVerificationCodeCountdown() {
        val currentBinding = binding ?: return
        verificationCodeTimer?.cancel()
        verificationCodeTimer = object : CountDownTimer(60_000L, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                val current = binding ?: return
                current.resetSendCode.isEnabled = false
                current.resetSendCode.setText(
                    getString(
                        R.string.social_action_retry_seconds,
                        (millisUntilFinished + 999L) / 1000L,
                    ),
                )
            }

            override fun onFinish() {
                val current = binding ?: return
                current.resetSendCode.isEnabled = true
                current.resetSendCode.setText(R.string.social_action_get_code)
            }
        }.start()
    }

    private fun showResultDialog(title: String, message: String) {
        LauncherDialogFactory.showInfo(requireContext(), title, message)
    }

    private fun textOf(view: TextView): String =
        view.text?.toString()?.trim().orEmpty()

    private fun applySystemBarInsets() {
        val currentBinding = binding ?: return
        val left = currentBinding.passwordResetScroll.paddingLeft
        val top = currentBinding.passwordResetScroll.paddingTop
        val right = currentBinding.passwordResetScroll.paddingRight
        val bottom = currentBinding.passwordResetScroll.paddingBottom
        currentBinding.root.setOnApplyWindowInsetsListener { _, insets ->
            currentBinding.passwordResetScroll.setPadding(
                left,
                top + insets.systemWindowInsetTop,
                right,
                bottom,
            )
            insets
        }
        currentBinding.root.requestApplyInsets()
    }

    /** 按承载宿主分派关闭：竖屏薄宿主 finish，HD 由父 Fragment 关闭子 Fragment。 */
    private fun requestClose(resultOk: Boolean) {
        when (val host = activity) {
            is LauncherPasswordResetActivity -> host.finishPasswordReset(resultOk)
            is HdModeActivity -> (parentFragment as? HdEmbeddedActivityOwner)?.closeEmbeddedActivity()
            else -> Unit
        }
    }

    companion object {
        @JvmStatic
        fun newInstance(): LauncherPasswordResetFragment = LauncherPasswordResetFragment()
    }
}
