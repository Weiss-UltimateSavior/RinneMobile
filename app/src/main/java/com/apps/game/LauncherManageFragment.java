package com.apps.game;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.yuki.yukihub.databinding.FragmentLauncherManageBinding;
import com.yuki.yukihub.launcherbridge.LauncherDiagnosticsBridge;
import com.yuki.yukihub.launcherbridge.LauncherScanBridge;
import com.yuki.yukihub.launcherbridge.LauncherSyncBridge;
import com.yuki.yukihub.scanner.ScanResult;
import com.yuki.yukihub.scanner.ScanRequest;
import com.yuki.yukihub.scanner.ScanReport;
import com.yuki.yukihub.util.AppExecutors;
import com.yuki.yukihub.util.RxMainQueue;
import com.yuki.yukihub.util.DevLogger;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONObject;
import com.apps.settings.LauncherKrkrSettingsActivity;
import com.apps.settings.LauncherMetadataSourceActivity;
import com.apps.sync.LauncherSyncCenterActivity;
import com.apps.theme.LauncherDialogFactory;
import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;
import com.apps.widget.LauncherTabletPortraitScaler;

public class LauncherManageFragment extends Fragment {
    private static final String APP_PREFS = "yukihub_prefs";
    private static final String KEY_SCAN_ROOT_URIS = "scan_root_uris";
    private static final String KEY_SCAN_ROOT_ENABLED = "scan_root_enabled";
    private static final String KEY_LAST_SCAN_ROOT_URI = "last_scan_root_uri";
    private static final String KEY_STARTUP_SCAN_DEPTH = "startup_scan_depth";
    private static final int DEFAULT_SCAN_DEPTH = 2;
    private static final int MAX_SCAN_DEPTH = 4;
    private static final int MAX_SCAN_ROOTS = 3;

    private FragmentLauncherManageBinding binding;
    private final RxMainQueue mainQueue = new RxMainQueue();
    private final ActivityResultLauncher<Uri> scanDirectoryPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) return;
                persistAndSaveScanDirectory(uri);
            });

    private final ActivityResultLauncher<String[]> backupOpenLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                importLocalBackup(uri);
            });

    private final ActivityResultLauncher<String> backupCreateLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), uri -> {
                if (uri == null) return;
                exportLocalBackup(uri);
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentLauncherManageBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        applyTabletPortraitLayout();
        applySystemBarInsets();
        bindActions();
        applyThemeTone();
        renderScanDirectories();
    }

    @Override
    public void onDestroyView() {
        if (activeScanRequest != null) activeScanRequest.cancel();
        activeScanRequest = null;
        if (binding != null) {
            binding.getRoot().setOnApplyWindowInsetsListener(null);
        }
        super.onDestroyView();
        binding = null;
    }

    private void applySystemBarInsets() {
        FragmentLauncherManageBinding currentBinding = binding;
        int originalLeft = currentBinding.manageScroll.getPaddingLeft();
        int originalTop = currentBinding.manageScroll.getPaddingTop();
        int originalRight = currentBinding.manageScroll.getPaddingRight();
        int originalBottom = currentBinding.manageScroll.getPaddingBottom();

        currentBinding.getRoot().setOnApplyWindowInsetsListener((view, insets) -> {
            currentBinding.manageScroll.setPadding(
                    originalLeft,
                    originalTop + insets.getSystemWindowInsetTop(),
                    originalRight,
                    originalBottom
            );
            return insets;
        });
        currentBinding.getRoot().requestApplyInsets();
    }

    private void bindActions() {
        binding.actionAddDirectory.setOnClickListener(view -> confirmAddDirectory());
        binding.actionScanGame.setOnClickListener(view -> scanConfiguredDirectories());
        binding.actionAddGame.setOnClickListener(view ->
                startActivity(new Intent(requireContext(), LauncherAddGameActivity.class)));
        binding.actionCloudSync.setOnClickListener(view -> showSyncOptions());
        binding.actionDiagnostics.setOnClickListener(view -> showDiagnosticsPrivacyDialog());
        binding.actionMetadataSource.setOnClickListener(view ->
                startActivity(new Intent(requireContext(), LauncherMetadataSourceActivity.class)));
        binding.actionKrkrSettings.setOnClickListener(view ->
                startActivity(new Intent(requireContext(), LauncherKrkrSettingsActivity.class)));
    }

    private void applyThemeTone() {
        LauncherTheme.applyPrimaryTone(binding.getRoot());
        for (int i = 0; i < binding.manageActionList.getChildCount(); i++) {
            View row = binding.manageActionList.getChildAt(i);
            LauncherTheme.styleManageRow(row);
        }
    }

    private void confirmAddDirectory() {
        showLauncherConfirmDialog("添加目录", "部分模拟器属于外置跳转，不可直接扫描游玩（PPSSPP、Winlator 等）可通过工具箱进行下载", "添加", () ->
                scanDirectoryPicker.launch(null));
    }

    private void persistAndSaveScanDirectory(Uri uri) {
        try {
            requireContext().getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            );
        } catch (SecurityException ignored) {
            // Some providers do not grant persistable permissions, but may still be readable.
        }

        List<String> roots = getScanRootUris();
        String value = uri.toString();
        roots.remove(value);
        if (roots.size() >= MAX_SCAN_ROOTS) {
            Toast.makeText(requireContext(), "最多绑定 " + MAX_SCAN_ROOTS + " 个扫描目录", Toast.LENGTH_SHORT).show();
            return;
        }
        roots.add(value);
        saveScanRootUris(roots);
        renderScanDirectories();
        Toast.makeText(requireContext(), "目录已添加，请选择扫描层次", Toast.LENGTH_SHORT).show();
        showScanDepthDialog(java.util.Collections.singletonList(value));
    }

    private void scanConfiguredDirectories() {
        List<String> roots = getActiveScanRootUris();
        if (roots.isEmpty()) {
            String message = getScanRootUris().isEmpty() ? "请先添加目录" : "请先启用扫描目录";
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
            return;
        }
        showScanDepthDialog(roots);
    }

    private void showScanDepthDialog(List<String> roots) {
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).create();
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);

        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(270), WindowManager.LayoutParams.WRAP_CONTENT);

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(20), dp(22), dp(16));
        root.setBackgroundResource(com.yuki.yukihub.R.drawable.launcher_dialog_bg);

        TextView title = new TextView(requireContext());
        title.setText("扫描游戏");
        title.setGravity(android.view.Gravity.CENTER);
        title.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_color));
        setResponsiveTextSize(title, 16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 深度选择区域
        String[] depthLabels = {
                "浅层扫描（1层）", "标准扫描（2层）", "深层扫描（3层）", "深度扫描（4层）",
                "遍历扫描（all层）", "递归扫描（命中）"
        };
        int currentDepth = scanDepth();
        int[] depthValues = {1, 2, 3, 4, LauncherScanBridge.SCAN_ALL_LEVELS, LauncherScanBridge.SCAN_UNTIL_GAME_MATCH};

        for (int i = 0; i < depthLabels.length; i++) {
            final int depth = depthValues[i];
            TextView option = new TextView(requireContext());
            option.setText((depth == currentDepth ? "● " : "○ ") + depthLabels[i]);
            option.setGravity(android.view.Gravity.CENTER);
            setResponsiveTextSize(option, 13);
            option.setTypeface(null, android.graphics.Typeface.BOLD);
            if (depth == currentDepth) {
                LauncherTheme.primaryButton(option);
            } else {
                LauncherTheme.menuItem(option);
            }
            option.setOnClickListener(v -> {
                dialog.dismiss();
                saveScanDepth(depth);
                executeScan(roots, depth);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
            lp.setMargins(0, dp(11), 0, 0);
            root.addView(option, lp);
        }

        TextView cancel = new TextView(requireContext());
        cancel.setText("取消");
        cancel.setGravity(android.view.Gravity.CENTER);
        cancel.setTextColor(LauncherTheme.primary(requireContext()));
        setResponsiveTextSize(cancel, 13);
        cancel.setTypeface(null, android.graphics.Typeface.BOLD);
        cancel.setBackground(LauncherTheme.cancelChip(requireContext()));
        cancel.setOnClickListener(view -> dialog.dismiss());
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        cancelLp.setMargins(0, dp(9), 0, 0);
        root.addView(cancel, cancelLp);

        window.setContentView(root);
    }

    private void saveScanDepth(int depth) {
        prefs().edit().putInt(KEY_STARTUP_SCAN_DEPTH, depth).apply();
    }

    private AlertDialog scanLoadingDialog;
    private ScanRequest activeScanRequest;

    private void executeScan(List<String> roots, int depth) {
        scanAndResolveXp3Targets(roots, depth);
    }

    private void scanAndResolveXp3Targets(List<String> roots, int depth) {
        ScanRequest request = ScanRequest.defaults(depth);
        activeScanRequest = request;
        scanLoadingDialog = showScanLoadingDialog();
        scanLoadingDialog.setCancelable(true);
        scanLoadingDialog.setCanceledOnTouchOutside(false);
        scanLoadingDialog.setButton(AlertDialog.BUTTON_NEGATIVE, "取消扫描", (dialog, which) -> request.cancel());
        scanLoadingDialog.setOnCancelListener(dialog -> request.cancel());
        android.content.Context appContext = requireContext().getApplicationContext();
        AppExecutors.runOnSingle(() -> {
            LauncherScanBridge.ScanBatchResult result = LauncherScanBridge.scanWithReport(appContext, roots, request);
            mainQueue.post(() -> {
                if (!isAdded()) return;
                dismissScanLoadingDialog();
                activeScanRequest = null;
                handleScanDiscovery(result);
            });
        });
    }

    private void handleScanDiscovery(LauncherScanBridge.ScanBatchResult result) {
        if (result == null) return;
        List<ScanResult> results = result.getResults();
        String summary = "已访问 " + result.getVisitedNodes() + " 个项目，发现 " + results.size() + " 个游戏";
        if (!result.getErrors().isEmpty()) summary += "\n错误 " + result.getErrors().size() + " 项：\n• " + TextUtils.join("\n• ", result.getErrors());
        if (result.isPartial()) {
            summary += "\n扫描已" + stopReasonText(result.getStopReason()) + "。";
            if (results.isEmpty()) {
                showLauncherConfirmDialog("扫描未完成", summary, "知道了", () -> {});
            } else {
                showLauncherConfirmDialog("扫描未完成", summary + "\n是否导入已发现的结果？", "导入", () -> resolveXp3Candidates(results, 0));
            }
            return;
        }
        if (!result.getErrors().isEmpty()) {
            if (results.isEmpty()) {
                showLauncherConfirmDialog("扫描完成（有错误）", summary, "知道了", () -> {});
            } else {
                showLauncherConfirmDialog("扫描完成（有错误）",
                        summary + "\n其余目录已扫描完成，是否继续导入已发现的结果？",
                        "继续导入", () -> resolveXp3Candidates(results, 0));
            }
            return;
        }
        resolveXp3Candidates(results, 0);
    }

    private String stopReasonText(ScanReport.StopReason reason) {
        if (reason == ScanReport.StopReason.CANCELLED) return "取消";
        if (reason == ScanReport.StopReason.DEADLINE) return "超时停止";
        if (reason == ScanReport.StopReason.NODE_LIMIT) return "达到项目上限";
        return "停止";
    }

    /**
     * data.xp3 is selected automatically by the scanner. A directory with multiple non-data
     * XP3 archives cannot be guessed reliably, so import waits for an explicit user choice.
     */
    private void resolveXp3Candidates(List<ScanResult> results, int startIndex) {
        if (!isAdded()) return;
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
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).create();
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(288), WindowManager.LayoutParams.WRAP_CONTENT);

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(18), dp(22), dp(15));
        root.setBackgroundResource(com.yuki.yukihub.R.drawable.launcher_dialog_bg);

        TextView title = new TextView(requireContext());
        title.setText("选择 XP3 入口");
        title.setGravity(android.view.Gravity.CENTER);
        title.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_color));
        setResponsiveTextSize(title, 16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView info = new TextView(requireContext());
        info.setText("《" + result.title + "》检测到多个 XP3 文件，请选择启动入口");
        info.setGravity(android.view.Gravity.CENTER);
        info.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_muted_color));
        setResponsiveTextSize(info, 12);
        info.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        infoLp.setMargins(0, dp(10), 0, 0);
        root.addView(info, infoLp);

        android.widget.ScrollView scroll = new android.widget.ScrollView(requireContext());
        LinearLayout choices = new LinearLayout(requireContext());
        choices.setOrientation(LinearLayout.VERTICAL);
        for (String candidate : result.xp3Candidates) {
            TextView option = new TextView(requireContext());
            option.setText(candidate);
            option.setGravity(android.view.Gravity.CENTER);
            option.setSingleLine(true);
            option.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            option.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_color));
            setResponsiveTextSize(option, 13);
            option.setBackground(LauncherTheme.cancelChip(requireContext()));
            option.setOnClickListener(view -> {
                result.launchTarget = candidate;
                dialog.dismiss();
                resolveXp3Candidates(results, index + 1);
            });
            LinearLayout.LayoutParams optionLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
            optionLp.setMargins(0, dp(8), 0, 0);
            choices.addView(option, optionLp);
        }
        scroll.addView(choices);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                Math.min(dp(250), dp(8) + result.xp3Candidates.size() * dp(46)));
        scrollLp.setMargins(0, dp(4), 0, 0);
        root.addView(scroll, scrollLp);

        LinearLayout buttons = new LinearLayout(requireContext());
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setWeightSum(2);
        LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        buttonsLp.setMargins(0, dp(12), 0, 0);

        TextView skip = new TextView(requireContext());
        skip.setText("跳过此游戏");
        skip.setGravity(android.view.Gravity.CENTER);
        setResponsiveTextSize(skip, 13);
        skip.setTypeface(null, android.graphics.Typeface.BOLD);
        LauncherTheme.secondaryButton(skip);
        skip.setOnClickListener(view -> {
            results.remove(index);
            dialog.dismiss();
            resolveXp3Candidates(results, index);
        });
        buttons.addView(skip, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));

        TextView cancel = new TextView(requireContext());
        cancel.setText("取消扫描");
        cancel.setGravity(android.view.Gravity.CENTER);
        setResponsiveTextSize(cancel, 13);
        cancel.setTypeface(null, android.graphics.Typeface.BOLD);
        LauncherTheme.secondaryButton(cancel);
        cancel.setOnClickListener(view -> dialog.dismiss());
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        cancelLp.setMargins(dp(8), 0, 0, 0);
        buttons.addView(cancel, cancelLp);
        root.addView(buttons, buttonsLp);
        window.setContentView(root);
    }

    private void importResolvedScanResults(List<ScanResult> results) {
        scanLoadingDialog = showScanLoadingDialog("正在导入...", "正在写入游戏库");
        android.content.Context appContext = requireContext().getApplicationContext();
        AppExecutors.runOnSingle(() -> {
            LauncherScanBridge.ImportStats stats = LauncherScanBridge.importScanResults(appContext, results);
            mainQueue.post(() -> {
                if (!isAdded()) return;
                dismissScanLoadingDialog();
                showScanResultDialog(stats);
            });
        });
    }

    private AlertDialog showScanLoadingDialog() {
        return showScanLoadingDialog("正在扫描...", "请不要关闭应用，扫描可能需要一些时间");
    }

    private AlertDialog showScanLoadingDialog(String titleText, String hintText) {
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).create();
        dialog.setCancelable(false);
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);

        Window window = dialog.getWindow();
        if (window == null) return dialog;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(270), WindowManager.LayoutParams.WRAP_CONTENT);

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(20), dp(22), dp(16));
        root.setBackgroundResource(com.yuki.yukihub.R.drawable.launcher_dialog_bg);

        TextView title = new TextView(requireContext());
        title.setText(titleText);
        title.setGravity(android.view.Gravity.CENTER);
        title.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_color));
        setResponsiveTextSize(title, 16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        android.widget.ProgressBar progressBar = new android.widget.ProgressBar(requireContext());
        progressBar.setIndeterminate(true);
        progressBar.getIndeterminateDrawable().setColorFilter(
                LauncherTheme.primary(requireContext()), android.graphics.PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(dp(32), dp(32));
        pbLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        pbLp.setMargins(0, dp(14), 0, 0);
        root.addView(progressBar, pbLp);

        TextView hint = new TextView(requireContext());
        hint.setText(hintText);
        hint.setGravity(android.view.Gravity.CENTER);
        hint.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_muted_color));
        setResponsiveTextSize(hint, 11);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintLp.setMargins(0, dp(10), 0, 0);
        root.addView(hint, hintLp);

        window.setContentView(root);
        return dialog;
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
        msg.append("扫描到 ").append(stats.scanned).append(" 个结果\n")
                .append("新增 ").append(stats.added).append(" 个，已存在 ").append(stats.skipped).append(" 个，失败 ").append(stats.failed).append(" 个");
        if (!stats.failedItems.isEmpty()) {
            msg.append("\n");
            for (String item : stats.failedItems) {
                msg.append("\n• ").append(item);
            }
        }
        showLauncherConfirmDialog("扫描完成", msg.toString(), "知道了", () -> {});
    }

    private void renderScanDirectories() {
        if (binding == null) return;
        List<String> roots = getScanRootUris();
        List<Boolean> states = getScanRootEnabledStates();
        binding.scanDirectoryList.removeAllViews();
        binding.scanDirectoryEmpty.setVisibility(roots.isEmpty() ? View.VISIBLE : View.GONE);
        binding.scanDirectoryList.setVisibility(roots.isEmpty() ? View.GONE : View.VISIBLE);
        for (int i = 0; i < roots.size(); i++) {
            binding.scanDirectoryList.addView(createDirectoryRow(roots.get(i), i, i >= states.size() || states.get(i)));
        }
    }

    private View createDirectoryRow(String root, int index, boolean enabled) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(13), 0, dp(9), 0);
        row.setBackgroundResource(com.yuki.yukihub.R.drawable.launcher_white_card);

        ImageView directoryIcon = new ImageView(requireContext());
        directoryIcon.setImageResource(com.yuki.yukihub.R.drawable.launcher_manage_scan_directory_icon);
        directoryIcon.setImageTintList(ColorStateList.valueOf(LauncherTheme.primary(requireContext())));
        row.addView(directoryIcon, new LinearLayout.LayoutParams(dp(25), dp(25)));

        TextView title = new TextView(requireContext());
        title.setText(directoryLabel(root));
        title.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_color));
        setResponsiveTextSize(title, 13);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        titleLp.setMargins(dp(11), 0, 0, 0);
        row.addView(title, titleLp);

        TextView toggle = smallAction(enabled ? "停用" : "启用", enabled);
        toggle.setOnClickListener(view -> {
            List<Boolean> states = getScanRootEnabledStates();
            while (states.size() <= index) states.add(true);
            states.set(index, !states.get(index));
            saveScanRootEnabledStates(states);
            renderScanDirectories();
        });
        row.addView(toggle);

        TextView remove = smallAction("移除", false);
        remove.setOnClickListener(view -> confirmRemoveDirectory(index));
        LinearLayout.LayoutParams removeLp = new LinearLayout.LayoutParams(dp(47), dp(29));
        removeLp.setMargins(dp(7), 0, 0, 0);
        row.addView(remove, removeLp);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
        );
        rowLp.setMargins(0, 0, 0, dp(9));
        row.setLayoutParams(rowLp);
        return row;
    }

    private TextView smallAction(String text, boolean selected) {
        TextView view = new TextView(requireContext());
        view.setText(text);
        view.setGravity(android.view.Gravity.CENTER);
        view.setSingleLine(true);
        setResponsiveTextSize(view, 11);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        if (selected) {
            view.setTextColor(LauncherTheme.onPrimary(requireContext()));
            view.setBackground(LauncherTheme.selectedChip(requireContext()));
        } else {
            LauncherTheme.menuItem(view);
        }
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(47), dp(29)));
        return view;
    }

    private void confirmRemoveDirectory(int index) {
        showLauncherConfirmDialog("移除扫描目录", "确定移除这个扫描目录吗？", "移除", () -> {
            List<String> roots = getScanRootUris();
            List<Boolean> states = getScanRootEnabledStates();
            if (index >= 0 && index < roots.size()) roots.remove(index);
            if (index >= 0 && index < states.size()) states.remove(index);
            saveScanRootUris(roots);
            saveScanRootEnabledStates(states);
            renderScanDirectories();
        });
    }

    private void showSyncOptions() {
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).create();
        dialog.show();

        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(270), WindowManager.LayoutParams.WRAP_CONTENT);

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(20), dp(22), dp(16));
        root.setBackgroundResource(com.yuki.yukihub.R.drawable.launcher_dialog_bg);

        TextView title = dialogTitle("云端同步");
        root.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView info = new TextView(requireContext());
        info.setGravity(android.view.Gravity.CENTER);
        info.setText(syncStatusText());
        info.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_muted_color));
        setResponsiveTextSize(info, 12);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        infoLp.setMargins(0, dp(11), 0, 0);
        root.addView(info, infoLp);

        addFeedbackOption(root, "立即同步", dialog, this::syncNow);
        addFeedbackOption(root, "打开同步中心", dialog, () ->
                startActivity(new Intent(requireContext(), LauncherSyncCenterActivity.class)));
        addFeedbackOption(root, "导出本地备份", dialog, this::exportLocalBackupToFile);
        addFeedbackOption(root, "导入本地备份", dialog, this::confirmImportLocalBackup);

        TextView cancel = dialogCancelButton(dialog);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        cancelLp.setMargins(0, dp(9), 0, 0);
        root.addView(cancel, cancelLp);

        window.setContentView(root);
    }

    private String syncStatusText() {
        if (!LauncherSyncBridge.isConfigured(requireContext())) return "WebDAV 尚未配置，请先打开同步中心。";
        long last = LauncherSyncBridge.lastSyncTime(requireContext());
        if (last <= 0L) return "已配置 WebDAV，尚未完成过同步。";
        return "上次同步：" + android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", last);
    }

    private void syncNow() {
        if (!LauncherSyncBridge.isConfigured(requireContext())) {
            // 未登录：弹窗提示去同步中心登录
            showLauncherConfirmDialog(
                    "未登录",
                    "请打开同步中心登录后再同步。",
                    "打开",
                    () -> startActivity(new Intent(requireContext(), LauncherSyncCenterActivity.class))
            );
            return;
        }
        // 已登录：直接使用主项目的方法同步
        LauncherSyncBridge.syncNow(requireContext(), new LauncherSyncBridge.Callback() {
            @Override
            public void onStart() {
                Toast.makeText(requireContext(), "正在同步...", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onProgress(String item, boolean changed) {
            }

            @Override
            public void onComplete(String message) {
                showLauncherConfirmDialog("同步完成", message, "知道了", () -> {});
            }

            @Override
            public void onError(String error) {
                showLauncherConfirmDialog("同步失败", error, "知道了", () -> {});
            }
        });
    }

    private void showDiagnosticsPrivacyDialog() {
        String message = "导出的日志可能包含设备信息、游戏路径、运行异常、WebView 或引擎输出等诊断内容。请先自行确认日志内容，再发送给反馈渠道。";
        showLauncherConfirmDialog("日志诊断", message, "继续", this::showDiagnosticsOptions);
    }

    private void showDiagnosticsOptions() {
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).create();
        dialog.show();

        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(270), WindowManager.LayoutParams.WRAP_CONTENT);

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(20), dp(22), dp(16));
        root.setBackgroundResource(com.yuki.yukihub.R.drawable.launcher_dialog_bg);

        TextView title = dialogTitle("日志诊断");
        root.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView info = new TextView(requireContext());
        info.setText("日志状态：" + (LauncherDiagnosticsBridge.isLogEnabled() ? "已开启" : "已关闭")
                + " · 当前大小：" + DevLogger.formatSize(LauncherDiagnosticsBridge.logSize()));
        info.setGravity(android.view.Gravity.CENTER);
        info.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_muted_color));
        setResponsiveTextSize(info, 12);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        infoLp.setMargins(0, dp(11), 0, 0);
        root.addView(info, infoLp);

        addFeedbackOption(root, LauncherDiagnosticsBridge.isLogEnabled() ? "关闭日志" : "开启日志", dialog, this::toggleDiagnosticLog);
        addFeedbackOption(root, "清空日志", dialog, this::confirmClearDiagnosticLog);
        addFeedbackOption(root, "导出日志", dialog, this::exportDiagnosticLog);

        TextView cancel = dialogCancelButton(dialog);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        cancelLp.setMargins(0, dp(9), 0, 0);
        root.addView(cancel, cancelLp);

        window.setContentView(root);
    }

    private TextView dialogTitle(String text) {
        TextView title = new TextView(requireContext());
        title.setText(text);
        title.setGravity(android.view.Gravity.CENTER);
        title.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_color));
        setResponsiveTextSize(title, 16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        return title;
    }

    private TextView dialogCancelButton(AlertDialog dialog) {
        TextView cancel = new TextView(requireContext());
        cancel.setText("取消");
        cancel.setGravity(android.view.Gravity.CENTER);
        cancel.setTextColor(LauncherTheme.primary(requireContext()));
        setResponsiveTextSize(cancel, 13);
        cancel.setTypeface(null, android.graphics.Typeface.BOLD);
        cancel.setBackground(LauncherTheme.cancelChip(requireContext()));
        cancel.setOnClickListener(view -> dialog.dismiss());
        return cancel;
    }

    private void exportDiagnosticLog() {
        try {
            if (!LauncherDiagnosticsBridge.exportLog(requireContext())) {
                Toast.makeText(requireContext(), "暂无日志文件", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable throwable) {
            Toast.makeText(requireContext(), "导出失败：" + throwable.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleDiagnosticLog() {
        boolean next = !LauncherDiagnosticsBridge.isLogEnabled();
        LauncherDiagnosticsBridge.setLogEnabled(requireContext(), next);
        Toast.makeText(requireContext(), next ? "日志已开启" : "日志已关闭", Toast.LENGTH_SHORT).show();
    }

    private void confirmClearDiagnosticLog() {
        showLauncherConfirmDialog("清空日志", "确定清空当前诊断日志吗？此操作不会删除游戏数据。", "清空", () -> {
            boolean success = LauncherDiagnosticsBridge.clearLog();
            Toast.makeText(requireContext(), success ? "日志已清空" : "清空失败", Toast.LENGTH_SHORT).show();
        });
    }

    private void addFeedbackOption(LinearLayout root, String label, AlertDialog dialog, Runnable action) {
        TextView option = new TextView(requireContext());
        option.setText(label);
        option.setGravity(android.view.Gravity.CENTER);
        option.setSingleLine(true);
        setResponsiveTextSize(option, 13);
        option.setTypeface(null, android.graphics.Typeface.BOLD);
        LauncherTheme.menuItem(option);
        option.setOnClickListener(view -> {
            dialog.dismiss();
            action.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        lp.setMargins(0, dp(11), 0, 0);
        root.addView(option, lp);
    }

    private void showLauncherConfirmDialog(String title, String message, String confirmText, Runnable onConfirm) {
        LauncherDialogFactory.showConfirm(requireContext(), title, message, confirmText, onConfirm);
    }

    private List<String> getScanRootUris() {
        List<String> roots = new ArrayList<>();
        String joined = prefs().getString(KEY_SCAN_ROOT_URIS, "");
        if (joined != null && !joined.trim().isEmpty()) {
            for (String part : joined.split("\\n")) {
                String root = part == null ? "" : part.trim();
                if (!root.isEmpty() && !roots.contains(root)) roots.add(root);
                if (roots.size() >= MAX_SCAN_ROOTS) break;
            }
        }
        String legacy = prefs().getString(KEY_LAST_SCAN_ROOT_URI, "");
        if (roots.isEmpty() && legacy != null && !legacy.trim().isEmpty()) roots.add(legacy.trim());
        return roots;
    }

    private void saveScanRootUris(List<String> roots) {
        List<String> cleaned = new ArrayList<>();
        if (roots != null) {
            for (String root : roots) {
                String value = root == null ? "" : root.trim();
                if (!value.isEmpty() && !cleaned.contains(value)) cleaned.add(value);
                if (cleaned.size() >= MAX_SCAN_ROOTS) break;
            }
        }
        StringBuilder joined = new StringBuilder();
        for (String root : cleaned) {
            if (joined.length() > 0) joined.append('\n');
            joined.append(root);
        }
        SharedPreferences.Editor editor = prefs().edit().putString(KEY_SCAN_ROOT_URIS, joined.toString());
        if (cleaned.isEmpty()) editor.remove(KEY_LAST_SCAN_ROOT_URI);
        else editor.putString(KEY_LAST_SCAN_ROOT_URI, cleaned.get(0));
        editor.apply();
    }

    private void saveScanRootEnabledStates(List<Boolean> states) {
        StringBuilder joined = new StringBuilder();
        List<String> roots = getScanRootUris();
        int count = Math.min(MAX_SCAN_ROOTS, roots.size());
        for (int i = 0; i < count; i++) {
            if (i > 0) joined.append(',');
            boolean enabled = states == null || i >= states.size() || states.get(i);
            joined.append(enabled ? '1' : '0');
        }
        prefs().edit().putString(KEY_SCAN_ROOT_ENABLED, joined.toString()).apply();
    }

    private List<String> getActiveScanRootUris() {
        List<String> roots = getScanRootUris();
        List<Boolean> states = getScanRootEnabledStates();
        List<String> active = new ArrayList<>();
        for (int i = 0; i < roots.size(); i++) {
            if (i < states.size() && states.get(i)) active.add(roots.get(i));
        }
        return active;
    }

    private List<Boolean> getScanRootEnabledStates() {
        List<Boolean> states = new ArrayList<>();
        String joined = prefs().getString(KEY_SCAN_ROOT_ENABLED, "");
        if (joined != null && !joined.trim().isEmpty()) {
            for (String part : joined.split(",")) {
                states.add("1".equals(part == null ? "" : part.trim()));
            }
        }
        while (states.size() < MAX_SCAN_ROOTS) states.add(true);
        return states;
    }

    private int scanDepth() {
        int depth = prefs().getInt(KEY_STARTUP_SCAN_DEPTH, DEFAULT_SCAN_DEPTH);
        if (depth == LauncherScanBridge.SCAN_ALL_LEVELS || depth == LauncherScanBridge.SCAN_UNTIL_GAME_MATCH) {
            return depth;
        }
        return Math.max(1, Math.min(MAX_SCAN_DEPTH, depth));
    }

    private SharedPreferences prefs() {
        return requireContext().getApplicationContext().getSharedPreferences(APP_PREFS, android.content.Context.MODE_PRIVATE);
    }

    private String directoryLabel(String root) {
        if (root == null || root.trim().isEmpty()) return "未命名目录";
        String last = Uri.parse(root).getLastPathSegment();
        if (last == null || last.trim().isEmpty()) return root;
        int colon = last.lastIndexOf(':');
        return colon >= 0 && colon < last.length() - 1 ? last.substring(colon + 1) : last;
    }

    /**
     * 与其余 Launcher 页面使用同一套平板竖屏倍率，避免页面之间的视觉比例不一致。
     */
    private float tabletPortraitScale() {
        return LauncherTabletPortraitScaler.scaleFor(binding == null ? null : binding.getRoot());
    }

    /** 对 XML 中已经解析出的行高、边距、内边距、文字和最小尺寸统一缩放。 */
    private void applyTabletPortraitLayout() {
        if (binding == null) return;
        LauncherTabletPortraitScaler.apply(binding.getRoot());
    }

    /** 用于 Java 动态创建的 TextView，确保它们与 XML 内容采用同一缩放比例。 */
    private void setResponsiveTextSize(TextView view, float baseSp) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSp * tabletPortraitScale());
    }

    /** Java 动态创建的尺寸统一走这里，手机保持原 dp，平板竖屏自动放大。 */
    private int dp(int value) {
        return (int) (value
                * getResources().getDisplayMetrics().density
                * tabletPortraitScale()
                + 0.5f);
    }

    private void confirmImportLocalBackup() {
        showLauncherConfirmDialog("本地导入", "将从备份 JSON 导入个人资料、游戏库、游玩记录和元数据。\n\n导入策略：\n- 游戏按 rootUri 去重合并\n- 游玩记录按 session_uuid 去重\n- 图片只恢复 URI/URL，不复制图片文件\n\n是否继续？", "选择文件", () ->
                backupOpenLauncher.launch(new String[]{"application/json", "text/*", "*/*"}));
    }

    private void importLocalBackup(Uri uri) {
        android.content.Context appContext = requireContext().getApplicationContext();
        AppExecutors.runOnSingle(() -> {
            try {
                String text = readTextFromUri(uri);
                JSONObject root = new JSONObject(text);
                if (!"YukiHub".equals(root.optString("app", ""))) {
                    mainQueue.post(() -> {
                        if (!isAdded()) return;
                        showLauncherConfirmDialog("导入失败", "不是有效的 YukiHub 备份", "知道了", () -> {});
                    });
                    return;
                }
                LauncherSyncBridge.importLocalBackup(appContext, root);
                int gameCount = root.optJSONArray("games") == null ? 0 : root.optJSONArray("games").length();
                int sessionCount = root.optJSONArray("play_sessions") == null ? 0 : root.optJSONArray("play_sessions").length();
                int metaCount = root.optJSONArray("metadata_cache") == null ? 0 : root.optJSONArray("metadata_cache").length();
                mainQueue.post(() -> {
                    if (!isAdded()) return;
                    showLauncherConfirmDialog("导入成功", "游戏 " + gameCount + "，记录 " + sessionCount + "，元数据 " + metaCount, "知道了", () -> {});
                });
            } catch (Throwable t) {
                Log.e("LauncherManage", "import backup failed", t);
                mainQueue.post(() -> {
                    if (!isAdded()) return;
                    showLauncherConfirmDialog("导入失败", t.getMessage() != null ? t.getMessage() : "未知错误", "知道了", () -> {});
                });
            }
        });
    }

    private void exportLocalBackupToFile() {
        try {
            backupCreateLauncher.launch("yukihub_backup_" + System.currentTimeMillis() + ".json");
        } catch (Throwable t) {
            showLauncherConfirmDialog("导出失败", t.getMessage() != null ? t.getMessage() : "未知错误", "知道了", () -> {});
        }
    }

    private void exportLocalBackup(Uri uri) {
        android.content.Context appContext = requireContext().getApplicationContext();
        AppExecutors.runOnSingle(() -> {
            try {
                JSONObject root = LauncherSyncBridge.exportLocalBackup(appContext);
                byte[] bytes = root.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                try (java.io.OutputStream out = appContext.getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new Exception("openOutputStream failed");
                    out.write(bytes);
                    out.flush();
                }
                int sizeKb = bytes.length / 1024;
                mainQueue.post(() -> {
                    if (!isAdded()) return;
                    showLauncherConfirmDialog("导出成功", "备份大小：" + sizeKb + "KB", "知道了", () -> {});
                });
            } catch (Throwable t) {
                Log.e("LauncherManage", "export backup failed", t);
                mainQueue.post(() -> {
                    if (!isAdded()) return;
                    showLauncherConfirmDialog("导出失败", t.getMessage() != null ? t.getMessage() : "未知错误", "知道了", () -> {});
                });
            }
        });
    }

    private String readTextFromUri(Uri uri) throws Exception {
        try (InputStream in = requireContext().getContentResolver().openInputStream(uri);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            if (in == null) throw new Exception("openInputStream failed");
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) != -1) bos.write(buf, 0, len);
            return bos.toString("UTF-8");
        }
    }
}
