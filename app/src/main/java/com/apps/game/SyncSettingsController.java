package com.apps.game;

import android.widget.Toast;

import com.apps.theme.LauncherDialogFactory;
import com.core.R;
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
        void openSyncCenter();
    }

    private final ManageHost host;
    private final BackupActions backupActions;

    public SyncSettingsController(ManageHost host, BackupActions backupActions) {
        this.host = host;
        this.backupActions = backupActions;
    }

    public void showSyncOptions() {
        LauncherDialogFactory.showMessageActionChoices(
                host.requireContext(),
                host.getString(R.string.game_sync_cloud),
                syncStatusText(),
                new CharSequence[] {
                        host.getString(R.string.game_sync_now),
                        host.getString(R.string.game_sync_open_center),
                        host.getString(R.string.game_sync_export_backup),
                        host.getString(R.string.game_sync_import_backup)
                },
                index -> {
                    switch (index) {
                        case 0: syncNow(); break;
                        case 1: backupActions.openSyncCenter(); break;
                        case 2: backupActions.onExportLocalBackup(); break;
                        case 3: backupActions.onConfirmImportLocalBackup(); break;
                    }
                });
    }

    private String syncStatusText() {
        if (!LauncherSyncBridge.isConfigured(host.requireContext())) {
            return host.getString(R.string.game_sync_webdav_not_configured);
        }
        long last = LauncherSyncBridge.lastSyncTime(host.requireContext());
        if (last <= 0L) return host.getString(R.string.game_sync_webdav_never);
        return host.getString(R.string.game_sync_last_time,
                android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", last));
    }

    private void syncNow() {
        if (!LauncherSyncBridge.isConfigured(host.requireContext())) {
            host.showConfirmDialog(
                    host.getString(R.string.game_sync_not_logged_in),
                    host.getString(R.string.game_sync_login_first),
                    host.getString(R.string.game_common_open),
                    backupActions::openSyncCenter
            );
            return;
        }
        LauncherSyncBridge.syncNow(host.requireContext(), new LauncherSyncBridge.Callback() {
            @Override
            public void onStart() {
                Toast.makeText(host.requireContext(), R.string.game_sync_syncing, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onProgress(String item, boolean changed) {
            }

            @Override
            public void onComplete(String message) {
                host.showConfirmDialog(host.getString(R.string.game_sync_complete), message,
                        host.getString(R.string.game_common_got_it), () -> {});
            }

            @Override
            public void onError(String error) {
                host.showConfirmDialog(host.getString(R.string.game_sync_failed), error,
                        host.getString(R.string.game_common_got_it), () -> {});
            }
        });
    }
}
