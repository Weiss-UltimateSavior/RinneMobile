package com.apps.account;

import android.graphics.Color;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.core.R;
import com.core.databinding.ActivityLauncherPasswordResetBinding;
import com.core.launcherbridge.LauncherAuthBridge;
import com.core.launcherbridge.SimpleCallback;
import com.apps.LauncherActivity;
import com.apps.theme.LauncherDialogFactory;
import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherTabletPortraitScaler;

/** 通过邮箱验证码重置密码。 */
public class LauncherPasswordResetActivity extends AppCompatActivity {
    private ActivityLauncherPasswordResetBinding binding;
    private CountDownTimer verificationCodeTimer;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        applySavedToneMode();
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();
        binding = ActivityLauncherPasswordResetBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        LauncherTabletPortraitScaler.applyActivityContent(this);
        applySystemBarInsets();
        LauncherTheme.applyPrimaryTone(binding.getRoot());
        LauncherTheme.formInputs(binding.resetEmail, binding.resetVerificationCode,
                binding.resetPassword, binding.resetConfirmPassword);
        // 获取验证码为内联文字操作：去掉按钮背景，只留跟随主题色的文字。
        binding.resetSendCode.setBackground(null);
        binding.resetSendCode.setTextColor(LauncherTheme.primary(this));
        LauncherTheme.longActionButton(binding.resetSubmit);
        binding.resetSendCode.setOnClickListener(view -> sendVerificationCode());
        binding.resetSubmit.setOnClickListener(view -> resetPassword());
        LauncherMotion.applyActivityOpen(this);
    }

    private void sendVerificationCode() {
        String email = textOf(binding.resetEmail);
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.resetEmail.setError(getString(R.string.social_error_registered_email));
            return;
        }
        binding.resetSendCode.setEnabled(false);
        binding.resetSendCode.setText(R.string.social_action_sending);
        LauncherAuthBridge.sendPasswordResetCode(this, email, new SimpleCallback() {
            @Override
            public void onSuccess() {
                if (isFinishing()) return;
                Toast.makeText(LauncherPasswordResetActivity.this, R.string.social_verification_sent, Toast.LENGTH_SHORT).show();
                startVerificationCodeCountdown();
            }

            @Override
            public void onError(String message) {
                if (isFinishing()) return;
                binding.resetSendCode.setEnabled(true);
                binding.resetSendCode.setText(R.string.social_action_get_code);
                showResultDialog(getString(R.string.social_verification_failed), message);
            }
        });
    }

    private void resetPassword() {
        String email = textOf(binding.resetEmail);
        String code = textOf(binding.resetVerificationCode);
        String password = textOf(binding.resetPassword);
        String confirmPassword = textOf(binding.resetConfirmPassword);
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.resetEmail.setError(getString(R.string.social_error_registered_email));
            return;
        }
        if (!code.matches("\\d{6}")) {
            binding.resetVerificationCode.setError(getString(R.string.social_error_verification_code));
            return;
        }
        if (password.length() < 6) {
            binding.resetPassword.setError(getString(R.string.social_error_password_min));
            return;
        }
        if (!password.equals(confirmPassword)) {
            binding.resetConfirmPassword.setError(getString(R.string.social_error_password_mismatch));
            return;
        }
        binding.resetSubmit.setEnabled(false);
        binding.resetSubmit.setText(R.string.social_resetting_password);
        LauncherAuthBridge.resetPassword(this, email, code, password, new SimpleCallback() {
            @Override
            public void onSuccess() {
                if (isFinishing()) return;
                Toast.makeText(LauncherPasswordResetActivity.this, R.string.social_password_reset_success, Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                LauncherMotion.finish(LauncherPasswordResetActivity.this);
            }

            @Override
            public void onError(String message) {
                if (isFinishing()) return;
                binding.resetSubmit.setEnabled(true);
                binding.resetSubmit.setText(R.string.social_reset_password);
                showResultDialog(getString(R.string.social_password_reset_failed), message);
            }
        });
    }

    private void startVerificationCodeCountdown() {
        if (verificationCodeTimer != null) verificationCodeTimer.cancel();
        verificationCodeTimer = new CountDownTimer(60_000L, 1_000L) {
            @Override
            public void onTick(long millisUntilFinished) {
                binding.resetSendCode.setEnabled(false);
                binding.resetSendCode.setText(getString(R.string.social_action_retry_seconds,
                        (millisUntilFinished + 999L) / 1000L));
            }

            @Override
            public void onFinish() {
                binding.resetSendCode.setEnabled(true);
                binding.resetSendCode.setText(R.string.social_action_get_code);
            }
        }.start();
    }

    private void showResultDialog(String title, String message) {
        LauncherDialogFactory.showInfo(this, title, message);
    }

    private String textOf(TextView view) {
        return view.getText() == null ? "" : view.getText().toString().trim();
    }

    private void applySystemBarInsets() {
        int left = binding.passwordResetScroll.getPaddingLeft();
        int top = binding.passwordResetScroll.getPaddingTop();
        int right = binding.passwordResetScroll.getPaddingRight();
        int bottom = binding.passwordResetScroll.getPaddingBottom();
        binding.getRoot().setOnApplyWindowInsetsListener((view, insets) -> {
            binding.passwordResetScroll.setPadding(left, top + insets.getSystemWindowInsetTop(), right, bottom);
            return insets;
        });
        binding.getRoot().requestApplyInsets();
    }

    private void configureEdgeToEdgeWindow() {
        boolean darkMode = LauncherActivity.isLauncherDarkMode(this);
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(ContextCompat.getColor(this, R.color.launcher_bg_color));
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        if (!darkMode) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private void applySavedToneMode() {
        LauncherActivity.applySavedToneMode(this);
    }

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase));
    }

    @Override
    protected void onDestroy() {
        if (verificationCodeTimer != null) verificationCodeTimer.cancel();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        LauncherMotion.finish(this);
    }

}
