package com.apps.agent;

import android.content.Context;

import com.yuki.yukihub.launcherbridge.LauncherRepositoryBridge;
import com.yuki.yukihub.model.Game;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Whitelisted local tools. Workspace paths are always relative to a game SAF tree. */
public final class AgentToolRegistry {
    private static final int MAX_RESULT_CHARS = 96 * 1024;

    private AgentToolRegistry() { }

    public static JSONArray definitions() throws Exception {
        JSONArray tools = new JSONArray();
        tools.put(tool("list_games", "查询本地游戏库。适合筛选、推荐和排序游戏。",
                objectSchema(new JSONObject()
                        .put("query", stringSchema("标题或标签关键词"))
                        .put("status", enumSchema("any", "unplayed", "playing", "completed"))
                        .put("favorite_only", new JSONObject().put("type", "boolean"))
                        .put("sort", enumSchema("recent", "least_recent", "play_time", "title"))
                        .put("limit", integerSchema(1, 30)), new JSONArray().put("limit"))));
        tools.put(tool("get_game_detail", "按本地游戏 ID 获取游戏详情，不包含目录和文件路径。",
                objectSchema(new JSONObject().put("game_id", integerSchema(1, Integer.MAX_VALUE)),
                        new JSONArray().put("game_id"))));
        tools.put(tool("get_recent_sessions", "获取最近的游玩记录。",
                objectSchema(new JSONObject().put("limit", integerSchema(1, 20)),
                        new JSONArray().put("limit"))));
        tools.put(tool("get_library_statistics", "统计游戏数量、收藏、状态和累计游玩时间。",
                objectSchema(new JSONObject(), new JSONArray())));
        tools.put(tool("list_game_files", "列出指定游戏已授权目录中的文件，只返回相对路径。",
                objectSchema(new JSONObject()
                                .put("game_id", integerSchema(1, Integer.MAX_VALUE))
                                .put("relative_path", stringSchema("游戏目录内的相对目录；根目录使用空字符串", 512))
                                .put("depth", integerSchema(0, 4))
                                .put("limit", integerSchema(1, 200)),
                        new JSONArray().put("game_id").put("relative_path").put("depth").put("limit"))));
        tools.put(tool("read_game_text", "读取指定游戏目录内的小型文本文件，返回内容、编码与 SHA-256。",
                objectSchema(new JSONObject()
                                .put("game_id", integerSchema(1, Integer.MAX_VALUE))
                                .put("relative_path", stringSchema("游戏目录内的文件相对路径", 512))
                                .put("encoding", enumSchema("auto", "utf-8", "gb18030", "shift_jis", "utf-16le", "utf-16be")),
                        new JSONArray().put("game_id").put("relative_path").put("encoding"))));
        tools.put(tool("search_game_text", "在指定游戏目录的文本文件中进行精确区分大小写搜索。",
                objectSchema(new JSONObject()
                                .put("game_id", integerSchema(1, Integer.MAX_VALUE))
                                .put("relative_path", stringSchema("搜索起点相对路径；根目录使用空字符串", 512))
                                .put("query", stringSchema("要搜索的文本", 200))
                                .put("encoding", enumSchema("auto", "utf-8", "gb18030", "shift_jis", "utf-16le", "utf-16be"))
                                .put("max_files", integerSchema(1, 100))
                                .put("max_matches", integerSchema(1, 100)),
                        new JSONArray().put("game_id").put("relative_path").put("query").put("encoding")
                                .put("max_files").put("max_matches"))));
        tools.put(tool("replace_game_text", "在用户确认后，对游戏文本文件执行一次唯一的精确文本替换。必须先读取文件并使用最新 SHA-256。",
                objectSchema(new JSONObject()
                                .put("game_id", integerSchema(1, Integer.MAX_VALUE))
                                .put("relative_path", stringSchema("游戏目录内的文件相对路径", 512))
                                .put("expected_sha256", stringSchema("read_game_text 返回的 SHA-256", 64))
                                .put("old_text", stringSchema("文件中只出现一次的原文本", 4096))
                                .put("new_text", stringSchema("替换后的文本", 4096))
                                .put("encoding", enumSchema("auto", "utf-8", "gb18030", "shift_jis", "utf-16le", "utf-16be")),
                        new JSONArray().put("game_id").put("relative_path").put("expected_sha256")
                                .put("old_text").put("new_text").put("encoding"))));
        tools.put(tool("list_game_snapshots", "列出指定游戏由智能体创建的可恢复修改快照。",
                objectSchema(new JSONObject().put("game_id", integerSchema(1, Integer.MAX_VALUE))
                                .put("limit", integerSchema(1, 20)),
                        new JSONArray().put("game_id").put("limit"))));
        tools.put(tool("restore_game_snapshot", "在用户确认后恢复一个智能体修改快照；若文件随后又变化会拒绝覆盖。",
                objectSchema(new JSONObject().put("snapshot_id", stringSchema("快照 ID", 36)),
                        new JSONArray().put("snapshot_id"))));
        tools.put(tool("run_game_workspace_command", "执行受限游戏工作区命令。它不是系统 Shell，仅支持 find/grep/cat/sha256，无管道、重定向或任意程序。",
                objectSchema(new JSONObject().put("game_id", integerSchema(1, Integer.MAX_VALUE))
                                .put("command", enumSchema("find", "grep", "cat", "sha256"))
                                .put("relative_path", stringSchema("游戏目录内相对路径；find/grep 可用空字符串", 512))
                                .put("query", stringSchema("grep 查询文本；其他命令传空字符串", 200))
                                .put("encoding", enumSchema("auto", "utf-8", "gb18030", "shift_jis", "utf-16le", "utf-16be"))
                                .put("limit", integerSchema(1, 100)),
                        new JSONArray().put("game_id").put("command").put("relative_path")
                                .put("query").put("encoding").put("limit"))));
        tools.put(tool("list_mcp_servers", "列出已由用户确认添加的远程 MCP 服务器。",
                objectSchema(new JSONObject(), new JSONArray())));
        tools.put(tool("add_mcp_server", "根据用户明确提供的名称和地址，提出添加一个 Streamable HTTP MCP 服务器。必须先由用户在本机确认；不要接收或请求 Token、Cookie、Authorization Header。",
                objectSchema(new JSONObject().put("name", stringSchema("用户可识别的服务器名称", 80))
                                .put("endpoint", stringSchema("HTTPS MCP Streamable HTTP 地址", 2048)),
                        new JSONArray().put("name").put("endpoint"))));
        tools.put(tool("remove_mcp_server", "移除一个已添加的 MCP 服务器，必须由用户在本机确认。",
                objectSchema(new JSONObject().put("server_id", stringSchema("list_mcp_servers 返回的 server_id", 36)),
                        new JSONArray().put("server_id"))));
        tools.put(tool("mcp_list_tools", "连接已添加的 MCP 服务器并列出其工具。工具描述会发送给模型服务。",
                objectSchema(new JSONObject().put("server_id", stringSchema("MCP server_id", 36)),
                        new JSONArray().put("server_id"))));
        tools.put(tool("mcp_call_tool", "调用已添加 MCP 服务器的一个工具。每一次远程工具调用都必须先由用户在本机确认。",
                objectSchema(new JSONObject().put("server_id", stringSchema("MCP server_id", 36))
                                .put("tool_name", stringSchema("mcp_list_tools 返回的工具名称", 120))
                                .put("arguments", new JSONObject().put("type", "object").put("additionalProperties", true)),
                        new JSONArray().put("server_id").put("tool_name").put("arguments"))));
        return tools;
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
            if ("find".equals(command)) return bounded(GameWorkspaceGateway.list(context, gameId, path, 4,
                    arguments.optInt("limit"), cancellation));
            if ("grep".equals(command)) return bounded(GameWorkspaceGateway.search(context, gameId, path,
                    arguments.optString("query"), arguments.optString("encoding"), arguments.optInt("limit"),
                    arguments.optInt("limit"), cancellation));
            if ("cat".equals(command)) return bounded(GameWorkspaceGateway.readText(context, gameId, path,
                    arguments.optString("encoding"), cancellation));
            if ("sha256".equals(command)) return bounded(GameWorkspaceGateway.fileHash(context, gameId, path, cancellation));
        }
        return error("UNKNOWN_TOOL", "未知或未授权的工具：" + safeText(name, 80));
    }

    static void validateArguments(String name, JSONObject args) {
        if (args == null) throw new IllegalArgumentException("工具参数不能为空");
        if ("list_games".equals(name)) {
            rejectUnknown(args, "query", "status", "favorite_only", "sort", "limit");
            optionalString(args, "query", 120);
            optionalEnum(args, "status", "any", "unplayed", "playing", "completed");
            optionalBoolean(args, "favorite_only");
            optionalEnum(args, "sort", "recent", "least_recent", "play_time", "title");
            optionalInteger(args, "limit", 1, 30);
            if (!args.has("limit")) throw new IllegalArgumentException("缺少 limit");
            return;
        }
        if ("get_game_detail".equals(name)) {
            rejectUnknown(args, "game_id");
            requiredInteger(args, "game_id", 1, Integer.MAX_VALUE);
            return;
        }
        if ("get_recent_sessions".equals(name)) {
            rejectUnknown(args, "limit");
            requiredInteger(args, "limit", 1, 20);
            return;
        }
        if ("get_library_statistics".equals(name)) {
            rejectUnknown(args);
            return;
        }
        if ("list_game_files".equals(name)) {
            rejectUnknown(args, "game_id", "relative_path", "depth", "limit");
            requiredInteger(args, "game_id", 1, Integer.MAX_VALUE);
            requiredString(args, "relative_path", 512, true);
            requiredInteger(args, "depth", 0, 4);
            requiredInteger(args, "limit", 1, 200);
            AgentRelativePath.normalize(args.optString("relative_path"), true);
            return;
        }
        if ("read_game_text".equals(name)) {
            rejectUnknown(args, "game_id", "relative_path", "encoding");
            requiredInteger(args, "game_id", 1, Integer.MAX_VALUE);
            requiredString(args, "relative_path", 512, false);
            requiredEnum(args, "encoding", "auto", "utf-8", "gb18030", "shift_jis", "utf-16le", "utf-16be");
            AgentRelativePath.normalize(args.optString("relative_path"), false);
            return;
        }
        if ("search_game_text".equals(name)) {
            rejectUnknown(args, "game_id", "relative_path", "query", "encoding", "max_files", "max_matches");
            requiredInteger(args, "game_id", 1, Integer.MAX_VALUE);
            requiredString(args, "relative_path", 512, true);
            requiredString(args, "query", 200, false);
            requiredEnum(args, "encoding", "auto", "utf-8", "gb18030", "shift_jis", "utf-16le", "utf-16be");
            requiredInteger(args, "max_files", 1, 100);
            requiredInteger(args, "max_matches", 1, 100);
            AgentRelativePath.normalize(args.optString("relative_path"), true);
            return;
        }
        if ("replace_game_text".equals(name)) {
            rejectUnknown(args, "game_id", "relative_path", "expected_sha256", "old_text", "new_text", "encoding");
            requiredInteger(args, "game_id", 1, Integer.MAX_VALUE);
            requiredString(args, "relative_path", 512, false);
            requiredString(args, "expected_sha256", 64, false);
            requiredString(args, "old_text", 4096, false);
            requiredString(args, "new_text", 4096, true);
            requiredEnum(args, "encoding", "auto", "utf-8", "gb18030", "shift_jis", "utf-16le", "utf-16be");
            AgentRelativePath.normalize(args.optString("relative_path"), false);
            if (!args.optString("expected_sha256").matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("expected_sha256 格式错误");
            }
            return;
        }
        if ("list_game_snapshots".equals(name)) {
            rejectUnknown(args, "game_id", "limit");
            requiredInteger(args, "game_id", 1, Integer.MAX_VALUE);
            requiredInteger(args, "limit", 1, 20);
            return;
        }
        if ("restore_game_snapshot".equals(name)) {
            rejectUnknown(args, "snapshot_id");
            requiredString(args, "snapshot_id", 36, false);
            if (!args.optString("snapshot_id").matches("[0-9a-fA-F-]{36}")) throw new IllegalArgumentException("snapshot_id 格式错误");
            return;
        }
        if ("run_game_workspace_command".equals(name)) {
            rejectUnknown(args, "game_id", "command", "relative_path", "query", "encoding", "limit");
            requiredInteger(args, "game_id", 1, Integer.MAX_VALUE);
            requiredEnum(args, "command", "find", "grep", "cat", "sha256");
            requiredString(args, "relative_path", 512, true);
            requiredString(args, "query", 200, true);
            requiredEnum(args, "encoding", "auto", "utf-8", "gb18030", "shift_jis", "utf-16le", "utf-16be");
            requiredInteger(args, "limit", 1, 100);
            AgentRelativePath.normalize(args.optString("relative_path"), true);
            String command = args.optString("command");
            if (("cat".equals(command) || "sha256".equals(command)) && args.optString("relative_path").isEmpty()) {
                throw new IllegalArgumentException("该命令需要文件路径");
            }
            if ("grep".equals(command) && args.optString("query").trim().isEmpty()) {
                throw new IllegalArgumentException("grep 需要 query");
            }
            return;
        }
        if ("list_mcp_servers".equals(name)) { rejectUnknown(args); return; }
        if ("add_mcp_server".equals(name)) {
            rejectUnknown(args, "name", "endpoint");
            requiredString(args, "name", 80, false);
            requiredString(args, "endpoint", 2048, false);
            McpServerStore.validateName(args.optString("name"));
            McpServerStore.validateEndpoint(args.optString("endpoint"));
            return;
        }
        if ("remove_mcp_server".equals(name) || "mcp_list_tools".equals(name)) {
            rejectUnknown(args, "server_id");
            requiredMcpServerId(args);
            return;
        }
        if ("mcp_call_tool".equals(name)) {
            rejectUnknown(args, "server_id", "tool_name", "arguments");
            requiredMcpServerId(args);
            requiredString(args, "tool_name", 120, false);
            if (!args.optString("tool_name").matches("[A-Za-z0-9_.:/-]{1,120}")) throw new IllegalArgumentException("tool_name 格式错误");
            Object values = args.opt("arguments");
            if (!(values instanceof JSONObject) || values.toString().length() > 16 * 1024) {
                throw new IllegalArgumentException("arguments 必须是小于 16KB 的对象");
            }
            return;
        }
        throw new IllegalArgumentException("未知或未授权的工具");
    }

    static boolean requiresApproval(String name) {
        return "replace_game_text".equals(name) || "restore_game_snapshot".equals(name);
    }

    static boolean requiresMcpApproval(String name) {
        return "add_mcp_server".equals(name) || "remove_mcp_server".equals(name) || "mcp_call_tool".equals(name);
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

    private static void rejectUnknown(JSONObject args, String... allowed) {
        Set<String> keys = new HashSet<>();
        java.util.Collections.addAll(keys, allowed);
        Iterator<String> iterator = args.keys();
        while (iterator.hasNext()) if (!keys.contains(iterator.next())) throw new IllegalArgumentException("包含未知参数");
    }

    private static void optionalString(JSONObject args, String key, int max) {
        if (!args.has(key)) return;
        Object value = args.opt(key);
        if (!(value instanceof String) || ((String) value).length() > max) throw new IllegalArgumentException(key + " 格式错误");
    }

    private static void requiredString(JSONObject args, String key, int max, boolean allowEmpty) {
        if (!args.has(key)) throw new IllegalArgumentException("缺少 " + key);
        optionalString(args, key, max);
        if (!allowEmpty && ((String) args.opt(key)).trim().isEmpty()) throw new IllegalArgumentException(key + " 不能为空");
    }

    private static void optionalBoolean(JSONObject args, String key) {
        if (args.has(key) && !(args.opt(key) instanceof Boolean)) throw new IllegalArgumentException(key + " 格式错误");
    }

    private static void optionalEnum(JSONObject args, String key, String... allowed) {
        if (!args.has(key)) return;
        Object raw = args.opt(key);
        if (!(raw instanceof String)) throw new IllegalArgumentException(key + " 格式错误");
        for (String value : allowed) if (value.equals(raw)) return;
        throw new IllegalArgumentException(key + " 不在允许范围内");
    }

    private static void requiredEnum(JSONObject args, String key, String... allowed) {
        if (!args.has(key)) throw new IllegalArgumentException("缺少 " + key);
        optionalEnum(args, key, allowed);
    }

    private static void requiredInteger(JSONObject args, String key, int min, int max) {
        if (!args.has(key)) throw new IllegalArgumentException("缺少 " + key);
        optionalInteger(args, key, min, max);
    }

    private static void requiredMcpServerId(JSONObject args) {
        requiredString(args, "server_id", 36, false);
        if (!args.optString("server_id").matches("[0-9a-fA-F-]{36}")) throw new IllegalArgumentException("server_id 格式错误");
    }

    private static void optionalInteger(JSONObject args, String key, int min, int max) {
        if (!args.has(key)) return;
        Object raw = args.opt(key);
        if (!(raw instanceof Number)) throw new IllegalArgumentException(key + " 格式错误");
        double value = ((Number) raw).doubleValue();
        if (value != Math.rint(value) || value < min || value > max) throw new IllegalArgumentException(key + " 超出范围");
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

    private static JSONObject tool(String name, String description, JSONObject parameters) throws Exception {
        return new JSONObject().put("type", "function").put("function", new JSONObject()
                .put("name", name).put("description", description).put("parameters", parameters));
    }

    private static JSONObject objectSchema(JSONObject properties, JSONArray required) throws Exception {
        return new JSONObject().put("type", "object").put("properties", properties)
                .put("required", required).put("additionalProperties", false);
    }

    private static JSONObject stringSchema(String description) throws Exception {
        return stringSchema(description, 120);
    }

    private static JSONObject stringSchema(String description, int maxLength) throws Exception {
        return new JSONObject().put("type", "string").put("description", description).put("maxLength", maxLength);
    }

    private static JSONObject enumSchema(String... values) throws Exception {
        JSONArray array = new JSONArray();
        for (String value : values) array.put(value);
        return new JSONObject().put("type", "string").put("enum", array);
    }

    private static JSONObject integerSchema(int min, int max) throws Exception {
        return new JSONObject().put("type", "integer").put("minimum", min).put("maximum", max);
    }
}
