package com.yuki.yukihub.scanner;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class GameScanner {
    private static final String TAG = "GameScanner";
    /** Traverse every descendant directory regardless of depth. */
    public static final int SCAN_ALL_LEVELS = -1;
    /** Traverse descendants until a directory is identified as a game, then stop below it. */
    public static final int SCAN_UNTIL_GAME_MATCH = -2;

    public static List<ScanResult> scan(Context context, Uri rootUri) {
        return scan(context, rootUri, 2);
    }

    public static List<ScanResult> scan(Context context, Uri rootUri, int maxDepth) {
        return scan(context, rootUri, ScanRequest.defaults(maxDepth)).getResults();
    }

    /** Runs a bounded scan and returns both partial results and stop/error information. */
    public static ScanReport scan(Context context, Uri rootUri, ScanRequest request) {
        ScanReport report = new ScanReport();
        Set<String> seenUris = new HashSet<>();
        if (context == null || rootUri == null) {
            report.setStopReason(ScanReport.StopReason.INVALID_ROOT);
            report.addError("扫描目录不可用");
            return report;
        }
        ScanRequest safeRequest = request == null ? ScanRequest.defaults(2) : request;
        int requestedDepth = safeRequest.getMaxDepth();
        boolean scanAllLevels = requestedDepth == SCAN_ALL_LEVELS || requestedDepth == SCAN_UNTIL_GAME_MATCH;
        boolean stopAtGameMatch = requestedDepth == SCAN_UNTIL_GAME_MATCH;
        int depth = scanAllLevels ? Integer.MAX_VALUE : Math.max(1, Math.min(4, requestedDepth));

        DocumentFile root;
        try {
            root = DocumentFile.fromTreeUri(context, rootUri);
        } catch (Throwable t) {
            Log.w(TAG, "fromTreeUri failed uri=" + rootUri, t);
            report.setStopReason(ScanReport.StopReason.INVALID_ROOT);
            report.addError("无法访问扫描目录：" + rootUri);
            return report;
        }
        if (root == null) {
            report.setStopReason(ScanReport.StopReason.INVALID_ROOT);
            report.addError("扫描目录不存在：" + rootUri);
            return report;
        }
        try {
            if (!root.isDirectory()) {
                report.setStopReason(ScanReport.StopReason.INVALID_ROOT);
                report.addError("扫描目标不是目录：" + rootUri);
                return report;
            }
        } catch (Throwable t) {
            Log.w(TAG, "root isDirectory failed uri=" + rootUri, t);
            report.setStopReason(ScanReport.StopReason.INVALID_ROOT);
            report.addError("无法读取扫描目录：" + rootUri);
            return report;
        }
        // A user may select one game directory itself rather than its parent. Probe the
        // root before traversing children so that Kirikiri/ONS/Tyrano/Artemis roots are
        // not skipped merely because they have no game-directory child.
        boolean rootGameMatched = detectGameDirectory(root, report, seenUris, safeRequest);
        if (!(stopAtGameMatch && rootGameMatched)) {
            scanChildren(root, 1, depth, stopAtGameMatch, report, seenUris, safeRequest);
        }
        return report;
    }

    private static boolean detectGameDirectory(DocumentFile dir, ScanReport report, Set<String> seenUris, ScanRequest request) {
        if (dir == null || report == null || !report.tryVisit(request, safeUri(dir))) return false;
        try {
            EngineDetector.Result detected = EngineDetector.detect(dir, 2);
            if (detected == null || detected.confidence <= 0 || !isRootDirectoryEngine(detected.engine)) return false;
            String uri = dir.getUri().toString();
            if (markSeen(seenUris, uri)) {
                report.addResult(new ScanResult(safeName(dir), uri, detected.engine, detected.confidence,
                        detected.launchTarget, "", detected.xp3Candidates));
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "root engine detection failed uri=" + safeUri(dir), t);
            report.addError("识别扫描目录失败：" + safeUri(dir));
            return false;
        }
    }

    /** PSP and Winlator roots are already emitted per entry file, not as a directory entry. */
    private static boolean isRootDirectoryEngine(com.yuki.yukihub.model.EngineType engine) {
        return engine == com.yuki.yukihub.model.EngineType.KIRIKIRI
                || engine == com.yuki.yukihub.model.EngineType.ONS
                || engine == com.yuki.yukihub.model.EngineType.TYRANO
                || engine == com.yuki.yukihub.model.EngineType.ARTEMIS;
    }

    private static void scanChildren(DocumentFile dir, int level, int maxDepth, boolean stopAtGameMatch, ScanReport report, Set<String> seenUris, ScanRequest request) {
        if (dir == null || report == null || report.shouldStop(request)) return;
        DocumentFile[] children;
        try {
            children = dir.listFiles();
        } catch (Throwable t) {
            Log.w(TAG, "listFiles failed uri=" + safeUri(dir), t);
            report.addError("无法读取目录：" + safeUri(dir));
            return;
        }
        if (children == null) return;

        for (DocumentFile child : children) {
            if (!report.tryVisit(request, safeUri(child))) return;
            if (child == null) continue;
            try {
                if (child.isFile()) {
                    String name = safeName(child);
                    String lowerName = name.toLowerCase(Locale.ROOT);
                    // 情况1：单个PSP文件在根目录
                    if (lowerName.endsWith(".iso") || lowerName.endsWith(".cso") || lowerName.endsWith(".chd") ||
                        lowerName.endsWith(".elf") || lowerName.endsWith(".pbp")) {
                        addPspFileResult(report, seenUris, child, name);
                        continue;
                    }
                    // 情况1：单个 Nintendo 3DS 文件在根目录
                    if (isN3dsFile(lowerName)) {
                        addN3dsFileResult(report, seenUris, child, name);
                        continue;
                    }
                    if (lowerName.endsWith(".desktop")) {
                        addDesktopResult(report, seenUris, stripDesktopSuffix(name), child.getUri().toString(), name, "");
                    }
                    continue;
                }
                if (!child.isDirectory()) continue;

                // 识别目录本身的 PSP / desktop 入口；是否继续遍历由扫描模式决定。
                // 全层模式会继续扫描嵌套游戏，命中模式则在识别游戏目录后停止向下。
                boolean pspDirectory = tryAddPspDirectory(child, report, seenUris);
                boolean n3dsDirectory = tryAddN3dsDirectory(child, report, seenUris);
                boolean desktopDirectory = tryAddDesktopDirectory(child, report, seenUris);

                String childName = safeName(child).toLowerCase(Locale.ROOT);
                boolean internalAssetDir = isInternalAssetDir(childName);

                boolean gameDirectoryMatched = pspDirectory || n3dsDirectory || desktopDirectory;
                if (!internalAssetDir && !gameDirectoryMatched) {
                    EngineDetector.Result detected = EngineDetector.detect(child, 2);
                    if (detected != null && detected.confidence > 0) {
                        String uri = child.getUri().toString();
                        if (markSeen(seenUris, uri)) {
                            report.addResult(new ScanResult(safeName(child), uri, detected.engine, detected.confidence,
                                    detected.launchTarget, "", detected.xp3Candidates));
                        }
                        gameDirectoryMatched = true;
                    }
                }

                if (level < maxDepth && !(stopAtGameMatch && gameDirectoryMatched)) {
                    scanChildren(child, level + 1, maxDepth, stopAtGameMatch, report, seenUris, request);
                }
            } catch (Throwable t) {
                Log.w(TAG, "scan child failed uri=" + safeUri(child), t);
                report.addError("扫描项目失败：" + safeUri(child));
            }
        }
    }

    private static boolean tryAddDesktopDirectory(DocumentFile dir, ScanReport report, Set<String> seenUris) {
        if (dir == null || report == null) return false;
        try {
            DocumentFile[] files = dir.listFiles();
            if (files == null || files.length == 0) return false;

            List<DocumentFile> desktops = new ArrayList<>();
            for (DocumentFile f : files) {
                if (f == null || !f.isFile()) continue;
                String name = safeName(f).toLowerCase(Locale.ROOT);
                if (name.endsWith(".desktop")) desktops.add(f);
            }
            if (desktops.isEmpty()) return false;

            String coverUri = "";
            DocumentFile folderCover = findBestImageInDir(dir);
            if (folderCover != null) coverUri = folderCover.getUri().toString();

            if (desktops.size() == 1) {
                // 情况2：文件夹内只有一个 desktop，标题取文件夹名，但入口仍然是 .desktop 文件本身。
                DocumentFile desktop = desktops.get(0);
                return addDesktopResult(report, seenUris, safeName(dir), desktop.getUri().toString(), safeName(desktop), coverUri);
            }

            // 情况3：文件夹里有多个 desktop，按多个单独条目识别。
            boolean added = false;
            for (DocumentFile desktop : desktops) {
                String name = safeName(desktop);
                added |= addDesktopResult(report, seenUris, stripDesktopSuffix(name), desktop.getUri().toString(), name, coverUri);
            }
            return added;
        } catch (Throwable t) {
            Log.w(TAG, "tryAddDesktopDirectory failed uri=" + safeUri(dir), t);
            return false;
        }
    }

    private static boolean addDesktopResult(ScanReport report, Set<String> seenUris, String title, String resultUri, String launchTarget, String coverUri) {
        if (report == null || resultUri == null || !markSeen(seenUris, resultUri)) return false;
        report.addResult(new ScanResult(
                title == null || title.trim().isEmpty() ? "未命名游戏" : title,
                resultUri,
                com.yuki.yukihub.model.EngineType.WINLATOR,
                90,
                launchTarget,
                coverUri
        ));
        return true;
    }

    private static boolean addPspFileResult(ScanReport report, Set<String> seenUris, DocumentFile pspFile, String fileName) {
        if (report == null || pspFile == null) return false;
        String uri = pspFile.getUri().toString();
        if (!markSeen(seenUris, uri)) return false;
        
        // 从文件名中提取游戏标题（去掉扩展名）
        String title = fileName;
        int dotIndex = title.lastIndexOf('.');
        if (dotIndex > 0) {
            title = title.substring(0, dotIndex);
        }
        
        report.addResult(new ScanResult(
                title == null || title.trim().isEmpty() ? "未命名PSP游戏" : title,
                uri,
                com.yuki.yukihub.model.EngineType.PSP,
                95,
                fileName, // launchTarget设置为文件名
                ""
        ));
        return true;
    }

    /**
     * 尝试添加文件夹里的PSP游戏文件
     * 情况2：文件夹里只有1个PSP文件，游戏名取文件夹名，但入口仍然是PSP文件本身
     * 情况3：文件夹里有多个PSP文件，按多个单独条目识别
     */
    private static boolean tryAddPspDirectory(DocumentFile dir, ScanReport report, Set<String> seenUris) {
        if (dir == null || report == null) return false;
        try {
            DocumentFile[] files = dir.listFiles();
            if (files == null || files.length == 0) return false;

            List<DocumentFile> pspFiles = new ArrayList<>();
            for (DocumentFile f : files) {
                if (f == null || !f.isFile()) continue;
                String name = safeName(f).toLowerCase(Locale.ROOT);
                if (name.endsWith(".iso") || name.endsWith(".cso") || name.endsWith(".chd") || 
                    name.endsWith(".elf") || name.endsWith(".pbp")) {
                    pspFiles.add(f);
                }
            }
            if (pspFiles.isEmpty()) return false;

            String coverUri = "";
            DocumentFile folderCover = findBestImageInDir(dir);
            if (folderCover != null) coverUri = folderCover.getUri().toString();

            if (pspFiles.size() == 1) {
                // 情况2：文件夹内只有一个 PSP文件，标题取文件夹名，但入口仍然是 PSP文件本身。
                DocumentFile pspFile = pspFiles.get(0);
                return addPspFileResultWithCover(report, seenUris, safeName(dir), pspFile.getUri().toString(), safeName(pspFile), coverUri);
            }

            // 情况3：文件夹里有多个 PSP文件，按多个单独条目识别。
            boolean added = false;
            for (DocumentFile pspFile : pspFiles) {
                String name = safeName(pspFile);
                String title = name;
                int dotIndex = title.lastIndexOf('.');
                if (dotIndex > 0) {
                    title = title.substring(0, dotIndex);
                }
                added |= addPspFileResultWithCover(report, seenUris, title, pspFile.getUri().toString(), name, coverUri);
            }
            return added;
        } catch (Throwable t) {
            Log.w(TAG, "tryAddPspDirectory failed uri=" + safeUri(dir), t);
            return false;
        }
    }

    private static boolean addPspFileResultWithCover(ScanReport report, Set<String> seenUris, String title, String resultUri, String launchTarget, String coverUri) {
        if (report == null || resultUri == null || !markSeen(seenUris, resultUri)) return false;
        report.addResult(new ScanResult(
                title == null || title.trim().isEmpty() ? "未命名PSP游戏" : title,
                resultUri,
                com.yuki.yukihub.model.EngineType.PSP,
                95,
                launchTarget,
                coverUri
        ));
        return true;
    }

    private static boolean isN3dsFile(String lowerName) {
        if (lowerName == null) return false;
        return lowerName.endsWith(".3ds") || lowerName.endsWith(".cci") || lowerName.endsWith(".zcci") ||
                lowerName.endsWith(".cxi") || lowerName.endsWith(".zcxi") || lowerName.endsWith(".cia") ||
                lowerName.endsWith(".zcia") || lowerName.endsWith(".3dsx") || lowerName.endsWith(".z3dsx");
    }

    private static boolean addN3dsFileResult(ScanReport report, Set<String> seenUris, DocumentFile n3dsFile, String fileName) {
        if (report == null || n3dsFile == null) return false;
        String uri = n3dsFile.getUri().toString();
        if (!markSeen(seenUris, uri)) return false;

        // 从文件名中提取游戏标题（去掉扩展名）
        String title = fileName;
        int dotIndex = title.lastIndexOf('.');
        if (dotIndex > 0) {
            title = title.substring(0, dotIndex);
        }

        report.addResult(new ScanResult(
                title == null || title.trim().isEmpty() ? "未命名3DS游戏" : title,
                uri,
                com.yuki.yukihub.model.EngineType.NINTENDO_3DS,
                95,
                fileName,
                ""
        ));
        return true;
    }

    /**
     * 尝试添加文件夹里的 Nintendo 3DS 游戏文件
     * 情况2：文件夹里只有1个3DS文件，游戏名取文件夹名，但入口仍然是3DS文件本身
     * 情况3：文件夹里有多个3DS文件，按多个单独条目识别
     */
    private static boolean tryAddN3dsDirectory(DocumentFile dir, ScanReport report, Set<String> seenUris) {
        if (dir == null || report == null) return false;
        try {
            DocumentFile[] files = dir.listFiles();
            if (files == null || files.length == 0) return false;

            List<DocumentFile> n3dsFiles = new ArrayList<>();
            for (DocumentFile f : files) {
                if (f == null || !f.isFile()) continue;
                String name = safeName(f).toLowerCase(Locale.ROOT);
                if (isN3dsFile(name)) {
                    n3dsFiles.add(f);
                }
            }
            if (n3dsFiles.isEmpty()) return false;

            String coverUri = "";
            DocumentFile folderCover = findBestImageInDir(dir);
            if (folderCover != null) coverUri = folderCover.getUri().toString();

            if (n3dsFiles.size() == 1) {
                // 情况2：文件夹内只有一个 3DS 文件，标题取文件夹名，但入口仍然是 3DS 文件本身。
                DocumentFile n3dsFile = n3dsFiles.get(0);
                return addN3dsFileResultWithCover(report, seenUris, safeName(dir), n3dsFile.getUri().toString(), safeName(n3dsFile), coverUri);
            }

            // 情况3：文件夹里有多个 3DS 文件，按多个单独条目识别。
            boolean added = false;
            for (DocumentFile n3dsFile : n3dsFiles) {
                String name = safeName(n3dsFile);
                String title = name;
                int dotIndex = title.lastIndexOf('.');
                if (dotIndex > 0) {
                    title = title.substring(0, dotIndex);
                }
                added |= addN3dsFileResultWithCover(report, seenUris, title, n3dsFile.getUri().toString(), name, coverUri);
            }
            return added;
        } catch (Throwable t) {
            Log.w(TAG, "tryAddN3dsDirectory failed uri=" + safeUri(dir), t);
            return false;
        }
    }

    private static boolean addN3dsFileResultWithCover(ScanReport report, Set<String> seenUris, String title, String resultUri, String launchTarget, String coverUri) {
        if (report == null || resultUri == null || !markSeen(seenUris, resultUri)) return false;
        report.addResult(new ScanResult(
                title == null || title.trim().isEmpty() ? "未命名3DS游戏" : title,
                resultUri,
                com.yuki.yukihub.model.EngineType.NINTENDO_3DS,
                95,
                launchTarget,
                coverUri
        ));
        return true;
    }

    private static DocumentFile findBestImageInDir(DocumentFile dir) {
        if (dir == null || !dir.isDirectory()) return null;
        try {
            DocumentFile[] files = dir.listFiles();
            if (files == null) return null;
            DocumentFile best = null;
            int bestScore = Integer.MIN_VALUE;
            for (DocumentFile f : files) {
                if (f == null || !f.isFile()) continue;
                String name = safeName(f);
                if (!isImageFile(name)) continue;
                int score = coverNameScore(name);
                if (best == null || score > bestScore) {
                    best = f;
                    bestScore = score;
                }
            }
            return best;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isImageFile(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".bmp");
    }

    private static int coverNameScore(String name) {
        if (name == null) return 0;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals("cover.jpg") || lower.equals("cover.png") || lower.equals("cover.webp")) return 100;
        if (lower.equals("folder.jpg") || lower.equals("folder.png") || lower.equals("folder.webp")) return 95;
        if (lower.contains("cover") || lower.contains("folder") || lower.contains("封面")) return 80;
        if (lower.contains("poster") || lower.contains("package") || lower.contains("main")) return 60;
        return 10;
    }

    private static boolean isInternalAssetDir(String name) {
        if (name == null) return false;
        return name.equals("data") || name.equals("tyrano") || name.equals("resources") || name.equals("arc")
                || name.equals("scenario") || name.equals("system") || name.equals("bgimage") || name.equals("fgimage")
                || name.equals("image") || name.equals("sound") || name.equals("bgm") || name.equals("voice") || name.equals("video")
                || name.equals("movie") || name.equals("font") || name.equals("others") || name.equals("app");
    }

    private static String safeName(DocumentFile file) {
        try {
            String name = file == null ? null : file.getName();
            return name == null || name.trim().isEmpty() ? "未命名游戏" : name;
        } catch (Throwable t) {
            Log.w(TAG, "safeName failed uri=" + safeUri(file), t);
            return "未命名游戏";
        }
    }

    private static boolean markSeen(Set<String> seenUris, String uri) {
        if (seenUris == null) return true;
        String key = com.yuki.yukihub.data.GameRepository.normalizeRootUriKey(uri);
        if (key.isEmpty()) return true;
        return seenUris.add(key);
    }

    private static String stripDesktopSuffix(String name) {
        if (name == null) return "未命名游戏";
        return name.toLowerCase(Locale.ROOT).endsWith(".desktop") ? name.substring(0, Math.max(0, name.length() - 8)) : name;
    }

    private static String safeUri(DocumentFile file) {
        try {
            return file == null || file.getUri() == null ? "null" : file.getUri().toString();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }
}
