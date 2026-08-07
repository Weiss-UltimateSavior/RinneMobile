package com.apps.account

import android.graphics.Rect
import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.apps.HDModel.HdEmbeddedActivityOwner
import com.apps.HDModel.HdModeActivity
import com.apps.HDModel.LauncherDialogRouter
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.R
import com.core.databinding.ActivityLauncherRegisterBinding
import com.core.launcherbridge.LauncherAuthBridge
import com.core.launcherbridge.SimpleCallback

/**
 * 注册页（重构计划 9.9 阶段 108 自 LauncherRegisterActivity 抽取）。
 *
 * 竖屏由 [LauncherRegisterActivity] 薄宿主承载，HD 由
 * [com.apps.HDModel.HdAccountFragment] 作为子 Fragment 承载；
 * 成功关闭时按宿主上下文分派（竖屏 finish + setResult，HD 关闭子 Fragment）。
 */
class LauncherRegisterFragment : Fragment() {
    private var binding: ActivityLauncherRegisterBinding? = null
    private var verificationCodeTimer: CountDownTimer? = null
    private var focusedInput: View? = null
    private var registerScrollOriginalLeft = 0
    private var registerScrollOriginalTop = 0
    private var registerScrollOriginalRight = 0
    private var registerScrollOriginalBottom = 0
    private var systemTopInset = 0
    private var windowBottomInset = 0
    private var layoutKeyboardInset = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val currentBinding = ActivityLauncherRegisterBinding.inflate(inflater, container, false)
        binding = currentBinding
        return currentBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (activity !is HdModeActivity) LauncherTabletPortraitScaler.apply(view)
        val currentBinding = binding ?: return
        applySystemBarInsets()
        bindKeyboardVisibility()
        bindActions()
        LauncherTheme.applyPrimaryTone(view)
        LauncherTheme.formInputs(
            currentBinding.registerName,
            currentBinding.registerEmail,
            currentBinding.registerVerificationCode,
            currentBinding.registerPassword,
            currentBinding.registerConfirmPassword,
            currentBinding.registerKey,
        )
        // 获取验证码为内联文字操作：去掉按钮背景，只留跟随主题色的文字。
        currentBinding.registerSendCode.background = null
        currentBinding.registerSendCode.setTextColor(LauncherTheme.primary(requireContext()))
        LauncherTheme.longActionButton(currentBinding.registerCreate)
    }

    override fun onDestroyView() {
        verificationCodeTimer?.cancel()
        verificationCodeTimer = null
        binding = null
        super.onDestroyView()
    }

    private fun applySystemBarInsets() {
        val currentBinding = binding ?: return
        registerScrollOriginalLeft = currentBinding.registerScroll.paddingLeft
        registerScrollOriginalTop = currentBinding.registerScroll.paddingTop
        registerScrollOriginalRight = currentBinding.registerScroll.paddingRight
        registerScrollOriginalBottom = currentBinding.registerScroll.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(currentBinding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            systemTopInset = systemBars.top
            windowBottomInset = maxOf(systemBars.bottom, ime.bottom)
            applyRegisterScrollPadding()
            if (ime.bottom > 0) {
                revealFocusedInput()
            }
            insets
        }
        currentBinding.root.viewTreeObserver.addOnGlobalLayoutListener {
            val current = binding ?: return@addOnGlobalLayoutListener
            val visibleFrame = Rect()
            current.root.getWindowVisibleDisplayFrame(visibleFrame)
            val rootHeight = current.root.rootView.height
            val hiddenBottom = maxOf(0, rootHeight - visibleFrame.bottom)
            val keyboardThreshold = maxOf(LauncherTheme.dp(requireContext(), 120), rootHeight / 5)
            layoutKeyboardInset = if (hiddenBottom > keyboardThreshold) hiddenBottom else 0
            applyRegisterScrollPadding()
            if (layoutKeyboardInset > 0) {
                revealFocusedInput()
            }
        }
        ViewCompat.requestApplyInsets(currentBinding.root)
    }

    private fun applyRegisterScrollPadding() {
        val currentBinding = binding ?: return
        currentBinding.registerScroll.setPadding(
            registerScrollOriginalLeft,
            registerScrollOriginalTop + systemTopInset,
            registerScrollOriginalRight,
            registerScrollOriginalBottom + maxOf(windowBottomInset, layoutKeyboardInset),
        )
    }

    private fun bindKeyboardVisibility() {
        val listener = View.OnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                focusedInput = view
                revealFocusedInput()
                view.postDelayed({ revealFocusedInput() }, 260L)
            } else if (focusedInput == view) {
                focusedInput = null
            }
        }
        val currentBinding = binding ?: return
        currentBinding.registerName.setOnFocusChangeListener(listener)
        currentBinding.registerEmail.setOnFocusChangeListener(listener)
        currentBinding.registerVerificationCode.setOnFocusChangeListener(listener)
        currentBinding.registerPassword.setOnFocusChangeListener(listener)
        currentBinding.registerConfirmPassword.setOnFocusChangeListener(listener)
        currentBinding.registerKey.setOnFocusChangeListener(listener)
    }

    private fun revealFocusedInput() {
        val input = focusedInput
        if (input == null || !input.hasFocus() || binding == null) return
        input.post {
            if (!input.hasFocus() || binding == null) return@post
            val rect = Rect(0, 0, input.width, input.height + LauncherTheme.dp(requireContext(), 24))
            input.requestRectangleOnScreen(rect, true)
        }
    }

    private fun bindActions() {
        val currentBinding = binding ?: return
        currentBinding.registerCreate.setOnClickListener { performRegister() }
        currentBinding.registerSendCode.setOnClickListener { sendVerificationCode() }
    }

    private fun sendVerificationCode() {
        val currentBinding = binding ?: return
        val email = textOf(currentBinding.registerEmail)
        val inviteCode = textOf(currentBinding.registerKey)
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            currentBinding.registerEmail.setError(getString(R.string.social_error_email_invalid))
            return
        }
        if (!Regex("[A-Za-z0-9]{7}").matches(inviteCode)) {
            currentBinding.registerKey.setError(getString(R.string.social_error_invite_code))
            return
        }
        currentBinding.registerSendCode.isEnabled = false
        currentBinding.registerSendCode.setText(R.string.social_action_sending)
        LauncherAuthBridge.sendRegistrationVerificationCode(
            requireContext(),
            email,
            inviteCode,
            object : SimpleCallback {
                override fun onSuccess() {
                    if (!isAdded) return
                    Toast.makeText(requireContext(), R.string.social_verification_sent, Toast.LENGTH_SHORT).show()
                    startVerificationCodeCountdown()
                }

                override fun onError(message: String) {
                    if (!isAdded) return
                    val current = binding ?: return
                    current.registerSendCode.isEnabled = true
                    current.registerSendCode.setText(R.string.social_action_get_code)
                    showAuthResultDialog(getString(R.string.social_verification_failed), message)
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
                current.registerSendCode.isEnabled = false
                current.registerSendCode.setText(
                    getString(
                        R.string.social_action_retry_seconds,
                        (millisUntilFinished + 999L) / 1000L,
                    ),
                )
            }

            override fun onFinish() {
                val current = binding ?: return
                current.registerSendCode.isEnabled = true
                current.registerSendCode.setText(R.string.social_action_get_code)
            }
        }.start()
    }

    private fun performRegister() {
        val currentBinding = binding ?: return
        val username = textOf(currentBinding.registerName)
        val email = textOf(currentBinding.registerEmail)
        val password = textOf(currentBinding.registerPassword)
        val confirmPassword = textOf(currentBinding.registerConfirmPassword)
        val inviteCode = textOf(currentBinding.registerKey)
        val verificationCode = textOf(currentBinding.registerVerificationCode)

        if (username.isEmpty()) {
            currentBinding.registerName.setError(getString(R.string.social_error_username_required))
            return
        }
        if (!Regex("[A-Za-z0-9_]{3,32}").matches(username)) {
            currentBinding.registerName.setError(getString(R.string.social_error_username_format))
            return
        }
        if (email.isEmpty()) {
            currentBinding.registerEmail.setError(getString(R.string.social_error_email_required))
            return
        }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            currentBinding.registerEmail.setError(getString(R.string.social_error_email_invalid))
            return
        }
        if (password.isEmpty()) {
            currentBinding.registerPassword.setError(getString(R.string.social_error_password_required))
            return
        }
        if (password.length < 6 || password.length > 128) {
            currentBinding.registerPassword.setError(getString(R.string.social_error_password_length))
            return
        }
        if (password != confirmPassword) {
            currentBinding.registerConfirmPassword.setError(getString(R.string.social_error_password_mismatch))
            return
        }
        if (!Regex("[A-Za-z0-9]{7}").matches(inviteCode)) {
            currentBinding.registerKey.setError(getString(R.string.social_error_invite_code))
            return
        }
        if (!Regex("\\d{6}").matches(verificationCode)) {
            currentBinding.registerVerificationCode.setError(getString(R.string.social_error_verification_code))
            return
        }

        currentBinding.registerCreate.isEnabled = false
        currentBinding.registerCreate.setText(R.string.social_registering)

        LauncherAuthBridge.register(
            requireContext(),
            username,
            email,
            password,
            inviteCode,
            verificationCode,
            object : com.core.launcherbridge.AuthCallback {
                override fun onSuccess(token: String) {
                    if (!isAdded) return
                    val current = binding
                    if (current != null) {
                        current.registerCreate.isEnabled = true
                        current.registerCreate.setText(R.string.social_create_account)
                    }
                    Toast.makeText(requireContext(), R.string.social_register_success, Toast.LENGTH_SHORT).show()
                    // 注册成功后关闭；竖屏宿主 finish 后 LauncherAccountFragment.onResume
                    // 会检测已登录状态并跳转到个人信息页，HD 下关闭子 Fragment 保持原行为。
                    requestClose(resultOk = true)
                }

                override fun onError(message: String) {
                    if (!isAdded) return
                    val current = binding
                    if (current != null) {
                        current.registerCreate.isEnabled = true
                        current.registerCreate.setText(R.string.social_create_account)
                    }
                    showAuthResultDialog(getString(R.string.social_register_failed), message)
                }
            },
        )
    }

    /** 按承载宿主分派关闭：竖屏薄宿主 finish，HD 由父 Fragment 关闭子 Fragment。 */
    private fun requestClose(resultOk: Boolean) {
        when (val host = activity) {
            is LauncherRegisterActivity -> host.finishRegister(resultOk)
            is HdModeActivity -> (parentFragment as? HdEmbeddedActivityOwner)?.closeEmbeddedActivity()
            else -> Unit
        }
    }

    private fun showAuthResultDialog(title: String, message: String) {
        LauncherDialogRouter.showInfo(requireContext(), title, message)
    }

    private fun textOf(view: TextView): String =
        view.text?.toString()?.trim().orEmpty()

    companion object {
        @JvmStatic
        fun newInstance(): LauncherRegisterFragment = LauncherRegisterFragment()
    }
}
