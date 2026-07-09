package com.yuki.yukihub.launcherbridge;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.DocumentsContract;

import com.yuki.yukihub.data.GameRepository;
import com.yuki.yukihub.model.EngineType;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.scanner.GameScanner;
import com.yuki.yukihub.scanner.ScanResult;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
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
            } catch (SecurityException e) {
                stats.failed++;
                stats.failedItems.add("目录权限已失效，请重新添加：" + simplifyUri(root));
            } catch (Throwable ignored) {
            }
        }
        importScannedGames(appContext, repository, results, stats);
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
            game.emulatorPackage = emulatorPackageForEngine(result.engine);
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
