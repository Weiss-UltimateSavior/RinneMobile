package com.core.agent.runtime;

import com.core.agent.store.McpServerStore;
import com.core.agent.workspace.AgentRelativePath;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Per-tool argument validation rules (split from {@link AgentToolRegistry},
 * refactoring plan 3.5). Pure validation with no state; the registry delegates
 * its {@code validateArguments} entry point here.
 */
final class AgentToolArgumentValidator {
    private AgentToolArgumentValidator() { }

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
            rejectUnknown(args, "game_id", "command", "relative_path", "secondary_path", "query", "encoding",
                    "limit", "depth", "pointer", "section", "key");
            requiredInteger(args, "game_id", 1, Integer.MAX_VALUE);
            requiredEnum(args, "command", "find", "grep", "cat", "sha256",
                    "stat", "tree", "head", "tail", "diff",
                    "json_get", "json_validate", "ini_get", "xml_validate",
                    "archive_list", "encoding_detect", "text_count");
            requiredString(args, "relative_path", 512, true);
            optionalString(args, "secondary_path", 512);
            optionalString(args, "query", 200);
            optionalEnum(args, "encoding", "auto", "utf-8", "gb18030", "shift_jis", "utf-16le", "utf-16be");
            optionalInteger(args, "limit", 1, 200);
            optionalInteger(args, "depth", 0, 8);
            optionalString(args, "pointer", 512);
            optionalString(args, "section", 120);
            optionalString(args, "key", 120);
            AgentRelativePath.normalize(args.optString("relative_path"), true);
            String command = args.optString("command");
            if (!("find".equals(command) || "tree".equals(command) || "grep".equals(command) || "stat".equals(command))
                    && args.optString("relative_path").isEmpty()) {
                throw new IllegalArgumentException("该命令需要文件路径");
            }
            if ("grep".equals(command) && args.optString("query").trim().isEmpty()) {
                throw new IllegalArgumentException("grep 需要 query");
            }
            if ("diff".equals(command)) {
                requiredString(args, "secondary_path", 512, false);
                AgentRelativePath.normalize(args.optString("secondary_path"), false);
            }
            if ("json_get".equals(command) && !args.has("pointer")) {
                throw new IllegalArgumentException("json_get 需要 pointer");
            }
            if ("ini_get".equals(command)) requiredString(args, "key", 120, false);
            return;
        }
        if ("list_scan_roots".equals(name)) { rejectUnknown(args); return; }
        if ("list_scan_root_files".equals(name)) {
            rejectUnknown(args, "root_id", "relative_path", "depth", "limit");
            requiredString(args, "root_id", 16, false);
            if (!args.optString("root_id").matches("[0-9a-f]{16}")) throw new IllegalArgumentException("root_id 格式错误");
            requiredString(args, "relative_path", 512, true);
            requiredInteger(args, "depth", 0, 6);
            requiredInteger(args, "limit", 1, 200);
            AgentRelativePath.normalize(args.optString("relative_path"), true);
            return;
        }
        if ("organize_scan_root".equals(name)) {
            rejectUnknown(args, "root_id", "operation", "relative_path", "destination_path");
            requiredString(args, "root_id", 16, false);
            if (!args.optString("root_id").matches("[0-9a-f]{16}")) throw new IllegalArgumentException("root_id 格式错误");
            requiredEnum(args, "operation", "mkdir", "rename", "move");
            requiredString(args, "relative_path", 512, false);
            AgentRelativePath.normalize(args.optString("relative_path"), false);
            String operation = args.optString("operation");
            if ("rename".equals(operation)) {
                requiredString(args, "destination_path", 512, false);
                AgentRelativePath.normalize(args.optString("destination_path"), false);
            } else if ("move".equals(operation)) {
                requiredString(args, "destination_path", 512, true);
                AgentRelativePath.normalize(args.optString("destination_path"), true);
            } else optionalString(args, "destination_path", 512);
            return;
        }
        if ("run_agent_workspace_command".equals(name)) {
            rejectUnknown(args, "command", "relative_path", "secondary_path", "content", "depth", "limit");
            requiredEnum(args, "command", "list", "read", "stat", "write", "append", "mkdir", "copy", "move", "delete");
            requiredString(args, "relative_path", 512, true);
            optionalString(args, "secondary_path", 512);
            optionalString(args, "content", 128 * 1024);
            optionalInteger(args, "depth", 0, 4);
            optionalInteger(args, "limit", 1, 200);
            String command = args.optString("command");
            AgentRelativePath.normalize(args.optString("relative_path"), "list".equals(command));
            if (("copy".equals(command) || "move".equals(command))) {
                requiredString(args, "secondary_path", 512, false);
                AgentRelativePath.normalize(args.optString("secondary_path"), false);
            }
            if ("write".equals(command) || "append".equals(command)) requiredString(args, "content", 128 * 1024, true);
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
}
