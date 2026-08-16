package com.apps.game

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import com.apps.HDModel.LauncherDialogRouter
import com.core.R
import com.core.importer.ImportGameData
import com.core.importer.ImportResult
import com.core.importer.ImporterService
import com.core.importer.LunaBoxImporter
import com.core.importer.PlayniteImporter
import com.core.importer.PotatoVnImporter
import com.core.importer.VniteImporter
import com.core.util.AppExecutors

/**
 * 从 LauncherManageFragment 抽取的跨端同步导入控制器。
 *
 * 负责从 Playnite / PotatoVN / Vnite / LunaBox 各平台解析数据、预览候选列表、
 * 用户勾选后写入库。所有 Fragment 相关能力通过 [ManageHost] 桥接，
 * 各平台 ActivityResultLauncher 由 Fragment 注册后通过构造器注入。
 */
class ExternalImportController(
    private val host: ManageHost,
    private val playniteLauncher: ActivityResultLauncher<Array<String>>,
    private val potatovnLauncher: ActivityResultLauncher<Array<String>>,
    private val vniteLauncher: ActivityResultLauncher<Uri?>,
    private val lunaboxLauncher: ActivityResultLauncher<Array<String>>
) {

    private var importLoadingDialog: AlertDialog? = null

    fun showExternalImportDialog() {
        if (host.isImportInProgress) return
        LauncherDialogRouter.showMessageActionChoices(
            host.requireContext(),
            host.getString(R.string.game_import_cross_platform),
            host.getString(R.string.game_import_source_message),
            arrayOf<CharSequence>(
                host.getString(R.string.game_import_playnite),
                host.getString(R.string.game_import_potatovn),
                host.getString(R.string.game_import_vnite_directory),
                host.getString(R.string.game_import_lunabox)
            )
        ) { index ->
            when (index) {
                0 -> playniteLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                1 -> potatovnLauncher.launch(arrayOf("application/zip", "application/*zip*", "*/*"))
                2 -> vniteLauncher.launch(null)
                3 -> lunaboxLauncher.launch(arrayOf("application/zip", "application/*zip*", "*/*"))
            }
        }
    }

    fun doImportFromPlaynite(uri: Uri) {
        val appContext = host.appContext
        parseAndPreview(appContext) { PlayniteImporter.parse(appContext, uri) }
    }

    fun doImportFromPotatoVn(uri: Uri) {
        val appContext = host.appContext
        parseAndPreview(appContext) { PotatoVnImporter.parse(appContext, uri) }
    }

    fun doImportFromVnite(uri: Uri) {
        val appContext = host.appContext
        parseAndPreview(appContext) { VniteImporter.parse(appContext, uri) }
    }

    fun doImportFromLunaBox(uri: Uri) {
        val appContext = host.appContext
        parseAndPreview(appContext) { LunaBoxImporter.parse(appContext, uri) }
    }

    private fun interface ParseTask {
        @Throws(Exception::class)
        fun parse(): List<ImportGameData>
    }

    private fun parseAndPreview(appContext: Context, task: ParseTask) {
        host.isImportInProgress = true
        showImportLoading(host.getString(R.string.game_import_parsing))
        AppExecutors.runOnSingle {
            try {
                val games = task.parse()
                ImporterService(appContext).markExisting(games)
                host.mainQueue.post {
                    if (!host.isUiAvailable) return@post
                    dismissImportLoading()
                    showImportPreviewDialog(games)
                }
            } catch (error: Error) {
                // OOM/VirtualMachineError 必须传播，避免在已损坏的 JVM 状态下继续运行
                throw error
            } catch (e: Exception) {
                Log.e("LauncherManage", "external import parse failed", e)
                host.mainQueue.post {
                    if (!host.isUiAvailable) return@post
                    dismissImportLoading()
                    host.isImportInProgress = false
                    host.showConfirmDialog(
                        host.getString(R.string.game_import_parse_failed),
                        e.message ?: host.getString(R.string.game_common_unknown_error),
                        host.getString(R.string.game_common_got_it)
                    ) { }
                }
            }
        }
    }

    private fun showImportLoading(hint: String) {
        dismissImportLoading()
        val dialog = LauncherDialogRouter.showLoading(
            host.requireContext(),
            host.getString(R.string.game_import_importing),
            hint
        )
        importLoadingDialog = dialog
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
    }

    private fun dismissImportLoading() {
        importLoadingDialog?.let {
            if (it.isShowing) it.dismiss()
        }
        importLoadingDialog = null
    }

    private fun showImportPreviewDialog(games: List<ImportGameData>?) {
        if (games == null || games.isEmpty()) {
            host.isImportInProgress = false
            host.showConfirmDialog(
                host.getString(R.string.game_import_none_title),
                host.getString(R.string.game_import_none_message),
                host.getString(R.string.game_common_got_it)
            ) { }
            return
        }

        ExternalImportPreviewDialog.show(host, games, object : ExternalImportPreviewDialog.Callback {
            override fun onImport() {
                executeExternalImport(games)
            }

            override fun onCancel() {
                host.isImportInProgress = false
                ImporterService.cancelImport()
            }
        })
    }

    private fun executeExternalImport(games: List<ImportGameData>) {
        val appContext = host.appContext
        showImportLoading(host.getString(R.string.game_import_writing))
        AppExecutors.runOnSingle {
            try {
                val result = ImporterService(appContext).importSelected(games)
                host.mainQueue.post {
                    if (!host.isUiAvailable) return@post
                    dismissImportLoading()
                    host.isImportInProgress = false
                    afterExternalImport(result)
                }
            } catch (error: Error) {
                // OOM/VirtualMachineError 必须传播，避免在已损坏的 JVM 状态下继续运行
                throw error
            } catch (e: Exception) {
                Log.e("LauncherManage", "external import write failed", e)
                host.mainQueue.post {
                    if (!host.isUiAvailable) return@post
                    dismissImportLoading()
                    host.isImportInProgress = false
                    host.showConfirmDialog(
                        host.getString(R.string.game_import_failed),
                        e.message ?: host.getString(R.string.game_common_unknown_error),
                        host.getString(R.string.game_common_got_it)
                    ) { }
                }
            }
        }
    }

    private fun afterExternalImport(result: ImportResult?) {
        if (result == null) {
            host.showConfirmDialog(
                host.getString(R.string.game_import_complete),
                host.getString(R.string.game_import_not_performed),
                host.getString(R.string.game_common_got_it)
            ) { }
            return
        }
        val msg = StringBuilder(result.summary())
        if (!result.skippedNames.isEmpty()) {
            msg.append(host.getString(R.string.game_import_skipped_items))
            for (n in result.skippedNames) msg.append("\n• ").append(n)
        }
        if (!result.failedNames.isEmpty()) {
            msg.append(host.getString(R.string.game_import_failed_items))
            for (n in result.failedNames) msg.append("\n• ").append(n)
        }
        host.showConfirmDialog(
            host.getString(R.string.game_import_complete),
            msg.toString(),
            host.getString(R.string.game_common_got_it)
        ) { }
    }

    fun cleanup() {
        dismissImportLoading()
        host.isImportInProgress = false
        ImporterService.cancelImport()
    }
}
