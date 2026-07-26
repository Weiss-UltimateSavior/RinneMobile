package com.apps.game;

import android.content.Intent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.apps.sync.LauncherSyncCenterActivity;
import com.apps.theme.LauncherMotion;
import com.core.launcherbridge.LauncherSyncBridge;

/**
 * 云端同步设置控制器：从 LauncherManageFragment 抽取的云端同步相关逻辑。
 * 包括显示同步选项、查询同步状态、立即同步等操作。
 *
 * 依赖 ManageHost 提供 Context、UI 缩放及共享对话框构建器；
 * 依赖 BackupActions 回调本地备份导入导出（保留在 Fragment 中）。
 */
public final class SyncSettingsController {

    /** 本地备份导入导出回调，由 LauncherManageFragment 实现。 */
    public interface BackupActions {
        void onExportLocalBackup();
        void onConfirmImportLocalBackup();
    }

    private final ManageHost host;
    private final BackupActions backupActions;

    public SyncSettingsController(ManageHost host, BackupActions backupActions) {
        this.host = host;
        this.backupActions = backupActions;
    }

    public void showSyncOptions() {
        AlertDialog dialog = new AlertDialog.Builder(host.requireContext()).create();
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);

        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(host.dp(252), WindowManager.LayoutParams.WRAP_CONTENT);

        LinearLayout root = new LinearLayout(host.requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(host.dp(22), host.dp(20), host.dp(22), host.dp(16));
        root.setBackgroundResource(com.core.R.drawable.launcher_dialog_bg);

        TextView title = host.createDialogTitle("云端同步");
        root.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView info = new TextView(host.requireContext());
        info.setGravity(android.view.Gravity.CENTER);
        info.setText(syncStatusText());
        info.setTextColor(ContextCompat.getColor(host.requireContext(), com.core.R.color.launcher_text_muted_color));
        host.setResponsiveTextSize(info, 12);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        infoLp.setMargins(0, host.dp(11), 0, 0);
        root.addView(info, infoLp);

        host.addFeedbackOption(root, "立即同步", dialog, this::syncNow);
        host.addFeedbackOption(root, "打开同步中心", dialog, () ->
                host.startActivity(new Intent(host.requireContext(), LauncherSyncCenterActivity.class)));
        host.addFeedbackOption(root, "导出本地备份", dialog, backupActions::onExportLocalBackup);
        host.addFeedbackOption(root, "导入本地备份", dialog, backupActions::onConfirmImportLocalBackup);

        TextView cancel = host.createDialogCancelButton(dialog);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, host.dp(36));
        cancelLp.setMargins(0, host.dp(9), 0, 0);
        root.addView(cancel, cancelLp);

        window.setContentView(root);
    }

    private String syncStatusText() {
        if (!LauncherSyncBridge.isConfigured(host.requireContext())) return "WebDAV 尚未配置，请先打开同步中心。";
        long last = LauncherSyncBridge.lastSyncTime(host.requireContext());
        if (last <= 0L) return "已配置 WebDAV，尚未完成过同步。";
        return "上次同步：" + android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", last);
    }

    private void syncNow() {
        if (!LauncherSyncBridge.isConfigured(host.requireContext())) {
            host.showConfirmDialog(
                    "未登录",
                    "请打开同步中心登录后再同步。",
                    "打开",
                    () -> host.startActivity(new Intent(host.requireContext(), LauncherSyncCenterActivity.class))
            );
            return;
        }
        LauncherSyncBridge.syncNow(host.requireContext(), new LauncherSyncBridge.Callback() {
            @Override
            public void onStart() {
                Toast.makeText(host.requireContext(), "正在同步...", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onProgress(String item, boolean changed) {
            }

            @Override
            public void onComplete(String message) {
                host.showConfirmDialog("同步完成", message, "知道了", () -> {});
            }

            @Override
            public void onError(String error) {
                host.showConfirmDialog("同步失败", error, "知道了", () -> {});
            }
        });
    }
}
