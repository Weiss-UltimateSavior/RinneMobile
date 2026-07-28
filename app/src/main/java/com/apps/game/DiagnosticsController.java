package com.apps.game;

import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.apps.theme.LauncherMotion;
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

        TextView title = host.createDialogTitle(host.getString(R.string.game_diagnostics_title));
        root.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView info = new TextView(host.requireContext());
        info.setText(host.getString(R.string.game_diagnostics_status,
                host.getString(LauncherDiagnosticsBridge.isLogEnabled()
                        ? R.string.game_diagnostics_enabled : R.string.game_diagnostics_disabled),
                DevLogger.formatSize(LauncherDiagnosticsBridge.logSize())));
        info.setGravity(android.view.Gravity.CENTER);
        info.setTextColor(ContextCompat.getColor(host.requireContext(), com.core.R.color.launcher_text_muted_color));
        host.setResponsiveTextSize(info, 12);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        infoLp.setMargins(0, host.dp(11), 0, 0);
        root.addView(info, infoLp);

        host.addFeedbackOption(root, host.getString(LauncherDiagnosticsBridge.isLogEnabled()
                ? R.string.game_diagnostics_disable : R.string.game_diagnostics_enable),
                dialog, this::toggleDiagnosticLog);
        host.addFeedbackOption(root, host.getString(R.string.game_diagnostics_clear),
                dialog, this::confirmClearDiagnosticLog);
        host.addFeedbackOption(root, host.getString(R.string.game_diagnostics_export),
                dialog, this::exportDiagnosticLog);

        TextView cancel = host.createDialogCancelButton(dialog);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, host.dp(36));
        cancelLp.setMargins(0, host.dp(9), 0, 0);
        root.addView(cancel, cancelLp);

        window.setContentView(root);
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
