package com.apps;

import android.graphics.Color;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.yuki.yukihub.R;
import com.yuki.yukihub.databinding.ActivityLauncherPasswordResetBinding;
import com.yuki.yukihub.launcherbridge.LauncherAuthBridge;

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
        applySystemBarInsets();
        LauncherTheme.applyPrimaryTone(binding.getRoot());
        LauncherTheme.primaryButton(binding.resetSendCode);
        LauncherTheme.primaryButton(binding.resetSubmit);
        binding.resetSendCode.setOnClickListener(view -> sendVerificationCode());
        binding.resetSubmit.setOnClickListener(view -> resetPassword());
        LauncherMotion.applyActivityOpen(this);
    }

    private void sendVerificationCode() {
        String email = textOf(binding.resetEmail);
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.resetEmail.setError("请输入正确的注册邮箱");
            return;
        }
        binding.resetSendCode.setEnabled(false);
        binding.resetSendCode.setText("发送中...");
        LauncherAuthBridge.sendPasswordResetCode(this, email, new LauncherAuthBridge.SimpleCallback() {
            @Override
            public void onSuccess() {
                if (isFinishing()) return;
                Toast.makeText(LauncherPasswordResetActivity.this, "验证码已发送，请查收邮箱", Toast.LENGTH_SHORT).show();
                startVerificationCodeCountdown();
            }

            @Override
            public void onError(String message) {
                if (isFinishing()) return;
                binding.resetSendCode.setEnabled(true);
                binding.resetSendCode.setText("获取验证码");
                showResultDialog("验证码发送失败", message);
            }
        });
    }

    private void resetPassword() {
        String email = textOf(binding.resetEmail);
        String code = textOf(binding.resetVerificationCode);
        String password = textOf(binding.resetPassword);
        String confirmPassword = textOf(binding.resetConfirmPassword);
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.resetEmail.setError("请输入正确的注册邮箱");
            return;
        }
        if (!code.matches("\\d{6}")) {
            binding.resetVerificationCode.setError("请输入 6 位邮箱验证码");
            return;
        }
        if (password.length() < 6) {
            binding.resetPassword.setError("密码至少 6 位");
            return;
        }
        if (!password.equals(confirmPassword)) {
            binding.resetConfirmPassword.setError("两次密码不一致");
            return;
        }
        binding.resetSubmit.setEnabled(false);
        binding.resetSubmit.setText("重置中...");
        LauncherAuthBridge.resetPassword(this, email, code, password, new LauncherAuthBridge.SimpleCallback() {
            @Override
            public void onSuccess() {
                if (isFinishing()) return;
                Toast.makeText(LauncherPasswordResetActivity.this, "密码已重置，请使用新密码登录", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                LauncherMotion.finish(LauncherPasswordResetActivity.this);
            }

            @Override
            public void onError(String message) {
                if (isFinishing()) return;
                binding.resetSubmit.setEnabled(true);
                binding.resetSubmit.setText("重置密码");
                showResultDialog("密码重置失败", message);
            }
        });
    }

    private void startVerificationCodeCountdown() {
        if (verificationCodeTimer != null) verificationCodeTimer.cancel();
        verificationCodeTimer = new CountDownTimer(60_000L, 1_000L) {
            @Override
            public void onTick(long millisUntilFinished) {
                binding.resetSendCode.setEnabled(false);
                binding.resetSendCode.setText((millisUntilFinished + 999L) / 1000L + " 秒后重试");
            }

            @Override
            public void onFinish() {
                binding.resetSendCode.setEnabled(true);
                binding.resetSendCode.setText("获取验证码");
            }
        }.start();
    }

    private void showResultDialog(String title, String message) {
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this).create();
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(270), WindowManager.LayoutParams.WRAP_CONTENT);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(20), dp(22), dp(16));
        root.setBackgroundResource(R.drawable.launcher_dialog_bg);
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setGravity(android.view.Gravity.CENTER);
        titleView.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_color));
        titleView.setTextSize(16);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(titleView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView messageView = new TextView(this);
        messageView.setText(message);
        messageView.setGravity(android.view.Gravity.CENTER);
        messageView.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_muted_color));
        messageView.setTextSize(12);
        LinearLayout.LayoutParams messageLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        messageLp.setMargins(0, dp(13), 0, 0);
        root.addView(messageView, messageLp);
        TextView confirm = new TextView(this);
        confirm.setText("知道了");
        confirm.setGravity(android.view.Gravity.CENTER);
        LauncherTheme.primaryButton(confirm);
        confirm.setOnClickListener(view -> dialog.dismiss());
        LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        confirmLp.setMargins(0, dp(11), 0, 0);
        root.addView(confirm, confirmLp);
        window.setContentView(root);
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

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
