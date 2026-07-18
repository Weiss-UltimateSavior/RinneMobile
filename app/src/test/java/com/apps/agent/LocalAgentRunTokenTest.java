package com.apps.agent;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public class LocalAgentRunTokenTest {
    @Test public void cancellationWinsBeforeCommit() {
        LocalAgentRuntime.RunToken token = new LocalAgentRuntime.RunToken();
        assertTrue(token.cancel());
        assertFalse(token.tryCommit());
        assertTrue(token.isCancelled());
    }

    @Test public void commitWinsBeforeLateCancellation() {
        LocalAgentRuntime.RunToken token = new LocalAgentRuntime.RunToken();
        assertTrue(token.tryCommit());
        assertFalse(token.cancel());
        assertFalse(token.isCancelled());
    }

    @Test public void interruptedRemoteMcpUsesUnknownStatusInsteadOfClaimingSuccess() {
        assertEquals("远程 MCP 调用已停止，服务器端是否执行成功未知；调用审计已保留。",
                LocalAgentRuntime.failureMessage(true, false, true, "", "cancelled"));
    }
}
