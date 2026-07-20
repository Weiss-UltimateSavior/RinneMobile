package com.yuki.yukihub.importer;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Vnite 数据库导入器。
 *
 * Vnite 导出的是一个目录，包含：
 * - gameDocs.json：游戏文档（元数据、标签、计时器等）
 * - gameLocalDocs.json：本地文档（路径、启动器配置）
 * - covers/<id>.jpg：封面图片（可选）
 *
 * 注意：Vnite 的路径是 Windows 路径，Android 上不适用。
 */
public final class VniteImporter {

    private static final String TAG = "VniteImporter";

    private VniteImporter() {
    }

    /**
     * 从 SAF 选中的目录 Uri 读取 Vnite 数据并解析。
     *
     * Vnite 的文档 ID 是 string 类型（UUID），需要合并 GameDocs 和 GameLocalDocs。
     * 在后台线程调用。
     */
    public static List<ImportGameData> parse(Context context, Uri dirUri) throws Exception {
        // SAF 列目录（递归，covers 子目录可达），找到 JSON 文件
        Map<String, Uri> fileUris = listDirectoryFilesRecursive(context, dirUri, "", 3);
        List<String> jsonFileNames = new ArrayList<>();
        for (Map.Entry<String, Uri> e : fileUris.entrySet()) {
            String name = e.getKey();
            if (name != null && name.endsWith(".json")) {
                jsonFileNames.add(name);
            }
        }

        // 读取 JSON 文件内容
        String gameDocsJson = null;
        String gameLocalDocsJson = null;
        for (String name : jsonFileNames) {
            String content = readUriToString(context, fileUris.get(name));
            // 优先匹配文件名精确包含 gameDocs / gameLocalDocs
            String lower = name.toLowerCase();
            if (lower.contains("gamedoc") && !lower.contains("gamelocal")) {
                gameDocsJson = content;
            } else if (lower.contains("gamelocaldoc")) {
                gameLocalDocsJson = content;
            }
        }

        // 也可能是单个 JSON 文件包含所有数据
        if (gameDocsJson == null && jsonFileNames.size() == 1) {
            gameDocsJson = readUriToString(context, fileUris.get(jsonFileNames.get(0)));
        }

        if (gameDocsJson == null) {
            throw new Exception("未找到 Vnite 游戏数据文件（gameDocs.json）");
        }

        // 解析 GameDocs（Vnite 格式可能是数组或 map）
        Map<String, JSONObject> gameDocsMap = parseJsonObjectOrArray(gameDocsJson);

        // 解析 GameLocalDocs
        Map<String, JSONObject> localDocsMap = gameLocalDocsJson != null
                ? parseJsonObjectOrArray(gameLocalDocsJson)
                : new HashMap<String, JSONObject>();

        // 合并并生成 ImportGameData 列表
        List<ImportGameData> result = new ArrayList<>();
        for (String id : gameDocsMap.keySet()) {
            JSONObject gameDoc = gameDocsMap.get(id);
            JSONObject localDoc = localDocsMap.get(id);
            ImportGameData g = convertFromVnite(gameDoc, localDoc);
            if (g != null && g.name != null && !g.name.trim().isEmpty()) {
                g.name = g.name.trim();
                result.add(g);
            }
        }
        return result;
    }

    private static ImportGameData convertFromVnite(JSONObject gameDoc, JSONObject localDoc) {
        ImportGameData g = new ImportGameData();

        // 名称
        JSONObject meta = gameDoc.optJSONObject("metadata");
        if (meta != null) {
            g.name = meta.optString("name", meta.optString("originalName", ""));
            g.originalName = meta.optString("originalName", "");
            g.developer = pickFirst(meta, "developers", "publishers");
            g.description = meta.optString("description", "");
            g.releaseDate = meta.optString("releaseDate", "");
            g.sourceId = pickSourceId(meta);
            g.sourceType = pickSourceType(meta);

            JSONArray tagArr = meta.optJSONArray("tags");
            if (tagArr != null && tagArr.length() > 0) {
                g.tags = new ArrayList<>();
                for (int i = 0; i < tagArr.length(); i++) {
                    String t = tagArr.optString(i, "");
                    if (!t.trim().isEmpty()) g.tags.add(t.trim());
                }
                if (g.tags.isEmpty()) g.tags = null;
            }
        }

        if (g.name == null || g.name.isEmpty()) {
            g.name = gameDoc.optString("name", gameDoc.optString("title", ""));
        }
        if (g.name == null || g.name.isEmpty()) return null;

        // 计时器
        JSONObject record = gameDoc.optJSONObject("record");
        if (record != null) {
            JSONArray timers = record.optJSONArray("timers");
            if (timers != null && timers.length() > 0) {
                g.vniteTimers = new ArrayList<>();
                for (int i = 0; i < timers.length(); i++) {
                    JSONObject t = timers.optJSONObject(i);
                    if (t == null) continue;
                    ImportGameData.VniteTimer timer = new ImportGameData.VniteTimer();
                    timer.start = t.optString("start", "");
                    timer.end = t.optString("end", "");
                    if (!timer.start.isEmpty() && !timer.end.isEmpty()) {
                        g.vniteTimers.add(timer);
                    }
                }
            }
            g.createdAt = parseTime(record.optString("addDate", ""));
        }

        // 本地路径
        if (localDoc != null) {
            JSONObject launcher = localDoc.optJSONObject("launcher");
            if (launcher != null) {
                JSONObject fileConfig = launcher.optJSONObject("fileConfig");
                if (fileConfig != null) {
                    g.path = fileConfig.optString("path", "");
                }
            }
            JSONObject path = localDoc.optJSONObject("path");
            if (path != null) {
                if (g.path == null || g.path.isEmpty()) {
                    g.path = path.optString("gamePath", "");
                }
                JSONArray savePaths = path.optJSONArray("savePaths");
                if (savePaths != null && savePaths.length() > 0) {
                    g.savePath = savePaths.optString(0, "");
                }
            }
        }

        return g;
    }

    // ==================== Vnite 辅助方法 ====================

    private static String pickFirst(JSONObject meta, String... keys) {
        for (String key : keys) {
            JSONArray arr = meta.optJSONArray(key);
            if (arr != null && arr.length() > 0) {
                String v = arr.optString(0, "");
                if (!v.trim().isEmpty()) return v.trim();
            }
        }
        return "";
    }

    private static String pickSourceId(JSONObject meta) {
        String[] keys = {"vndbId", "ymgalId", "bangumiId", "steamId"};
        for (String key : keys) {
            String v = meta.optString(key, "");
            if (!v.trim().isEmpty()) return v.trim();
        }
        return "";
    }

    private static String pickSourceType(JSONObject meta) {
        String[] keys = {"vndbId", "ymgalId", "bangumiId", "steamId"};
        String[] types = {"vndb", "ymgal", "bangumi", "steam"};
        for (int i = 0; i < keys.length; i++) {
            String v = meta.optString(keys[i], "");
            if (!v.trim().isEmpty()) return types[i];
        }
        return "local";
    }

    private static long parseTime(String raw) {
        if (raw == null || raw.isEmpty()) return System.currentTimeMillis();
        String[] layouts = {
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd"
        };
        for (String fmt : layouts) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(fmt, Locale.US);
                if (fmt.endsWith("'Z'")) sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                Date d = sdf.parse(raw);
                if (d != null) return d.getTime();
            } catch (Exception ignored) {
            }
        }
        Log.w(TAG, "无法解析时间，fallback 当前时间: " + raw);
        return System.currentTimeMillis();
    }

    /**
     * JSON 同时兼容数组和对象两种形式，统一返回 Map<id, JSONObject>。
     * 数组模式下缺 id 时使用 "__index_N" 作为稳定 key，确保 GameDocs 与 GameLocalDocs
     * 在相同缺 id 情况下也能正确合并（Vnite 数组模式下两者索引顺序通常一致）。
     */
    private static Map<String, JSONObject> parseJsonObjectOrArray(String json) throws Exception {
        Map<String, JSONObject> map = new LinkedHashMap<>();
        Object parsed = new JSONTokener(json).nextValue();
        if (parsed instanceof JSONArray) {
            JSONArray arr = (JSONArray) parsed;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", o.optString("_id", ""));
                if (id.isEmpty()) id = "__index_" + i;
                map.put(id, o);
            }
        } else if (parsed instanceof JSONObject) {
            JSONObject obj = (JSONObject) parsed;
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject o = obj.optJSONObject(key);
                if (o != null) map.put(key, o);
            }
        }
        return map;
    }

    /**
     * 递归列举 SAF 目录下的所有文件（含子目录），返回 path -> Uri 映射。
     * path 为相对根目录的路径（如 "covers/abc.jpg"），用于区分同名文件。
     */
    private static Map<String, Uri> listDirectoryFilesRecursive(Context context, Uri treeUri,
                                                                  String relativePrefix, int maxDepth) {
        Map<String, Uri> result = new HashMap<>();
        if (maxDepth < 0) return result;
        try {
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            Cursor cursor = context.getContentResolver().query(childrenUri, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
            }, null, null, null);
            if (cursor == null) return result;
            try {
                while (cursor.moveToNext()) {
                    String docId = cursor.getString(0);
                    String name = cursor.getString(1);
                    String mime = cursor.getString(2);
                    if (name == null) continue;
                    Uri childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                    String relPath = relativePrefix.isEmpty() ? name : relativePrefix + "/" + name;
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        // 递归进入子目录
                        result.putAll(listDirectoryFilesRecursive(context, childUri, relPath, maxDepth - 1));
                    } else {
                        result.put(relPath, childUri);
                    }
                }
            } finally {
                cursor.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "列举目录失败: " + relativePrefix, e);
        }
        return result;
    }

    private static String readUriToString(Context context, Uri uri) throws Exception {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("无法读取文件: " + uri);
            return ImporterIO.readString(in, ImporterIO.MAX_ENTRY_BYTES, StandardCharsets.UTF_8);
        }
    }
}
