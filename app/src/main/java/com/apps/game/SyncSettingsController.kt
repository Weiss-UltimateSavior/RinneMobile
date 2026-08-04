package com.apps.game

import android.text.format.DateFormat
import android.widget.Toast

import com.apps.theme.LauncherDialogFactory
import com.core.R
import com.core.launcherbridge.LauncherSyncBridge

/**
 * 云端同步设置控制器：从 LauncherManageFragment 抽取的云端同步相关逻辑。
 * 包括显示同步选项、查询同步状态、立即同步等操作。
 *
 * 依赖 ManageHost 提供 Context、UI 缩放及共享对话框构建器；
 * 依赖 BackupActions 回调本地备份导入导出（保留在 Fragment 中）。
 */
class SyncSettingsController(
    private val host: ManageHost,
    private val backupActions: BackupActions
) {

    /** 本地备份导入导出回调，由 LauncherManageFragment 实现。 */
    interface BackupActions {
        fun onExportLocalBackup()
        fun onConfirmImportLocalBackup()
        fun openSyncCenter()
    }

    fun showSyncOptions() {
        LauncherDialogFactory.showMessageActionChoices(
            host.requireContext(),
            host.getString(R.string.game_sync_cloud),
            syncStatusText(),
            arrayOf<CharSequence>(
                host.getString(R.string.game_sync_now),
                host.getString(R.string.game_sync_open_center),
                host.getString(R.string.game_sync_export_backup),
                host.getString(R.string.game_sync_import_backup)
            )
        ) { index ->
            when (index) {
                0 -> syncNow()
                1 -> backupActions.openSyncCenter()
                2 -> backupActions.onExportLocalBackup()
                3 -> backupActions.onConfirmImportLocalBackup()
            }
        }
    }

    private fun syncStatusText(): String {
        if (!LauncherSyncBridge.isConfigured(host.requireContext())) {
            return host.getString(R.string.game_sync_webdav_not_configured)
        }
        val last = LauncherSyncBridge.lastSyncTime(host.requireContext())
        if (last <= 0L) return host.getString(R.string.game_sync_webdav_never)
        return host.getString(R.string.game_sync_last_time, DateFormat.format("yyyy-MM-dd HH:mm", last))
    }

    private fun syncNow() {
        if (!LauncherSyncBridge.isConfigured(host.requireContext())) {
            host.showConfirmDialog(
                host.getString(R.string.game_sync_not_logged_in),
                host.getString(R.string.game_sync_login_first),
                host.getString(R.string.game_common_open)
            ) { backupActions.openSyncCenter() }
            return
        }
        LauncherSyncBridge.syncNow(
            host.requireContext(),
            object : LauncherSyncBridge.Callback {
                override fun onStart() {
                    if (!host.isUiAvailable()) return@onStart
                    Toast.makeText(host.requireContext(), R.string.game_sync_syncing, Toast.LENGTH_SHORT).show()
                }

                override fun onProgress(item: String, changed: Boolean) {
                }

                override fun onComplete(message: String) {
                    if (!host.isUiAvailable()) return@onComplete
                    host.showConfirmDialog(
                        host.getString(R.string.game_sync_complete),
                        message,
                        host.getString(R.string.game_common_got_it)
                    ) { }
                }

                override fun onError(error: String) {
                    if (!host.isUiAvailable()) return@onError
                    host.showConfirmDialog(
                        host.getString(R.string.game_sync_failed),
                        error,
                        host.getString(R.string.game_common_got_it)
                    ) { }
                }
            }
        )
    }
}
