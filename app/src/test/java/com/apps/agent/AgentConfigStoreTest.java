package com.apps.agent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class AgentConfigStoreTest {
    @Test public void buildsCompatibleChatCompletionUrls() {
        assertEquals("https://api.example.com/v1/chat/completions",
                AgentConfigStore.chatCompletionsUrl("https://api.example.com"));
        assertEquals("https://api.example.com/v1/chat/completions",
                AgentConfigStore.chatCompletionsUrl("https://api.example.com/v1/"));
        assertEquals("https://api.example.com/custom/chat/completions",
                AgentConfigStore.chatCompletionsUrl("https://api.example.com/custom/chat/completions"));
    }

    @Test public void permitsHttpsAndExactLocalHttpOnly() {
        assertEquals("https://api.example.com/v1", AgentConfigStore.validateBaseUrl("https://api.example.com/v1/"));
        assertEquals("http://localhost:11434/v1", AgentConfigStore.validateBaseUrl("http://localhost:11434/v1"));
        assertThrows(IllegalArgumentException.class,
                () -> AgentConfigStore.validateBaseUrl("http://localhost.evil.example/v1"));
        assertThrows(IllegalArgumentException.class,
                () -> AgentConfigStore.validateBaseUrl("http://192.168.1.2/v1"));
    }

    @Test public void rejectsCredentialsQueriesAndFragments() {
        assertThrows(IllegalArgumentException.class,
                () -> AgentConfigStore.validateBaseUrl("https://user:pass@example.com/v1"));
        assertThrows(IllegalArgumentException.class,
                () -> AgentConfigStore.validateBaseUrl("https://example.com/v1?key=secret"));
        assertThrows(IllegalArgumentException.class,
                () -> AgentConfigStore.validateBaseUrl("https://example.com/v1#fragment"));
    }

    @Test public void validatesExecutionSettings() {
        assertEquals(1, AgentConfigStore.validateToolCalls(1));
        assertEquals(50, AgentConfigStore.validateToolCalls(50));
        assertThrows(IllegalArgumentException.class, () -> AgentConfigStore.validateToolCalls(0));
        assertThrows(IllegalArgumentException.class, () -> AgentConfigStore.validateToolCalls(51));
        assertEquals(16, AgentConfigStore.validateContextBudgetKb(16));
        assertEquals(72, AgentConfigStore.validateContextBudgetKb(72));
        assertEquals(1024, AgentConfigStore.validateContextBudgetKb(1024));
        assertThrows(IllegalArgumentException.class, () -> AgentConfigStore.validateContextBudgetKb(15));
        assertThrows(IllegalArgumentException.class, () -> AgentConfigStore.validateContextBudgetKb(1025));
        assertEquals(AgentConfigStore.PERMISSION_FULL,
                AgentConfigStore.validatePermissionMode("full"));
        assertThrows(IllegalArgumentException.class,
                () -> AgentConfigStore.validatePermissionMode("root"));
    }
}
