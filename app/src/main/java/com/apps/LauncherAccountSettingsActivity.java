package com.apps;

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
import com.yuki.yukihub.R;
import com.yuki.yukihub.databinding.ActivityLauncherAccountSettingsBinding;
import com.yuki.yukihub.launcherbridge.LauncherAuthBridge;

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
        LauncherAuthBridge.fetchEmailSubscription(this, new LauncherAuthBridge.SubscriptionCallback() {
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
            showResultDialog("需要登录", "登录后才能管理邮件订阅");
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
        AlertDialog dialog = new AlertDialog.Builder(this).create();
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

        TextView title = new TextView(this);
        title.setText("开启邮件订阅");
        title.setGravity(Gravity.CENTER);
        title.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_color));
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView message = new TextView(this);
        message.setText("开启后，管理员可向你的注册邮箱发送系统通知和广播邮件。");
        message.setGravity(Gravity.CENTER);
        message.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_muted_color));
        message.setTextSize(12);
        LinearLayout.LayoutParams messageLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        messageLp.setMargins(0, dp(13), 0, 0);
        root.addView(message, messageLp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        buttonsLp.setMargins(0, dp(14), 0, 0);

        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setGravity(Gravity.CENTER);
        cancel.setTextSize(13);
        cancel.setTypeface(null, android.graphics.Typeface.BOLD);
        LauncherTheme.secondaryButton(cancel);
        cancel.setOnClickListener(view -> dialog.dismiss());
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));

        TextView confirm = new TextView(this);
        confirm.setText("开启订阅");
        confirm.setGravity(Gravity.CENTER);
        confirm.setTextSize(13);
        confirm.setTypeface(null, android.graphics.Typeface.BOLD);
        LauncherTheme.primaryButton(confirm);
        confirm.setOnClickListener(view -> {
            dialog.dismiss();
            updateEmailSubscription(true);
        });
        LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        confirmLp.setMargins(dp(8), 0, 0, 0);
        buttons.addView(confirm, confirmLp);
        root.addView(buttons, buttonsLp);
        window.setContentView(root);
    }

    private void updateEmailSubscription(boolean subscribed) {
        emailSubscriptionUpdating = true;
        binding.rowEmailSubscribe.setEnabled(false);
        LauncherAuthBridge.updateEmailSubscription(this, subscribed, new LauncherAuthBridge.SubscriptionCallback() {
            @Override
            public void onSuccess(boolean actualSubscribed) {
                if (isFinishing()) return;
                emailSubscriptionUpdating = false;
                binding.rowEmailSubscribe.setEnabled(true);
                saveEmailSubscription(actualSubscribed);
                renderChip(binding.chipEmailSubscribe, actualSubscribed);
                Toast.makeText(LauncherAccountSettingsActivity.this,
                        actualSubscribed ? "已开启邮件订阅" : "已取消邮件订阅", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String message) {
                if (isFinishing()) return;
                emailSubscriptionUpdating = false;
                binding.rowEmailSubscribe.setEnabled(true);
                showResultDialog("邮件订阅更新失败", message);
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
        AlertDialog dialog = new AlertDialog.Builder(this).create();
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

        TextView title = new TextView(this);
        title.setText("实时游玩时间");
        title.setGravity(Gravity.CENTER);
        title.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_color));
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView message = new TextView(this);
        message.setText("此功能启用将实时上传游玩详细信息，确定要使用吗？");
        message.setGravity(Gravity.CENTER);
        message.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_muted_color));
        message.setTextSize(12);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        msgLp.setMargins(0, dp(13), 0, 0);
        root.addView(message, msgLp);

        TextView confirmBtn = new TextView(this);
        confirmBtn.setText("确定开启");
        confirmBtn.setGravity(Gravity.CENTER);
        LauncherTheme.primaryButton(confirmBtn);
        confirmBtn.setOnClickListener(v -> {
            dialog.dismiss();
            getSharedPreferences(PREFS_NAME, 0).edit().putBoolean("realtime_playtime", true).apply();
            renderChip(binding.chipRealtimePlaytime, true);
        });
        LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        confirmLp.setMargins(0, dp(11), 0, 0);
        root.addView(confirmBtn, confirmLp);

        TextView cancelBtn = new TextView(this);
        cancelBtn.setText("取消");
        cancelBtn.setGravity(Gravity.CENTER);
        cancelBtn.setTextColor(LauncherTheme.primary(this));
        cancelBtn.setTextSize(13);
        cancelBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        cancelBtn.setBackground(LauncherTheme.cancelChip(this));
        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        cancelLp.setMargins(0, dp(9), 0, 0);
        root.addView(cancelBtn, cancelLp);

        window.setContentView(root);
    }

    private void showSyncConfirmDialog() {
        AlertDialog dialog = new AlertDialog.Builder(this).create();
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

        TextView title = new TextView(this);
        title.setText("配置同步");
        title.setGravity(Gravity.CENTER);
        title.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_color));
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView message = new TextView(this);
        message.setText("是否上传当前配置到云端？开启后将在每晚12点自动备份。");
        message.setGravity(Gravity.CENTER);
        message.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_muted_color));
        message.setTextSize(12);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        msgLp.setMargins(0, dp(13), 0, 0);
        root.addView(message, msgLp);

        TextView confirmBtn = new TextView(this);
        confirmBtn.setText("确定上传");
        confirmBtn.setGravity(Gravity.CENTER);
        LauncherTheme.primaryButton(confirmBtn);
        confirmBtn.setOnClickListener(v -> {
            dialog.dismiss();
            enableSyncAndUpload();
        });
        LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        confirmLp.setMargins(0, dp(11), 0, 0);
        root.addView(confirmBtn, confirmLp);

        TextView cancelBtn = new TextView(this);
        cancelBtn.setText("取消");
        cancelBtn.setGravity(Gravity.CENTER);
        cancelBtn.setTextColor(LauncherTheme.primary(this));
        cancelBtn.setTextSize(13);
        cancelBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        cancelBtn.setBackground(LauncherTheme.cancelChip(this));
        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        cancelLp.setMargins(0, dp(9), 0, 0);
        root.addView(cancelBtn, cancelLp);

        window.setContentView(root);
    }

    private void enableSyncAndUpload() {
        // 先开启开关
        getSharedPreferences(PREFS_NAME, 0).edit().putBoolean("sync_config", true).apply();
        renderChip(binding.chipSyncConfig, true);
        LauncherSyncScheduler.updateSchedule(this);

        // 显示加载弹窗
        loadingDialog = showLoadingDialog("正在上传配置...", "请不要关闭应用及网络，否则可能导致配置出错");

        // 导出并上传
        String settingsJson = LauncherUserData.exportSettingsJson(this);
        LauncherAuthBridge.uploadConfig(this, settingsJson, new LauncherAuthBridge.ConfigCallback() {
            @Override
            public void onSuccess(String configJson) {
                // 上传游玩记录
                String playData = LauncherUserData.exportCloudPlayData(LauncherAccountSettingsActivity.this);
                if (playData == null || playData.trim().isEmpty()) {
                    // 导出失败，仅配置上传成功
                    dismissLoading();
                    showResultDialog("部分上传失败", "配置已上传，但本地数据导出失败，游玩记录未能上传");
                    return;
                }
                LauncherAuthBridge.uploadPlayData(LauncherAccountSettingsActivity.this, playData, new LauncherAuthBridge.PlayDataCallback() {
                    @Override
                    public void onSuccess(String playData) {
                        dismissLoading();
                        showResultDialog("上传成功", "配置及游玩记录已同步到云端");
                    }

                    @Override
                    public void onError(String message) {
                        dismissLoading();
                        // 服务端对未变化的游玩数据会以 USER_NOT_FOUND 返回；
                        // 前一步配置上传已成功，故将其视为无需重复上传。
                        if (isUnchangedPlayDataError(message)) {
                            showResultDialog("上传成功", "配置已上传，游玩记录没有变化，无需重复上传");
                            return;
                        }
                        showResultDialog("部分上传失败", "配置已上传，游玩记录上传失败：" + message);
                    }
                });
            }

            @Override
            public void onError(String message) {
                dismissLoading();
                showResultDialog("上传失败", message);
            }
        });
    }

    private boolean isUnchangedPlayDataError(String message) {
        return message != null && (message.contains("USER_NOT_FOUND") || message.contains("用户不存在"));
    }

    private AlertDialog showLoadingDialog(String titleText, String hintText) {
        AlertDialog dialog = new AlertDialog.Builder(this).create();
        dialog.setCancelable(false);
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);

        Window window = dialog.getWindow();
        if (window == null) return dialog;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(270), WindowManager.LayoutParams.WRAP_CONTENT);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(20), dp(22), dp(16));
        root.setBackgroundResource(R.drawable.launcher_dialog_bg);

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setGravity(Gravity.CENTER);
        title.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_color));
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        ProgressBar progressBar = new ProgressBar(this);
        progressBar.setIndeterminate(true);
        progressBar.getIndeterminateDrawable().setColorFilter(
                LauncherTheme.primary(this), android.graphics.PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(dp(32), dp(32));
        pbLp.gravity = Gravity.CENTER_HORIZONTAL;
        pbLp.setMargins(0, dp(14), 0, 0);
        root.addView(progressBar, pbLp);

        TextView hint = new TextView(this);
        hint.setText(hintText);
        hint.setGravity(Gravity.CENTER);
        hint.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_muted_color));
        hint.setTextSize(11);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintLp.setMargins(0, dp(10), 0, 0);
        root.addView(hint, hintLp);

        window.setContentView(root);
        return dialog;
    }

    private void dismissLoading() {
        if (loadingDialog != null && loadingDialog.isShowing()) {
            loadingDialog.dismiss();
            loadingDialog = null;
        }
    }

    private void showResultDialog(String title, String message) {
        AlertDialog dialog = new AlertDialog.Builder(this).create();
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
        titleView.setGravity(Gravity.CENTER);
        titleView.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_color));
        titleView.setTextSize(16);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(titleView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView msgView = new TextView(this);
        msgView.setText(message);
        msgView.setGravity(Gravity.CENTER);
        msgView.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_muted_color));
        msgView.setTextSize(12);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        msgLp.setMargins(0, dp(13), 0, 0);
        root.addView(msgView, msgLp);

        TextView okBtn = new TextView(this);
        okBtn.setText("知道了");
        okBtn.setGravity(Gravity.CENTER);
        LauncherTheme.primaryButton(okBtn);
        okBtn.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        okLp.setMargins(0, dp(11), 0, 0);
        root.addView(okBtn, okLp);

        window.setContentView(root);
    }

    private void renderAllChips() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, 0);
        renderChip(binding.chipSyncConfig, prefs.getBoolean("sync_config", getDefault("sync_config")));
        renderChip(binding.chipRealtimePlaytime, prefs.getBoolean("realtime_playtime", getDefault("realtime_playtime")));
        renderChip(binding.chipEmailSubscribe, prefs.getBoolean("email_subscribe", getDefault("email_subscribe")));
    }

    private void renderChip(android.widget.TextView chip, boolean enabled) {
        chip.setText(enabled ? "关闭" : "开启");
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
