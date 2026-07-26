package com.apps.game;

import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.apps.theme.LauncherMotion;
import com.core.launcherbridge.LauncherDiagnosticsBridge;
import com.core.util.DevLogger;

/**
 * 诊断控制器：从 LauncherManageFragment 抽取的日志诊断相关逻辑。
 * 包括显示诊断隐私提示、日志开关/清空/导出等操作。
 *
 * 依赖 ManageHost 提供 Context、UI 缩放及共享对话框构建器。
 */
public final class DiagnosticsController {

    private final ManageHost host;

    public DiagnosticsController(ManageHost host) {
        this.host = host;
    }

    public void showDiagnosticsPrivacyDialog() {
        String message = "导出的日志可能包含设备信息、游戏路径、运行异常、WebView 或引擎输出等诊断内容。请先自行确认日志内容，再发送给反馈渠道。";
        host.showConfirmDialog("日志诊断", message, "继续", this::showDiagnosticsOptions);
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

        TextView title = host.createDialogTitle("日志诊断");
        root.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView info = new TextView(host.requireContext());
        info.setText("日志状态：" + (LauncherDiagnosticsBridge.isLogEnabled() ? "已开启" : "已关闭")
                + " · 当前大小：" + DevLogger.formatSize(LauncherDiagnosticsBridge.logSize()));
        info.setGravity(android.view.Gravity.CENTER);
        info.setTextColor(ContextCompat.getColor(host.requireContext(), com.core.R.color.launcher_text_muted_color));
        host.setResponsiveTextSize(info, 12);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        infoLp.setMargins(0, host.dp(11), 0, 0);
        root.addView(info, infoLp);

        host.addFeedbackOption(root, LauncherDiagnosticsBridge.isLogEnabled() ? "关闭日志" : "开启日志", dialog, this::toggleDiagnosticLog);
        host.addFeedbackOption(root, "清空日志", dialog, this::confirmClearDiagnosticLog);
        host.addFeedbackOption(root, "导出 Rinne 诊断包", dialog, this::exportDiagnosticLog);

        TextView cancel = host.createDialogCancelButton(dialog);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, host.dp(36));
        cancelLp.setMargins(0, host.dp(9), 0, 0);
        root.addView(cancel, cancelLp);

        window.setContentView(root);
    }

    private void exportDiagnosticLog() {
        try {
            if (!LauncherDiagnosticsBridge.exportLog(host.requireContext())) {
                Toast.makeText(host.requireContext(), "暂无日志文件", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable throwable) {
            Toast.makeText(host.requireContext(), "导出失败：" + throwable.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleDiagnosticLog() {
        boolean next = !LauncherDiagnosticsBridge.isLogEnabled();
        LauncherDiagnosticsBridge.setLogEnabled(host.requireContext(), next);
        Toast.makeText(host.requireContext(), next ? "日志已开启" : "日志已关闭", Toast.LENGTH_SHORT).show();
    }

    private void confirmClearDiagnosticLog() {
        host.showConfirmDialog("清空日志", "确定清空当前诊断日志吗？此操作不会删除游戏数据。", "清空", () -> {
            boolean success = LauncherDiagnosticsBridge.clearLog();
            Toast.makeText(host.requireContext(), success ? "日志已清空" : "清空失败", Toast.LENGTH_SHORT).show();
        });
    }
}
