package com.yuki.yukihub.launcherbridge;

import android.content.Context;
import android.net.Uri;

import com.yuki.yukihub.data.GameRepository;
import com.yuki.yukihub.model.EngineType;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.scanner.GameScanner;
import com.yuki.yukihub.scanner.ScanResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class LauncherScanBridge {
    private LauncherScanBridge() {
    }

    public static ImportStats scanAndImport(Context context, List<String> roots, int depth) {
        ImportStats stats = new ImportStats();
        if (context == null || roots == null || roots.isEmpty()) return stats;
        Context appContext = context.getApplicationContext();
        GameRepository repository = new GameRepository(appContext);
        List<ScanResult> results = new ArrayList<>();
        for (String root : roots) {
            if (root == null || root.trim().isEmpty()) continue;
            try {
                results.addAll(GameScanner.scan(appContext, Uri.parse(root), depth));
            } catch (Throwable ignored) {
            }
        }
        importScannedGames(repository, results, stats);
        return stats;
    }

    private static void importScannedGames(GameRepository repository, List<ScanResult> results, ImportStats stats) {
        stats.scanned = results == null ? 0 : results.size();
        if (repository == null || results == null || results.isEmpty()) return;
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
    }

    private static String emulatorPackageForEngine(EngineType engine) {
        if (engine == EngineType.KIRIKIRI) return "internal.krkr";
        if (engine == EngineType.ONS) return "internal.ons";
        if (engine == EngineType.TYRANO) return "internal.tyrano";
        if (engine == EngineType.PSP) return "org.ppsspp.ppsspp";
        return "";
    }

    private static String defaultLaunchTargetForEngine(EngineType engine) {
        return "[游戏目录]";
    }

    private static String emptyText(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    public static final class ImportStats {
        public int scanned;
        public int added;
        public int skipped;
        public int failed;
        public final List<String> addedItems = new ArrayList<>();
        public final List<String> skippedItems = new ArrayList<>();
        public final List<String> failedItems = new ArrayList<>();
    }
}
