package com.apps.agent;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertThrows;

public class AgentToolRegistryTest {
    @Test public void validatesMcpArgumentsAndRejectsUnexpectedFields() throws Exception {
        AgentToolRegistry.validateArguments("add_mcp_server", new JSONObject()
                .put("name", "Demo MCP").put("endpoint", "https://mcp.example.com/mcp"));
        AgentToolRegistry.validateArguments("mcp_call_tool", new JSONObject()
                .put("server_id", "00000000-0000-0000-0000-000000000001")
                .put("tool_name", "demo.search").put("arguments", new JSONObject().put("query", "x")));
        assertThrows(IllegalArgumentException.class, () -> AgentToolRegistry.validateArguments("add_mcp_server",
                new JSONObject().put("name", "Demo").put("endpoint", "https://mcp.example.com").put("token", "secret")));
    }
    @Test public void rejectsUnknownAndMistypedArguments() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> AgentToolRegistry.validateArguments(
                "list_games", new JSONObject().put("limit", 10).put("root_uri", "content://secret")));
        assertThrows(IllegalArgumentException.class, () -> AgentToolRegistry.validateArguments(
                "list_games", new JSONObject().put("limit", "10")));
        assertThrows(IllegalArgumentException.class, () -> AgentToolRegistry.validateArguments(
                "list_games", new JSONObject().put("limit", 10).put("status", "deleted")));
    }

    @Test public void rejectsMissingRequiredArgumentsAndUnknownTools() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> AgentToolRegistry.validateArguments(
                "get_game_detail", new JSONObject()));
        assertThrows(IllegalArgumentException.class, () -> AgentToolRegistry.validateArguments(
                "run_shell", new JSONObject()));
    }

    @Test public void acceptsValidReadOnlyArguments() throws Exception {
        AgentToolRegistry.validateArguments("list_games", new JSONObject()
                .put("limit", 10).put("status", "playing").put("favorite_only", true));
        AgentToolRegistry.validateArguments("get_game_detail", new JSONObject().put("game_id", 42));
        AgentToolRegistry.validateArguments("get_library_statistics", new JSONObject());
    }

    @Test public void validatesApprovedWriteToolArgumentsLocally() throws Exception {
        JSONObject valid = new JSONObject().put("game_id", 1).put("relative_path", "data/config.ini")
                .put("expected_sha256", "a".repeat(64)).put("old_text", "fullscreen=0")
                .put("new_text", "fullscreen=1").put("encoding", "utf-8");
        AgentToolRegistry.validateArguments("replace_game_text", valid);
        valid.put("relative_path", "../config.ini");
        assertThrows(IllegalArgumentException.class,
                () -> AgentToolRegistry.validateArguments("replace_game_text", valid));
    }

    @Test public void constrainedCommandRejectsShellSyntaxAndMissingOperands() throws Exception {
        JSONObject base = new JSONObject().put("game_id", 1).put("relative_path", "")
                .put("query", "text").put("encoding", "utf-8").put("limit", 20);
        assertThrows(IllegalArgumentException.class, () -> AgentToolRegistry.validateArguments(
                "run_game_workspace_command", new JSONObject(base.toString()).put("command", "sh")));
        assertThrows(IllegalArgumentException.class, () -> AgentToolRegistry.validateArguments(
                "run_game_workspace_command", new JSONObject(base.toString()).put("command", "cat")));
        AgentToolRegistry.validateArguments("run_game_workspace_command",
                new JSONObject(base.toString()).put("command", "grep"));
    }

    @Test public void acceptsAllExpandedReadOnlyWorkspaceCommands() throws Exception {
        String[] simpleFileCommands = {"stat", "head", "tail", "json_validate", "xml_validate",
                "archive_list", "encoding_detect", "text_count"};
        for (String command : simpleFileCommands) {
            AgentToolRegistry.validateArguments("run_game_workspace_command", new JSONObject()
                    .put("game_id", 1).put("command", command).put("relative_path", "config/settings.json")
                    .put("encoding", "auto").put("limit", 30));
        }
        AgentToolRegistry.validateArguments("run_game_workspace_command", new JSONObject()
                .put("game_id", 1).put("command", "tree").put("relative_path", "")
                .put("depth", 6).put("limit", 100));
        AgentToolRegistry.validateArguments("run_game_workspace_command", new JSONObject()
                .put("game_id", 1).put("command", "diff").put("relative_path", "config/a.ini")
                .put("secondary_path", "config/b.ini"));
        AgentToolRegistry.validateArguments("run_game_workspace_command", new JSONObject()
                .put("game_id", 1).put("command", "json_get").put("relative_path", "config/settings.json")
                .put("pointer", "/graphics/fps"));
        AgentToolRegistry.validateArguments("run_game_workspace_command", new JSONObject()
                .put("game_id", 1).put("command", "ini_get").put("relative_path", "config/settings.ini")
                .put("section", "graphics").put("key", "fullscreen"));
    }

    @Test public void expandedCommandsRejectMissingOrUnsafeOperands() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> AgentToolRegistry.validateArguments(
                "run_game_workspace_command", new JSONObject().put("game_id", 1).put("command", "diff")
                        .put("relative_path", "config/a.ini")));
        assertThrows(IllegalArgumentException.class, () -> AgentToolRegistry.validateArguments(
                "run_game_workspace_command", new JSONObject().put("game_id", 1).put("command", "diff")
                        .put("relative_path", "config/a.ini").put("secondary_path", "../b.ini")));
        assertThrows(IllegalArgumentException.class, () -> AgentToolRegistry.validateArguments(
                "run_game_workspace_command", new JSONObject().put("game_id", 1).put("command", "json_get")
                        .put("relative_path", "config/settings.json")));
        assertThrows(IllegalArgumentException.class, () -> AgentToolRegistry.validateArguments(
                "run_game_workspace_command", new JSONObject().put("game_id", 1).put("command", "ini_get")
                        .put("relative_path", "config/settings.ini")));
        assertThrows(IllegalArgumentException.class, () -> AgentToolRegistry.validateArguments(
                "run_game_workspace_command", new JSONObject().put("game_id", 1).put("command", "tree")
                        .put("relative_path", "").put("depth", 9)));
    }

    @Test public void validatesScanRootAndPrivateWorkspaceTools() throws Exception {
        AgentToolRegistry.validateArguments("list_scan_roots", new JSONObject());
        AgentToolRegistry.validateArguments("list_scan_root_files", new JSONObject()
                .put("root_id", "0123456789abcdef").put("relative_path", "")
                .put("depth", 2).put("limit", 100));
        AgentToolRegistry.validateArguments("organize_scan_root", new JSONObject()
                .put("root_id", "0123456789abcdef").put("operation", "rename")
                .put("relative_path", "Old Game").put("destination_path", "New Game"));
        AgentToolRegistry.validateArguments("run_agent_workspace_command", new JSONObject()
                .put("command", "write").put("relative_path", "plans/task.md").put("content", "plan"));

        assertThrows(IllegalArgumentException.class, () -> AgentToolRegistry.validateArguments(
                "organize_scan_root", new JSONObject().put("root_id", "bad")
                        .put("operation", "move").put("relative_path", "Game").put("destination_path", "")));
        assertThrows(IllegalArgumentException.class, () -> AgentToolRegistry.validateArguments(
                "organize_scan_root", new JSONObject().put("root_id", "0123456789abcdef")
                        .put("operation", "delete").put("relative_path", "Game")));
        assertThrows(IllegalArgumentException.class, () -> AgentToolRegistry.validateArguments(
                "run_agent_workspace_command", new JSONObject().put("command", "delete").put("relative_path", "")));
    }
}
