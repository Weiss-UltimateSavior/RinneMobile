package com.core.agent.runtime;

import android.content.Context;

import com.core.agent.net.McpHttpClient;
import com.core.agent.store.AgentSnapshotStore;
import com.core.agent.store.McpServerStore;
import com.core.agent.workspace.AgentPrivateWorkspace;
import com.core.agent.workspace.AgentScanRootGateway;
import com.core.agent.workspace.GameWorkspaceGateway;
import com.core.launcherbridge.LauncherRepositoryBridge;
import com.core.model.Game;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Whitelisted local tools. Workspace paths are always relative to a game SAF tree.
 *
 * 职责切分（重构计划 3.5 阶段 93，§8:323 按职责切片）：
 *   - 工具 Schema 目录 → {@link AgentToolSchemas}
 *   - 逐工具参数校验 → {@link AgentToolArgumentValidator}
 * 本类保留执行分派、审批编排与游戏库查询，公开 API（definitions/execute）与
 * 包私有签名（validateArguments 等）不变，调用方（LocalAgentRuntime）零变更。
 */
public final class AgentToolRegistry {
    private static final int MAX_RESULT_CHARS = 96 * 1024;

    private AgentToolRegistry() { }

    public static JSONArray definitions() throws Exception {
        return AgentToolSchemas.definitions();
    }

    public static String execute(Context context, String name, JSONObject arguments) throws Exception {
        return execute(context, name, arguments, () -> true, client -> { });
    }

    static String execute(Context context, String name, JSONObject arguments,
                          GameWorkspaceGateway.CancellationProbe cancellation) throws Exception {
        return execute(context, name, arguments, cancellation, client -> { });
    }

    static String execute(Context context, String name, JSONObject arguments,
                          GameWorkspaceGateway.CancellationProbe cancellation,
                          McpClientObserver mcpObserver) throws Exception {
        validateArguments(name, arguments == null ? new JSONObject() : arguments);
        if ("list_games".equals(name)) return listGames(context, arguments);
        if ("get_game_detail".equals(name)) return gameDetail(context, arguments);
        if ("get_recent_sessions".equals(name)) return recentSessions(context, arguments);
        if ("get_library_statistics".equals(name)) return statistics(context);
        if ("list_game_files".equals(name)) return bounded(GameWorkspaceGateway.list(context,
                arguments.optLong("game_id"), arguments.optString("relative_path"),
                arguments.optInt("depth"), arguments.optInt("limit"), cancellation));
        if ("read_game_text".equals(name)) return bounded(GameWorkspaceGateway.readText(context,
                arguments.optLong("game_id"), arguments.optString("relative_path"), arguments.optString("encoding"), cancellation));
        if ("search_game_text".equals(name)) return bounded(GameWorkspaceGateway.search(context,
                arguments.optLong("game_id"), arguments.optString("relative_path"), arguments.optString("query"),
                arguments.optString("encoding"), arguments.optInt("max_files"), arguments.optInt("max_matches"), cancellation));
        if ("list_scan_roots".equals(name)) return bounded(AgentScanRootGateway.listRoots(context));
        if ("list_scan_root_files".equals(name)) return bounded(AgentScanRootGateway.listFiles(context,
                arguments.optString("root_id"), arguments.optString("relative_path"),
                arguments.optInt("depth"), arguments.optInt("limit"), cancellation));
        if ("run_agent_workspace_command".equals(name)) return bounded(AgentPrivateWorkspace.execute(context, arguments));
        if ("list_game_snapshots".equals(name)) return bounded(AgentSnapshotStore.list(context,
                arguments.optLong("game_id"), arguments.optInt("limit")));
        if ("list_mcp_servers".equals(name)) return bounded(McpServerStore.list(context));
        if ("mcp_list_tools".equals(name)) {
            McpHttpClient client = new McpHttpClient(mcpObserver::onToolRequestStarted);
            mcpObserver.onChanged(client);
            try {
                if (!cancellation.isActive()) throw new InterruptedException("cancelled");
                McpHttpClient.Session session = client.open(McpServerStore.get(context, arguments.optString("server_id")));
                return bounded(client.listTools(session).toString());
            } finally {
                mcpObserver.onChanged(null);
            }
        }
        if ("mcp_call_tool".equals(name)) {
            McpHttpClient client = new McpHttpClient(mcpObserver::onToolRequestStarted);
            mcpObserver.onChanged(client);
            try {
                if (!cancellation.isActive()) throw new InterruptedException("cancelled");
                McpHttpClient.Session session = client.open(McpServerStore.get(context, arguments.optString("server_id")));
                return bounded(client.callTool(session, arguments.optString("tool_name"),
                        arguments.optJSONObject("arguments")).toString());
            } finally {
                mcpObserver.onChanged(null);
            }
        }
        if ("run_game_workspace_command".equals(name)) {
            String command = arguments.optString("command");
            long gameId = arguments.optLong("game_id");
            String path = arguments.optString("relative_path");
            String encoding = arguments.optString("encoding", "auto");
            int limit = arguments.optInt("limit", 50);
            if ("find".equals(command)) return bounded(GameWorkspaceGateway.list(context, gameId, path, 4,
                    limit, cancellation));
            if ("grep".equals(command)) return bounded(GameWorkspaceGateway.search(context, gameId, path,
                    arguments.optString("query"), encoding, limit, limit, cancellation));
            if ("cat".equals(command)) return bounded(GameWorkspaceGateway.readText(context, gameId, path,
                    encoding, cancellation));
            if ("sha256".equals(command)) return bounded(GameWorkspaceGateway.fileHash(context, gameId, path, cancellation));
            if ("stat".equals(command)) return bounded(GameWorkspaceGateway.stat(context, gameId, path, cancellation));
            if ("tree".equals(command)) return bounded(GameWorkspaceGateway.list(context, gameId, path,
                    arguments.optInt("depth", 4), limit, cancellation));
            if ("head".equals(command) || "tail".equals(command)) return bounded(GameWorkspaceGateway.textSlice(
                    context, gameId, path, encoding, limit, "tail".equals(command), cancellation));
            if ("diff".equals(command)) return bounded(GameWorkspaceGateway.diff(context, gameId, path,
                    arguments.optString("secondary_path"), encoding, limit, cancellation));
            if ("json_get".equals(command)) return bounded(GameWorkspaceGateway.jsonGet(context, gameId, path,
                    encoding, arguments.optString("pointer", ""), cancellation));
            if ("json_validate".equals(command)) return bounded(GameWorkspaceGateway.jsonValidate(
                    context, gameId, path, encoding, cancellation));
            if ("ini_get".equals(command)) return bounded(GameWorkspaceGateway.iniGet(context, gameId, path,
                    encoding, arguments.optString("section", ""), arguments.optString("key"), cancellation));
            if ("xml_validate".equals(command)) return bounded(GameWorkspaceGateway.xmlValidate(
                    context, gameId, path, cancellation));
            if ("archive_list".equals(command)) return bounded(GameWorkspaceGateway.archiveList(
                    context, gameId, path, limit, cancellation));
            if ("encoding_detect".equals(command)) return bounded(GameWorkspaceGateway.detectEncoding(
                    context, gameId, path, cancellation));
            if ("text_count".equals(command)) return bounded(GameWorkspaceGateway.textCount(context, gameId, path,
                    encoding, arguments.optString("query", ""), cancellation));
        }
        return error("UNKNOWN_TOOL", "未知或未授权的工具：" + safeText(name, 80));
    }

    static void validateArguments(String name, JSONObject args) {
        AgentToolArgumentValidator.validateArguments(name, args);
    }

    static boolean requiresApproval(String name) {
        return "replace_game_text".equals(name) || "restore_game_snapshot".equals(name);
    }

    static boolean requiresMcpApproval(String name) {
        return "add_mcp_server".equals(name) || "remove_mcp_server".equals(name) || "mcp_call_tool".equals(name);
    }

    static boolean isScanRootTool(String name) {
        return "list_scan_roots".equals(name) || "list_scan_root_files".equals(name)
                || "organize_scan_root".equals(name);
    }

    static boolean isScanRootMutation(String name) { return "organize_scan_root".equals(name); }

    static boolean isAgentWorkspaceMutation(String name, JSONObject args) {
        return "run_agent_workspace_command".equals(name)
                && AgentPrivateWorkspace.isMutation(args == null ? "" : args.optString("command"));
    }

    static AgentScanRootGateway.PendingOperation prepareScanRootOperation(Context context, JSONObject args) throws Exception {
        validateArguments("organize_scan_root", args);
        return AgentScanRootGateway.prepare(context, args);
    }

    static String executeApprovedScanRootOperation(Context context, AgentScanRootGateway.PendingOperation pending,
                                                   GameWorkspaceGateway.CancellationProbe cancellation) throws Exception {
        return bounded(AgentScanRootGateway.commit(context, pending, cancellation));
    }

    static McpApproval prepareMcpApproval(Context context, String name, JSONObject args) throws Exception {
        validateArguments(name, args);
        if ("add_mcp_server".equals(name)) {
            McpServerStore.Server server = new McpServerStore.Server("待添加", McpServerStore.validateName(args.optString("name")),
                    McpServerStore.validateEndpoint(args.optString("endpoint")), System.currentTimeMillis());
            return new McpApproval("添加 MCP 服务器", McpServerStore.preview(server), "添加并信任");
        }
        if ("remove_mcp_server".equals(name)) {
            McpServerStore.Server server = McpServerStore.get(context, args.optString("server_id"));
            return new McpApproval("移除 MCP 服务器", "名称：" + server.name + "\n地址：" + server.endpoint
                    + "\n\n移除后，智能体将不能再调用该服务器。", "移除服务器");
        }
        if ("mcp_call_tool".equals(name)) {
            McpServerStore.Server server = McpServerStore.get(context, args.optString("server_id"));
            return new McpApproval("运行 MCP 工具", "服务器：" + server.name + "\n地址：" + server.endpoint
                    + "\n工具：" + args.optString("tool_name") + "\n\n参数（将发送给远程服务器）：\n"
                    + args.optJSONObject("arguments").toString(2), "运行工具");
        }
        throw new IllegalArgumentException("该工具不需要 MCP 确认");
    }

    static String executeApprovedMcp(Context context, String name, JSONObject args,
                                     GameWorkspaceGateway.CancellationProbe cancellation,
                                     McpClientObserver mcpObserver) throws Exception {
        if ("add_mcp_server".equals(name)) {
            McpServerStore.Server existing = McpServerStore.findByEndpoint(context, args.optString("endpoint"));
            McpServerStore.Server server = McpServerStore.add(context, args.optString("name"), args.optString("endpoint"));
            boolean created = existing == null;
            return new JSONObject().put("success", true).put("operation", "add_mcp_server")
                    .put("registration_status", created ? "added" : "already_exists")
                    .put("message", created ? "MCP 服务器已在本机添加成功" : "MCP 服务器已存在，无需重复添加")
                    .put("server_id", server.id).put("name", server.name)
                    .put("endpoint", server.endpoint).toString();
        }
        if ("remove_mcp_server".equals(name)) {
            McpServerStore.Server server = McpServerStore.remove(context, args.optString("server_id"));
            return new JSONObject().put("success", true).put("server_id", server.id).put("name", server.name).toString();
        }
        if ("mcp_call_tool".equals(name)) return execute(context, name, args, cancellation, mcpObserver);
        throw new IllegalArgumentException("未知 MCP 操作");
    }

    interface McpClientObserver {
        void onChanged(McpHttpClient client);
        default void onToolRequestStarted() { }
    }

    static final class McpApproval {
        final String title;
        final String preview;
        final String confirmText;
        McpApproval(String title, String preview, String confirmText) {
            this.title = title; this.preview = preview; this.confirmText = confirmText;
        }
    }

    static boolean isWorkspaceTool(String name) {
        return "list_game_files".equals(name) || "read_game_text".equals(name)
                || "search_game_text".equals(name) || "replace_game_text".equals(name)
                || "list_game_snapshots".equals(name) || "restore_game_snapshot".equals(name)
                || "run_game_workspace_command".equals(name);
    }

    static long workspaceGameId(Context context, String name, JSONObject args) throws Exception {
        if ("restore_game_snapshot".equals(name)) return AgentSnapshotStore.load(context, args.optString("snapshot_id")).gameId;
        return args.optLong("game_id", -1L);
    }

    static GameWorkspaceGateway.PendingWrite prepareWrite(Context context, String name, JSONObject args) throws Exception {
        validateArguments(name, args);
        if ("replace_game_text".equals(name)) {
            return GameWorkspaceGateway.prepareReplace(context, args.optLong("game_id"),
                    args.optString("relative_path"), args.optString("expected_sha256"),
                    args.optString("old_text"), args.optString("new_text"), args.optString("encoding"));
        }
        if ("restore_game_snapshot".equals(name)) {
            return GameWorkspaceGateway.prepareRestore(context, args.optString("snapshot_id"));
        }
        throw new IllegalArgumentException("该工具不是写入工具");
    }

    private static String listGames(Context context, JSONObject args) throws Exception {
        String query = args.optString("query", "").trim().toLowerCase(Locale.ROOT);
        String status = args.optString("status", "any");
        boolean favoriteOnly = args.optBoolean("favorite_only", false);
        String sort = args.optString("sort", "recent");
        int limit = Math.max(1, Math.min(30, args.optInt("limit", 10)));
        List<Game> matches = new ArrayList<>();
        for (Game game : LauncherRepositoryBridge.getAllGames(context)) {
            if (game == null) continue;
            if (!"any".equals(status) && !status.equals(game.playStatus)) continue;
            if (favoriteOnly && !game.favorite) continue;
            String searchable = safe(game.title) + " " + safe(game.originalTitle) + " " + safe(game.tags);
            if (!query.isEmpty() && !searchable.toLowerCase(Locale.ROOT).contains(query)) continue;
            matches.add(game);
        }
        Comparator<Game> comparator;
        if ("least_recent".equals(sort)) comparator = Comparator.comparingLong(g -> g.lastPlayedAt);
        else if ("play_time".equals(sort)) comparator = (a, b) -> Long.compare(b.totalPlayTime, a.totalPlayTime);
        else if ("title".equals(sort)) comparator = Comparator.comparing(g -> safe(g.title), String.CASE_INSENSITIVE_ORDER);
        else comparator = (a, b) -> Long.compare(b.lastPlayedAt, a.lastPlayedAt);
        matches.sort(comparator);
        JSONArray items = new JSONArray();
        for (int i = 0; i < Math.min(limit, matches.size()); i++) items.put(gameSummary(matches.get(i)));
        return bounded(new JSONObject().put("count", matches.size()).put("items", items).toString());
    }

    private static String gameDetail(Context context, JSONObject args) throws Exception {
        long id = args.optLong("game_id", -1L);
        if (id <= 0) return error("INVALID_ARGUMENT", "game_id 必须是正整数");
        Game game = LauncherRepositoryBridge.findGameById(context, id);
        if (game == null) return error("NOT_FOUND", "找不到该游戏");
        JSONObject value = gameSummary(game)
                .put("original_title", safeText(game.originalTitle, 200))
                .put("description", safeText(game.description, 1200))
                .put("tags", safeText(game.tags, 500))
                .put("launch_configured", !safe(game.rootUri).isEmpty() || !safe(game.emulatorPackage).isEmpty())
                .put("last_played_at", game.lastPlayedAt);
        return bounded(value.toString());
    }

    private static String recentSessions(Context context, JSONObject args) throws Exception {
        int limit = Math.max(1, Math.min(20, args.optInt("limit", 8)));
        JSONArray items = new JSONArray();
        for (LauncherRepositoryBridge.RecentActivity item
                : LauncherRepositoryBridge.getRecentPlayActivities(context, limit)) {
            if (item == null) continue;
            items.put(new JSONObject()
                    .put("game_id", item.gameId)
                    .put("title", safeText(item.gameTitle, 200))
                    .put("duration_minutes", Math.max(0L, item.duration) / 60000L)
                    .put("ended_at", item.endTime)
                    .put("launch_type", safeText(item.launchType, 80))
                    .put("status", safeText(item.playStatus, 40)));
        }
        return bounded(new JSONObject().put("items", items).toString());
    }

    private static String statistics(Context context) throws Exception {
        int favorite = 0, unplayed = 0, playing = 0, completed = 0;
        long total = 0L;
        List<Game> games = LauncherRepositoryBridge.getAllGames(context);
        for (Game game : games) {
            if (game == null) continue;
            if (game.favorite) favorite++;
            if ("playing".equals(game.playStatus)) playing++;
            else if ("completed".equals(game.playStatus)) completed++;
            else unplayed++;
            total += Math.max(0L, game.totalPlayTime);
        }
        return new JSONObject()
                .put("game_count", games.size())
                .put("favorite_count", favorite)
                .put("unplayed_count", unplayed)
                .put("playing_count", playing)
                .put("completed_count", completed)
                .put("total_play_minutes", total / 60000L)
                .toString();
    }

    private static JSONObject gameSummary(Game game) throws Exception {
        return new JSONObject()
                .put("id", game.id)
                .put("title", safeText(game.title, 200))
                .put("engine", game.engine == null ? "UNKNOWN" : game.engine.name())
                .put("status", safeText(game.playStatus, 40))
                .put("favorite", game.favorite)
                .put("total_play_minutes", Math.max(0L, game.totalPlayTime) / 60000L)
                .put("last_played_at", game.lastPlayedAt);
    }

    private static String error(String code, String message) throws Exception {
        return new JSONObject().put("error", code).put("message", message).toString();
    }

    private static String bounded(String value) {
        if (value.length() <= MAX_RESULT_CHARS) return value;
        try {
            return new JSONObject()
                    .put("error", "RESULT_TOO_LARGE")
                    .put("message", "工具结果超过本地安全上限，请缩小查询范围")
                    .toString();
        } catch (Exception ignored) {
            return "{\"error\":\"RESULT_TOO_LARGE\"}";
        }
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private static String safeText(String value, int maxChars) {
        String result = safe(value).replace('\u0000', ' ').trim();
        return result.length() <= maxChars ? result : result.substring(0, maxChars) + "…";
    }
}
