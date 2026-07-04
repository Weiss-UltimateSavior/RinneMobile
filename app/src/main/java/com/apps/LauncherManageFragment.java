package com.apps;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import com.yuki.yukihub.MainActivity;
import com.yuki.yukihub.data.GameRepository;
import com.yuki.yukihub.databinding.FragmentLauncherManageBinding;
import com.yuki.yukihub.model.EngineType;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.scanner.GameScanner;
import com.yuki.yukihub.scanner.ScanResult;
import com.yuki.yukihub.util.AppExecutors;
import com.yuki.yukihub.util.DevLogger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<Uri> scanDirectoryPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) return;
                persistAndSaveScanDirectory(uri);
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
        applySystemBarInsets();
        bindActions();
        applyThemeTone();
        renderScanDirectories();
    }

    @Override
    public void onDestroyView() {
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
        binding.actionAddDirectory.setOnClickListener(view -> scanDirectoryPicker.launch(null));
        binding.actionScanGame.setOnClickListener(view -> scanConfiguredDirectories());
        binding.actionAddGame.setOnClickListener(view -> openMainActivity(MainActivity.ACTION_ADD_GAME));
        binding.actionCloudSync.setOnClickListener(view -> openMainActivity(MainActivity.ACTION_SYNC_CENTER));
        binding.actionModelSettings.setOnClickListener(view -> openMainActivity(MainActivity.ACTION_AI_REVIEW_SETTINGS));
        binding.actionFeedback.setOnClickListener(view -> showFeedbackOptions());
        binding.actionAppSettings.setOnClickListener(view -> openMainActivity(MainActivity.ACTION_SETTINGS));
        binding.actionDiagnostics.setOnClickListener(view -> showDiagnosticsPrivacyDialog());
    }

    private void applyThemeTone() {
        LauncherTheme.applyPrimaryTone(binding.getRoot());
        for (int i = 0; i < binding.manageActionList.getChildCount(); i++) {
            View row = binding.manageActionList.getChildAt(i);
            if (!(row instanceof ViewGroup)) continue;
            View icon = ((ViewGroup) row).getChildAt(0);
            if (icon instanceof TextView) {
                icon.setBackground(LauncherTheme.circle(requireContext()));
                ((TextView) icon).setTextColor(LauncherTheme.onPrimary(requireContext()));
            }
        }
    }

    private void openMainActivity(String action) {
        Intent intent = new Intent(requireContext(), MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_LAUNCH_ACTION, action);
        startActivity(intent);
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
        Toast.makeText(requireContext(), "已添加目录", Toast.LENGTH_SHORT).show();
    }

    private void scanConfiguredDirectories() {
        List<String> roots = getActiveScanRootUris();
        if (roots.isEmpty()) {
            String message = getScanRootUris().isEmpty() ? "请先添加目录" : "请先启用扫描目录";
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(requireContext(), "正在扫描目录...", Toast.LENGTH_SHORT).show();
        int depth = scanDepth();
        android.content.Context appContext = requireContext().getApplicationContext();
        AppExecutors.runOnSingle(() -> {
            GameRepository repository = new GameRepository(appContext);
            List<ScanResult> results = new ArrayList<>();
            for (String root : roots) {
                if (root == null || root.trim().isEmpty()) continue;
                try {
                    results.addAll(GameScanner.scan(appContext, Uri.parse(root), depth));
                } catch (Throwable ignored) {
                }
            }
            ScanImportStats stats = importScannedGames(repository, results);
            mainHandler.post(() -> {
                if (!isAdded()) return;
                renderScanResult(stats);
                Toast.makeText(
                        requireContext(),
                        "扫描完成：新增 " + stats.added + " 个，已存在 " + stats.skipped + " 个，失败 " + stats.failed + " 个",
                        Toast.LENGTH_SHORT
                ).show();
            });
        });
    }

    private ScanImportStats importScannedGames(GameRepository repository, List<ScanResult> results) {
        ScanImportStats stats = new ScanImportStats();
        stats.scanned = results == null ? 0 : results.size();
        if (repository == null || results == null || results.isEmpty()) return stats;
        Set<String> existing = repository.getRootUriKeySet();
        for (ScanResult result : results) {
            if (result == null || result.uri == null || result.uri.trim().isEmpty()) {
                stats.failed++;
                stats.failedItems.add("无法读取路径的扫描结果");
                continue;
            }
            String rootKey = GameRepository.normalizeRootUriKey(result.uri);
            if (existing.contains(rootKey)) {
                stats.skipped++;
                stats.skippedItems.add(emptyText(result.title, result.uri));
                continue;
            }
            Game game = new Game();
            game.title = result.title;
            game.rootUri = result.uri;
            game.engine = result.engine;
            game.launchTarget = result.launchTarget == null || result.launchTarget.trim().isEmpty()
                    ? defaultLaunchTargetForEngine(result.engine)
                    : result.launchTarget;
            game.emulatorPackage = emulatorPackageForEngine(result.engine);
            long id = repository.insertIfNotExists(game);
            if (id > 0) {
                existing.add(rootKey);
                stats.added++;
                stats.addedItems.add(emptyText(result.title, result.uri));
            } else {
                stats.failed++;
                stats.failedItems.add(emptyText(result.title, result.uri));
            }
        }
        return stats;
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
        row.setPadding(dp(14), 0, dp(10), 0);
        row.setBackgroundResource(com.yuki.yukihub.R.drawable.launcher_white_card);

        TextView title = new TextView(requireContext());
        title.setText(directoryLabel(root));
        title.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_color));
        title.setTextSize(14);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView toggle = smallAction(enabled ? "启用" : "停用", enabled);
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
        LinearLayout.LayoutParams removeLp = new LinearLayout.LayoutParams(dp(52), dp(32));
        removeLp.setMargins(dp(8), 0, 0, 0);
        row.addView(remove, removeLp);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(58)
        );
        rowLp.setMargins(0, 0, 0, dp(10));
        row.setLayoutParams(rowLp);
        return row;
    }

    private TextView smallAction(String text, boolean selected) {
        TextView view = new TextView(requireContext());
        view.setText(text);
        view.setGravity(android.view.Gravity.CENTER);
        view.setSingleLine(true);
        view.setTextSize(12);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setTextColor(selected ? LauncherTheme.onPrimary(requireContext()) : LauncherTheme.primary(requireContext()));
        view.setBackground(selected
                ? LauncherTheme.selectedChip(requireContext())
                : ContextCompat.getDrawable(requireContext(), com.yuki.yukihub.R.drawable.launcher_filter_chip_unselected));
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(52), dp(32)));
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

    private void renderScanResult(ScanImportStats stats) {
        if (binding == null || stats == null) return;
        binding.scanResultTitle.setVisibility(View.VISIBLE);
        binding.scanResultDetail.setVisibility(View.VISIBLE);
        binding.scanResultDetail.removeAllViews();
        addResultLine("扫描到 " + stats.scanned + " 个结果");
        addResultLine("新增 " + stats.added + " 个，已存在 " + stats.skipped + " 个，失败 " + stats.failed + " 个");
        addResultGroup("新增", stats.addedItems);
        addResultGroup("已存在", stats.skippedItems);
        addResultGroup("失败", stats.failedItems);
    }

    private void addResultGroup(String title, List<String> items) {
        if (items == null || items.isEmpty()) return;
        addResultLine(title + "：");
        int limit = Math.min(items.size(), 6);
        for (int i = 0; i < limit; i++) {
            addResultLine("· " + items.get(i));
        }
        if (items.size() > limit) addResultLine("· 还有 " + (items.size() - limit) + " 个未显示");
    }

    private void addResultLine(String text) {
        TextView view = new TextView(requireContext());
        view.setText(text);
        view.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_muted_color));
        view.setTextSize(13);
        view.setPadding(0, dp(3), 0, dp(3));
        binding.scanResultDetail.addView(view);
    }

    private void showFeedbackOptions() {
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).create();
        dialog.show();

        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(300), WindowManager.LayoutParams.WRAP_CONTENT);

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(22), dp(24), dp(18));
        root.setBackgroundResource(com.yuki.yukihub.R.drawable.launcher_dialog_bg);

        TextView title = new TextView(requireContext());
        title.setText("建议反馈");
        title.setGravity(android.view.Gravity.CENTER);
        title.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_color));
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        addFeedbackOption(root, "GitHub 仓库", dialog, () -> openExternalUrl("https://github.com/xm486/YukiHub"));
        addFeedbackOption(root, "YukiHub 官网", dialog, () -> openExternalUrl("https://yukihub.kesug.com/"));
        addFeedbackOption(root, "QQ 交流群", dialog, () -> openExternalUrl("https://qun.qq.com/universal-share/share?ac=1&authKey=nZMa0s3mxxG1A0f%2BY0nAWmBYpul7FWTEDI6UWrzqb2IgKC4aDkUhvkV2AekAkW%2F1&busi_data=eyJncm91cENvZGUiOiIxNjM2MDM2MzUiLCJ0b2tlbiI6Im93eFRyY0tqNDdxK3FGQXlVZ0lhMEZGbWZWemphZnpYYW1kWWpPN1ViL3A0SkRUd1dEclMwZkM1bWI0UEYxME4iLCJ1aW4iOiIzMDg2Njc4NzU1In0%3D&data=bwoLG7XAPzqsvtfneNCQUUlu-HpX1yCn-6dkgd8ubDeBJKEPgd7wKYa6ym-EbW07Vapc3xm_o-iy0GbFHhZk5Q&svctype=4&tempid=h5_group_info"));
        addFeedbackOption(root, "关于与设置", dialog, () -> openMainActivity(MainActivity.ACTION_SETTINGS));

        TextView cancel = new TextView(requireContext());
        cancel.setText("取消");
        cancel.setGravity(android.view.Gravity.CENTER);
        cancel.setTextColor(LauncherTheme.primary(requireContext()));
        cancel.setTextSize(14);
        cancel.setTypeface(null, android.graphics.Typeface.BOLD);
        cancel.setBackground(LauncherTheme.cancelChip(requireContext()));
        cancel.setOnClickListener(view -> dialog.dismiss());
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        cancelLp.setMargins(0, dp(10), 0, 0);
        root.addView(cancel, cancelLp);

        window.setContentView(root);
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
        window.setLayout(dp(300), WindowManager.LayoutParams.WRAP_CONTENT);

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(22), dp(24), dp(18));
        root.setBackgroundResource(com.yuki.yukihub.R.drawable.launcher_dialog_bg);

        TextView title = dialogTitle("日志诊断");
        root.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView info = new TextView(requireContext());
        info.setText("当前日志大小：" + DevLogger.formatSize(DevLogger.getLogSize()));
        info.setGravity(android.view.Gravity.CENTER);
        info.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_muted_color));
        info.setTextSize(13);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        infoLp.setMargins(0, dp(12), 0, 0);
        root.addView(info, infoLp);

        addFeedbackOption(root, "导出日志", dialog, this::exportDiagnosticLog);
        addFeedbackOption(root, "打开反馈渠道", dialog, this::showFeedbackOptions);

        TextView cancel = dialogCancelButton(dialog);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        cancelLp.setMargins(0, dp(10), 0, 0);
        root.addView(cancel, cancelLp);

        window.setContentView(root);
    }

    private TextView dialogTitle(String text) {
        TextView title = new TextView(requireContext());
        title.setText(text);
        title.setGravity(android.view.Gravity.CENTER);
        title.setTextColor(ContextCompat.getColor(requireContext(), com.yuki.yukihub.R.color.launcher_text_color));
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        return title;
    }

    private TextView dialogCancelButton(AlertDialog dialog) {
        TextView cancel = new TextView(requireContext());
        cancel.setText("取消");
        cancel.setGravity(android.view.Gravity.CENTER);
        cancel.setTextColor(LauncherTheme.primary(requireContext()));
        cancel.setTextSize(14);
        cancel.setTypeface(null, android.graphics.Typeface.BOLD);
        cancel.setBackground(LauncherTheme.cancelChip(requireContext()));
        cancel.setOnClickListener(view -> dialog.dismiss());
        return cancel;
    }

    private void exportDiagnosticLog() {
        File logFile = DevLogger.getLogFile();
        if (logFile == null || !logFile.exists()) {
            Toast.makeText(requireContext(), "暂无日志文件", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(
                    Intent.EXTRA_STREAM,
                    FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".fileprovider", logFile)
            );
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "YukiHub Logcat");
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "导出日志"));
        } catch (Throwable throwable) {
            Toast.makeText(requireContext(), "导出失败：" + throwable.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void addFeedbackOption(LinearLayout root, String label, AlertDialog dialog, Runnable action) {
        TextView option = new TextView(requireContext());
        option.setText(label);
        option.setGravity(android.view.Gravity.CENTER);
        option.setSingleLine(true);
        option.setTextColor(LauncherTheme.primary(requireContext()));
        option.setTextSize(14);
        option.setTypeface(null, android.graphics.Typeface.BOLD);
        option.setBackgroundResource(com.yuki.yukihub.R.drawable.launcher_filter_chip_unselected);
        option.setOnClickListener(view -> {
            dialog.dismiss();
            action.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        lp.setMargins(0, dp(12), 0, 0);
        root.addView(option, lp);
    }

    private void showLauncherConfirmDialog(String title, String message, String confirmText, Runnable onConfirm) {
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).create();
        dialog.show();

        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(280), WindowManager.LayoutParams.WRAP_CONTENT);
        View dialogView = LayoutInflater.from(requireContext())
                .inflate(com.yuki.yukihub.R.layout.dialog_launcher_confirm, null);
        window.setContentView(dialogView);

        TextView titleView = dialogView.findViewById(com.yuki.yukihub.R.id.dialogTitle);
        TextView messageView = dialogView.findViewById(com.yuki.yukihub.R.id.dialogMessage);
        TextView btnCancel = dialogView.findViewById(com.yuki.yukihub.R.id.dialogBtnCancel);
        TextView btnConfirm = dialogView.findViewById(com.yuki.yukihub.R.id.dialogBtnConfirm);

        titleView.setText(title);
        messageView.setText(message);
        btnConfirm.setText(confirmText);
        LauncherTheme.dialogButtons(btnCancel, btnConfirm);
        btnCancel.setOnClickListener(view -> dialog.dismiss());
        btnConfirm.setOnClickListener(view -> {
            dialog.dismiss();
            onConfirm.run();
        });
    }

    private void openExternalUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable throwable) {
            Toast.makeText(requireContext(), "无法打开链接", Toast.LENGTH_SHORT).show();
        }
    }

    private String emulatorPackageForEngine(EngineType engine) {
        if (engine == EngineType.KIRIKIRI) return "internal.krkr";
        if (engine == EngineType.ONS) return "internal.ons";
        if (engine == EngineType.TYRANO) return "internal.tyrano";
        if (engine == EngineType.PSP) return "org.ppsspp.ppsspp";
        return "";
    }

    private String defaultLaunchTargetForEngine(EngineType engine) {
        return "[游戏目录]";
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

    private String emptyText(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class ScanImportStats {
        int scanned;
        int added;
        int skipped;
        int failed;
        final List<String> addedItems = new ArrayList<>();
        final List<String> skippedItems = new ArrayList<>();
        final List<String> failedItems = new ArrayList<>();
    }
}
