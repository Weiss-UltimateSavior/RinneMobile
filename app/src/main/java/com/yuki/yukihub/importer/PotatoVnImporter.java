package com.yuki.yukihub.importer;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * PotatoVN ZIP 导入器。
 *
 * PotatoVN 导出的 ZIP 包含：
 * - data.galgames.json：游戏列表（核心数据）
 * - 封面图片文件（路径由 ImagePath 字段指定）
 *
 * 每条 Galgame 记录包含：名字、路径、开发商、简介、评分、标签、
 * 游玩记录（PlayedTime: {"2024/1/1": 120} 表示这天玩了 120 分钟）、
 * 数据源类型（RssType: 0=VNDB, 1=Bangumi, 5=ymgal, 7=steam）和数据源 ID（Ids 数组）。
 *
 * Android 上 path（Windows 路径）无效，导入时不设 rootUri，
 * 以"无路径游戏"方式存入。
 */
public final class PotatoVnImporter {

    private static final String TAG = "PotatoVnImporter";

    /** PotatoVN 默认图标路径（不导入） */
    private static final String DEFAULT_IMAGE_PATH = "ms-appx:///Assets/WindowIcon.ico";

    private PotatoVnImporter() {
    }

    /**
     * 从 SAF Uri 读取 PotatoVN ZIP 并解析成候选项列表。
     * 封面图片解压到应用缓存目录，路径写入 coverLocalPath。
     * 在后台线程调用。
     */
    public static List<ImportGameData> parse(Context context, Uri uri) throws Exception {
        File tempDir = new File(context.getCacheDir(),
                "potatovn_import_" + System.currentTimeMillis());
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            throw new Exception("无法创建缓存目录");
        }
        ImporterIO.registerTempDir(tempDir);

        String galgamesJson;
        try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new Exception("无法打开 ZIP 文件");
            galgamesJson = extractZip(raw, tempDir);
        }

        if (galgamesJson == null) {
            throw new Exception("ZIP 中未找到 data.galgames.json");
        }

        JSONArray arr = new JSONArray(galgamesJson);
        List<ImportGameData> result = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            ImportGameData g = convertFromPotatoVn(o, tempDir);
            if (g != null && g.name != null && !g.name.trim().isEmpty()) {
                g.name = g.name.trim();
                result.add(g);
            }
        }

        // 临时目录由 ImporterIO 统一注册，待 ImporterService 写库完成后清理。
        // 不再使用 deleteOnExit() —— Android 进程几乎不退出，shutdown hook 不会执行。
        return result;
    }

    private static String extractZip(InputStream raw, File tempDir) throws Exception {
        String galgamesJson = null;
        // 累计字节计数器：单条 entry 检查 MAX_ENTRY_BYTES，累计检查 MAX_TOTAL_BYTES
        ImporterIO.ReadAccumulator acc = new ImporterIO.ReadAccumulator(ImporterIO.MAX_TOTAL_BYTES);
        int entryCount = 0;
        ZipInputStream zis = new ZipInputStream(raw);
        try {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                // N6: ZIP entry 数量上限，防止恶意 ZIP 用海量空 entry 耗尽资源
                if (++entryCount > ImporterIO.MAX_ENTRY_COUNT) {
                    throw new IOException("ZIP entry 数量超过上限 " + ImporterIO.MAX_ENTRY_COUNT);
                }
                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }
                String name = entry.getName();
                if ("data.galgames.json".equals(name)) {
                    galgamesJson = ImporterIO.readString(zis,
                            ImporterIO.MAX_ENTRY_BYTES, StandardCharsets.UTF_8, acc);
                } else if (isImageFile(name)) {
                    extractImage(zis, tempDir, name, acc);
                } else {
                    // 未知 entry，分块跳过以维持流进度，不分配大缓冲区
                    ImporterIO.skipFully(zis, ImporterIO.MAX_ENTRY_BYTES);
                }
                zis.closeEntry();
            }
        } finally {
            zis.close();
        }
        return galgamesJson;
    }

    private static ImportGameData convertFromPotatoVn(JSONObject o, File tempDir) {
        ImportGameData g = new ImportGameData();
        g.name = pickDisplayName(o);
        if (g.name == null || g.name.trim().isEmpty()) return null;

        g.originalName = optLockableString(o, "OriginalName");
        g.developer = optLockableString(o, "Developer");
        g.description = optLockableString(o, "Description");
        g.releaseDate = optLockableDate(o);
        g.rating = optLockableDouble(o, "Rating");
        g.tags = optLockableStringList(o, "Tags");

        // 封面：imagePath 来自 ZIP 内 JSON，攻击者可控，必须做 canonical path 校验
        String imagePath = optLockableString(o, "ImagePath");
        if (imagePath != null && !imagePath.isEmpty()
                && !DEFAULT_IMAGE_PATH.equals(imagePath)) {
            if (imagePath.startsWith("http://") || imagePath.startsWith("https://")) {
                g.coverUrl = imagePath;
            } else {
                File coverFile = resolveSafeChild(tempDir, imagePath);
                if (coverFile != null && coverFile.exists()) {
                    g.coverLocalPath = coverFile.getAbsolutePath();
                }
            }
        }

        // 游戏路径（Windows 路径，Android 上无效）
        String exePath = o.optString("ExePath", "");
        if (exePath != null && !exePath.isEmpty() && !"null".equals(exePath)) {
            g.path = exePath.trim();
        }
        String savePath = o.optString("SavePath", "");
        if (savePath != null && !savePath.isEmpty() && !"null".equals(savePath)) {
            g.savePath = savePath.trim();
        }

        // 数据源
        int rssType = o.optInt("RssType", 3);
        g.sourceType = mapRssType(rssType);
        g.sourceId = pickSourceId(o, rssType);

        // 创建时间
        g.createdAt = parseFlexTime(o.optString("AddTime", ""));

        // 游玩记录：PlayedTime {"2024/1/1": 120}
        JSONObject playedTime = o.optJSONObject("PlayedTime");
        if (playedTime != null && playedTime.length() > 0) {
            g.playedTimeMap = new LinkedHashMap<>();
            for (java.util.Iterator<String> it = playedTime.keys(); it.hasNext(); ) {
                String dateStr = it.next();
                int minutes = playedTime.optInt(dateStr, 0);
                if (minutes > 0) g.playedTimeMap.put(dateStr, minutes);
            }
        }

        // PotatoVN 存秒
        g.totalPlayTime = o.optLong("TotalPlayTime", 0);

        return g;
    }

    // ==================== PotatoVN 字段提取辅助 ====================

    /** 优先中文名 > 原始名 */
    private static String pickDisplayName(JSONObject o) {
        String cn = optLockableString(o, "ChineseName");
        if (cn != null && !cn.trim().isEmpty()) return cn.trim();
        String name = optLockableString(o, "Name");
        if (name != null && !name.trim().isEmpty()) return name.trim();
        return "";
    }

    /**
     * PotatoVN 的可锁定属性格式：{"Value": "xxx", "IsLock": false}
     */
    private static String optLockableString(JSONObject o, String key) {
        JSONObject prop = o.optJSONObject(key);
        if (prop == null) return "";
        String val = prop.optString("Value", "");
        return val == null ? "" : val;
    }

    private static double optLockableDouble(JSONObject o, String key) {
        JSONObject prop = o.optJSONObject(key);
        if (prop == null) return 0;
        return prop.optDouble("Value", 0);
    }

    private static List<String> optLockableStringList(JSONObject o, String key) {
        JSONObject prop = o.optJSONObject(key);
        if (prop == null) return null;
        JSONArray arr = prop.optJSONArray("Value");
        if (arr == null) return null;
        List<String> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "");
            if (!s.trim().isEmpty()) list.add(s.trim());
        }
        return list.isEmpty() ? null : list;
    }

    private static String optLockableDate(JSONObject o) {
        JSONObject prop = o.optJSONObject("ReleaseDate");
        if (prop == null) return "";
        String raw = prop.optString("Value", "");
        if (raw == null || raw.isEmpty() || "null".equals(raw)) return "";
        for (String fmt : new String[]{"yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd"}) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(fmt, Locale.US);
                sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                Date d = sdf.parse(raw);
                if (d != null) {
                    return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d);
                }
            } catch (Exception ignored) {
            }
        }
        return raw;
    }

    /** 从 Ids 数组按 RssType 位置取 SourceID */
    private static String pickSourceId(JSONObject o, int rssType) {
        JSONArray ids = o.optJSONArray("Ids");
        if (ids == null || ids.length() == 0) return "";
        if (rssType >= 0 && rssType < ids.length()) {
            String id = ids.optString(rssType, "");
            if (!id.isEmpty()) return id;
        }
        // 兜底取第一个非空
        for (int i = 0; i < ids.length(); i++) {
            String id = ids.optString(i, "");
            if (!id.isEmpty()) return id;
        }
        return "";
    }

    private static String mapRssType(int rssType) {
        switch (rssType) {
            case 0:
                return "vndb";
            case 1:
                return "bangumi";
            case 5:
                return "ymgal";
            case 7:
                return "steam";
            default:
                return "local";
        }
    }

    private static long parseFlexTime(String raw) {
        if (raw == null || raw.isEmpty() || "null".equals(raw)) {
            return System.currentTimeMillis();
        }
        for (String fmt : new String[]{
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd"
        }) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(fmt, Locale.US);
                Date d = sdf.parse(raw);
                if (d != null) return d.getTime();
            } catch (Exception ignored) {
            }
        }
        Log.w(TAG, "无法解析时间，fallback 当前时间: " + raw);
        return System.currentTimeMillis();
    }

    // ==================== ZIP 辅助 ====================

    private static boolean isImageFile(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp");
    }

    /**
     * 解析 ZIP entry name（或 JSON 中的 imagePath）为 tempDir 内的安全子路径。
     * 通过 canonical path 校验防止 zip slip 路径穿越。
     * 返回 null 表示路径非法或解析失败。
     */
    private static File resolveSafeChild(File tempDir, String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) return null;
        String normalized = rawPath.replace("\\", "/").replaceAll("^/+", "");
        File outFile = new File(tempDir, normalized);
        try {
            ImporterIO.ensurePathInside(outFile, tempDir);
            return outFile;
        } catch (IOException e) {
            Log.w(TAG, "拒绝跨目录路径: " + rawPath, e);
            return null;
        }
    }

    private static void extractImage(ZipInputStream zis, File tempDir, String entryName,
                                     ImporterIO.ReadAccumulator acc) throws Exception {
        File outFile = resolveSafeChild(tempDir, entryName);
        if (outFile == null) {
            // N5: 路径非法时不再 readBytes 分配 50MB byte[]，改为 skipFully 分块跳过
            ImporterIO.skipFully(zis, ImporterIO.MAX_ENTRY_BYTES);
            return;
        }
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new Exception("无法创建目录: " + parent.getAbsolutePath());
        }
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            byte[] data = ImporterIO.readBytes(zis, ImporterIO.MAX_ENTRY_BYTES, acc);
            fos.write(data);
        }
    }
}
