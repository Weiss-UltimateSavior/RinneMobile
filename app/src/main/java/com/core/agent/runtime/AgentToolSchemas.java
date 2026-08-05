package com.core.agent.runtime;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Whitelisted local agent tools' schema catalog (split from {@link AgentToolRegistry},
 * refactoring plan 3.5). Pure declarative metadata with no state; the registry
 * delegates its {@code definitions()} entry point here.
 */
final class AgentToolSchemas {
    private AgentToolSchemas() { }

    static JSONArray definitions() throws Exception {
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
        tools.put(tool("run_game_workspace_command", "执行只读、受限的游戏工作区命令。文件检查：stat；tree 用 depth/limit；head/tail 用 limit；diff 用 secondary_path/limit。配置解析：json_get 用 pointer；json_validate；ini_get 用 section/key；xml_validate。游戏诊断：archive_list 只检查 ZIP/APK/JAR 且用 limit；encoding_detect；text_count 可用 query 统计出现次数。文本命令可传 encoding。它不是系统 Shell，不支持管道、重定向、写入或运行程序。",
                objectSchema(new JSONObject().put("game_id", integerSchema(1, Integer.MAX_VALUE))
                                .put("command", enumSchema("find", "grep", "cat", "sha256",
                                        "stat", "tree", "head", "tail", "diff",
                                        "json_get", "json_validate", "ini_get", "xml_validate",
                                        "archive_list", "encoding_detect", "text_count"))
                                .put("relative_path", stringSchema("游戏目录内相对路径；find/tree/grep 可用空字符串", 512))
                                .put("secondary_path", stringSchema("diff 使用的第二个文件相对路径", 512))
                                .put("query", stringSchema("grep 查询文本；text_count 可用它统计指定文本出现次数", 200))
                                .put("encoding", enumSchema("auto", "utf-8", "gb18030", "shift_jis", "utf-16le", "utf-16be"))
                                .put("limit", integerSchema(1, 200))
                                .put("depth", integerSchema(0, 8))
                                .put("pointer", stringSchema("json_get 使用的 RFC 6901 JSON Pointer；空字符串表示根节点", 512))
                                .put("section", stringSchema("ini_get 的节名；全局键使用空字符串", 120))
                                .put("key", stringSchema("ini_get 的键名", 120)),
                        new JSONArray().put("game_id").put("command").put("relative_path"))));
        tools.put(tool("list_scan_roots", "列出用户在游戏管理页添加的扫描目录。返回的 enabled=false 表示用户已禁用该目录，list_scan_root_files 和 organize_scan_root 会拒绝访问，需要请用户在管理页重新启用。首次访问需要本次页面会话授权；只返回本地生成的 root_id 和目录标签，不返回原始 URI。",
                objectSchema(new JSONObject(), new JSONArray())));
        tools.put(tool("list_scan_root_files", "列出某个已添加扫描目录中的文件和子目录，用于了解游戏文件夹结构。只允许普通相对路径，敏感账号、密钥和存档路径会被阻止。",
                objectSchema(new JSONObject().put("root_id", stringSchema("list_scan_roots 返回的 root_id", 16))
                                .put("relative_path", stringSchema("扫描目录内相对目录；根目录使用空字符串", 512))
                                .put("depth", integerSchema(0, 6)).put("limit", integerSchema(1, 200)),
                        new JSONArray().put("root_id").put("relative_path").put("depth").put("limit"))));
        tools.put(tool("organize_scan_root", "整理用户扫描目录。mkdir 创建目录；rename 用 destination_path 指定同级新路径；move 用 destination_path 指定已存在的目标目录并保留原名称。不支持永久删除。受限模式逐次确认，移动或重命名会同步已登记游戏路径。",
                objectSchema(new JSONObject().put("root_id", stringSchema("list_scan_roots 返回的 root_id", 16))
                                .put("operation", enumSchema("mkdir", "rename", "move"))
                                .put("relative_path", stringSchema("要创建或整理的相对路径", 512))
                                .put("destination_path", stringSchema("rename 的同级目标路径，或 move 的目标目录；根目录用空字符串", 512)),
                        new JSONArray().put("root_id").put("operation").put("relative_path"))));
        tools.put(tool("run_agent_workspace_command", "操作 Rinne 自己的应用私有工作目录。支持 list/read/stat/write/append/mkdir/copy/move/delete；可自由增删改查，无需用户确认。它不是系统 Shell，不能访问扫描目录、游戏目录或应用私有目录之外的位置。",
                objectSchema(new JSONObject().put("command", enumSchema("list", "read", "stat", "write", "append", "mkdir", "copy", "move", "delete"))
                                .put("relative_path", stringSchema("Rinne 工作目录内的普通相对路径；list 根目录可用空字符串", 512))
                                .put("secondary_path", stringSchema("copy/move 的目标相对路径", 512))
                                .put("content", stringSchema("write/append 写入的 UTF-8 文本", 128 * 1024))
                                .put("depth", integerSchema(0, 4)).put("limit", integerSchema(1, 200)),
                        new JSONArray().put("command").put("relative_path"))));
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
