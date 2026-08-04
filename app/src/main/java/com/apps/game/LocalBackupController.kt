package com.apps.game

import android.content.ActivityNotFoundException
import android.net.Uri
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import com.core.CoreBackup
import com.core.R
import com.core.launcherbridge.LauncherSyncBridge
import com.core.util.AppExecutors
import java.io.IOException

/**
 * 本地备份导入/导出控制器：从 LauncherManageFragment 抽离的本地备份逻辑。
 *
 * 依赖 ManageHost 提供生命周期守卫、Context、主线程队列与共享对话框，
 * 通过注入的 ActivityResultLauncher 完成文件选择与创建。
 */
class LocalBackupController(
    private val host: ManageHost,
    private val backupOpenLauncher: ActivityResultLauncher<Array<String>>,
    private val backupCreateLauncher: ActivityResultLauncher<String>
) {

    fun confirmImportLocalBackup() {
        host.showConfirmDialog(
            host.getString(R.string.game_backup_import_title),
            host.getString(R.string.game_backup_import_message),
            host.getString(R.string.game_backup_choose_file)
        ) {
            backupOpenLauncher.launch(arrayOf("application/octet-stream", "application/json", "text/*", "*/*"))
        }
    }

    fun importLocalBackup(uri: Uri) {
        if (host.isImportInProgress) return
        host.isImportInProgress = true
        val appContext = host.appContext
        AppExecutors.runOnSingle {
            try {
                val rawBytes = LauncherSyncBridge.readBytesFromUri(appContext, uri)
                val root = LauncherSyncBridge.importLocalBackupFromBytes(appContext, rawBytes)
                val games = root.optJSONArray("games")
                val gameCount = games?.length() ?: 0
                val playSessions = root.optJSONArray("play_sessions")
                val sessionCount = playSessions?.length() ?: 0
                val metadataCache = root.optJSONArray("metadata_cache")
                val metaCount = metadataCache?.length() ?: 0
                host.mainQueue.post {
                    if (!host.isUiAvailable) return@post
                    host.isImportInProgress = false
                    host.showConfirmDialog(
                        host.getString(R.string.game_backup_import_success),
                        host.getString(R.string.game_backup_import_counts, gameCount, sessionCount, metaCount),
                        host.getString(R.string.game_common_got_it)
                    ) { }
                }
            } catch (error: Error) {
                throw error
            } catch (error: Exception) {
                Log.e("LauncherManage", "import backup failed", error)
                host.mainQueue.post {
                    if (!host.isUiAvailable) return@post
                    host.isImportInProgress = false
                    host.showConfirmDialog(
                        host.getString(R.string.game_import_failed),
                        error.message ?: host.getString(R.string.game_common_unknown_error),
                        host.getString(R.string.game_common_got_it)
                    ) { }
                }
            }
        }
    }

    fun exportLocalBackupToFile() {
        try {
            backupCreateLauncher.launch(CoreBackup.FILE_PREFIX + System.currentTimeMillis() + ".ykbak")
        } catch (error: ActivityNotFoundException) {
            showExportError(error)
        } catch (error: IllegalStateException) {
            showExportError(error)
        } catch (error: IllegalArgumentException) {
            showExportError(error)
        } catch (error: SecurityException) {
            showExportError(error)
        }
    }

    fun exportLocalBackup(uri: Uri) {
        val appContext = host.appContext
        AppExecutors.runOnSingle {
            try {
                val backup = LauncherSyncBridge.exportLocalBackupAsGzip(appContext)
                val out = appContext.contentResolver.openOutputStream(uri)
                    ?: throw IOException("openOutputStream failed")
                out.use {
                    it.write(backup.bytes)
                    it.flush()
                }
                val compressedKb = backup.bytes.size / 1024
                val originalKb = backup.originalSize / 1024
                host.mainQueue.post {
                    if (!host.isUiAvailable) return@post
                    host.showConfirmDialog(
                        host.getString(R.string.game_backup_export_success),
                        host.getString(R.string.game_backup_export_size, compressedKb, originalKb),
                        host.getString(R.string.game_common_got_it)
                    ) { }
                }
            } catch (error: Error) {
                throw error
            } catch (error: Exception) {
                Log.e("LauncherManage", "export backup failed", error)
                host.mainQueue.post {
                    if (!host.isUiAvailable) return@post
                    host.showConfirmDialog(
                        host.getString(R.string.game_save_export_failed),
                        error.message ?: host.getString(R.string.game_common_unknown_error),
                        host.getString(R.string.game_common_got_it)
                    ) { }
                }
            }
        }
    }

    private fun showExportError(error: Exception) {
        host.showConfirmDialog(
            host.getString(R.string.game_save_export_failed),
            error.message ?: host.getString(R.string.game_common_unknown_error),
            host.getString(R.string.game_common_got_it)
        ) { }
    }
}
