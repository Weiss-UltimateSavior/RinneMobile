package com.apps;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.yuki.yukihub.MainActivity;
import com.yuki.yukihub.data.GameRepository;
import com.yuki.yukihub.databinding.FragmentLauncherManageBinding;
import com.yuki.yukihub.model.EngineType;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.scanner.GameScanner;
import com.yuki.yukihub.scanner.ScanResult;
import com.yuki.yukihub.util.AppExecutors;

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
        Toast.makeText(requireContext(), "已添加目录", Toast.LENGTH_SHORT).show();
    }

    private void scanConfiguredDirectories() {
        List<String> roots = getActiveScanRootUris();
        if (roots.isEmpty()) {
            Toast.makeText(requireContext(), "请先添加目录", Toast.LENGTH_SHORT).show();
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
                Toast.makeText(
                        requireContext(),
                        "扫描完成：新增 " + stats.added + " 个，已存在 " + stats.skipped + " 个",
                        Toast.LENGTH_SHORT
                ).show();
            });
        });
    }

    private ScanImportStats importScannedGames(GameRepository repository, List<ScanResult> results) {
        ScanImportStats stats = new ScanImportStats();
        if (repository == null || results == null || results.isEmpty()) return stats;
        Set<String> existing = repository.getRootUriKeySet();
        for (ScanResult result : results) {
            if (result == null || result.uri == null || result.uri.trim().isEmpty()) continue;
            String rootKey = GameRepository.normalizeRootUriKey(result.uri);
            if (existing.contains(rootKey)) {
                stats.skipped++;
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
            } else {
                stats.skipped++;
            }
        }
        return stats;
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

    private static final class ScanImportStats {
        int added;
        int skipped;
    }
}
