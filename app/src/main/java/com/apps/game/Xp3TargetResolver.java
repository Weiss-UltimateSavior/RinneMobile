package com.apps.game;

import android.text.TextUtils;

import androidx.appcompat.app.AlertDialog;

import com.apps.theme.LauncherDialogFactory;
import com.core.R;
import com.core.launcherbridge.LauncherScanBridge;
import com.core.scanner.ScanReport;
import com.core.scanner.ScanRequest;
import com.core.scanner.ScanResult;
import com.core.util.AppExecutors;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 LauncherManageFragment 抽取的 XP3 入口解析与扫描执行控制器。
 *
 * 负责扫描根目录、收集 XP3 候选项、在多个候选时弹窗让用户选择启动入口，
 * 最终将解析后的结果写入游戏库。所有 Fragment 相关能力通过 {@link ManageHost} 桥接。
 */
public final class Xp3TargetResolver {

    private final ManageHost host;
    private AlertDialog scanLoadingDialog;
    private ScanRequest activeScanRequest;

    public Xp3TargetResolver(ManageHost host) {
        this.host = host;
    }

    public void executeScan(List<String> roots, int depth, boolean fullRefresh) {
        scanAndResolveXp3Targets(roots, depth, fullRefresh);
    }

    private void scanAndResolveXp3Targets(List<String> roots, int depth, boolean fullRefresh) {
        ScanRequest request = ScanRequest.defaults(depth, !fullRefresh);
        activeScanRequest = request;
        scanLoadingDialog = LauncherDialogFactory.showLoading(host.requireContext(),
                host.getString(R.string.game_scan_scanning),
                host.getString(R.string.game_scan_wait_hint));
        scanLoadingDialog.setCancelable(true);
        scanLoadingDialog.setCanceledOnTouchOutside(false);
        scanLoadingDialog.setButton(AlertDialog.BUTTON_NEGATIVE,
                host.getString(R.string.game_xp3_cancel_scan),
                (dialog, which) -> request.cancel());
        scanLoadingDialog.setOnCancelListener(dialog -> request.cancel());
        android.content.Context appContext = host.getAppContext();
        AppExecutors.runOnSingle(() -> {
            LauncherScanBridge.ScanBatchResult result = LauncherScanBridge.scanWithReport(appContext, roots, request);
            host.getMainQueue().post(() -> {
                if (!host.isAdded()) return;
                dismissScanLoadingDialog();
                activeScanRequest = null;
                handleScanDiscovery(result);
            });
        });
    }

    private void handleScanDiscovery(LauncherScanBridge.ScanBatchResult result) {
        if (result == null) return;
        List<ScanResult> results = result.getResults();
        String summary = host.getString(R.string.game_scan_summary,
                result.getVisitedNodes(), results.size());
        if (!result.getErrors().isEmpty()) {
            summary += host.getString(R.string.game_scan_error_summary,
                    result.getErrors().size(), TextUtils.join("\n• ", result.getErrors()));
        }
        if (result.isPartial()) {
            summary += host.getString(
                    R.string.game_scan_stopped_summary, stopReasonText(result.getStopReason()));
            if (results.isEmpty()) {
                host.showConfirmDialog(host.getString(R.string.game_scan_incomplete), summary,
                        host.getString(R.string.game_common_got_it), () -> {});
            } else {
                host.showConfirmDialog(host.getString(R.string.game_scan_incomplete),
                        summary + host.getString(R.string.game_scan_import_found),
                        host.getString(R.string.game_common_import),
                        () -> resolveXp3Candidates(results, 0));
            }
            return;
        }
        if (!result.getErrors().isEmpty()) {
            if (results.isEmpty()) {
                host.showConfirmDialog(host.getString(R.string.game_scan_complete_errors), summary,
                        host.getString(R.string.game_common_got_it), () -> {});
            } else {
                host.showConfirmDialog(host.getString(R.string.game_scan_complete_errors),
                        summary + host.getString(R.string.game_scan_continue_import),
                        host.getString(R.string.game_scan_continue_import_action),
                        () -> resolveXp3Candidates(results, 0));
            }
            return;
        }
        resolveXp3Candidates(results, 0);
    }

    private String stopReasonText(ScanReport.StopReason reason) {
        if (reason == ScanReport.StopReason.CANCELLED) {
            return host.getString(R.string.game_scan_stop_cancelled);
        }
        if (reason == ScanReport.StopReason.DEADLINE) {
            return host.getString(R.string.game_scan_stop_timeout);
        }
        if (reason == ScanReport.StopReason.NODE_LIMIT) {
            return host.getString(R.string.game_scan_stop_limit);
        }
        return host.getString(R.string.game_scan_stop_generic);
    }

    private void resolveXp3Candidates(List<ScanResult> results, int startIndex) {
        if (!host.isAdded()) return;
        if (results == null) {
            importResolvedScanResults(new ArrayList<>());
            return;
        }
        for (int i = startIndex; i < results.size(); i++) {
            ScanResult result = results.get(i);
            if (result == null || result.xp3Candidates == null || result.xp3Candidates.size() < 2) continue;
            showXp3TargetDialog(results, i, result);
            return;
        }
        importResolvedScanResults(results);
    }

    private void showXp3TargetDialog(List<ScanResult> results, int index, ScanResult result) {
        LauncherDialogFactory.showTextChoicesWithSkip(
                host.requireContext(),
                host.getString(R.string.game_xp3_choose_entry),
                host.getString(R.string.game_xp3_multiple_files, result.title),
                result.xp3Candidates,
                host.getString(R.string.game_xp3_skip),
                host.getString(R.string.game_xp3_cancel_scan),
                candidate -> {
                result.launchTarget = candidate;
                resolveXp3Candidates(results, index + 1);
        },
                () -> {
            results.remove(index);
            resolveXp3Candidates(results, index);
        },
                () -> {});
    }

    private void importResolvedScanResults(List<ScanResult> results) {
        scanLoadingDialog = LauncherDialogFactory.showLoading(
                host.requireContext(),
                host.getString(R.string.game_import_importing),
                host.getString(R.string.game_import_writing_library));
        android.content.Context appContext = host.getAppContext();
        AppExecutors.runOnSingle(() -> {
            LauncherScanBridge.ImportStats stats = LauncherScanBridge.importScanResults(appContext, results);
            host.getMainQueue().post(() -> {
                if (!host.isAdded()) return;
                dismissScanLoadingDialog();
                showScanResultDialog(stats);
            });
        });
    }

    private void dismissScanLoadingDialog() {
        if (scanLoadingDialog != null && scanLoadingDialog.isShowing()) {
            scanLoadingDialog.dismiss();
            scanLoadingDialog = null;
        }
    }

    private void showScanResultDialog(LauncherScanBridge.ImportStats stats) {
        if (stats == null) return;
        StringBuilder msg = new StringBuilder();
        msg.append(host.getString(R.string.game_scan_result_counts,
                stats.scanned, stats.added, stats.skipped, stats.failed));
        if (!stats.failedItems.isEmpty()) {
            msg.append("\n");
            for (String item : stats.failedItems) {
                msg.append("\n• ").append(item);
            }
        }
        host.showConfirmDialog(host.getString(R.string.game_scan_complete_title), msg.toString(),
                host.getString(R.string.game_common_got_it), () -> {});
    }

    public void cleanup() {
        if (activeScanRequest != null) activeScanRequest.cancel();
        activeScanRequest = null;
        dismissScanLoadingDialog();
    }
}
