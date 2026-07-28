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

import com.core.R;
import com.core.databinding.ActivityLauncherProfileEditBinding;
import com.core.launcherbridge.AuthCallback;
import com.core.launcherbridge.LauncherAuthBridge;
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
            binding.inputNewUsername.setError(getString(R.string.profile_enter_new_username));
            return;
        }
        if (newUsername.length() < 3 || newUsername.length() > 32) {
            binding.inputNewUsername.setError(getString(R.string.profile_username_length_error));
            return;
        }
        if (!newUsername.matches("^[a-zA-Z0-9_]+$")) {
            binding.inputNewUsername.setError(getString(R.string.profile_username_characters_error));
            return;
        }
        showConfirmDialog(getString(R.string.profile_change_username),
                getString(R.string.profile_confirm_username_change, newUsername),
                this::performUpdateUsername);
    }

    private void confirmUpdatePassword() {
        String oldPassword = binding.inputOldPassword.getText() == null ? "" : binding.inputOldPassword.getText().toString().trim();
        String newPassword = binding.inputNewPassword.getText() == null ? "" : binding.inputNewPassword.getText().toString().trim();
        String confirmPassword = binding.inputConfirmNewPassword.getText() == null ? "" : binding.inputConfirmNewPassword.getText().toString().trim();
        if (oldPassword.isEmpty()) {
            binding.inputOldPassword.setError(getString(R.string.profile_enter_old_password));
            return;
        }
        if (newPassword.isEmpty()) {
            binding.inputNewPassword.setError(getString(R.string.profile_enter_new_password));
            return;
        }
        if (newPassword.length() < 6) {
            binding.inputNewPassword.setError(getString(R.string.profile_password_too_short));
            return;
        }
        if (!newPassword.equals(confirmPassword)) {
            binding.inputConfirmNewPassword.setError(getString(R.string.profile_passwords_do_not_match));
            return;
        }
        showConfirmDialog(getString(R.string.profile_change_password),
                getString(R.string.profile_confirm_password_change), this::performUpdatePassword);
    }

    private void showConfirmDialog(String title, String message, Runnable onConfirm) {
        LauncherDialogFactory.showStandardConfirm(this, title, message,
                getString(R.string.settings_confirm), onConfirm);
    }

    private void performUpdateUsername() {
        String newUsername = binding.inputNewUsername.getText() == null ? "" : binding.inputNewUsername.getText().toString().trim();

        binding.btnUpdateUsername.setEnabled(false);
        binding.btnUpdateUsername.setText(R.string.profile_updating);

        LauncherAuthBridge.updateUsername(this, newUsername, new AuthCallback() {
            @Override
            public void onSuccess(String token) {
                if (binding != null) {
                    binding.btnUpdateUsername.setEnabled(true);
                    binding.btnUpdateUsername.setText(R.string.profile_change_username);
                }
                Toast.makeText(LauncherProfileEditActivity.this,
                        R.string.profile_username_changed, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String message) {
                if (binding != null) {
                    binding.btnUpdateUsername.setEnabled(true);
                    binding.btnUpdateUsername.setText(R.string.profile_change_username);
                }
                showResultDialog(getString(R.string.profile_update_failed), message);
            }
        });
    }

    private void performUpdatePassword() {
        String oldPassword = binding.inputOldPassword.getText() == null ? "" : binding.inputOldPassword.getText().toString().trim();
        String newPassword = binding.inputNewPassword.getText() == null ? "" : binding.inputNewPassword.getText().toString().trim();

        binding.btnUpdatePassword.setEnabled(false);
        binding.btnUpdatePassword.setText(R.string.profile_updating);

        LauncherAuthBridge.updatePassword(this, oldPassword, newPassword, new AuthCallback() {
            @Override
            public void onSuccess(String token) {
                if (binding != null) {
                    binding.btnUpdatePassword.setEnabled(true);
                    binding.btnUpdatePassword.setText(R.string.profile_change_password);
                }
                Toast.makeText(LauncherProfileEditActivity.this,
                        R.string.profile_password_changed_relogin, Toast.LENGTH_SHORT).show();
                // 密码修改后 Token 已吊销，返回登录页
                setResult(RESULT_OK);
                finish();
            }

            @Override
            public void onError(String message) {
                if (binding != null) {
                    binding.btnUpdatePassword.setEnabled(true);
                    binding.btnUpdatePassword.setText(R.string.profile_change_password);
                }
                showResultDialog(getString(R.string.profile_update_failed), message);
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
