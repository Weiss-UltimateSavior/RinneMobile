package com.apps.game;

import android.util.Log;
import android.widget.Toast;

import com.apps.theme.LauncherDialogFactory;
import com.core.R;
import com.core.launcherbridge.LauncherDiagnosticsBridge;
import com.core.util.DevLogger;

/**
 * 诊断控制器：从 LauncherManageFragment 抽取的日志诊断相关逻辑。
 * 包括显示诊断隐私提示、日志开关/清空/导出等操作。
 *
 * 依赖 ManageHost 提供 Context、UI 缩放及共享对话框构建器。
 */
public final class DiagnosticsController {

    private static final String TAG = "DiagnosticsController";

    private final ManageHost host;

    public DiagnosticsController(ManageHost host) {
        this.host = host;
    }

    public void showDiagnosticsPrivacyDialog() {
        String message = host.getString(R.string.game_diagnostics_warning);
        host.showConfirmDialog(host.getString(R.string.game_diagnostics_title), message,
                host.getString(R.string.game_common_continue), this::showDiagnosticsOptions);
    }

    private void showDiagnosticsOptions() {
        LauncherDialogFactory.showMessageActionChoices(
                host.requireContext(),
                host.getString(R.string.game_diagnostics_title),
                host.getString(R.string.game_diagnostics_status,
                        host.getString(LauncherDiagnosticsBridge.isLogEnabled()
                                ? R.string.game_diagnostics_enabled : R.string.game_diagnostics_disabled),
                        DevLogger.formatSize(LauncherDiagnosticsBridge.logSize())),
                new CharSequence[] {
                        host.getString(LauncherDiagnosticsBridge.isLogEnabled()
                                ? R.string.game_diagnostics_disable : R.string.game_diagnostics_enable),
                        host.getString(R.string.game_diagnostics_clear),
                        host.getString(R.string.game_diagnostics_export)
                },
                index -> {
                    switch (index) {
                        case 0: toggleDiagnosticLog(); break;
                        case 1: confirmClearDiagnosticLog(); break;
                        case 2: exportDiagnosticLog(); break;
                    }
                });
    }

    private void exportDiagnosticLog() {
        try {
            if (!LauncherDiagnosticsBridge.exportLog(host.requireContext())) {
                Toast.makeText(host.requireContext(), R.string.game_diagnostics_empty, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "exportLog failed", e);
            Toast.makeText(host.requireContext(), host.getString(
                    R.string.game_diagnostics_export_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleDiagnosticLog() {
        boolean next = !LauncherDiagnosticsBridge.isLogEnabled();
        LauncherDiagnosticsBridge.setLogEnabled(host.requireContext(), next);
        Toast.makeText(host.requireContext(), next
                ? R.string.game_diagnostics_enabled : R.string.game_diagnostics_disabled,
                Toast.LENGTH_SHORT).show();
    }

    private void confirmClearDiagnosticLog() {
        host.showConfirmDialog(host.getString(R.string.game_diagnostics_clear),
                host.getString(R.string.game_diagnostics_clear_message),
                host.getString(R.string.game_diagnostics_clear), () -> {
            boolean success = LauncherDiagnosticsBridge.clearLog();
            Toast.makeText(host.requireContext(), success
                    ? R.string.game_diagnostics_cleared : R.string.game_diagnostics_clear_failed,
                    Toast.LENGTH_SHORT).show();
        });
    }
}
