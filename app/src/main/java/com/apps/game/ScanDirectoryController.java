package com.apps.game;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AlertDialog;

import com.apps.theme.LauncherDialogFactory;
import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;
import com.core.R;
import com.core.launcherbridge.LauncherScanBridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 扫描目录管理控制器：从 LauncherManageFragment 抽离的扫描目录增删、启用状态、
 * 扫描深度选择与目录列表渲染逻辑。
 *
 * 依赖 ManageHost 提供 Context、UI 缩放、SharedPreferences 与共享对话框；
 * 通过 OnScanRequestedListener 回调将扫描请求转交 Xp3TargetResolver 等外部组件。
 */
public final class ScanDirectoryController {

    /** 扫描请求回调，由外部（如 Xp3TargetResolver）实现。 */
    public interface OnScanRequestedListener {
        void onScanRequested(List<String> roots, int depth, boolean fullRefresh);
    }

    private static final String KEY_SCAN_ROOT_URIS = "scan_root_uris";
    private static final String KEY_SCAN_ROOT_ENABLED = "scan_root_enabled";
    private static final String KEY_LAST_SCAN_ROOT_URI = "last_scan_root_uri";
    private static final String KEY_STARTUP_SCAN_DEPTH = "startup_scan_depth";
    private static final int DEFAULT_SCAN_DEPTH = 2;
    private static final int MAX_SCAN_DEPTH = 4;
    private static final int MAX_SCAN_ROOTS = 3;

    private final ManageHost host;
    private final ActivityResultLauncher<Uri> scanDirectoryPicker;
    private final OnScanRequestedListener scanListener;
    private final ViewGroup directoryList;
    private final View directoryEmpty;

    public ScanDirectoryController(ManageHost host,
                                   ActivityResultLauncher<Uri> scanDirectoryPicker,
                                   OnScanRequestedListener scanListener,
                                   ViewGroup directoryList,
                                   View directoryEmpty) {
        this.host = host;
        this.scanDirectoryPicker = scanDirectoryPicker;
        this.scanListener = scanListener;
        this.directoryList = directoryList;
        this.directoryEmpty = directoryEmpty;
    }

    public void confirmAddDirectory() {
        host.showConfirmDialog(host.getString(R.string.game_scan_add_directory),
                host.getString(R.string.game_scan_add_directory_message),
                host.getString(R.string.game_scan_add), () ->
                scanDirectoryPicker.launch(null));
    }

    public void persistAndSaveScanDirectory(Uri uri) {
        try {
            host.requireContext().getContentResolver().takePersistableUriPermission(
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
            Toast.makeText(host.requireContext(),
                    host.getString(R.string.game_scan_root_limit, MAX_SCAN_ROOTS),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        roots.add(value);
        saveScanRootUris(roots);
        renderScanDirectories();
        Toast.makeText(host.requireContext(), R.string.game_scan_added, Toast.LENGTH_SHORT).show();
        showScanDepthDialog(Collections.singletonList(value));
    }

    public void scanConfiguredDirectories() {
        List<String> roots = getActiveScanRootUris();
        if (roots.isEmpty()) {
            String message = host.getString(getScanRootUris().isEmpty()
                    ? R.string.game_scan_add_first : R.string.game_scan_enable_first);
            Toast.makeText(host.requireContext(), message, Toast.LENGTH_SHORT).show();
            return;
        }
        showScanDepthDialog(roots);
    }

    public void showScanDepthDialog(List<String> roots) {
        CharSequence[] depthLabels = {
                host.getString(R.string.game_scan_shallow),
                host.getString(R.string.game_scan_standard),
                host.getString(R.string.game_scan_deep),
                host.getString(R.string.game_scan_deeper),
                host.getString(R.string.game_scan_all),
                host.getString(R.string.game_scan_recursive)
        };
        int currentDepth = scanDepth();
        int[] depthValues = {1, 2, 3, 4, LauncherScanBridge.SCAN_ALL_LEVELS, LauncherScanBridge.SCAN_UNTIL_GAME_MATCH};

        LauncherDialogFactory.showScanDepthChoices(
                host.requireContext(),
                host.getString(R.string.game_scan_title),
                host.getString(R.string.game_scan_mode_quick),
                host.getString(R.string.game_scan_mode_full),
                depthLabels,
                depthValues,
                currentDepth,
                (depth, fullRefresh) -> {
                saveScanDepth(depth);
                scanListener.onScanRequested(roots, depth, fullRefresh);
        });
    }

    public void saveScanDepth(int depth) {
        host.getPrefs().edit().putInt(KEY_STARTUP_SCAN_DEPTH, depth).apply();
    }

    public void renderScanDirectories() {
        if (directoryList == null || directoryEmpty == null) return;
        List<String> roots = getScanRootUris();
        List<Boolean> states = getScanRootEnabledStates();
        directoryList.removeAllViews();
        directoryEmpty.setVisibility(roots.isEmpty() ? View.VISIBLE : View.GONE);
        directoryList.setVisibility(roots.isEmpty() ? View.GONE : View.VISIBLE);
        for (int i = 0; i < roots.size(); i++) {
            directoryList.addView(createDirectoryRow(roots.get(i), i, i >= states.size() || states.get(i)));
        }
    }

    private View createDirectoryRow(String root, int index, boolean enabled) {
        LinearLayout row = new LinearLayout(host.requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(host.dp(13), 0, host.dp(9), 0);
        row.setBackgroundResource(com.core.R.drawable.launcher_white_card);

        ImageView directoryIcon = new ImageView(host.requireContext());
        directoryIcon.setImageResource(com.core.R.drawable.launcher_manage_scan_directory_icon);
        directoryIcon.setImageTintList(ColorStateList.valueOf(LauncherTheme.primary(host.requireContext())));
        row.addView(directoryIcon, new LinearLayout.LayoutParams(host.dp(25), host.dp(25)));

        TextView title = new TextView(host.requireContext());
        title.setText(directoryLabel(root));
        title.setTextColor(LauncherTheme.text(host.requireContext()));
        host.setResponsiveTextSize(title, 13);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        titleLp.setMargins(host.dp(11), 0, 0, 0);
        row.addView(title, titleLp);

        TextView toggle = smallAction(host.getString(enabled
                ? R.string.game_scan_disable : R.string.game_scan_enable), enabled);
        toggle.setOnClickListener(view -> {
            List<Boolean> states = getScanRootEnabledStates();
            while (states.size() <= index) states.add(true);
            states.set(index, !states.get(index));
            saveScanRootEnabledStates(states);
            renderScanDirectories();
        });
        row.addView(toggle);

        TextView remove = smallAction(host.getString(R.string.game_common_remove), false);
        remove.setOnClickListener(view -> confirmRemoveDirectory(index));
        LinearLayout.LayoutParams removeLp = new LinearLayout.LayoutParams(host.dp(47), host.dp(29));
        removeLp.setMargins(host.dp(7), 0, 0, 0);
        row.addView(remove, removeLp);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                host.dp(52)
        );
        rowLp.setMargins(0, 0, 0, host.dp(9));
        row.setLayoutParams(rowLp);
        return row;
    }

    private TextView smallAction(String text, boolean selected) {
        TextView view = new TextView(host.requireContext());
        view.setText(text);
        view.setGravity(android.view.Gravity.CENTER);
        view.setSingleLine(true);
        host.setResponsiveTextSize(view, 11);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        if (selected) {
            view.setTextColor(LauncherTheme.onPrimary(host.requireContext()));
            view.setBackground(LauncherTheme.selectedChip(host.requireContext()));
        } else {
            LauncherTheme.menuItem(view);
        }
        view.setLayoutParams(new LinearLayout.LayoutParams(host.dp(47), host.dp(29)));
        return view;
    }

    private void confirmRemoveDirectory(int index) {
        host.showConfirmDialog(host.getString(R.string.game_scan_remove_title),
                host.getString(R.string.game_scan_remove_message),
                host.getString(R.string.game_common_remove), () -> {
            List<String> roots = getScanRootUris();
            List<Boolean> states = getScanRootEnabledStates();
            if (index >= 0 && index < roots.size()) roots.remove(index);
            if (index >= 0 && index < states.size()) states.remove(index);
            saveScanRootUris(roots);
            saveScanRootEnabledStates(states);
            renderScanDirectories();
        });
    }

    public void saveScanRootUris(List<String> roots) {
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
        SharedPreferences.Editor editor = host.getPrefs().edit().putString(KEY_SCAN_ROOT_URIS, joined.toString());
        if (cleaned.isEmpty()) editor.remove(KEY_LAST_SCAN_ROOT_URI);
        else editor.putString(KEY_LAST_SCAN_ROOT_URI, cleaned.get(0));
        editor.apply();
    }

    public void saveScanRootEnabledStates(List<Boolean> states) {
        StringBuilder joined = new StringBuilder();
        List<String> roots = getScanRootUris();
        int count = Math.min(MAX_SCAN_ROOTS, roots.size());
        for (int i = 0; i < count; i++) {
            if (i > 0) joined.append(',');
            boolean enabled = states == null || i >= states.size() || states.get(i);
            joined.append(enabled ? '1' : '0');
        }
        host.getPrefs().edit().putString(KEY_SCAN_ROOT_ENABLED, joined.toString()).apply();
    }

    public List<String> getScanRootUris() {
        List<String> roots = new ArrayList<>();
        String joined = host.getPrefs().getString(KEY_SCAN_ROOT_URIS, "");
        if (joined != null && !joined.trim().isEmpty()) {
            for (String part : joined.split("\\n")) {
                String root = part == null ? "" : part.trim();
                if (!root.isEmpty() && !roots.contains(root)) roots.add(root);
                if (roots.size() >= MAX_SCAN_ROOTS) break;
            }
        }
        String legacy = host.getPrefs().getString(KEY_LAST_SCAN_ROOT_URI, "");
        if (roots.isEmpty() && legacy != null && !legacy.trim().isEmpty()) roots.add(legacy.trim());
        return roots;
    }

    public List<String> getActiveScanRootUris() {
        List<String> roots = getScanRootUris();
        List<Boolean> states = getScanRootEnabledStates();
        List<String> active = new ArrayList<>();
        for (int i = 0; i < roots.size(); i++) {
            if (i < states.size() && states.get(i)) active.add(roots.get(i));
        }
        return active;
    }

    public List<Boolean> getScanRootEnabledStates() {
        List<Boolean> states = new ArrayList<>();
        String joined = host.getPrefs().getString(KEY_SCAN_ROOT_ENABLED, "");
        if (joined != null && !joined.trim().isEmpty()) {
            for (String part : joined.split(",")) {
                states.add("1".equals(part == null ? "" : part.trim()));
            }
        }
        while (states.size() < MAX_SCAN_ROOTS) states.add(true);
        return states;
    }

    public int scanDepth() {
        int depth = host.getPrefs().getInt(KEY_STARTUP_SCAN_DEPTH, DEFAULT_SCAN_DEPTH);
        if (depth == LauncherScanBridge.SCAN_ALL_LEVELS || depth == LauncherScanBridge.SCAN_UNTIL_GAME_MATCH) {
            return depth;
        }
        return Math.max(1, Math.min(MAX_SCAN_DEPTH, depth));
    }

    private String directoryLabel(String root) {
        if (root == null || root.trim().isEmpty()) {
            return host.getString(R.string.game_directory_unnamed);
        }
        String last = Uri.parse(root).getLastPathSegment();
        if (last == null || last.trim().isEmpty()) return root;
        int colon = last.lastIndexOf(':');
        return colon >= 0 && colon < last.length() - 1 ? last.substring(colon + 1) : last;
    }
}
