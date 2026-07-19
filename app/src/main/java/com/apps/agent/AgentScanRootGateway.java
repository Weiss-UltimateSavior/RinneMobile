package com.apps.agent;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.documentfile.provider.DocumentFile;

import com.yuki.yukihub.launcherbridge.LauncherRepositoryBridge;
import com.yuki.yukihub.model.Game;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Access to user-configured SAF scan roots. Raw content URIs are never returned to the model. */
final class AgentScanRootGateway {
    private static final String PREFS = "yukihub_prefs";
    private static final String KEY_ROOTS = "scan_root_uris";
    private static final String KEY_LEGACY_ROOT = "last_scan_root_uri";
    private static final String KEY_ENABLED = "scan_root_enabled";
    private static final int MAX_ROOTS = 3;
    private static final int MAX_NODES = 2000;

    private AgentScanRootGateway() { }

    static String listRoots(Context context) throws Exception {
        JSONArray values = new JSONArray();
        for (Root root : roots(context)) {
            DocumentFile document = openRoot(context, root, false);
            values.put(new JSONObject().put("root_id", root.id).put("label", root.label)
                    .put("enabled", root.enabled).put("available", document != null)
                    .put("readable", document != null && document.canRead())
                    .put("writable", document != null && document.canWrite()));
        }
        return new JSONObject().put("roots", values).put("count", values.length()).toString();
    }

    static String listFiles(Context context, String rootId, String relativePath, int depth, int limit,
                            GameWorkspaceGateway.CancellationProbe cancellation) throws Exception {
        Root root = requireRoot(context, rootId);
        DocumentFile rootFile = openRoot(context, root, true);
        String path = AgentRelativePath.normalize(relativePath, true);
        if (!path.isEmpty()) rejectSensitive(path);
        DocumentFile start = resolve(rootFile, path);
        if (start == null || !start.exists()) return error("NOT_FOUND", "目录不存在");
        if (!start.isDirectory()) return error("NOT_DIRECTORY", "目标不是目录");
        JSONArray items = new JSONArray();
        Deque<Node> queue = new ArrayDeque<>();
        queue.add(new Node(start, path, 0));
        int visited = 0;
        boolean truncated = false;
        while (!queue.isEmpty()) {
            checkActive(cancellation);
            Node node = queue.removeFirst();
            DocumentFile[] children;
            try { children = node.file.listFiles(); } catch (Throwable ignored) { continue; }
            if (children == null) continue;
            java.util.Arrays.sort(children, java.util.Comparator.comparing(
                    (DocumentFile file) -> safeName(file), String.CASE_INSENSITIVE_ORDER));
            for (DocumentFile child : children) {
                checkActive(cancellation);
                if (++visited > MAX_NODES || items.length() >= limit) { truncated = true; queue.clear(); break; }
                String name = safeName(child);
                if (!safeProviderName(name)) continue;
                String childPath = node.path.isEmpty() ? name : node.path + "/" + name;
                if (AgentRelativePath.isSensitive(childPath)) continue;
                items.put(new JSONObject().put("relative_path", childPath)
                        .put("type", child.isDirectory() ? "directory" : child.isFile() ? "file" : "other")
                        .put("size", child.isFile() ? Math.max(-1L, child.length()) : JSONObject.NULL)
                        .put("last_modified", Math.max(0L, child.lastModified())));
                if (child.isDirectory() && node.level < depth) queue.addLast(new Node(child, childPath, node.level + 1));
            }
        }
        return new JSONObject().put("root_id", root.id).put("label", root.label)
                .put("relative_path", path).put("items", items).put("truncated", truncated).toString();
    }

    static PendingOperation prepare(Context context, JSONObject args) throws Exception {
        Root root = requireRoot(context, args.optString("root_id"));
        DocumentFile rootFile = openRoot(context, root, true);
        String operation = args.optString("operation");
        String sourcePath = AgentRelativePath.normalize(args.optString("relative_path"), false);
        rejectSensitive(sourcePath);
        if ("mkdir".equals(operation)) {
            if (resolve(rootFile, sourcePath) != null) throw new IOException("目标目录已存在");
            String parentPath = parentPath(sourcePath);
            DocumentFile parent = resolve(rootFile, parentPath);
            if (parent == null || !parent.isDirectory() || !parent.canWrite()) throw new IOException("父目录不存在或不可写");
            return new PendingOperation(root, operation, sourcePath, "", "", parent.getUri().toString(),
                    new ArrayList<>(), preview(root, operation, sourcePath, "", 0));
        }
        DocumentFile source = resolve(rootFile, sourcePath);
        if (source == null || !source.exists()) throw new IOException("源路径不存在");
        if (!source.canWrite()) throw new IOException("源路径不可写");
        String destination = args.optString("destination_path", "");
        List<GameBinding> bindings = registeredGames(context, source);
        if ("rename".equals(operation)) {
            destination = AgentRelativePath.normalize(destination, false);
            rejectSensitive(destination);
            if (!parentPath(sourcePath).equals(parentPath(destination))) {
                throw new IllegalArgumentException("rename 的 destination_path 必须与源路径位于同一目录");
            }
            if (resolve(rootFile, destination) != null) throw new IOException("目标路径已存在");
            DocumentFile parent = resolve(rootFile, parentPath(sourcePath));
            return new PendingOperation(root, operation, sourcePath, destination, source.getUri().toString(),
                    parent == null ? "" : parent.getUri().toString(), bindings,
                    preview(root, operation, sourcePath, destination, bindings.size()));
        }
        if ("move".equals(operation)) {
            destination = AgentRelativePath.normalize(destination, true);
            if (!destination.isEmpty()) rejectSensitive(destination);
            if (destination.isEmpty() && parentPath(sourcePath).isEmpty()) {
                throw new IllegalArgumentException("源已在根目录，不能移动到根目录");
            }
            if (source.isDirectory() && (destination.equals(sourcePath) || destination.startsWith(sourcePath + "/"))) {
                throw new IllegalArgumentException("不能把目录移动到自身内部");
            }
            DocumentFile destinationDirectory = resolve(rootFile, destination);
            if (destinationDirectory == null || !destinationDirectory.isDirectory() || !destinationDirectory.canWrite()) {
                throw new IOException("目标目录不存在或不可写");
            }
            if (destinationDirectory.findFile(safeName(source)) != null) throw new IOException("目标目录中已有同名项目");
            DocumentFile sourceParent = resolve(rootFile, parentPath(sourcePath));
            return new PendingOperation(root, operation, sourcePath, destination, source.getUri().toString(),
                    sourceParent == null ? "" : sourceParent.getUri().toString(), bindings,
                    preview(root, operation, sourcePath, destination, bindings.size()));
        }
        throw new IllegalArgumentException("不支持的扫描目录整理操作");
    }

    static String commit(Context context, PendingOperation pending,
                         GameWorkspaceGateway.CancellationProbe cancellation) throws Exception {
        checkActive(cancellation);
        Root currentRoot = requireRoot(context, pending.root.id);
        if (!currentRoot.uri.equals(pending.root.uri)) throw new IOException("确认后扫描目录已变化");
        DocumentFile root = openRoot(context, currentRoot, true);
        boolean changed = false;
        try {
            DocumentFile newBase;
            if ("mkdir".equals(pending.operation)) {
                if (resolve(root, pending.sourcePath) != null) throw new IOException("确认后目标路径已存在");
                DocumentFile parent = resolve(root, parentPath(pending.sourcePath));
                if (parent == null || !parent.getUri().toString().equals(pending.parentIdentity)) {
                    throw new IOException("确认后父目录已变化");
                }
                newBase = parent.createDirectory(leafName(pending.sourcePath));
                if (newBase == null) throw new IOException("创建目录失败");
                if (!leafName(pending.sourcePath).equals(safeName(newBase))) {
                    boolean deleted = false;
                    try { deleted = newBase.delete(); } catch (Throwable ignored) { }
                    if (!deleted) {
                        changed = true;
                        throw new IOException("文件提供方更改了新目录名称，且无法删除新建目录");
                    }
                    throw new IOException("文件提供方更改了新目录名称，已删除新建目录");
                }
                changed = true;
            } else {
                DocumentFile source = resolve(root, pending.sourcePath);
                if (source == null || !source.getUri().toString().equals(pending.sourceIdentity)) {
                    throw new IOException("确认后源路径已变化");
                }
                if ("rename".equals(pending.operation)) {
                    if (resolve(root, pending.destinationPath) != null) throw new IOException("确认后目标路径已存在");
                    if (!source.renameTo(leafName(pending.destinationPath))) throw new IOException("提供方不支持重命名");
                    newBase = resolve(root, pending.destinationPath);
                    if (newBase == null || !newBase.exists()) {
                        boolean rolledBack = false;
                        try { rolledBack = source.renameTo(leafName(pending.sourcePath)); } catch (Throwable ignored) { }
                        if (rolledBack) throw new IOException("文件提供方更改了重命名结果，已回滚到原名称");
                        changed = true;
                        throw new IOException("文件提供方更改了重命名结果，且无法回滚到原名称");
                    }
                    changed = true;
                } else {
                    DocumentFile sourceParent = resolve(root, parentPath(pending.sourcePath));
                    DocumentFile destination = resolve(root, pending.destinationPath);
                    if (sourceParent == null || destination == null
                            || !sourceParent.getUri().toString().equals(pending.parentIdentity)) {
                        throw new IOException("确认后目录结构已变化");
                    }
                    boolean sourceIsDirectory = source.isDirectory();
                    String sourceName = safeName(source);
                    Uri moved = DocumentsContract.moveDocument(context.getContentResolver(), source.getUri(),
                            sourceParent.getUri(), destination.getUri());
                    if (moved == null) throw new IOException("文件提供方不支持移动");
                    changed = true;
                    newBase = destination.findFile(sourceName);
                    if (newBase == null) {
                        if (sourceIsDirectory) {
                            try { newBase = DocumentFile.fromTreeUri(context, moved); } catch (Throwable ignored) { }
                        }
                        if (newBase == null) newBase = DocumentFile.fromSingleUri(context, moved);
                    }
                }
            }
            if (newBase == null || !newBase.exists()) throw new IOException("操作完成后无法重新定位目标");
            int updatedGames = updateRegisteredGames(context, pending.bindings, newBase);
            return new JSONObject().put("success", true).put("operation", pending.operation)
                    .put("root_id", pending.root.id).put("relative_path", pending.sourcePath)
                    .put("destination_path", pending.destinationPath)
                    .put("updated_game_records", updatedGames).toString();
        } catch (Throwable error) {
            if (changed) throw new MutationFailure("扫描目录已变化，但后续校验或游戏记录同步失败", error);
            throw error;
        }
    }

    private static int updateRegisteredGames(Context context, List<GameBinding> bindings,
                                             DocumentFile newBase) throws Exception {
        int updated = 0;
        for (GameBinding binding : bindings) {
            Game game = LauncherRepositoryBridge.findGameById(context, binding.gameId);
            if (game == null || !binding.oldUri.equals(game.rootUri)) continue;
            DocumentFile target = resolve(newBase, binding.relativeToSource);
            if (target == null || !target.exists()) throw new IOException("无法同步游戏记录：" + game.title);
            game.rootUri = target.getUri().toString();
            if (LauncherRepositoryBridge.updateGame(context, game) != 1) throw new IOException("更新游戏目录记录失败：" + game.title);
            updated++;
        }
        return updated;
    }

    private static List<GameBinding> registeredGames(Context context, DocumentFile source) throws Exception {
        Map<String, Game> gamesByUri = new HashMap<>();
        for (Game game : LauncherRepositoryBridge.getAllGames(context)) {
            if (game != null && game.id > 0 && game.rootUri != null && !game.rootUri.isEmpty()) gamesByUri.put(game.rootUri, game);
        }
        List<GameBinding> result = new ArrayList<>();
        Deque<Node> queue = new ArrayDeque<>();
        queue.add(new Node(source, "", 0));
        int visitedDirectories = 0;
        while (!queue.isEmpty()) {
            Node node = queue.removeFirst();
            Game game = gamesByUri.get(node.file.getUri().toString());
            if (game != null) {
                result.add(new GameBinding(game.id, game.rootUri, node.path));
                // A registered game is a synchronization boundary. Its internal resources
                // cannot be separate scan results, so do not walk thousands of game files.
                continue;
            }
            if (!node.file.isDirectory()) continue;
            if (++visitedDirectories > MAX_NODES) {
                throw new IOException("待整理目录超过 2000 层可检查子目录，无法安全同步游戏记录");
            }
            DocumentFile[] children = node.file.listFiles();
            if (children == null) continue;
            for (DocumentFile child : children) {
                String name = safeName(child);
                if (!safeProviderName(name)) continue;
                String path = node.path.isEmpty() ? name : node.path + "/" + name;
                Game childGame = gamesByUri.get(child.getUri().toString());
                if (childGame != null) {
                    result.add(new GameBinding(childGame.id, childGame.rootUri, path));
                } else if (child.isDirectory()) {
                    queue.addLast(new Node(child, path, node.level + 1));
                }
            }
        }
        return result;
    }

    private static String preview(Root root, String operation, String source, String destination, int games) {
        String action = "mkdir".equals(operation) ? "创建目录" : "rename".equals(operation) ? "重命名" : "移动";
        return "扫描目录：" + root.label + "\n操作：" + action + "\n源路径：" + source
                + (destination.isEmpty() ? "" : "\n目标路径：" + destination)
                + "\n关联游戏记录：" + games + " 个"
                + "\n\n操作会直接改变用户扫描目录；移动和重命名会同步已登记游戏路径，但不提供自动快照恢复。";
    }

    private static List<Root> roots(Context context) throws Exception {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        List<String> uris = new ArrayList<>();
        String joined = prefs.getString(KEY_ROOTS, "");
        if (joined != null && !joined.trim().isEmpty()) {
            for (String part : joined.split("\\n")) {
                String value = part == null ? "" : part.trim();
                if (!value.isEmpty() && value.startsWith("content://") && !uris.contains(value)) uris.add(value);
                if (uris.size() >= MAX_ROOTS) break;
            }
        }
        String legacy = prefs.getString(KEY_LEGACY_ROOT, "");
        if (uris.isEmpty() && legacy != null && legacy.startsWith("content://")) uris.add(legacy.trim());
        String[] states = prefs.getString(KEY_ENABLED, "").split(",", -1);
        List<Root> roots = new ArrayList<>();
        for (int i = 0; i < uris.size(); i++) {
            String uri = uris.get(i);
            boolean enabled = i >= states.length || states[i].isEmpty() || "1".equals(states[i].trim());
            roots.add(new Root(rootId(uri), uri, label(uri), enabled));
        }
        return roots;
    }

    private static Root requireRoot(Context context, String id) throws Exception {
        for (Root root : roots(context)) {
            if (root.id.equals(id)) {
                if (!root.enabled) throw new IllegalArgumentException("该扫描目录已被禁用，请在管理页重新启用后再操作");
                return root;
            }
        }
        throw new IllegalArgumentException("扫描目录 root_id 不存在或已移除");
    }

    private static DocumentFile openRoot(Context context, Root root, boolean required) throws IOException {
        DocumentFile file;
        try { file = DocumentFile.fromTreeUri(context, Uri.parse(root.uri)); }
        catch (Throwable error) { file = null; }
        if (file == null || !file.exists() || !file.isDirectory()) {
            if (required) throw new IOException("扫描目录不可访问，请在管理页重新授权");
            return null;
        }
        return file;
    }

    private static DocumentFile resolve(DocumentFile root, String path) {
        DocumentFile current = root;
        if (path == null || path.isEmpty()) return current;
        for (String segment : path.split("/")) {
            current = current == null ? null : current.findFile(segment);
            if (current == null) return null;
        }
        return current;
    }

    private static String rootId(String uri) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(uri.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(16);
        for (int i = 0; i < 8; i++) hex.append(String.format(Locale.ROOT, "%02x", digest[i] & 0xff));
        return hex.toString();
    }

    private static String label(String uri) {
        try {
            String value = Uri.parse(uri).getLastPathSegment();
            if (value == null || value.trim().isEmpty()) return "扫描目录";
            int colon = value.lastIndexOf(':');
            return safeText(colon >= 0 && colon < value.length() - 1 ? value.substring(colon + 1) : value, 80);
        } catch (Throwable ignored) { return "扫描目录"; }
    }

    private static String parentPath(String path) { int slash = path.lastIndexOf('/'); return slash < 0 ? "" : path.substring(0, slash); }
    private static String leafName(String path) { int slash = path.lastIndexOf('/'); return slash < 0 ? path : path.substring(slash + 1); }
    private static String safeName(DocumentFile file) { String value = file.getName(); return value == null ? "" : value; }
    private static boolean safeProviderName(String name) {
        try { return !name.isEmpty() && name.equals(AgentRelativePath.normalize(name, false)); }
        catch (Throwable ignored) { return false; }
    }
    private static void rejectSensitive(String path) { if (AgentRelativePath.isSensitive(path)) throw new SecurityException("敏感账号、密钥或存档路径禁止智能体访问"); }
    private static String safeText(String value, int max) {
        String text = value == null ? "" : value.replaceAll("[\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069]", " ").trim();
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
    private static String error(String code, String message) throws Exception { return new JSONObject().put("error", code).put("message", message).toString(); }
    private static void checkActive(GameWorkspaceGateway.CancellationProbe cancellation) throws IOException {
        if (cancellation != null && !cancellation.isActive()) throw new IOException("cancelled");
    }

    static final class PendingOperation {
        final Root root; final String operation; final String sourcePath; final String destinationPath;
        final String sourceIdentity; final String parentIdentity; final List<GameBinding> bindings; final String preview;
        PendingOperation(Root root, String operation, String sourcePath, String destinationPath,
                         String sourceIdentity, String parentIdentity, List<GameBinding> bindings, String preview) {
            this.root = root; this.operation = operation; this.sourcePath = sourcePath; this.destinationPath = destinationPath;
            this.sourceIdentity = sourceIdentity; this.parentIdentity = parentIdentity; this.bindings = bindings; this.preview = preview;
        }
    }
    static final class MutationFailure extends IOException { MutationFailure(String message, Throwable cause) { super(message, cause); } }
    private static final class Root { final String id, uri, label; final boolean enabled; Root(String id, String uri, String label, boolean enabled) { this.id = id; this.uri = uri; this.label = label; this.enabled = enabled; } }
    private static final class GameBinding { final long gameId; final String oldUri, relativeToSource; GameBinding(long gameId, String oldUri, String relativeToSource) { this.gameId = gameId; this.oldUri = oldUri; this.relativeToSource = relativeToSource; } }
    private static final class Node { final DocumentFile file; final String path; final int level; Node(DocumentFile file, String path, int level) { this.file = file; this.path = path; this.level = level; } }
}
