package com.apps;

import android.view.LayoutInflater;
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

import com.yuki.yukihub.MainActivity;
import com.yuki.yukihub.R;
import com.yuki.yukihub.databinding.ActivityLauncherToolboxBinding;
import com.yuki.yukihub.launcherbridge.LauncherDiagnosticsBridge;
import com.yuki.yukihub.launcherbridge.YukiHubBridge;
import com.yuki.yukihub.util.DevLogger;

public class LauncherToolboxActivity extends AppCompatActivity {
    private ActivityLauncherToolboxBinding binding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        applySavedToneMode();
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();

        binding = ActivityLauncherToolboxBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applySystemBarInsets();
        bindActions();
        applyThemeTone();
    }

    private void applySystemBarInsets() {
        int originalLeft = binding.toolboxScroll.getPaddingLeft();
        int originalTop = binding.toolboxScroll.getPaddingTop();
        int originalRight = binding.toolboxScroll.getPaddingRight();
        int originalBottom = binding.toolboxScroll.getPaddingBottom();

        binding.getRoot().setOnApplyWindowInsetsListener((view, insets) -> {
            binding.toolboxScroll.setPadding(
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
        bindPendingTool(binding.toolTranslate, "文本翻译");
        binding.toolArchive.setOnClickListener(view -> showLauncherMenu(
                "资源整理",
                new MenuItem("同步中心", () -> YukiHubBridge.openAction(this, MainActivity.ACTION_SYNC_CENTER)),
                new MenuItem("导出备份", () -> YukiHubBridge.openAction(this, MainActivity.ACTION_LOCAL_BACKUP_EXPORT)),
                new MenuItem("导入备份", () -> YukiHubBridge.openAction(this, MainActivity.ACTION_LOCAL_BACKUP_IMPORT))
        ));
        binding.toolLauncher.setOnClickListener(view -> startActivity(new android.content.Intent(this, LauncherAddGameActivity.class)));
        bindPendingTool(binding.toolRecharge, "记录换算");
        binding.toolPayBill.setOnClickListener(view -> YukiHubBridge.openAction(this, MainActivity.ACTION_SCAN));
        binding.toolCreditCard.setOnClickListener(view -> confirmClearCache());
        binding.toolReport.setOnClickListener(view -> showLauncherMenu(
                "运行报告",
                new MenuItem("生成点评", () -> startActivity(new android.content.Intent(this, LauncherAiReviewGenerateActivity.class))),
                new MenuItem("点评历史", () -> startActivity(new android.content.Intent(this, LauncherAiReviewHistoryActivity.class))),
                new MenuItem("智能评价", () -> startActivity(new android.content.Intent(this, LauncherAiReviewActivity.class)))
        ));
    }

    private void bindPendingTool(View view, String name) {
        view.setOnClickListener(v ->
                Toast.makeText(this, name + " 待接入", Toast.LENGTH_SHORT).show());
    }

    private void confirmClearCache() {
        long cacheSize = LauncherDiagnosticsBridge.cacheSize(this);
        showLauncherConfirmDialog(
                "缓存清理",
                "当前可清理缓存约 " + DevLogger.formatSize(cacheSize) + "，确定清理吗？",
                "清理",
                () -> {
                    LauncherDiagnosticsBridge.clearCache(this);
                    Toast.makeText(this, "缓存已清理", Toast.LENGTH_SHORT).show();
                }
        );
    }

    private void showLauncherConfirmDialog(String title, String message, String confirmText, Runnable onConfirm) {
        AlertDialog dialog = new AlertDialog.Builder(this).create();
        dialog.show();

        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout((int) (280 * getResources().getDisplayMetrics().density), WindowManager.LayoutParams.WRAP_CONTENT);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_launcher_confirm, null);
        window.setContentView(dialogView);

        TextView titleView = dialogView.findViewById(R.id.dialogTitle);
        TextView messageView = dialogView.findViewById(R.id.dialogMessage);
        TextView btnCancel = dialogView.findViewById(R.id.dialogBtnCancel);
        TextView btnConfirm = dialogView.findViewById(R.id.dialogBtnConfirm);

        titleView.setText(title);
        messageView.setText(message);
        btnConfirm.setText(confirmText);
        LauncherTheme.dialogButtons(btnCancel, btnConfirm);
        btnCancel.setOnClickListener(view -> dialog.dismiss());
        btnConfirm.setOnClickListener(view -> {
            dialog.dismiss();
            onConfirm.run();
        });
    }

    private void showLauncherMenu(String titleText, MenuItem... items) {
        AlertDialog dialog = new AlertDialog.Builder(this).create();
        dialog.show();

        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(300), WindowManager.LayoutParams.WRAP_CONTENT);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(22), dp(24), dp(18));
        root.setBackgroundResource(R.drawable.launcher_dialog_bg);

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setGravity(android.view.Gravity.CENTER);
        title.setTextColor(ContextCompat.getColor(this, R.color.launcher_text_color));
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        for (MenuItem item : items) {
            addMenuOption(root, item.label, dialog, item.action);
        }

        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setGravity(android.view.Gravity.CENTER);
        cancel.setTextColor(LauncherTheme.primary(this));
        cancel.setTextSize(14);
        cancel.setTypeface(null, android.graphics.Typeface.BOLD);
        cancel.setBackground(LauncherTheme.cancelChip(this));
        cancel.setOnClickListener(view -> dialog.dismiss());
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        cancelLp.setMargins(0, dp(10), 0, 0);
        root.addView(cancel, cancelLp);

        window.setContentView(root);
    }

    private void addMenuOption(LinearLayout root, String label, AlertDialog dialog, Runnable action) {
        TextView option = new TextView(this);
        option.setText(label);
        option.setGravity(android.view.Gravity.CENTER);
        option.setSingleLine(true);
        option.setTextColor(LauncherTheme.primary(this));
        option.setTextSize(14);
        option.setTypeface(null, android.graphics.Typeface.BOLD);
        option.setBackgroundResource(R.drawable.launcher_filter_chip_unselected);
        option.setOnClickListener(view -> {
            dialog.dismiss();
            action.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        lp.setMargins(0, dp(12), 0, 0);
        root.addView(option, lp);
    }

    private void applyThemeTone() {
        LauncherTheme.textPrimary(binding.toolboxNoticeTitle);
        LauncherTheme.textPrimary(binding.toolboxNoticeText);
        binding.toolPayBill.getChildAt(0).setBackground(LauncherTheme.primaryButton(this, 4f));
        LauncherTheme.applyPrimaryTone(binding.getRoot());
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

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class MenuItem {
        final String label;
        final Runnable action;

        MenuItem(String label, Runnable action) {
            this.label = label;
            this.action = action;
        }
    }
}
