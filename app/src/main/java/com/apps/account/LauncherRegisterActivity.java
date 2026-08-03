package com.apps.account;

import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.core.R;
import com.core.databinding.ActivityLauncherRegisterBinding;
import com.core.launcherbridge.AuthCallback;
import com.core.launcherbridge.LauncherAuthBridge;
import com.core.launcherbridge.SimpleCallback;
import com.apps.LauncherActivity;
import com.apps.theme.LauncherDialogFactory;
import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherTabletPortraitScaler;

public class LauncherRegisterActivity extends AppCompatActivity {
    private ActivityLauncherRegisterBinding binding;
    private CountDownTimer verificationCodeTimer;
    private View focusedInput;
    private int registerScrollOriginalLeft;
    private int registerScrollOriginalTop;
    private int registerScrollOriginalRight;
    private int registerScrollOriginalBottom;
    private int systemTopInset;
    private int windowBottomInset;
    private int layoutKeyboardInset;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        applySavedToneMode();
        super.onCreate(savedInstanceState);
        com.apps.LauncherEdgeToEdgeHelper.apply(this, true);

        binding = ActivityLauncherRegisterBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        LauncherTabletPortraitScaler.applyActivityContent(this);
        applySystemBarInsets();
        bindKeyboardVisibility();
        bindActions();
        LauncherTheme.applyPrimaryTone(binding.getRoot());
        LauncherTheme.formInputs(binding.registerName, binding.registerEmail,
                binding.registerVerificationCode, binding.registerPassword,
                binding.registerConfirmPassword, binding.registerKey);
        // 获取验证码为内联文字操作：去掉按钮背景，只留跟随主题色的文字。
        binding.registerSendCode.setBackground(null);
        binding.registerSendCode.setTextColor(LauncherTheme.primary(this));
        LauncherTheme.longActionButton(binding.registerCreate);
        LauncherMotion.applyActivityOpen(this);
    }

    private void applySystemBarInsets() {
        registerScrollOriginalLeft = binding.registerScroll.getPaddingLeft();
        registerScrollOriginalTop = binding.registerScroll.getPaddingTop();
        registerScrollOriginalRight = binding.registerScroll.getPaddingRight();
        registerScrollOriginalBottom = binding.registerScroll.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            systemTopInset = systemBars.top;
            windowBottomInset = Math.max(systemBars.bottom, ime.bottom);
            applyRegisterScrollPadding();
            if (ime.bottom > 0) {
                revealFocusedInput();
            }
            return insets;
        });
        binding.getRoot().getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (binding == null) return;
            Rect visibleFrame = new Rect();
            binding.getRoot().getWindowVisibleDisplayFrame(visibleFrame);
            int rootHeight = binding.getRoot().getRootView().getHeight();
            int hiddenBottom = Math.max(0, rootHeight - visibleFrame.bottom);
            int keyboardThreshold = Math.max(LauncherTheme.dp(this, 120), rootHeight / 5);
            layoutKeyboardInset = hiddenBottom > keyboardThreshold ? hiddenBottom : 0;
            applyRegisterScrollPadding();
            if (layoutKeyboardInset > 0) {
                revealFocusedInput();
            }
        });
        ViewCompat.requestApplyInsets(binding.getRoot());
    }

    private void applyRegisterScrollPadding() {
        if (binding == null) return;
        binding.registerScroll.setPadding(
                registerScrollOriginalLeft,
                registerScrollOriginalTop + systemTopInset,
                registerScrollOriginalRight,
                registerScrollOriginalBottom + Math.max(windowBottomInset, layoutKeyboardInset)
        );
    }

    private void bindKeyboardVisibility() {
        View.OnFocusChangeListener listener = (view, hasFocus) -> {
            if (hasFocus) {
                focusedInput = view;
                revealFocusedInput();
                view.postDelayed(this::revealFocusedInput, 260L);
            } else if (focusedInput == view) {
                focusedInput = null;
            }
        };
        binding.registerName.setOnFocusChangeListener(listener);
        binding.registerEmail.setOnFocusChangeListener(listener);
        binding.registerVerificationCode.setOnFocusChangeListener(listener);
        binding.registerPassword.setOnFocusChangeListener(listener);
        binding.registerConfirmPassword.setOnFocusChangeListener(listener);
        binding.registerKey.setOnFocusChangeListener(listener);
    }

    private void revealFocusedInput() {
        View input = focusedInput;
        if (input == null || !input.hasFocus() || binding == null) return;
        input.post(() -> {
            if (!input.hasFocus() || binding == null) return;
            Rect rect = new Rect(0, 0, input.getWidth(), input.getHeight() + LauncherTheme.dp(this, 24));
            input.requestRectangleOnScreen(rect, true);
        });
    }

    private void bindActions() {
        binding.registerCreate.setOnClickListener(view -> performRegister());
        binding.registerSendCode.setOnClickListener(view -> sendVerificationCode());
    }

    private void sendVerificationCode() {
        String email = textOf(binding.registerEmail);
        String inviteCode = textOf(binding.registerKey);
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.registerEmail.setError(getString(R.string.social_error_email_invalid));
            return;
        }
        if (!inviteCode.matches("[A-Za-z0-9]{7}")) {
            binding.registerKey.setError(getString(R.string.social_error_invite_code));
            return;
        }
        binding.registerSendCode.setEnabled(false);
        binding.registerSendCode.setText(R.string.social_action_sending);
        LauncherAuthBridge.sendRegistrationVerificationCode(this, email, inviteCode, new SimpleCallback() {
            @Override
            public void onSuccess() {
                if (isFinishing()) return;
                Toast.makeText(LauncherRegisterActivity.this, R.string.social_verification_sent, Toast.LENGTH_SHORT).show();
                startVerificationCodeCountdown();
            }

            @Override
            public void onError(String message) {
                if (isFinishing()) return;
                binding.registerSendCode.setEnabled(true);
                binding.registerSendCode.setText(R.string.social_action_get_code);
                showAuthResultDialog(getString(R.string.social_verification_failed), message);
            }
        });
    }

    private void startVerificationCodeCountdown() {
        if (verificationCodeTimer != null) verificationCodeTimer.cancel();
        verificationCodeTimer = new CountDownTimer(60_000L, 1_000L) {
            @Override
            public void onTick(long millisUntilFinished) {
                binding.registerSendCode.setEnabled(false);
                binding.registerSendCode.setText(getString(R.string.social_action_retry_seconds,
                        (millisUntilFinished + 999L) / 1000L));
            }

            @Override
            public void onFinish() {
                binding.registerSendCode.setEnabled(true);
                binding.registerSendCode.setText(R.string.social_action_get_code);
            }
        }.start();
    }

    private void performRegister() {
        String username = textOf(binding.registerName);
        String email = textOf(binding.registerEmail);
        String password = textOf(binding.registerPassword);
        String confirmPassword = textOf(binding.registerConfirmPassword);
        String inviteCode = textOf(binding.registerKey);
        String verificationCode = textOf(binding.registerVerificationCode);

        if (username.isEmpty()) {
            binding.registerName.setError(getString(R.string.social_error_username_required));
            return;
        }
        if (!username.matches("[A-Za-z0-9_]{3,32}")) {
            binding.registerName.setError(getString(R.string.social_error_username_format));
            return;
        }
        if (email.isEmpty()) {
            binding.registerEmail.setError(getString(R.string.social_error_email_required));
            return;
        }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.registerEmail.setError(getString(R.string.social_error_email_invalid));
            return;
        }
        if (password.isEmpty()) {
            binding.registerPassword.setError(getString(R.string.social_error_password_required));
            return;
        }
        if (password.length() < 6 || password.length() > 128) {
            binding.registerPassword.setError(getString(R.string.social_error_password_length));
            return;
        }
        if (!password.equals(confirmPassword)) {
            binding.registerConfirmPassword.setError(getString(R.string.social_error_password_mismatch));
            return;
        }
        if (!inviteCode.matches("[A-Za-z0-9]{7}")) {
            binding.registerKey.setError(getString(R.string.social_error_invite_code));
            return;
        }
        if (!verificationCode.matches("\\d{6}")) {
            binding.registerVerificationCode.setError(getString(R.string.social_error_verification_code));
            return;
        }

        binding.registerCreate.setEnabled(false);
        binding.registerCreate.setText(R.string.social_registering);

        LauncherAuthBridge.register(this, username, email, password, inviteCode, verificationCode, new AuthCallback() {
            @Override
            public void onSuccess(String token) {
                if (binding != null) {
                    binding.registerCreate.setEnabled(true);
                    binding.registerCreate.setText(R.string.social_create_account);
                }
                Toast.makeText(LauncherRegisterActivity.this, R.string.social_register_success, Toast.LENGTH_SHORT).show();
                // 注册成功后返回，LauncherAccountFragment.onResume 会检测已登录状态并跳转到个人信息页
                setResult(RESULT_OK);
                LauncherMotion.finish(LauncherRegisterActivity.this);
            }

            @Override
            public void onError(String message) {
                if (binding != null) {
                    binding.registerCreate.setEnabled(true);
                    binding.registerCreate.setText(R.string.social_create_account);
                }
                showAuthResultDialog(getString(R.string.social_register_failed), message);
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (verificationCodeTimer != null) verificationCodeTimer.cancel();
        super.onDestroy();
    }

    private void showAuthResultDialog(String title, String message) {
        LauncherDialogFactory.showInfo(this, title, message);
    }

    private String textOf(TextView view) {
        return view.getText() == null ? "" : view.getText().toString().trim();
    }

    @Override
    public void onBackPressed() {
        LauncherMotion.finish(this);
    }

    private void applySavedToneMode() {
        LauncherActivity.applySavedToneMode(this);
    }

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase));
    }

}
