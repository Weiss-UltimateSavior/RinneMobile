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
}
