package com.apps.game;

import android.net.Uri;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;

import org.json.JSONObject;

import com.core.R;
import com.core.launcherbridge.LauncherSyncBridge;
import com.core.util.AppExecutors;

/**
 * 本地备份导入/导出控制器：从 LauncherManageFragment 抽离的本地备份逻辑。
 *
 * 依赖 ManageHost 提供生命周期守卫、Context、主线程队列与共享对话框，
 * 通过注入的 ActivityResultLauncher 完成文件选择与创建。
 */
public final class LocalBackupController {

    private final ManageHost host;
    private final ActivityResultLauncher<String[]> backupOpenLauncher;
    private final ActivityResultLauncher<String> backupCreateLauncher;

    public LocalBackupController(ManageHost host,
                                 ActivityResultLauncher<String[]> backupOpenLauncher,
                                 ActivityResultLauncher<String> backupCreateLauncher) {
        this.host = host;
        this.backupOpenLauncher = backupOpenLauncher;
        this.backupCreateLauncher = backupCreateLauncher;
    }

    public void confirmImportLocalBackup() {
        host.showConfirmDialog(host.getString(R.string.game_backup_import_title),
                host.getString(R.string.game_backup_import_message),
                host.getString(R.string.game_backup_choose_file), () ->
                backupOpenLauncher.launch(new String[]{"application/octet-stream", "application/json", "text/*", "*/*"}));
    }

    public void importLocalBackup(Uri uri) {
        if (host.isImportInProgress()) return;
        host.setImportInProgress(true);
        android.content.Context appContext = host.getAppContext();
        AppExecutors.runOnSingle(() -> {
            try {
                byte[] rawBytes = LauncherSyncBridge.readBytesFromUri(appContext, uri);
                JSONObject root = LauncherSyncBridge.importLocalBackupFromBytes(appContext, rawBytes);
                int gameCount = root.optJSONArray("games") == null ? 0 : root.optJSONArray("games").length();
                int sessionCount = root.optJSONArray("play_sessions") == null ? 0 : root.optJSONArray("play_sessions").length();
                int metaCount = root.optJSONArray("metadata_cache") == null ? 0 : root.optJSONArray("metadata_cache").length();
                host.getMainQueue().post(() -> {
                    if (!host.isAdded()) return;
                    host.setImportInProgress(false);
                    host.showConfirmDialog(host.getString(R.string.game_backup_import_success),
                            host.getString(R.string.game_backup_import_counts,
                                    gameCount, sessionCount, metaCount),
                            host.getString(R.string.game_common_got_it), () -> {});
                });
            } catch (Throwable t) {
                Log.e("LauncherManage", "import backup failed", t);
                host.getMainQueue().post(() -> {
                    if (!host.isAdded()) return;
                    host.setImportInProgress(false);
                    host.showConfirmDialog(host.getString(R.string.game_import_failed),
                            t.getMessage() != null ? t.getMessage()
                                    : host.getString(R.string.game_common_unknown_error),
                            host.getString(R.string.game_common_got_it), () -> {});
                });
            }
        });
    }

    public void exportLocalBackupToFile() {
        try {
            backupCreateLauncher.launch("yukihub_backup_" + System.currentTimeMillis() + ".ykbak");
        } catch (Throwable t) {
            host.showConfirmDialog(host.getString(R.string.game_save_export_failed),
                    t.getMessage() != null ? t.getMessage()
                            : host.getString(R.string.game_common_unknown_error),
                    host.getString(R.string.game_common_got_it), () -> {});
        }
    }

    public void exportLocalBackup(Uri uri) {
        android.content.Context appContext = host.getAppContext();
        AppExecutors.runOnSingle(() -> {
            try {
                LauncherSyncBridge.GzipBackup backup = LauncherSyncBridge.exportLocalBackupAsGzip(appContext);
                try (java.io.OutputStream out = appContext.getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new Exception("openOutputStream failed");
                    out.write(backup.bytes);
                    out.flush();
                }
                int compressedKb = backup.bytes.length / 1024;
                int originalKb = backup.originalSize / 1024;
                host.getMainQueue().post(() -> {
                    if (!host.isAdded()) return;
                    host.showConfirmDialog(host.getString(R.string.game_backup_export_success),
                            host.getString(R.string.game_backup_export_size,
                                    compressedKb, originalKb),
                            host.getString(R.string.game_common_got_it), () -> {});
                });
            } catch (Throwable t) {
                Log.e("LauncherManage", "export backup failed", t);
                host.getMainQueue().post(() -> {
                    if (!host.isAdded()) return;
                    host.showConfirmDialog(host.getString(R.string.game_save_export_failed),
                            t.getMessage() != null ? t.getMessage()
                                    : host.getString(R.string.game_common_unknown_error),
                            host.getString(R.string.game_common_got_it), () -> {});
                });
            }
        });
    }
}
