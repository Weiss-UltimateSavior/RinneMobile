package com.yuki.yukihub.importer;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Playnite JSON 导入器。
 *
 * Playnite 导出的是 JSON 数组，每个元素包含：
 * name, cover_url, company, summary, rating, release_date,
 * path, save_path, source_type, source_id, created_at
 *
 * 注意：Playnite 的 path 是 Windows 路径，Android 上不适用。
 * 导入时 path 留空，以"无路径游戏"方式存入（和 YukiHub 收藏但没设目录的游戏一样）。
 */
public final class PlayniteImporter {

    /** Playnite JSON 文件最大 50MB（足够容纳大型游戏库导出） */
    private static final long MAX_FILE_BYTES = ImporterIO.MAX_ENTRY_BYTES;

    private PlayniteImporter() {
    }

    /**
     * 从 SAF Uri 读取 Playnite JSON 并解析成候选项列表。
     * 在后台线程调用。
     */
    public static List<ImportGameData> parse(Context context, Uri uri) throws Exception {
        byte[] buf = readAllBytes(context, uri);

        // 去掉 UTF-8 BOM
        if (buf.length >= 3
                && buf[0] == (byte) 0xEF && buf[1] == (byte) 0xBB && buf[2] == (byte) 0xBF) {
            byte[] trimmed = new byte[buf.length - 3];
            System.arraycopy(buf, 3, trimmed, 0, trimmed.length);
            buf = trimmed;
        }

        String json = new String(buf, StandardCharsets.UTF_8);
        JSONArray arr = new JSONArray(json);

        List<ImportGameData> result = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            ImportGameData g = new ImportGameData();
            g.name = o.optString("name", "").trim();
            if (g.name.isEmpty()) continue;
            g.originalName = g.name;
            g.developer = o.optString("company", "").trim();
            g.description = o.optString("summary", "").trim();
            g.coverUrl = o.optString("cover_url", "").trim();
            g.releaseDate = o.optString("release_date", "").trim();
            g.rating = o.optDouble("rating", 0);
            g.path = o.optString("path", "").trim();
            g.savePath = o.optString("save_path", "").trim();
            g.sourceType = mapSourceType(o.optString("source_type", ""));
            g.sourceId = o.optString("source_id", "").trim();
            long createdAt = o.optLong("created_at", 0);
            g.createdAt = createdAt > 0 ? createdAt * 1000L : System.currentTimeMillis();
            result.add(g);
        }
        return result;
    }

    private static String mapSourceType(String raw) {
        if (raw == null) return "local";
        switch (raw.toLowerCase().trim()) {
            case "bangumi":
                return "bangumi";
            case "vndb":
                return "vndb";
            case "ymgal":
                return "ymgal";
            case "steam":
                return "steam";
            default:
                return "local";
        }
    }

    private static byte[] readAllBytes(Context context, Uri uri) throws Exception {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("无法打开文件");
            // 单文件场景，acc=null 表示不跟踪累计
            return ImporterIO.readBytes(in, MAX_FILE_BYTES, null);
        }
    }
}
