package com.apps.agent;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/** App-private scratch workspace. It never resolves paths outside files/rinne_agent_workspace. */
final class AgentPrivateWorkspace {
    private static final long MAX_TOTAL_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_WRITE_CHARS = 128 * 1024;
    private static final int MAX_NODES = 2000;

    private AgentPrivateWorkspace() { }

    static String execute(Context context, JSONObject args) throws Exception {
        File root = new File(context.getFilesDir(), "rinne_agent_workspace");
        return execute(root, args);
    }

    static String execute(File root, JSONObject args) throws Exception {
        ensureRoot(root);
        String command = args.optString("command");
        String path = AgentRelativePath.normalize(args.optString("relative_path"), "list".equals(command));
        String secondary = args.optString("secondary_path", "");
        int limit = args.optInt("limit", 100);
        if ("list".equals(command)) return list(root, path, args.optInt("depth", 2), limit);
        if ("read".equals(command)) return read(root, path);
        if ("stat".equals(command)) return stat(root, path);
        if ("write".equals(command)) return write(root, path, args.optString("content", ""), false);
        if ("append".equals(command)) return write(root, path, args.optString("content", ""), true);
        if ("mkdir".equals(command)) return mkdir(root, path);
        if ("copy".equals(command)) return copy(root, path,
                AgentRelativePath.normalize(secondary, false));
        if ("move".equals(command)) return move(root, path,
                AgentRelativePath.normalize(secondary, false));
        if ("delete".equals(command)) return delete(root, path);
        throw new IllegalArgumentException("不支持的工作目录命令");
    }

    static boolean isMutation(String command) {
        return "write".equals(command) || "append".equals(command) || "mkdir".equals(command)
                || "copy".equals(command) || "move".equals(command) || "delete".equals(command);
    }

    private static String list(File root, String relativePath, int depth, int limit) throws Exception {
        File start = resolve(root, relativePath);
        if (!start.exists()) return error("NOT_FOUND", "目录不存在");
        if (!start.isDirectory()) return error("NOT_DIRECTORY", "目标不是目录");
        JSONArray items = new JSONArray();
        Deque<Node> queue = new ArrayDeque<>();
        queue.add(new Node(start, relativePath, 0));
        int visited = 0;
        boolean truncated = false;
        while (!queue.isEmpty()) {
            Node node = queue.removeFirst();
            File[] children = node.file.listFiles();
            if (children == null) continue;
            java.util.Arrays.sort(children, java.util.Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            for (File child : children) {
                if (++visited > MAX_NODES || items.length() >= limit) { truncated = true; queue.clear(); break; }
                if (Files.isSymbolicLink(child.toPath())) throw new SecurityException("工作目录中禁止符号链接");
                String childPath = node.path.isEmpty() ? child.getName() : node.path + "/" + child.getName();
                items.put(new JSONObject().put("relative_path", childPath)
                        .put("type", child.isDirectory() ? "directory" : child.isFile() ? "file" : "other")
                        .put("size", child.isFile() ? child.length() : JSONObject.NULL)
                        .put("last_modified", Math.max(0L, child.lastModified())));
                if (child.isDirectory() && node.level < depth) queue.addLast(new Node(child, childPath, node.level + 1));
            }
        }
        return new JSONObject().put("workspace", "rinne_private").put("relative_path", relativePath)
                .put("items", items).put("truncated", truncated).toString();
    }

    private static String read(File root, String relativePath) throws Exception {
        File file = resolve(root, relativePath);
        if (!file.isFile()) return error("NOT_FOUND", "文件不存在");
        byte[] bytes = readBytes(file);
        String content = decodeUtf8(bytes);
        return new JSONObject().put("workspace", "rinne_private").put("relative_path", relativePath)
                .put("byte_length", bytes.length).put("sha256", sha256(bytes)).put("content", content).toString();
    }

    private static String stat(File root, String relativePath) throws Exception {
        File file = resolve(root, relativePath);
        if (!file.exists()) return error("NOT_FOUND", "文件或目录不存在");
        return new JSONObject().put("workspace", "rinne_private").put("relative_path", relativePath)
                .put("type", file.isDirectory() ? "directory" : file.isFile() ? "file" : "other")
                .put("size", file.isFile() ? file.length() : JSONObject.NULL)
                .put("last_modified", Math.max(0L, file.lastModified())).toString();
    }

    private static String write(File root, String relativePath, String content, boolean append) throws Exception {
        if (content == null || content.length() > MAX_WRITE_CHARS) throw new IllegalArgumentException("content 超过 128K 字符");
        File target = resolve(root, relativePath);
        File parent = target.getParentFile();
        if (parent == null || !parent.isDirectory()) return error("PARENT_NOT_FOUND", "父目录不存在，请先创建目录");
        if (target.exists() && !target.isFile()) return error("NOT_FILE", "目标不是文件");
        byte[] addition = content.getBytes(StandardCharsets.UTF_8);
        byte[] bytes;
        if (append && target.isFile()) {
            byte[] before = readBytes(target);
            bytes = java.util.Arrays.copyOf(before, before.length + addition.length);
            System.arraycopy(addition, 0, bytes, before.length, addition.length);
        } else bytes = addition;
        long newLength = bytes.length;
        if (newLength > MAX_FILE_BYTES) throw new IOException("单个工作文件不能超过 2MB");
        long currentTotal = totalBytes(root);
        long replaced = target.isFile() ? target.length() : 0L;
        if (currentTotal - replaced + newLength > MAX_TOTAL_BYTES) throw new IOException("智能体工作目录超过 16MB 上限");
        File temp = File.createTempFile(".rinne-", ".tmp", parent);
        try {
            try (FileOutputStream output = new FileOutputStream(temp)) {
                output.write(bytes); output.flush(); output.getFD().sync();
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable error) {
            if (temp.exists()) temp.delete();
            throw error;
        }
        byte[] written = readBytes(target);
        return new JSONObject().put("success", true).put("workspace", "rinne_private")
                .put("operation", append ? "append" : "write").put("relative_path", relativePath)
                .put("byte_length", written.length).put("sha256", sha256(written)).toString();
    }

    private static String mkdir(File root, String relativePath) throws Exception {
        File target = resolve(root, relativePath);
        if (target.exists()) return error("ALREADY_EXISTS", "目标已存在");
        File parent = target.getParentFile();
        if (parent == null || !parent.isDirectory()) return error("PARENT_NOT_FOUND", "父目录不存在，请逐级创建目录");
        if (!target.mkdir()) throw new IOException("创建目录失败");
        return success("mkdir", relativePath);
    }

    private static String copy(File root, String sourcePath, String destinationPath) throws Exception {
        File source = resolve(root, sourcePath);
        File destination = resolve(root, destinationPath);
        if (!source.isFile()) return error("NOT_FILE", "copy 目前只支持普通文件");
        if (destination.exists()) return error("ALREADY_EXISTS", "目标已存在");
        if (destination.getParentFile() == null || !destination.getParentFile().isDirectory()) {
            return error("PARENT_NOT_FOUND", "目标父目录不存在");
        }
        if (totalBytes(root) + source.length() > MAX_TOTAL_BYTES) throw new IOException("智能体工作目录超过 16MB 上限");
        try {
            Files.copy(source.toPath(), destination.toPath());
        } catch (Throwable error) {
            if (destination.exists() && !destination.delete()) {
                throw new MutationFailure("复制失败且无法移除不完整目标文件", error);
            }
            throw error;
        }
        return success("copy", destinationPath);
    }

    private static String move(File root, String sourcePath, String destinationPath) throws Exception {
        File source = resolve(root, sourcePath);
        File destination = resolve(root, destinationPath);
        if (!source.exists()) return error("NOT_FOUND", "源路径不存在");
        if (destination.exists()) return error("ALREADY_EXISTS", "目标已存在");
        if (destination.getParentFile() == null || !destination.getParentFile().isDirectory()) {
            return error("PARENT_NOT_FOUND", "目标父目录不存在");
        }
        String sourceCanonical = source.getCanonicalPath();
        String destinationCanonical = destination.getCanonicalPath();
        if (source.isDirectory() && destinationCanonical.startsWith(sourceCanonical + File.separator)) {
            throw new IllegalArgumentException("不能把目录移动到自身内部");
        }
        Files.move(source.toPath(), destination.toPath());
        return success("move", destinationPath);
    }

    private static String delete(File root, String relativePath) throws Exception {
        File target = resolve(root, relativePath);
        if (!target.exists()) return error("NOT_FOUND", "目标不存在");
        List<File> postorder = new java.util.ArrayList<>();
        collectDeleteOrder(target, postorder);
        int deleted = 0;
        for (File item : postorder) {
            if (!item.delete()) {
                if (deleted > 0) throw new MutationFailure("部分工作目录项目已删除，后续删除失败", null);
                throw new IOException("删除失败：" + item.getName());
            }
            deleted++;
        }
        return new JSONObject().put("success", true).put("workspace", "rinne_private")
                .put("operation", "delete").put("relative_path", relativePath)
                .put("deleted_nodes", deleted).toString();
    }

    private static void collectDeleteOrder(File file, List<File> postorder) throws Exception {
        if (postorder.size() >= MAX_NODES) throw new IOException("删除项目超过 2000 个安全上限");
        if (Files.isSymbolicLink(file.toPath())) throw new SecurityException("工作目录中禁止符号链接");
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("无法读取待删除目录");
            for (File child : children) collectDeleteOrder(child, postorder);
        }
        postorder.add(file);
    }

    private static File resolve(File root, String relativePath) throws Exception {
        File canonicalRoot = root.getCanonicalFile();
        File rawTarget = relativePath.isEmpty() ? canonicalRoot : new File(canonicalRoot, relativePath);
        File rawCursor = rawTarget;
        while (rawCursor != null && !rawCursor.equals(canonicalRoot)) {
            if (rawCursor.exists() && Files.isSymbolicLink(rawCursor.toPath())) {
                throw new SecurityException("工作目录中禁止符号链接");
            }
            rawCursor = rawCursor.getParentFile();
        }
        File target = rawTarget.getCanonicalFile();
        String rootPath = canonicalRoot.getPath();
        String targetPath = target.getPath();
        if (!targetPath.equals(rootPath) && !targetPath.startsWith(rootPath + File.separator)) {
            throw new SecurityException("工作目录路径越界");
        }
        File cursor = target;
        while (cursor != null && !cursor.equals(canonicalRoot)) {
            if (cursor.exists() && Files.isSymbolicLink(cursor.toPath())) throw new SecurityException("工作目录中禁止符号链接");
            cursor = cursor.getParentFile();
        }
        return target;
    }

    private static void ensureRoot(File root) throws IOException {
        if (!root.exists() && !root.mkdirs()) throw new IOException("无法创建智能体工作目录");
        if (!root.isDirectory() || Files.isSymbolicLink(root.toPath())) throw new IOException("智能体工作目录不可用");
    }

    private static byte[] readBytes(File file) throws IOException {
        if (file.length() > MAX_FILE_BYTES) throw new IOException("文件超过 2MB 读取上限");
        try (FileInputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.max(32, file.length()));
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_FILE_BYTES) throw new IOException("文件超过 2MB 读取上限");
                output.write(buffer, 0, count);
            }
            byte[] bytes = output.toByteArray();
            if (bytes.length > MAX_FILE_BYTES) throw new IOException("文件超过 2MB 读取上限");
            return bytes;
        }
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static long totalBytes(File root) throws Exception {
        Deque<File> queue = new ArrayDeque<>();
        queue.add(root);
        long total = 0L;
        int nodes = 0;
        while (!queue.isEmpty()) {
            File file = queue.removeFirst();
            if (++nodes > MAX_NODES) throw new IOException("智能体工作目录项目超过 2000 个上限");
            if (Files.isSymbolicLink(file.toPath())) throw new SecurityException("工作目录中禁止符号链接");
            if (file.isFile()) total += Math.max(0L, file.length());
            else if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children == null) throw new IOException("无法统计智能体工作目录");
                java.util.Collections.addAll(queue, children);
            }
        }
        return total;
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder result = new StringBuilder(64);
        for (byte item : digest) result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return result.toString();
    }

    private static String success(String operation, String path) throws Exception {
        return new JSONObject().put("success", true).put("workspace", "rinne_private")
                .put("operation", operation).put("relative_path", path).toString();
    }

    private static String error(String code, String message) throws Exception {
        return new JSONObject().put("error", code).put("message", message).toString();
    }

    private static final class Node {
        final File file; final String path; final int level;
        Node(File file, String path, int level) { this.file = file; this.path = path; this.level = level; }
    }
    static final class MutationFailure extends IOException {
        MutationFailure(String message, Throwable cause) { super(message, cause); }
    }
}
