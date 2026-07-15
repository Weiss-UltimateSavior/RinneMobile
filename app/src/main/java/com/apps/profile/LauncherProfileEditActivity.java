package com.apps.profile;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.yuki.yukihub.R;
import com.yuki.yukihub.databinding.ActivityLauncherProfileEditBinding;
import com.yuki.yukihub.launcherbridge.LauncherAuthBridge;
import com.apps.LauncherActivity;
import com.apps.theme.LauncherDialogFactory;
import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherTabletPortraitScaler;

public class LauncherProfileEditActivity extends AppCompatActivity {
    private ActivityLauncherProfileEditBinding binding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        applySavedToneMode();
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();

        binding = ActivityLauncherProfileEditBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        LauncherTabletPortraitScaler.applyActivityContent(this);
        applySystemBarInsets();
        bindActions();
        LauncherTheme.applyPrimaryTone(binding.getRoot());
        LauncherTheme.longActionButton(binding.profileEditBack);
        LauncherTheme.longActionButton(binding.btnUpdateUsername);
        LauncherTheme.longActionButton(binding.btnUpdatePassword);
    }

    private void applySystemBarInsets() {
        int originalLeft = binding.profileEditScroll.getPaddingLeft();
        int originalTop = binding.profileEditScroll.getPaddingTop();
        int originalRight = binding.profileEditScroll.getPaddingRight();
        int originalBottom = binding.profileEditScroll.getPaddingBottom();

        binding.getRoot().setOnApplyWindowInsetsListener((view, insets) -> {
            binding.profileEditScroll.setPadding(
                    originalLeft,
                    originalTop + insets.getSystemWindowInsetTop(),
                    originalRight,
                    originalBottom
            );
            return insets;
        });
        binding.getRoot().requestApplyInsets();
    }

    private void bindActions() {
        binding.profileEditBack.setOnClickListener(view -> LauncherMotion.finish(this));
        binding.btnUpdateUsername.setOnClickListener(view -> confirmUpdateUsername());
        binding.btnUpdatePassword.setOnClickListener(view -> confirmUpdatePassword());
    }

    private void confirmUpdateUsername() {
        String newUsername = binding.inputNewUsername.getText() == null ? "" : binding.inputNewUsername.getText().toString().trim();
        if (newUsername.isEmpty()) {
            binding.inputNewUsername.setError("请输入新用户名");
            return;
        }
        if (newUsername.length() < 3 || newUsername.length() > 32) {
            binding.inputNewUsername.setError("用户名需3-32位");
            return;
        }
        if (!newUsername.matches("^[a-zA-Z0-9_]+$")) {
            binding.inputNewUsername.setError("仅支持字母、数字和下划线");
            return;
        }
        showConfirmDialog("修改用户名", "确定将用户名修改为「" + newUsername + "」吗？", this::performUpdateUsername);
    }

    private void confirmUpdatePassword() {
        String oldPassword = binding.inputOldPassword.getText() == null ? "" : binding.inputOldPassword.getText().toString().trim();
        String newPassword = binding.inputNewPassword.getText() == null ? "" : binding.inputNewPassword.getText().toString().trim();
        String confirmPassword = binding.inputConfirmNewPassword.getText() == null ? "" : binding.inputConfirmNewPassword.getText().toString().trim();
        if (oldPassword.isEmpty()) {
            binding.inputOldPassword.setError("请输入旧密码");
            return;
        }
        if (newPassword.isEmpty()) {
            binding.inputNewPassword.setError("请输入新密码");
            return;
        }
        if (newPassword.length() < 6) {
            binding.inputNewPassword.setError("密码至少6位");
            return;
        }
        if (!newPassword.equals(confirmPassword)) {
            binding.inputConfirmNewPassword.setError("两次密码不一致");
            return;
        }
        showConfirmDialog("修改密码", "确定修改密码吗？修改后需重新登录。", this::performUpdatePassword);
    }

    private void showConfirmDialog(String title, String message, Runnable onConfirm) {
        LauncherDialogFactory.showStandardConfirm(this, title, message, "确定", onConfirm);
    }

    private void performUpdateUsername() {
        String newUsername = binding.inputNewUsername.getText() == null ? "" : binding.inputNewUsername.getText().toString().trim();

        binding.btnUpdateUsername.setEnabled(false);
        binding.btnUpdateUsername.setText("修改中...");

        LauncherAuthBridge.updateUsername(this, newUsername, new LauncherAuthBridge.AuthCallback() {
            @Override
            public void onSuccess(String token) {
                if (binding != null) {
                    binding.btnUpdateUsername.setEnabled(true);
                    binding.btnUpdateUsername.setText("修改用户名");
                }
                Toast.makeText(LauncherProfileEditActivity.this, "用户名修改成功", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String message) {
                if (binding != null) {
                    binding.btnUpdateUsername.setEnabled(true);
                    binding.btnUpdateUsername.setText("修改用户名");
                }
                showResultDialog("修改失败", message);
            }
        });
    }

    private void performUpdatePassword() {
        String oldPassword = binding.inputOldPassword.getText() == null ? "" : binding.inputOldPassword.getText().toString().trim();
        String newPassword = binding.inputNewPassword.getText() == null ? "" : binding.inputNewPassword.getText().toString().trim();

        binding.btnUpdatePassword.setEnabled(false);
        binding.btnUpdatePassword.setText("修改中...");

        LauncherAuthBridge.updatePassword(this, oldPassword, newPassword, new LauncherAuthBridge.AuthCallback() {
            @Override
            public void onSuccess(String token) {
                if (binding != null) {
                    binding.btnUpdatePassword.setEnabled(true);
                    binding.btnUpdatePassword.setText("修改密码");
                }
                Toast.makeText(LauncherProfileEditActivity.this, "密码修改成功，请重新登录", Toast.LENGTH_SHORT).show();
                // 密码修改后 Token 已吊销，返回登录页
                setResult(RESULT_OK);
                finish();
            }

            @Override
            public void onError(String message) {
                if (binding != null) {
                    binding.btnUpdatePassword.setEnabled(true);
                    binding.btnUpdatePassword.setText("修改密码");
                }
                showResultDialog("修改失败", message);
            }
        });
    }

    private void showResultDialog(String title, String message) {
        LauncherDialogFactory.showInfo(this, title, message);
    }

    private void configureEdgeToEdgeWindow() {
        boolean darkMode = LauncherActivity.isLauncherDarkMode(this);
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
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
