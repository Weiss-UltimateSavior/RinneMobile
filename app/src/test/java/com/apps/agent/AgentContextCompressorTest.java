package com.apps.agent;

import org.json.JSONArray;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AgentContextCompressorTest {
    @Test public void compressesOldHistoryAndKeepsRecentMessages() {
        List<OpenAiCompatibleAgentClient.ModelMessage> values = new ArrayList<>();
        values.add(new OpenAiCompatibleAgentClient.ModelMessage("system", "rules"));
        for (int i = 0; i < 30; i++) values.add(new OpenAiCompatibleAgentClient.ModelMessage(
                i % 2 == 0 ? "user" : "assistant", "message-" + i + "-" + "x".repeat(2000)));

        List<OpenAiCompatibleAgentClient.ModelMessage> compact =
                AgentContextCompressor.compact(values, 24 * 1024);

        assertTrue(compact.size() < values.size());
        assertTrue(AgentContextCompressor.estimatedChars(compact) <= 24 * 1024);
        assertEquals("system", compact.get(0).role);
        assertTrue(compact.get(1).content.contains("自动压缩"));
        assertTrue(compact.get(compact.size() - 1).content.contains("message-29"));
    }

    @Test public void preservesLatestToolCallPair() throws Exception {
        List<OpenAiCompatibleAgentClient.ModelMessage> values = new ArrayList<>();
        values.add(new OpenAiCompatibleAgentClient.ModelMessage("system", "s"));
        for (int i = 0; i < 15; i++) values.add(new OpenAiCompatibleAgentClient.ModelMessage("user", "x".repeat(3000)));
        JSONArray calls = new JSONArray().put(new org.json.JSONObject().put("id", "call_1"));
        values.add(new OpenAiCompatibleAgentClient.ModelMessage("assistant", "", "", "", calls));
        values.add(new OpenAiCompatibleAgentClient.ModelMessage("tool", "result", "demo", "call_1", null));

        List<OpenAiCompatibleAgentClient.ModelMessage> compact =
                AgentContextCompressor.compact(values, 20 * 1024);

        assertEquals("assistant", compact.get(compact.size() - 2).role);
        assertEquals("tool", compact.get(compact.size() - 1).role);
        assertEquals("call_1", compact.get(compact.size() - 1).toolCallId);
    }
}
