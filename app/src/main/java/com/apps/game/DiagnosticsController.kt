package com.apps.game

import android.util.Log
import android.widget.Toast

import com.apps.theme.LauncherDialogFactory
import com.core.R
import com.core.launcherbridge.LauncherDiagnosticsBridge
import com.core.util.AppExecutors
import com.core.util.DevLogger

/**
 * 诊断控制器：从 LauncherManageFragment 抽取的日志诊断相关逻辑。
 * 包括显示诊断隐私提示、日志开关/清空/导出等操作。
 *
 * 依赖 ManageHost 提供 Context、UI 缩放及共享对话框构建器。
 */
class DiagnosticsController(private val host: ManageHost) {

    companion object {
        private const val TAG = "DiagnosticsController"
    }

    fun showDiagnosticsPrivacyDialog() {
        host.showConfirmDialog(
            host.getString(R.string.game_diagnostics_title),
            host.getString(R.string.game_diagnostics_warning),
            host.getString(R.string.game_common_continue)
        ) { showDiagnosticsOptions() }
    }

    private fun showDiagnosticsOptions() {
        LauncherDialogFactory.showMessageActionChoices(
            host.requireContext(),
            host.getString(R.string.game_diagnostics_title),
            host.getString(
                R.string.game_diagnostics_status,
                host.getString(
                    if (LauncherDiagnosticsBridge.isLogEnabled()) R.string.game_diagnostics_enabled
                    else R.string.game_diagnostics_disabled
                ),
                DevLogger.formatSize(LauncherDiagnosticsBridge.logSize())
            ),
            arrayOf<CharSequence>(
                host.getString(
                    if (LauncherDiagnosticsBridge.isLogEnabled()) R.string.game_diagnostics_disable
                    else R.string.game_diagnostics_enable
                ),
                host.getString(R.string.game_diagnostics_clear),
                host.getString(R.string.game_diagnostics_export)
            )
        ) { index ->
            when (index) {
                0 -> toggleDiagnosticLog()
                1 -> confirmClearDiagnosticLog()
                2 -> exportDiagnosticLog()
            }
        }
    }

    private fun exportDiagnosticLog() {
        val appContext = host.getAppContext()
        AppExecutors.runOnSingle {
            var exportResult: Boolean? = null
            var errorMessage: String? = null
            try {
                exportResult = LauncherDiagnosticsBridge.exportLog(appContext)
            } catch (e: Exception) {
                // DB 查询/导出失败兜底，避免单次失败影响主流程
                Log.e(TAG, "exportLog failed", e)
                errorMessage = e.message
            }
            host.getMainQueue().post {
                if (!host.isUiAvailable()) return@post
                if (errorMessage != null) {
                    Toast.makeText(
                        host.requireContext(),
                        host.getString(R.string.game_diagnostics_export_failed, errorMessage),
                        Toast.LENGTH_SHORT
                    ).show()
                } else if (exportResult == false) {
                    Toast.makeText(host.requireContext(), R.string.game_diagnostics_empty, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun toggleDiagnosticLog() {
        val next = !LauncherDiagnosticsBridge.isLogEnabled()
        LauncherDiagnosticsBridge.setLogEnabled(host.requireContext(), next)
        Toast.makeText(
            host.requireContext(),
            if (next) R.string.game_diagnostics_enabled else R.string.game_diagnostics_disabled,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun confirmClearDiagnosticLog() {
        host.showConfirmDialog(
            host.getString(R.string.game_diagnostics_clear),
            host.getString(R.string.game_diagnostics_clear_message),
            host.getString(R.string.game_diagnostics_clear)
        ) {
            AppExecutors.runOnSingle {
                val success = LauncherDiagnosticsBridge.clearLog()
                host.getMainQueue().post {
                    if (!host.isUiAvailable()) return@post
                    Toast.makeText(
                        host.requireContext(),
                        if (success) R.string.game_diagnostics_cleared else R.string.game_diagnostics_clear_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}
