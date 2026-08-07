package com.apps.game

import android.util.Log
import android.text.TextUtils
import androidx.appcompat.app.AlertDialog
import com.apps.HDModel.LauncherDialogRouter
import com.core.R
import com.core.launcherbridge.LauncherScanBridge
import com.core.scanner.ScanReport
import com.core.scanner.ScanRequest
import com.core.scanner.ScanResult
import com.core.util.AppExecutors

/**
 * 从 LauncherManageFragment 抽取的 XP3 入口解析与扫描执行控制器。
 *
 * 负责扫描根目录、收集 XP3 候选项、在多个候选时弹窗让用户选择启动入口，
 * 最终将解析后的结果写入游戏库。所有 Fragment 相关能力通过 [ManageHost] 桥接。
 */
class Xp3TargetResolver(private val host: ManageHost) {

    private companion object {
        private const val TAG = "Xp3Scanner"
    }

    private var scanLoadingDialog: AlertDialog? = null
    private var activeScanRequest: ScanRequest? = null

    fun executeScan(roots: List<String>, depth: Int, fullRefresh: Boolean) {
        scanAndResolveXp3Targets(roots, depth, fullRefresh)
    }

    private fun scanAndResolveXp3Targets(roots: List<String>, depth: Int, fullRefresh: Boolean) {
        val request = ScanRequest.defaults(depth, !fullRefresh)
        activeScanRequest = request
        val dialog = LauncherDialogRouter.showLoading(
            host.requireContext(),
            host.getString(R.string.game_scan_scanning),
            host.getString(R.string.game_scan_wait_hint)
        )
        scanLoadingDialog = dialog
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setButton(AlertDialog.BUTTON_NEGATIVE,
            host.getString(R.string.game_xp3_cancel_scan)) { _, _ -> request.cancel() }
        dialog.setOnCancelListener { request.cancel() }
        val appContext = host.appContext
        AppExecutors.runOnSingle {
            try {
                val result = LauncherScanBridge.scanWithReport(appContext, roots, request)
                host.mainQueue.post {
                    if (!host.isUiAvailable) return@post
                    dismissScanLoadingDialog()
                    activeScanRequest = null
                    handleScanDiscovery(result)
                }
            } catch (e: Exception) {
                Log.e(TAG, "scanWithReport failed", e)
                host.mainQueue.post {
                    if (!host.isUiAvailable) return@post
                    dismissScanLoadingDialog()
                    activeScanRequest = null
                    host.showConfirmDialog(
                        host.getString(R.string.game_scan_title),
                        host.getString(R.string.game_common_unknown_error),
                        host.getString(R.string.game_common_got_it)
                    ) { }
                }
            }
        }
    }

    private fun handleScanDiscovery(result: LauncherScanBridge.ScanBatchResult?) {
        if (result == null) return
        val results = result.results.toMutableList()
        var summary = host.getString(R.string.game_scan_summary, result.getVisitedNodes(), results.size)
        if (result.errors.isNotEmpty()) {
            summary += host.getString(R.string.game_scan_error_summary,
                result.errors.size, TextUtils.join("\n• ", result.errors))
        }
        if (result.isPartial) {
            summary += host.getString(
                R.string.game_scan_stopped_summary, stopReasonText(result.getStopReason()))
            if (results.isEmpty()) {
                host.showConfirmDialog(host.getString(R.string.game_scan_incomplete), summary,
                    host.getString(R.string.game_common_got_it)) { }
            } else {
                host.showConfirmDialog(host.getString(R.string.game_scan_incomplete),
                    summary + host.getString(R.string.game_scan_import_found),
                    host.getString(R.string.game_common_import)
                ) { resolveXp3Candidates(results, 0) }
            }
            return
        }
        if (result.errors.isNotEmpty()) {
            if (results.isEmpty()) {
                host.showConfirmDialog(host.getString(R.string.game_scan_complete_errors), summary,
                    host.getString(R.string.game_common_got_it)) { }
            } else {
                host.showConfirmDialog(host.getString(R.string.game_scan_complete_errors),
                    summary + host.getString(R.string.game_scan_continue_import),
                    host.getString(R.string.game_scan_continue_import_action)
                ) { resolveXp3Candidates(results, 0) }
            }
            return
        }
        resolveXp3Candidates(results, 0)
    }

    private fun stopReasonText(reason: ScanReport.StopReason?): String {
        if (reason == ScanReport.StopReason.CANCELLED) {
            return host.getString(R.string.game_scan_stop_cancelled)
        }
        if (reason == ScanReport.StopReason.DEADLINE) {
            return host.getString(R.string.game_scan_stop_timeout)
        }
        if (reason == ScanReport.StopReason.NODE_LIMIT) {
            return host.getString(R.string.game_scan_stop_limit)
        }
        return host.getString(R.string.game_scan_stop_generic)
    }

    private fun resolveXp3Candidates(results: MutableList<ScanResult>?, startIndex: Int) {
        if (!host.isUiAvailable) return
        if (results == null) {
            importResolvedScanResults(ArrayList())
            return
        }
        for (i in startIndex until results.size) {
            val result: ScanResult? = results[i]
            val candidates = result?.xp3Candidates
            if (result == null || candidates == null || candidates.size < 2) continue
            showXp3TargetDialog(results, i, result)
            return
        }
        importResolvedScanResults(results)
    }

    private fun showXp3TargetDialog(results: MutableList<ScanResult>, index: Int, result: ScanResult) {
        LauncherDialogRouter.showTextChoicesWithSkip(
            host.requireContext(),
            host.getString(R.string.game_xp3_choose_entry),
            host.getString(R.string.game_xp3_multiple_files, result.title),
            result.xp3Candidates,
            host.getString(R.string.game_xp3_skip),
            host.getString(R.string.game_xp3_cancel_scan),
            { candidate ->
                result.launchTarget = candidate
                resolveXp3Candidates(results, index + 1)
            },
            {
                results.removeAt(index)
                resolveXp3Candidates(results, index)
            },
            {}
        )
    }

    private fun importResolvedScanResults(results: List<ScanResult>?) {
        scanLoadingDialog = LauncherDialogRouter.showLoading(
            host.requireContext(),
            host.getString(R.string.game_import_importing),
            host.getString(R.string.game_import_writing_library)
        )
        val appContext = host.appContext
        AppExecutors.runOnSingle {
            try {
                val stats = LauncherScanBridge.importScanResults(appContext, results)
                host.mainQueue.post {
                    if (!host.isUiAvailable) return@post
                    dismissScanLoadingDialog()
                    showScanResultDialog(stats)
                }
            } catch (e: Exception) {
                Log.e(TAG, "importScanResults failed", e)
                host.mainQueue.post {
                    if (!host.isUiAvailable) return@post
                    dismissScanLoadingDialog()
                    host.showConfirmDialog(
                        host.getString(R.string.game_import_failed),
                        host.getString(R.string.game_common_unknown_error),
                        host.getString(R.string.game_common_got_it)
                    ) { }
                }
            }
        }
    }

    private fun dismissScanLoadingDialog() {
        val dialog = scanLoadingDialog
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss()
            scanLoadingDialog = null
        }
    }

    private fun showScanResultDialog(stats: LauncherScanBridge.ImportStats?) {
        if (stats == null) return
        val msg = StringBuilder()
        msg.append(host.getString(R.string.game_scan_result_counts,
            stats.scanned, stats.added, stats.skipped, stats.failed))
        if (stats.failedItems.isNotEmpty()) {
            msg.append("\n")
            for (item in stats.failedItems) {
                msg.append("\n• ").append(item)
            }
        }
        host.showConfirmDialog(host.getString(R.string.game_scan_complete_title), msg.toString(),
            host.getString(R.string.game_common_got_it)) { }
    }

    fun cleanup() {
        activeScanRequest?.cancel()
        activeScanRequest = null
        dismissScanLoadingDialog()
    }
}
