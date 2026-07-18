package com.apps.agent;

import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import com.yuki.yukihub.launcherbridge.LauncherRepositoryBridge;
import com.yuki.yukihub.model.Game;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharsetEncoder;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Workspace access is scoped to a game selected by local database ID and its persisted SAF tree. */
final class GameWorkspaceGateway {
    private static final int MAX_READ_BYTES = 64 * 1024;
    private static final int MAX_SEARCH_FILE_BYTES = 128 * 1024;
    private static final int MAX_SEARCH_TOTAL_BYTES = 2 * 1024 * 1024;

    private GameWorkspaceGateway() { }

    static String list(Context context, long gameId, String relativePath, int depth, int limit,
                       CancellationProbe cancellation) throws Exception {
        Workspace workspace = open(context, gameId);
        String path = AgentRelativePath.normalize(relativePath, true);
        DocumentFile start = resolve(workspace.root, path);
        if (start == null || !start.exists()) return error("NOT_FOUND", "目录不存在");
        if (!start.isDirectory()) return error("NOT_DIRECTORY", "目标不是目录");
        JSONArray items = new JSONArray();
        Deque<Node> queue = new ArrayDeque<>();
        queue.add(new Node(start, path, 0));
        boolean truncated = false;
        while (!queue.isEmpty()) {
            checkActive(cancellation);
            Node node = queue.removeFirst();
            DocumentFile[] children;
            try { children = node.file.listFiles(); }
            catch (Throwable error) { continue; }
            for (DocumentFile child : children) {
                checkActive(cancellation);
                if (items.length() >= limit) { truncated = true; queue.clear(); break; }
                String name = safeName(child);
                if (!safeProviderName(name)) continue;
                String childPath = node.path.isEmpty() ? name : node.path + "/" + name;
                if (AgentRelativePath.isSensitive(childPath)) continue;
                items.put(new JSONObject()
                        .put("relative_path", childPath)
                        .put("type", child.isDirectory() ? "directory" : "file")
                        .put("size", child.isFile() ? Math.max(-1L, child.length()) : JSONObject.NULL));
                if (child.isDirectory() && node.level < depth) queue.addLast(new Node(child, childPath, node.level + 1));
            }
        }
        return new JSONObject().put("game_id", gameId).put("game_title", workspace.title)
                .put("items", items).put("truncated", truncated).toString();
    }

    static String readText(Context context, long gameId, String relativePath, String encoding,
                           CancellationProbe cancellation) throws Exception {
        Workspace workspace = open(context, gameId);
        String path = AgentRelativePath.normalize(relativePath, false);
        rejectSensitive(path);
        DocumentFile file = resolve(workspace.root, path);
        if (file == null || !file.exists()) return error("NOT_FOUND", "文件不存在");
        if (!file.isFile()) return error("NOT_FILE", "目标不是文件");
        byte[] bytes = readBounded(context, file, MAX_READ_BYTES, cancellation);
        Decoded decoded = decode(bytes, encoding);
        return new JSONObject().put("game_id", gameId).put("relative_path", path)
                .put("encoding", decoded.encoding).put("sha256", sha256(bytes))
                .put("byte_length", bytes.length).put("content", decoded.text).toString();
    }

    static String fileHash(Context context, long gameId, String relativePath,
                           CancellationProbe cancellation) throws Exception {
        Workspace workspace = open(context, gameId);
        String path = AgentRelativePath.normalize(relativePath, false);
        rejectSensitive(path);
        DocumentFile file = resolve(workspace.root, path);
        if (file == null || !file.exists() || !file.isFile()) return error("NOT_FOUND", "文件不存在");
        byte[] bytes = readBounded(context, file, MAX_READ_BYTES, cancellation);
        return new JSONObject().put("game_id", gameId).put("relative_path", path)
                .put("byte_length", bytes.length).put("sha256", sha256(bytes)).toString();
    }

    static String search(Context context, long gameId, String relativePath, String query,
                         String encoding, int maxFiles, int maxMatches, CancellationProbe cancellation) throws Exception {
        if (query == null || query.trim().isEmpty() || query.length() > 200) {
            throw new IllegalArgumentException("query 长度必须为 1-200");
        }
        Workspace workspace = open(context, gameId);
        String path = AgentRelativePath.normalize(relativePath, true);
        if (!path.isEmpty()) rejectSensitive(path);
        DocumentFile start = resolve(workspace.root, path);
        if (start == null || !start.exists()) return error("NOT_FOUND", "目录或文件不存在");
        JSONArray matches = new JSONArray();
        Deque<Node> queue = new ArrayDeque<>();
        queue.add(new Node(start, path, 0));
        int files = 0;
        int bytesRead = 0;
        int nodesVisited = 0;
        Set<String> visited = new HashSet<>();
        while (!queue.isEmpty() && files < maxFiles && matches.length() < maxMatches
                && bytesRead < MAX_SEARCH_TOTAL_BYTES && nodesVisited < 1000) {
            checkActive(cancellation);
            Node node = queue.removeFirst();
            if (node.level > 12 || !visited.add(node.file.getUri().toString())) continue;
            nodesVisited++;
            if (node.file.isDirectory()) {
                DocumentFile[] children;
                try { children = node.file.listFiles(); } catch (Throwable error) { continue; }
                for (DocumentFile child : children) {
                    checkActive(cancellation);
                    String name = safeName(child);
                    if (!safeProviderName(name)) continue;
                    String childPath = node.path.isEmpty() ? name : node.path + "/" + name;
                    if (AgentRelativePath.isSensitive(childPath)) continue;
                    if (queue.size() < 1000) queue.addLast(new Node(child, childPath, node.level + 1));
                }
                continue;
            }
            long declared = node.file.length();
            if (!node.file.isFile() || declared > MAX_SEARCH_FILE_BYTES) continue;
            files++;
            byte[] bytes;
            try { bytes = readBounded(context, node.file, MAX_SEARCH_FILE_BYTES, cancellation); }
            catch (Throwable ignored) { continue; }
            bytesRead += bytes.length;
            Decoded decoded;
            try { decoded = decode(bytes, encoding); } catch (Throwable ignored) { continue; }
            String[] lines = decoded.text.split("\\R", -1);
            for (int i = 0; i < lines.length && matches.length() < maxMatches; i++) {
                int column = lines[i].indexOf(query);
                if (column < 0) continue;
                String excerpt = lines[i].trim();
                if (excerpt.length() > 300) excerpt = excerpt.substring(0, 300) + "…";
                matches.put(new JSONObject().put("relative_path", node.path).put("line", i + 1)
                        .put("column", column + 1).put("excerpt", excerpt));
            }
        }
        return new JSONObject().put("game_id", gameId).put("query", query).put("matches", matches)
                .put("files_scanned", files).put("bytes_scanned", bytesRead)
                .put("truncated", !queue.isEmpty() || matches.length() >= maxMatches || nodesVisited >= 1000).toString();
    }

    static PendingWrite prepareReplace(Context context, long gameId, String relativePath,
                                       String expectedSha256, String oldText, String newText,
                                       String encoding) throws Exception {
        Workspace workspace = open(context, gameId);
        String path = AgentRelativePath.normalize(relativePath, false);
        rejectSensitive(path);
        if (expectedSha256 == null || !expectedSha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("expected_sha256 格式错误");
        }
        if (oldText == null || oldText.isEmpty() || oldText.length() > 4096) {
            throw new IllegalArgumentException("old_text 长度必须为 1-4096");
        }
        if (newText == null || newText.length() > 4096) {
            throw new IllegalArgumentException("new_text 不能超过 4096 字符");
        }
        rejectVisualControls(oldText);
        rejectVisualControls(newText);
        DocumentFile file = resolve(workspace.root, path);
        if (file == null || !file.exists() || !file.isFile()) throw new IOException("要修改的文件不存在");
        if (!file.canWrite()) throw new IOException("游戏目录当前没有写权限，请重新授权目录");
        byte[] before = readBounded(context, file, MAX_READ_BYTES);
        String actualHash = sha256(before);
        if (!actualHash.equalsIgnoreCase(expectedSha256)) throw new IOException("文件已发生变化，请重新读取后再修改");
        Decoded decoded = decode(before, encoding);
        if (!Arrays.equals(before, encode(decoded.text, decoded.encoding))) {
            throw new IOException("该文件编码无法无损往返，已拒绝自动修改");
        }
        int first = decoded.text.indexOf(oldText);
        if (first < 0) throw new IOException("文件中找不到要替换的原文本");
        if (decoded.text.indexOf(oldText, first + oldText.length()) >= 0) {
            throw new IOException("原文本出现多次，请提供更完整且唯一的上下文");
        }
        String changed = decoded.text.substring(0, first) + newText + decoded.text.substring(first + oldText.length());
        byte[] after = encode(changed, decoded.encoding);
        if (after.length > MAX_READ_BYTES) throw new IOException("修改后的文件超过 65536 字节安全上限");
        String preview = "游戏：" + safeDisplay(workspace.title) + "\n文件：" + safeDisplay(path) + "\n编码：" + decoded.encoding
                + "\n原始 SHA-256：" + actualHash + "\n\n删除内容（完整）：\n" + diffLines(oldText, "- ")
                + "\n\n新增内容（完整）：\n" + diffLines(newText, "+ ")
                + "\n\n确认后会先在应用私有目录创建原文件快照，再写入并校验。";
        return new PendingWrite(gameId, workspace.title, workspace.rootIdentity, file.getUri().toString(),
                path, actualHash, decoded.encoding, before, after, preview);
    }

    static PendingWrite prepareRestore(Context context, String snapshotId) throws Exception {
        AgentSnapshotStore.Snapshot snapshot = AgentSnapshotStore.load(context, snapshotId);
        Workspace workspace = open(context, snapshot.gameId);
        if (!workspace.rootIdentity.equals(snapshot.rootIdentity)) throw new IOException("游戏目录已变更，不能恢复到另一个目录");
        String path = AgentRelativePath.normalize(snapshot.relativePath, false);
        DocumentFile file = resolve(workspace.root, path);
        if (file == null || !file.exists() || !file.isFile() || !file.canWrite()) throw new IOException("快照目标不可写");
        byte[] current = readBounded(context, file, MAX_READ_BYTES);
        String currentHash = sha256(current);
        if (!snapshot.contentSha256.equals(sha256(snapshot.content))) throw new IOException("快照内容校验失败");
        boolean conflict = !snapshot.expectedCurrentSha256.equals(currentHash);
        String preview = "游戏：" + safeDisplay(workspace.title) + "\n文件：" + safeDisplay(path)
                + "\n当前 SHA-256：" + currentHash + "\n恢复为 SHA-256：" + snapshot.contentSha256
                + (conflict ? "\n\n警告：当前文件与快照记录的预期版本不同，可能是后续修改或写入损坏。此次属于冲突恢复。" : "")
                + "\n\n当前完整内容：\n" + fullContentPreview(current, snapshot.encoding, "- ")
                + "\n\n恢复后的完整内容：\n" + fullContentPreview(snapshot.content, snapshot.encoding, "+ ")
                + "\n\n确认后会先快照当前版本，再恢复所选历史版本并校验。";
        return new PendingWrite(snapshot.gameId, workspace.title, workspace.rootIdentity, file.getUri().toString(),
                path, currentHash, snapshot.encoding, current, snapshot.content, preview);
    }

    static String commitReplace(Context context, PendingWrite pending, CancellationProbe cancellation,
                                Runnable onCommitted) throws Exception {
        if (!cancellation.isActive()) throw new InterruptedException("cancelled");
        Workspace workspace = open(context, pending.gameId);
        if (!workspace.rootIdentity.equals(pending.rootIdentity)) throw new IOException("审批后游戏目录已变化，已取消写入");
        DocumentFile file = resolve(workspace.root, pending.relativePath);
        if (file == null || !file.exists() || !file.isFile()) throw new IOException("要修改的文件不再存在");
        if (!file.getUri().toString().equals(pending.targetIdentity)) throw new IOException("审批后目标文件已变化，已取消写入");
        byte[] current = readBounded(context, file, MAX_READ_BYTES);
        if (!pending.beforeSha256.equals(sha256(current))) throw new IOException("文件已发生变化，已取消写入");
        String afterHash = sha256(pending.after);
        String snapshotId = AgentSnapshotStore.create(context, pending.gameId, pending.gameTitle,
                pending.rootIdentity, pending.relativePath, pending.beforeSha256, afterHash,
                pending.encoding, current);
        try {
            AgentMutationTransaction.replace(new AgentMutationTransaction.DocumentIo() {
                @Override public byte[] read() throws Exception { return readBounded(context, file, MAX_READ_BYTES); }
                @Override public void write(byte[] value) throws Exception { GameWorkspaceGateway.write(context, file, value); }
            }, current, pending.after, cancellation::isActive, () -> {
                AgentSnapshotStore.markStatus(context, snapshotId, "committed", afterHash);
                if (onCommitted != null) onCommitted.run();
            });
            return new JSONObject().put("success", true).put("game_id", pending.gameId)
                    .put("relative_path", pending.relativePath).put("before_sha256", pending.beforeSha256)
                    .put("after_sha256", afterHash).put("snapshot_id", snapshotId).toString();
        } catch (AgentMutationTransaction.Failure error) {
            boolean restored = error.restored;
            String observed = "";
            try { observed = sha256(readBounded(context, file, MAX_READ_BYTES)); } catch (Throwable ignored) { }
            try { AgentSnapshotStore.markStatus(context, snapshotId,
                    restored ? "rolled_back" : "recovery_required", observed); }
            catch (Throwable statusError) { error.addSuppressed(statusError); }
            throw new WriteFailure(restored ? "写入失败，已恢复原文件" : "写入及自动恢复失败，文件可能已损坏",
                    pending.gameTitle, pending.relativePath, snapshotId, restored, error);
        } catch (Throwable error) {
            try { AgentSnapshotStore.markStatus(context, snapshotId, "aborted", pending.beforeSha256); }
            catch (Throwable statusError) { error.addSuppressed(statusError); }
            throw error;
        }
    }

    static String rootIdentity(Context context, long gameId) throws Exception { return open(context, gameId).rootIdentity; }
    static String gameTitle(Context context, long gameId) throws Exception { return open(context, gameId).title; }

    private static Workspace open(Context context, long gameId) throws Exception {
        Game game = LauncherRepositoryBridge.findGameById(context, gameId);
        if (game == null) throw new IllegalArgumentException("找不到该游戏");
        String rootUri = game.rootUri == null ? "" : game.rootUri.trim();
        if (!rootUri.startsWith("content://")) throw new IllegalStateException("该游戏没有可授权访问的本地目录");
        DocumentFile root = DocumentFile.fromTreeUri(context, Uri.parse(rootUri));
        if (root == null || !root.exists() || !root.isDirectory()) throw new IOException("游戏目录不可访问，请重新授权目录");
        return new Workspace(root, safeText(game.title, 200), sha256(rootUri.getBytes(StandardCharsets.UTF_8)));
    }

    private static DocumentFile resolve(DocumentFile root, String path) {
        DocumentFile current = root;
        if (path.isEmpty()) return current;
        for (String segment : path.split("/")) {
            current = current == null ? null : current.findFile(segment);
            if (current == null) return null;
        }
        return current;
    }

    private static byte[] readBounded(Context context, DocumentFile file, int maxBytes) throws IOException {
        return readBounded(context, file, maxBytes, () -> true);
    }

    private static byte[] readBounded(Context context, DocumentFile file, int maxBytes,
                                      CancellationProbe cancellation) throws IOException {
        long length = file.length();
        if (length > maxBytes) throw new IOException("文件超过读取上限 " + maxBytes + " 字节");
        try (InputStream input = context.getContentResolver().openInputStream(file.getUri())) {
            if (input == null) throw new IOException("无法打开文件");
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.max(32, Math.min(length, maxBytes)));
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkActive(cancellation);
                total += count;
                if (total > maxBytes) throw new IOException("文件超过读取上限 " + maxBytes + " 字节");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static void write(Context context, DocumentFile file, byte[] bytes) throws IOException {
        try (OutputStream output = context.getContentResolver().openOutputStream(file.getUri(), "rwt")) {
            if (output == null) throw new IOException("无法打开文件进行写入");
            output.write(bytes); output.flush();
        }
    }

    private static byte[] encode(String text, String encoding) throws CharacterCodingException {
        Charset charset;
        byte[] bom = new byte[0];
        if ("utf-8-bom".equals(encoding)) { charset = StandardCharsets.UTF_8; bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}; }
        else if ("utf-16le-bom".equals(encoding)) { charset = StandardCharsets.UTF_16LE; bom = new byte[]{(byte) 0xFF, (byte) 0xFE}; }
        else if ("utf-16be-bom".equals(encoding)) { charset = StandardCharsets.UTF_16BE; bom = new byte[]{(byte) 0xFE, (byte) 0xFF}; }
        else if ("gb18030".equals(encoding)) charset = Charset.forName("GB18030");
        else if ("shift_jis".equals(encoding)) charset = Charset.forName("Shift_JIS");
        else if ("utf-16le".equals(encoding)) charset = StandardCharsets.UTF_16LE;
        else if ("utf-16be".equals(encoding)) charset = StandardCharsets.UTF_16BE;
        else charset = StandardCharsets.UTF_8;
        CharsetEncoder encoder = charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer encoded = encoder.encode(CharBuffer.wrap(text));
        byte[] body = new byte[encoded.remaining()];
        encoded.get(body);
        byte[] result = Arrays.copyOf(bom, bom.length + body.length);
        System.arraycopy(body, 0, result, bom.length, body.length);
        return result;
    }

    private static String diffLines(String value, String prefix) {
        String visible = (value == null ? "" : value).replace("\r\n", "\\r\n").replace("\r", "\\r");
        return prefix + visible.replace("\n", "\n" + prefix);
    }

    private static String safeDisplay(String value) {
        return (value == null ? "" : value).replaceAll("[\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069]", "�");
    }

    private static String fullContentPreview(byte[] bytes, String encoding, String prefix) {
        try {
            String text = decode(bytes, encoding).text;
            if (containsUnsafePreviewCharacters(text)) return fullHexPreview(bytes, prefix);
            return diffLines(text, prefix);
        }
        catch (Throwable ignored) {
            return fullHexPreview(bytes, prefix);
        }
    }

    private static boolean containsUnsafePreviewCharacters(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\u0000' || c == '\u2028' || c == '\u2029'
                    || (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')
                    || (c >= '\u202A' && c <= '\u202E') || (c >= '\u2066' && c <= '\u2069')) return true;
        }
        return false;
    }

    private static String fullHexPreview(byte[] bytes, String prefix) {
        StringBuilder hex = new StringBuilder(bytes.length * 3 + 32);
        for (int i = 0; i < bytes.length; i++) {
            if (i % 16 == 0) hex.append(prefix);
            hex.append(String.format(Locale.ROOT, "%02x", bytes[i] & 0xff));
            if (i % 16 == 15 || i == bytes.length - 1) hex.append('\n'); else hex.append(' ');
        }
        return hex.toString();
    }

    private static Decoded decode(byte[] bytes, String requested) throws CharacterCodingException {
        String mode = requested == null ? "auto" : requested.toLowerCase(Locale.ROOT);
        int offset = 0;
        Charset charset;
        String label;
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
            charset = StandardCharsets.UTF_8; label = "utf-8-bom"; offset = 3;
        } else if (bytes.length >= 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) {
            charset = StandardCharsets.UTF_16LE; label = "utf-16le-bom"; offset = 2;
        } else if (bytes.length >= 2 && bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF) {
            charset = StandardCharsets.UTF_16BE; label = "utf-16be-bom"; offset = 2;
        } else {
            if ("auto".equals(mode) || "utf-8".equals(mode)) { charset = StandardCharsets.UTF_8; label = "utf-8"; }
            else if ("gb18030".equals(mode)) { charset = Charset.forName("GB18030"); label = "gb18030"; }
            else if ("shift_jis".equals(mode)) { charset = Charset.forName("Shift_JIS"); label = "shift_jis"; }
            else if ("utf-16le".equals(mode)) { charset = StandardCharsets.UTF_16LE; label = "utf-16le"; }
            else if ("utf-16be".equals(mode)) { charset = StandardCharsets.UTF_16BE; label = "utf-16be"; }
            else throw new IllegalArgumentException("不支持的 encoding");
        }
        CharBuffer chars = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset));
        return new Decoded(chars.toString(), label);
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte item : digest) hex.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return hex.toString();
    }

    private static String safeName(DocumentFile file) { String value = file.getName(); return value == null ? "" : value; }
    private static boolean safeProviderName(String name) {
        try { return !name.isEmpty() && name.equals(AgentRelativePath.normalize(name, false)); }
        catch (Throwable ignored) { return false; }
    }
    private static void rejectSensitive(String path) { if (AgentRelativePath.isSensitive(path)) throw new SecurityException("敏感文件或账号/存档目录默认禁止智能体访问"); }
    private static void rejectVisualControls(String value) {
        for (int i = 0; value != null && i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\u0000' || (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')
                    || (c >= '\u202A' && c <= '\u202E') || (c >= '\u2066' && c <= '\u2069')) {
                throw new IllegalArgumentException("替换文本包含不可安全预览的控制字符");
            }
        }
    }
    private static String safeText(String value, int max) {
        String text = value == null ? "" : value.replaceAll("[\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069]", " ").trim();
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
    private static String error(String code, String message) throws Exception { return new JSONObject().put("error", code).put("message", message).toString(); }

    private static final class Workspace { final DocumentFile root; final String title; final String rootIdentity; Workspace(DocumentFile root, String title, String rootIdentity) { this.root = root; this.title = title; this.rootIdentity = rootIdentity; } }
    private static final class Node { final DocumentFile file; final String path; final int level; Node(DocumentFile file, String path, int level) { this.file = file; this.path = path; this.level = level; } }
    private static final class Decoded { final String text; final String encoding; Decoded(String text, String encoding) { this.text = text; this.encoding = encoding; } }

    interface CancellationProbe { boolean isActive(); }

    private static void checkActive(CancellationProbe cancellation) throws IOException {
        if (cancellation != null && !cancellation.isActive()) throw new IOException("cancelled");
    }

    static final class PendingWrite {
        final long gameId;
        final String gameTitle;
        final String rootIdentity;
        final String targetIdentity;
        final String relativePath;
        final String beforeSha256;
        final String encoding;
        final byte[] before;
        final byte[] after;
        final String preview;

        PendingWrite(long gameId, String gameTitle, String rootIdentity, String targetIdentity,
                     String relativePath, String beforeSha256,
                     String encoding, byte[] before, byte[] after, String preview) {
            this.gameId = gameId; this.gameTitle = gameTitle; this.rootIdentity = rootIdentity;
            this.targetIdentity = targetIdentity; this.relativePath = relativePath;
            this.beforeSha256 = beforeSha256; this.encoding = encoding;
            this.before = before; this.after = after; this.preview = preview;
        }
    }

    static final class WriteFailure extends IOException {
        final String gameTitle;
        final String relativePath;
        final String snapshotId;
        final boolean restored;
        WriteFailure(String message, String gameTitle, String relativePath, String snapshotId,
                     boolean restored, Throwable cause) {
            super(message, cause); this.gameTitle = gameTitle; this.relativePath = relativePath;
            this.snapshotId = snapshotId; this.restored = restored;
        }
    }
}
