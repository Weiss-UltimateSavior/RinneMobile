package com.yuki.yukihub.launcherbridge;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.DocumentsContract;

import com.yuki.yukihub.data.GameRepository;
import com.yuki.yukihub.model.EngineType;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.scanner.EngineDetector;
import com.yuki.yukihub.scanner.GameScanner;
import com.yuki.yukihub.scanner.ScanRequest;
import com.yuki.yukihub.scanner.ScanReport;
import com.yuki.yukihub.scanner.ScanResult;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class LauncherScanBridge {
    private LauncherScanBridge() {
    }

    /** Scan depth constant: scan all levels. Mirrors GameScanner.SCAN_ALL_LEVELS. */
    public static final int SCAN_ALL_LEVELS = -1;

    /** Scan depth constant: scan until first game match. Mirrors GameScanner.SCAN_UNTIL_GAME_MATCH. */
    public static final int SCAN_UNTIL_GAME_MATCH = -2;

    /**
     * Detects the engine of a game directory by probing its file features.
     * Returns a DetectionResult with UNKNOWN engine and 0 confidence on failure.
     */
    public static DetectionResult detectEngine(DocumentFile dir, int featureDepth) {
        DetectionResult out = new DetectionResult();
        if (dir == null) return out;
        try {
            EngineDetector.Result source = EngineDetector.detect(dir, featureDepth);
            if (source == null) return out;
            out.engine = source.engine == null ? EngineType.UNKNOWN : source.engine;
            out.confidence = source.confidence;
            out.launchTarget = source.launchTarget == null ? "" : source.launchTarget;
            out.rpgMakerSubtype = source.rpgMakerSubtype == null ? "" : source.rpgMakerSubtype;
            out.renpySubtype = source.renpySubtype == null ? "" : source.renpySubtype;
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** Value class equivalent to EngineDetector.Result, exposed without importing scanner layer. */
    public static final class DetectionResult {
        public EngineType engine = EngineType.UNKNOWN;
        public int confidence = 0;
        public String launchTarget = "";
        /**
         * 仅当 engine == RPGMAKER 时有意义。取值：
         * "rpgmxp" / "rpgmvx" / "rpgmvxace" / "mkxp-z"。空串表示需用户自行决定。
         */
        public String rpgMakerSubtype = "";
        /**
         * 仅当 engine == RENPY 时有意义。取值："renpy" 或 "renpy8"。
         */
        public String renpySubtype = "";
    }

    public static ImportStats scanAndImport(Context context, List<String> roots, int depth) {
        return scanAndImport(context, roots, ScanRequest.defaults(depth)).importStats;
    }

    /** Controlled variant; callers can inspect partial discovery before or after importing it. */
    public static ScanAndImportResult scanAndImport(Context context, List<String> roots, ScanRequest request) {
        ScanBatchResult scan = scanWithReport(context, roots, request);
        ImportStats stats = new ImportStats();
        stats.scanStopReason = scan.getStopReason();
        stats.partialDiscovery = scan.isPartial();
        for (String error : scan.getErrors()) { stats.failed++; stats.failedItems.add(error); }
        // Partial discovery must never be silently persisted. UI callers must explicitly confirm
        // the returned ScanBatchResult and then call importScanResults(results).
        if (scan.isPartial()) {
            stats.failed++;
            stats.failedItems.add("扫描未完整结束，未自动导入；请确认后导入已发现结果。");
            return new ScanAndImportResult(scan, stats);
        }
        if (context != null) importScannedGames(context.getApplicationContext(), new GameRepository(context.getApplicationContext()), scan.getResults(), stats);
        return new ScanAndImportResult(scan, stats);
    }

    /** Performs discovery only. Callers may resolve {@link ScanResult#xp3Candidates} before importing. */
    public static List<ScanResult> scan(Context context, List<String> roots, int depth) {
        return scanWithReport(context, roots, ScanRequest.defaults(depth)).getResults();
    }

    /**
     * Performs bounded discovery across roots. Completed roots are retained when a later root
     * fails or the request is stopped, allowing callers to offer a partial import.
     */
    public static ScanBatchResult scanWithReport(Context context, List<String> roots, ScanRequest request) {
        ScanBatchResult batch = new ScanBatchResult();
        if (context == null || roots == null || roots.isEmpty()) return batch;
        Context appContext = context.getApplicationContext();
        ScanRequest safeRequest = request == null ? ScanRequest.defaults(2) : request;
        int initialVisitedNodes = safeRequest.getVisitedNodes();
        for (String root : roots) {
            if (root == null || root.trim().isEmpty()) continue;
            if (safeRequest.isCancelled()) {
                batch.stopReason = ScanReport.StopReason.CANCELLED;
                break;
            }
            if (safeRequest.isDeadlineReached()) {
                batch.stopReason = ScanReport.StopReason.DEADLINE;
                break;
            }
            if (safeRequest.isNodeLimitReached()) {
                batch.stopReason = ScanReport.StopReason.NODE_LIMIT;
                break;
            }
            try {
                ScanReport report = GameScanner.scan(appContext, Uri.parse(root), safeRequest);
                batch.results.addAll(report.getResults());
                batch.errors.addAll(report.getErrors());
                ScanReport.StopReason reason = report.getStopReason();
                // A bad SAF root is local to that root. Keep scanning other configured roots;
                // cancellation and shared resource limits stop the whole batch.
                if (reason.stopsBatch()) {
                    batch.stopReason = report.getStopReason();
                    break;
                }
            } catch (SecurityException e) {
                batch.errors.add("目录权限已失效，请重新添加：" + simplifyUri(root));
            } catch (Throwable t) {
                batch.errors.add("扫描目录失败：" + simplifyUri(root));
            }
        }
        batch.visitedNodes = Math.max(0, safeRequest.getVisitedNodes() - initialVisitedNodes);
        return batch;
    }

    /** Imports results after any interactive launch-target selection has been completed. */
    public static ImportStats importScanResults(Context context, List<ScanResult> results) {
        ImportStats stats = new ImportStats();
        if (context == null) return stats;
        importScannedGames(context.getApplicationContext(), new GameRepository(context.getApplicationContext()), results, stats);
        return stats;
    }

    private static String simplifyUri(String uri) {
        if (uri == null) return "";
        try {
            Uri parsed = Uri.parse(uri);
            String last = parsed.getLastPathSegment();
            return last != null ? last : uri;
        } catch (Throwable e) {
            return uri;
        }
    }

    private static void importScannedGames(Context context, GameRepository repository, List<ScanResult> results, ImportStats stats) {
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
            game.emulatorPackage = emulatorPackageForResult(result);
            Game restored = repository.findScannedMatch(game);
            if (restored != null) {
                if (!rootKey.equals(GameRepository.normalizeRootUriKey(restored.rootUri))) {
                    repository.bindScannedLocation(restored, game);
                }
                existing.add(rootKey);
                stats.skipped++;
                stats.skippedItems.add(emptyText(result.title, result.uri));
                continue;
            }
            long id = repository.insertIfNotExists(game);
            if (id > 0) {
                existing.add(rootKey);
                stats.added++;
                stats.addedItems.add(emptyText(result.title, result.uri));
                game.id = id;
                String cover = resolveLocalCover(context, result);
                if (cover != null) {
                    game.coverUri = cover;
                    game.coverPersistUri = cover;
                    game.coverSourceType = 1;
                    try { repository.update(game); } catch (Throwable ignored) {}
                } else {
                    LauncherCoverBridge.fetchCoverForGameAsync(context, game);
                }
            } else {
                stats.failed++;
                stats.failedItems.add(emptyText(result.title, result.uri));
            }
        }
    }

    private static String resolveLocalCover(Context context, ScanResult result) {
        if (result.coverUri != null && !result.coverUri.trim().isEmpty()) {
            String cover = copyCoverToInternalStorage(context, result.coverUri);
            if (cover != null) return cover;
        }
        String dirImage = findFirstImageInDir(context, result.uri);
        if (dirImage != null) {
            return copyCoverToInternalStorage(context, dirImage);
        }
        return null;
    }

    private static String findFirstImageInDir(Context context, String dirUri) {
        if (dirUri == null || dirUri.trim().isEmpty()) return null;
        try {
            Uri tree = Uri.parse(dirUri);
            String parentId = DocumentsContract.getTreeDocumentId(tree);
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
            try (android.database.Cursor cursor = context.getContentResolver().query(
                    childrenUri, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE},
                    null, null, null)) {
                if (cursor == null) return null;
                while (cursor.moveToNext()) {
                    String mime = cursor.getString(1);
                    if (mime != null && mime.startsWith("image/")) {
                        String docId = cursor.getString(0);
                        return DocumentsContract.buildDocumentUriUsingTree(tree, docId).toString();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static String copyCoverToInternalStorage(Context context, String sourceUriStr) {
        if (sourceUriStr == null || sourceUriStr.trim().isEmpty()) return null;
        InputStream is = null;
        try {
            Uri source = Uri.parse(sourceUriStr);
            is = context.getContentResolver().openInputStream(source);
            if (is == null) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(is, null, opts);
            is.close();
            is = context.getContentResolver().openInputStream(source);
            if (is == null) return null;
            int maxDim = Math.max(opts.outWidth, opts.outHeight);
            int sampleSize = 1;
            while (maxDim / sampleSize > 1440) sampleSize *= 2;
            BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
            decodeOpts.inSampleSize = sampleSize;
            Bitmap bitmap = BitmapFactory.decodeStream(is, null, decodeOpts);
            is.close();
            is = null;
            if (bitmap == null) return null;
            int maxPx = 720;
            float scale = Math.min(1f, (float) maxPx / Math.max(bitmap.getWidth(), bitmap.getHeight()));
            if (scale < 1f) {
                int nw = Math.round(bitmap.getWidth() * scale);
                int nh = Math.round(bitmap.getHeight() * scale);
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, nw, nh, true);
                bitmap.recycle();
                bitmap = scaled;
            }
            File dir = new File(context.getFilesDir(), "covers");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, "cover_" + System.currentTimeMillis() + ".jpg");
            FileOutputStream fos = new FileOutputStream(out);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, fos);
            fos.close();
            bitmap.recycle();
            return Uri.fromFile(out).toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (is != null) try { is.close(); } catch (Throwable ignored) {}
        }
    }

    private static String emulatorPackageForEngine(EngineType engine) {
        if (engine == EngineType.KIRIKIRI) return "internal.krkr";
        if (engine == EngineType.ONS) return "internal.ons";
        if (engine == EngineType.TYRANO) return "internal.tyrano";
        if (engine == EngineType.ARTEMIS) return "internal.artemis";
        if (engine == EngineType.PSP) return "org.ppsspp.ppsspp";
        if (engine == EngineType.NINTENDO_3DS) return "io.github.azaharplus.android";
        // Legacy/future scanner results without a subtype retain the conservative RPG XP fallback.
        if (engine == EngineType.RPGMAKER) return "internal.rpgmxp";
        if (engine == EngineType.RENPY) return "internal.renpy";
        return "";
    }

    static String emulatorPackageForResult(ScanResult result) {
        if (result == null) return "";
        if (result.engine == EngineType.RPGMAKER) {
            String subtype = normalizeSubtype(result.rpgMakerSubtype);
            if (subtype.equals("rpgmxp") || subtype.equals("rpgmvx")
                    || subtype.equals("rpgmvxace") || subtype.equals("mkxp-z")) {
                return "internal." + subtype;
            }
        } else if (result.engine == EngineType.RENPY) {
            String subtype = normalizeSubtype(result.renpySubtype);
            if (subtype.equals("renpy") || subtype.equals("renpy8")) {
                return "internal." + subtype;
            }
        }
        return emulatorPackageForEngine(result.engine);
    }

    private static String normalizeSubtype(String subtype) {
        return subtype == null ? "" : subtype.trim().toLowerCase(java.util.Locale.ROOT);
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
        /** Diagnostics for legacy scanAndImport callers. */
        public ScanReport.StopReason scanStopReason = ScanReport.StopReason.COMPLETED;
        public boolean partialDiscovery;
    }

    /** Aggregated outcome for a multi-root scan. Results can be imported even when partial. */
    public static final class ScanBatchResult {
        private final List<ScanResult> results = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();
        private int visitedNodes;
        private ScanReport.StopReason stopReason = ScanReport.StopReason.COMPLETED;

        public List<ScanResult> getResults() { return new ArrayList<>(results); }
        public List<String> getErrors() { return new ArrayList<>(errors); }
        public int getVisitedNodes() { return visitedNodes; }
        public ScanReport.StopReason getStopReason() { return stopReason; }
        public boolean isPartial() { return stopReason != ScanReport.StopReason.COMPLETED; }
    }

    public static final class ScanAndImportResult {
        public final ScanBatchResult scan;
        public final ImportStats importStats;
        ScanAndImportResult(ScanBatchResult scan, ImportStats importStats) { this.scan = scan; this.importStats = importStats; }
    }
}
