package com.apps.agent;

import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AgentOutcomeGuardTest {
    @Test public void localMcpRegistrationOverridesModelFailureClaim() {
        String confirmation = "MCP「Demo」已在本机添加成功。";
        String value = AgentOutcomeGuard.enforceMcpRegistration("抱歉，MCP 没有添加成功。", confirmation);
        org.junit.Assert.assertTrue(value.startsWith(confirmation));
        org.junit.Assert.assertFalse(value.contains("抱歉"));
    }

    @Test public void localMcpRegistrationIsPrependedWhenModelIsAmbiguous() {
        String confirmation = "MCP「Demo」已在本机添加成功。";
        String value = AgentOutcomeGuard.enforceMcpRegistration("可以继续列出工具。", confirmation);
        org.junit.Assert.assertTrue(value.startsWith(confirmation));
    }

    @Test public void savedMcpRegistryOverridesMissingConfirmationClaimOnLaterTurn() {
        String summary = "本机当前已确认并保存的 MCP：\n- Demo（https://example.com/mcp）";
        String value = AgentOutcomeGuard.enforceMcpRegistry(
                "MCP 没有弹窗确认，所以还没有保存。", summary);
        org.junit.Assert.assertTrue(value.startsWith(summary));
        org.junit.Assert.assertFalse(value.contains("所以还没有保存"));
    }

    @Test public void blocksRestoreSuccessClaimWithoutSuccessfulRestoreTool() {
        String value = AgentOutcomeGuard.enforce("✅ 快照恢复成功！文件已恢复到原始状态。",
                Collections.emptySet());
        assertTrue(value.contains("没有获得 restore_game_snapshot 的本地成功结果"));
        assertTrue(value.contains("不能确认"));
    }

    @Test public void allowsRestoreSuccessClaimAfterSuccessfulRestoreTool() {
        Set<String> successes = new HashSet<>();
        successes.add("restore_game_snapshot");
        String expected = "✅ 快照恢复成功！文件已恢复到原始状态。";
        assertEquals(expected, AgentOutcomeGuard.enforce(expected, successes));
    }

    @Test public void blocksReplaceSuccessClaimWithoutSuccessfulReplaceTool() {
        String value = AgentOutcomeGuard.enforce("替换已成功执行！", Collections.emptySet());
        assertTrue(value.contains("没有获得 replace_game_text 的本地成功结果"));
    }

    @Test public void allowsExplicitFailureExplanation() {
        String expected = "文件未恢复成功，请检查当前 SHA-256。";
        assertEquals(expected, AgentOutcomeGuard.enforce(expected, Collections.emptySet()));
    }

    @Test public void doesNotTreatOrdinaryReadSuccessAsMutation() {
        String expected = "目录读取成功，共找到两个文件。";
        assertEquals(expected, AgentOutcomeGuard.enforce(expected, Collections.emptySet()));
    }

    @Test public void privateWorkspaceSuccessRequiresRealToolResult() {
        String blocked = AgentOutcomeGuard.enforce("Rinne 工作目录文件已写入成功。", Collections.emptySet());
        assertTrue(blocked.contains("run_agent_workspace_command"));

        Set<String> successes = new HashSet<>();
        successes.add("run_agent_workspace_command");
        assertEquals("Rinne 工作目录文件已写入成功。",
                AgentOutcomeGuard.enforce("Rinne 工作目录文件已写入成功。", successes));
    }

    @Test public void scanRootSuccessRequiresRealToolResult() {
        String blocked = AgentOutcomeGuard.enforce("游戏扫描目录整理完成。", Collections.emptySet());
        assertTrue(blocked.contains("organize_scan_root"));
        assertTrue(AgentOutcomeGuard.enforce("扫描目录已重命名。", Collections.emptySet())
                .contains("organize_scan_root"));
    }

    @Test public void allowsScanRootSuccessClaimAfterSuccessfulOrganizeTool() {
        Set<String> successes = new HashSet<>();
        successes.add("organize_scan_root");
        String expected = "游戏扫描目录整理完成。";
        assertEquals(expected, AgentOutcomeGuard.enforce(expected, successes));
    }
}
