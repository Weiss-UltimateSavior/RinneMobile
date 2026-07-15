package com.apps.account;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
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
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.yuki.yukihub.R;
import com.yuki.yukihub.databinding.ActivityLauncherRegisterBinding;
import com.yuki.yukihub.launcherbridge.LauncherAuthBridge;
import com.apps.LauncherActivity;
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
        configureEdgeToEdgeWindow();

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
        LauncherTheme.shortActionButton(binding.registerSendCode);
        LauncherTheme.longActionButton(binding.registerCreate);
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
            int keyboardThreshold = Math.max(dp(120), rootHeight / 5);
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
            Rect rect = new Rect(0, 0, input.getWidth(), input.getHeight() + dp(24));
            input.requestRectangleOnScreen(rect, true);
        });
    }

    private void bindActions() {
        binding.registerCreate.setOnClickListener(view -> performRegister());
        binding.registerSendCode.setOnClickListener(view -> sendVerificationCode());
    }

    private void sendVerificationCode() {
        String email = binding.registerEmail.getText() == null ? "" : binding.registerEmail.getText().toString().trim();
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.registerEmail.setError("请输入正确的邮箱");
            return;
        }
        binding.registerSendCode.setEnabled(false);
        binding.registerSendCode.setText("发送中...");
        LauncherAuthBridge.sendRegistrationVerificationCode(this, email, new LauncherAuthBridge.SimpleCallback() {
            @Override
            public void onSuccess() {
                if (isFinishing()) return;
                Toast.makeText(LauncherRegisterActivity.this, "验证码已发送，请查收邮箱", Toast.LENGTH_SHORT).show();
                startVerificationCodeCountdown();
            }

            @Override
            public void onError(String message) {
                if (isFinishing()) return;
                binding.registerSendCode.setEnabled(true);
                binding.registerSendCode.setText("获取验证码");
                showAuthResultDialog("验证码发送失败", message);
            }
        });
    }

    private void startVerificationCodeCountdown() {
        if (verificationCodeTimer != null) verificationCodeTimer.cancel();
        verificationCodeTimer = new CountDownTimer(60_000L, 1_000L) {
            @Override
            public void onTick(long millisUntilFinished) {
                binding.registerSendCode.setEnabled(false);
                binding.registerSendCode.setText((millisUntilFinished + 999L) / 1000L + " 秒后重试");
            }

            @Override
            public void onFinish() {
                binding.registerSendCode.setEnabled(true);
                binding.registerSendCode.setText("获取验证码");
            }
        }.start();
    }

    private void performRegister() {
        String username = binding.registerName.getText() == null ? "" : binding.registerName.getText().toString().trim();
        String email = binding.registerEmail.getText() == null ? "" : binding.registerEmail.getText().toString().trim();
        String password = binding.registerPassword.getText() == null ? "" : binding.registerPassword.getText().toString().trim();
        String confirmPassword = binding.registerConfirmPassword.getText() == null ? "" : binding.registerConfirmPassword.getText().toString().trim();
        String inviteCode = binding.registerKey.getText() == null ? "" : binding.registerKey.getText().toString().trim();
        String verificationCode = binding.registerVerificationCode.getText() == null ? "" : binding.registerVerificationCode.getText().toString().trim();

        if (username.isEmpty()) {
            binding.registerName.setError("请输入用户名");
            return;
        }
        if (!username.matches("[A-Za-z0-9_]{3,32}")) {
            binding.registerName.setError("用户名需为 3-32 位字母、数字或下划线");
            return;
        }
        if (email.isEmpty()) {
            binding.registerEmail.setError("请输入邮箱");
            return;
        }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.registerEmail.setError("邮箱格式不正确");
            return;
        }
        if (password.isEmpty()) {
            binding.registerPassword.setError("请输入密码");
            return;
        }
        if (password.length() < 6) {
            binding.registerPassword.setError("密码至少 6 位");
            return;
        }
        if (!password.equals(confirmPassword)) {
            binding.registerConfirmPassword.setError("两次密码不一致");
            return;
        }
        if (!inviteCode.matches("[A-Za-z0-9]{7}")) {
            binding.registerKey.setError("请输入 7 位邀请码");
            return;
        }
        if (!verificationCode.matches("\\d{6}")) {
            binding.registerVerificationCode.setError("请输入 6 位邮箱验证码");
            return;
        }

        binding.registerCreate.setEnabled(false);
        binding.registerCreate.setText("注册中...");

        LauncherAuthBridge.register(this, username, email, password, inviteCode, verificationCode, new LauncherAuthBridge.AuthCallback() {
            @Override
            public void onSuccess(String token) {
                if (binding != null) {
                    binding.registerCreate.setEnabled(true);
                    binding.registerCreate.setText("创建账户");
                }
                Toast.makeText(LauncherRegisterActivity.this, "注册成功", Toast.LENGTH_SHORT).show();
                // 注册成功后返回，LauncherAccountFragment.onResume 会检测已登录状态并跳转到个人信息页
                setResult(RESULT_OK);
                finish();
            }

            @Override
            public void onError(String message) {
                if (binding != null) {
                    binding.registerCreate.setEnabled(true);
                    binding.registerCreate.setText("创建账户");
                }
                showAuthResultDialog("注册失败", message);
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (verificationCodeTimer != null) verificationCodeTimer.cancel();
        super.onDestroy();
    }

    private void showAuthResultDialog(String title, String message) {
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this).create();
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);

        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(252), WindowManager.LayoutParams.WRAP_CONTENT);

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

        TextView msgView = new TextView(this);
        msgView.setText(message);
        msgView.setGravity(android.view.Gravity.CENTER);
        msgView.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_muted_color));
        msgView.setTextSize(12);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        msgLp.setMargins(0, dp(13), 0, 0);
        root.addView(msgView, msgLp);

        TextView okBtn = new TextView(this);
        okBtn.setText("知道了");
        okBtn.setGravity(android.view.Gravity.CENTER);
        LauncherTheme.primaryButton(okBtn);
        okBtn.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        okLp.setMargins(0, dp(11), 0, 0);
        root.addView(okBtn, okLp);

        window.setContentView(root);
    }

    private void configureEdgeToEdgeWindow() {
        boolean darkMode = LauncherActivity.isLauncherDarkMode(this);
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(ContextCompat.getColor(this, R.color.launcher_bg_color));
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        if (!darkMode) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private void applySavedToneMode() {
        LauncherActivity.applySavedToneMode(this);
    }

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
