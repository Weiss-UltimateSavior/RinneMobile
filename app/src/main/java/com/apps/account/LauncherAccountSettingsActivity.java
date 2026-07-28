package com.apps.account;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.apps.UserData.LauncherUserData;
import com.core.R;
import com.core.databinding.ActivityLauncherAccountSettingsBinding;
import com.core.launcherbridge.ConfigCallback;
import com.core.launcherbridge.LauncherAuthBridge;
import com.core.launcherbridge.PlayDataCallback;
import com.core.launcherbridge.SubscriptionCallback;
import com.apps.LauncherActivity;
import com.apps.sync.LauncherSyncScheduler;
import com.apps.theme.LauncherDialogFactory;
import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherTabletPortraitScaler;

public class LauncherAccountSettingsActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "launcher_account_settings";

    private ActivityLauncherAccountSettingsBinding binding;
    private AlertDialog loadingDialog;
    private boolean emailSubscriptionUpdating;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        applySavedToneMode();
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();

        binding = ActivityLauncherAccountSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        LauncherTabletPortraitScaler.applyActivityContent(this);
        applySystemBarInsets();
        LauncherTheme.applyPrimaryTone(binding.getRoot());

        bindActions();
        renderAllChips();
        refreshEmailSubscription();
        LauncherSyncScheduler.updateSchedule(this);
    }

    private void bindActions() {
        binding.rowSyncConfig.setOnClickListener(v -> onSyncConfigClick());
        binding.rowRealtimePlaytime.setOnClickListener(v -> onRealtimePlaytimeClick());
        binding.rowEmailSubscribe.setOnClickListener(v -> onEmailSubscriptionClick());
    }

    private void refreshEmailSubscription() {
        if (!LauncherAuthBridge.isLoggedIn(this)) return;
        LauncherAuthBridge.fetchEmailSubscription(this, new SubscriptionCallback() {
            @Override
            public void onSuccess(boolean subscribed) {
                if (isFinishing()) return;
                saveEmailSubscription(subscribed);
                renderChip(binding.chipEmailSubscribe, subscribed);
            }

            @Override
            public void onError(String message) {
                // 保留本地缓存状态；网络错误不打断账号设置页的其他操作。
            }
        });
    }

    private void onEmailSubscriptionClick() {
        if (emailSubscriptionUpdating) return;
        if (!LauncherAuthBridge.isLoggedIn(this)) {
            showResultDialog(getString(R.string.social_login_required),
                    getString(R.string.social_subscription_login_required));
            return;
        }
        boolean subscribed = getSharedPreferences(PREFS_NAME, 0).getBoolean("email_subscribe", false);
        if (subscribed) {
            updateEmailSubscription(false);
        } else {
            showEmailSubscriptionConfirmDialog();
        }
    }

    private void showEmailSubscriptionConfirmDialog() {
        LauncherDialogFactory.showConfirm(
                this,
                getString(R.string.social_enable_subscription),
                getString(R.string.social_enable_subscription_message),
                getString(R.string.social_enable_subscription),
                () -> updateEmailSubscription(true)
        );
    }

    private void updateEmailSubscription(boolean subscribed) {
        emailSubscriptionUpdating = true;
        binding.rowEmailSubscribe.setEnabled(false);
        LauncherAuthBridge.updateEmailSubscription(this, subscribed, new SubscriptionCallback() {
            @Override
            public void onSuccess(boolean actualSubscribed) {
                if (isFinishing()) return;
                emailSubscriptionUpdating = false;
                binding.rowEmailSubscribe.setEnabled(true);
                saveEmailSubscription(actualSubscribed);
                renderChip(binding.chipEmailSubscribe, actualSubscribed);
                Toast.makeText(LauncherAccountSettingsActivity.this,
                        actualSubscribed ? R.string.social_subscription_enabled
                                : R.string.social_subscription_disabled, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String message) {
                if (isFinishing()) return;
                emailSubscriptionUpdating = false;
                binding.rowEmailSubscribe.setEnabled(true);
                showResultDialog(getString(R.string.social_subscription_update_failed), message);
            }
        });
    }

    private void saveEmailSubscription(boolean subscribed) {
        getSharedPreferences(PREFS_NAME, 0).edit().putBoolean("email_subscribe", subscribed).apply();
    }

    private void onSyncConfigClick() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, 0);
        boolean currentEnabled = prefs.getBoolean("sync_config", false);
        if (currentEnabled) {
            // 关闭：直接关闭并取消定时备份
            prefs.edit().putBoolean("sync_config", false).apply();
            renderChip(binding.chipSyncConfig, false);
            LauncherSyncScheduler.updateSchedule(this);
            return;
        }
        // 开启：弹窗确认是否上传当前配置
        showSyncConfirmDialog();
    }

    private void onRealtimePlaytimeClick() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, 0);
        boolean currentEnabled = prefs.getBoolean("realtime_playtime", getDefault("realtime_playtime"));
        if (currentEnabled) {
            // 关闭：直接关闭
            prefs.edit().putBoolean("realtime_playtime", false).apply();
            renderChip(binding.chipRealtimePlaytime, false);
            return;
        }
        // 开启：弹窗确认
        showRealtimePlaytimeConfirmDialog();
    }

    private void showRealtimePlaytimeConfirmDialog() {
        LauncherDialogFactory.showStandardConfirm(
                this,
                getString(R.string.social_realtime_play_time),
                getString(R.string.social_realtime_play_time_message),
                getString(R.string.social_confirm_enable),
                () -> {
                    getSharedPreferences(PREFS_NAME, 0).edit().putBoolean("realtime_playtime", true).apply();
                    renderChip(binding.chipRealtimePlaytime, true);
                }
        );
    }

    private void showSyncConfirmDialog() {
        LauncherDialogFactory.showStandardConfirm(
                this,
                getString(R.string.social_config_sync),
                getString(R.string.social_config_sync_message),
                getString(R.string.social_confirm_upload),
                this::enableSyncAndUpload
        );
    }

    private void enableSyncAndUpload() {
        // 先开启开关
        getSharedPreferences(PREFS_NAME, 0).edit().putBoolean("sync_config", true).apply();
        renderChip(binding.chipSyncConfig, true);
        LauncherSyncScheduler.updateSchedule(this);

        // 显示加载弹窗
        loadingDialog = showLoadingDialog(getString(R.string.social_uploading_config),
                getString(R.string.social_uploading_config_note));

        // 导出并上传
        String settingsJson = LauncherUserData.exportSettingsJson(this);
        LauncherAuthBridge.uploadConfig(this, settingsJson, new ConfigCallback() {
            @Override
            public void onSuccess(String configJson) {
                // 上传游玩记录
                String playData = LauncherUserData.exportCloudPlayData(LauncherAccountSettingsActivity.this);
                if (playData == null || playData.trim().isEmpty()) {
                    // 导出失败，仅配置上传成功
                    dismissLoading();
                    showResultDialog(getString(R.string.social_partial_upload_failed),
                            getString(R.string.social_export_failed));
                    return;
                }
                LauncherAuthBridge.uploadPlayData(LauncherAccountSettingsActivity.this, playData, new PlayDataCallback() {
                    @Override
                    public void onSuccess(String playData) {
                        dismissLoading();
                        showResultDialog(getString(R.string.social_upload_success),
                                getString(R.string.social_upload_success_all));
                    }

                    @Override
                    public void onError(String message) {
                        dismissLoading();
                        // 服务端对未变化的游玩数据会以 USER_NOT_FOUND 返回；
                        // 前一步配置上传已成功，故将其视为无需重复上传。
                        if (isUnchangedPlayDataError(message)) {
                            showResultDialog(getString(R.string.social_upload_success),
                                    getString(R.string.social_upload_no_changes));
                            return;
                        }
                        showResultDialog(getString(R.string.social_partial_upload_failed),
                                getString(R.string.social_play_record_upload_failed, message));
                    }
                });
            }

            @Override
            public void onError(String message) {
                dismissLoading();
                showResultDialog(getString(R.string.social_upload_failed), message);
            }
        });
    }

    private boolean isUnchangedPlayDataError(String message) {
        return message != null && (message.contains("USER_NOT_FOUND") || message.contains("用户不存在"));
    }

    private AlertDialog showLoadingDialog(String titleText, String hintText) {
        return LauncherDialogFactory.showLoading(this, titleText, hintText);
    }

    private void dismissLoading() {
        if (loadingDialog != null && loadingDialog.isShowing()) {
            loadingDialog.dismiss();
            loadingDialog = null;
        }
    }

    private void showResultDialog(String title, String message) {
        LauncherDialogFactory.showInfo(this, title, message);
    }

    private void renderAllChips() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, 0);
        renderChip(binding.chipSyncConfig, prefs.getBoolean("sync_config", getDefault("sync_config")));
        renderChip(binding.chipRealtimePlaytime, prefs.getBoolean("realtime_playtime", getDefault("realtime_playtime")));
        renderChip(binding.chipEmailSubscribe, prefs.getBoolean("email_subscribe", getDefault("email_subscribe")));
    }

    private void renderChip(android.widget.TextView chip, boolean enabled) {
        chip.setText(enabled ? R.string.social_action_disable : R.string.social_action_enable);
        LauncherTheme.chip(chip, enabled);
    }

    private boolean getDefault(String key) {
        switch (key) {
            case "realtime_playtime":
                return true;
            default:
                return false;
        }
    }

    private void applySystemBarInsets() {
        int originalLeft = binding.accountSettingsScroll.getPaddingLeft();
        int originalTop = binding.accountSettingsScroll.getPaddingTop();
        int originalRight = binding.accountSettingsScroll.getPaddingRight();
        int originalBottom = binding.accountSettingsScroll.getPaddingBottom();

        binding.getRoot().setOnApplyWindowInsetsListener((view, insets) -> {
            binding.accountSettingsScroll.setPadding(
                    originalLeft,
                    originalTop + insets.getSystemWindowInsetTop(),
                    originalRight,
                    originalBottom
            );
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
