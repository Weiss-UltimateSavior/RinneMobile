package com.apps.agent;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Recoverable mutation snapshots stored outside Android backup and FileProvider roots. */
final class AgentSnapshotStore {
    private static final int MAX_SNAPSHOTS = 50;
    private static final long INCOMPLETE_GRACE_MS = 5 * 60 * 1000L;

    static final class Snapshot {
        final String id;
        final long gameId;
        final String gameTitle;
        final String rootIdentity;
        final String relativePath;
        final String contentSha256;
        final String expectedCurrentSha256;
        final String encoding;
        final String status;
        final long createdAt;
        final byte[] content;

        Snapshot(String id, long gameId, String gameTitle, String rootIdentity, String relativePath,
                 String contentSha256, String expectedCurrentSha256, String encoding, String status,
                 long createdAt, byte[] content) {
            this.id = id; this.gameId = gameId; this.gameTitle = gameTitle; this.rootIdentity = rootIdentity;
            this.relativePath = relativePath; this.contentSha256 = contentSha256;
            this.expectedCurrentSha256 = expectedCurrentSha256; this.createdAt = createdAt; this.content = content;
            this.encoding = encoding; this.status = status;
        }
    }

    private AgentSnapshotStore() { }

    static String create(Context context, long gameId, String gameTitle, String rootIdentity,
                         String relativePath, String contentSha256, String expectedCurrentSha256,
                         String encoding, byte[] content) throws Exception {
        File directory = directory(context);
        String id = UUID.randomUUID().toString();
        File data = new File(directory, id + ".bin");
        File metadata = new File(directory, id + ".json");
        File metadataTemp = new File(directory, id + ".json.tmp");
        try {
            syncWrite(data, content);
            JSONObject value = new JSONObject().put("id", id).put("game_id", gameId)
                    .put("game_title", safe(gameTitle, 200)).put("root_identity", rootIdentity)
                    .put("relative_path", relativePath).put("content_sha256", contentSha256)
                    .put("expected_current_sha256", expectedCurrentSha256)
                    .put("encoding", encoding).put("status", "pending")
                    .put("created_at", System.currentTimeMillis());
            syncWrite(metadataTemp, value.toString().getBytes(StandardCharsets.UTF_8));
            moveAtomically(metadataTemp, metadata);
            cleanup(directory);
            return id;
        } catch (Throwable error) {
            data.delete(); metadata.delete(); metadataTemp.delete();
            throw error;
        }
    }

    static Snapshot load(Context context, String id) throws Exception {
        if (id == null || !id.matches("[0-9a-fA-F-]{36}")) throw new IllegalArgumentException("snapshot_id 格式错误");
        File directory = directory(context);
        JSONObject value = new JSONObject(new String(read(new File(directory, id + ".json"), 16 * 1024), StandardCharsets.UTF_8));
        byte[] content = read(new File(directory, id + ".bin"), 64 * 1024);
        return new Snapshot(id, value.getLong("game_id"), value.optString("game_title"),
                value.getString("root_identity"), value.getString("relative_path"),
                value.getString("content_sha256"), value.getString("expected_current_sha256"),
                value.optString("encoding", "utf-8"), value.optString("status", "pending"),
                value.getLong("created_at"), content);
    }

    static void markStatus(Context context, String id, String status, String observedHash) throws Exception {
        File directory = directory(context);
        File metadata = new File(directory, id + ".json");
        JSONObject value = new JSONObject(new String(read(metadata, 16 * 1024), StandardCharsets.UTF_8));
        value.put("status", status).put("status_updated_at", System.currentTimeMillis());
        if (observedHash != null && !observedHash.isEmpty()) value.put("observed_sha256", observedHash);
        File temp = new File(directory, id + ".json.status.tmp");
        syncWrite(temp, value.toString().getBytes(StandardCharsets.UTF_8));
        try {
            moveAtomically(temp, metadata);
        } finally {
            temp.delete();
        }
    }

    static String list(Context context, long gameId, int limit) throws Exception {
        File[] files = directory(context).listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) files = new File[0];
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        JSONArray items = new JSONArray();
        for (File file : files) {
            if (items.length() >= limit) break;
            try {
                JSONObject value = new JSONObject(new String(read(file, 16 * 1024), StandardCharsets.UTF_8));
                if (value.optLong("game_id") != gameId) continue;
                items.put(new JSONObject().put("snapshot_id", value.getString("id"))
                        .put("game_id", gameId).put("relative_path", value.getString("relative_path"))
                        .put("content_sha256", value.getString("content_sha256"))
                        .put("expected_current_sha256", value.getString("expected_current_sha256"))
                        .put("status", value.optString("status", "pending"))
                        .put("created_at", value.getLong("created_at")));
            } catch (Throwable ignored) { }
        }
        return new JSONObject().put("items", items).toString();
    }

    static String recentDisplay(Context context, int limit) throws Exception {
        File[] files = directory(context).listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null || files.length == 0) return "暂无智能体文件修改快照。";
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        StringBuilder text = new StringBuilder("这些记录独立于对话，清空会话不会删除。需要恢复时，可把快照 ID 发给智能体。\n");
        int count = 0;
        for (File file : files) {
            if (count >= limit) break;
            try {
                JSONObject value = new JSONObject(new String(read(file, 16 * 1024), StandardCharsets.UTF_8));
                text.append("\n游戏：").append(value.optString("game_title"))
                        .append("\n文件：").append(value.optString("relative_path"))
                        .append("\n状态：").append(value.optString("status", "pending"))
                        .append("\n快照 ID：").append(value.optString("id"))
                        .append("\n时间：").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",
                                java.util.Locale.getDefault()).format(new java.util.Date(value.optLong("created_at"))))
                        .append('\n');
                count++;
            } catch (Throwable ignored) { }
        }
        return text.toString();
    }

    private static File directory(Context context) throws IOException {
        File value = new File(context.getNoBackupFilesDir(), "agent_snapshots");
        if (!value.exists() && !value.mkdirs()) throw new IOException("无法创建本地快照目录");
        recoverIncompleteSnapshots(value, System.currentTimeMillis());
        return value;
    }

    private static void syncWrite(File file, byte[] content) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(content); output.flush(); output.getFD().sync();
        }
    }

    private static void moveAtomically(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static byte[] read(File file, int max) throws IOException {
        if (!file.isFile() || file.length() > max) throw new IOException("快照不存在或已损坏");
        byte[] result = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < result.length) {
                int count = input.read(result, offset, result.length - offset);
                if (count < 0) throw new IOException("快照读取不完整");
                offset += count;
            }
        }
        return result;
    }

    static void recoverIncompleteSnapshots(File directory, long now) throws IOException {
        File[] temporary = directory.listFiles((dir, name) -> name.endsWith(".json.tmp"));
        if (temporary != null) for (File temp : temporary) {
            if (now - temp.lastModified() < INCOMPLETE_GRACE_MS) continue;
            String name = temp.getName();
            String id = name.substring(0, name.length() - ".json.tmp".length());
            File data = new File(directory, id + ".bin");
            File metadata = new File(directory, id + ".json");
            if (!metadata.exists() && data.isFile() && validTemporaryMetadata(temp, id)) {
                moveAtomically(temp, metadata);
            }
            else temp.delete();
        }

        File[] statusTemporary = directory.listFiles((dir, name) -> name.endsWith(".json.status.tmp"));
        if (statusTemporary != null) for (File temp : statusTemporary) {
            if (now - temp.lastModified() < INCOMPLETE_GRACE_MS) continue;
            String name = temp.getName();
            String id = name.substring(0, name.length() - ".json.status.tmp".length());
            File data = new File(directory, id + ".bin");
            File metadata = new File(directory, id + ".json");
            if (data.isFile() && validTemporaryMetadata(temp, id)) moveAtomically(temp, metadata);
            else temp.delete();
        }

        File[] dataFiles = directory.listFiles((dir, name) -> name.endsWith(".bin"));
        if (dataFiles != null) for (File data : dataFiles) {
            if (now - data.lastModified() < INCOMPLETE_GRACE_MS) continue;
            String name = data.getName();
            String id = name.substring(0, name.length() - ".bin".length());
            if (!new File(directory, id + ".json").isFile()
                    && !new File(directory, id + ".json.tmp").isFile()) data.delete();
        }
    }

    private static boolean validTemporaryMetadata(File file, String expectedId) {
        try {
            JSONObject value = new JSONObject(new String(read(file, 16 * 1024), StandardCharsets.UTF_8));
            return expectedId.equals(value.optString("id"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void cleanup(File directory) {
        File[] metadata = directory.listFiles((dir, name) -> name.endsWith(".json"));
        if (metadata == null || metadata.length <= MAX_SNAPSHOTS) return;
        Arrays.sort(metadata, Comparator.comparingLong(File::lastModified).reversed());
        int retained = metadata.length;
        for (int i = metadata.length - 1; i >= 0 && retained > MAX_SNAPSHOTS; i--) {
            try {
                JSONObject value = new JSONObject(new String(read(metadata[i], 16 * 1024), StandardCharsets.UTF_8));
                String status = value.optString("status", "pending");
                if ("pending".equals(status) || "recovery_required".equals(status)) continue;
                String name = metadata[i].getName();
                String id = name.substring(0, name.length() - 5);
                if (metadata[i].delete()) {
                    new File(directory, id + ".bin").delete();
                    retained--;
                }
            } catch (Throwable ignored) { }
        }
    }

    private static String safe(String value, int max) {
        String text = value == null ? "" : value.replaceAll("[\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069]", " ").trim();
        return text.length() <= max ? text : text.substring(0, max);
    }
}
