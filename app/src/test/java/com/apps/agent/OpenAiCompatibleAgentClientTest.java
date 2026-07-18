package com.apps.agent;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class OpenAiCompatibleAgentClientTest {
    @Test public void mergesStreamingToolCallChunksAndStopsAtDone() throws Exception {
        String stream =
                "data: {\"choices\":[{\"delta\":{\"content\":\"查询中\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"list_\",\"arguments\":\"{\\\"lim\"}}]}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"games\",\"arguments\":\"it\\\":5}\"}}]}}]}\n\n" +
                "data: [DONE]\n" +
                "data: {not-json-after-done}\n";
        OpenAiCompatibleAgentClient.Result result = new OpenAiCompatibleAgentClient().parseStream(
                new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)), null);
        assertEquals("查询中", result.content);
        assertEquals(1, result.toolCalls.size());
        assertEquals("list_games", result.toolCalls.get(0).name);
        assertEquals("{\"limit\":5}", result.toolCalls.get(0).arguments);
    }

    @Test public void rejectsOversizedSseLineBeforeJsonParsing() {
        StringBuilder value = new StringBuilder("data: ");
        for (int i = 0; i < 270_000; i++) value.append('a');
        value.append('\n');
        assertThrows(IOException.class, () -> new OpenAiCompatibleAgentClient().parseStream(
                new ByteArrayInputStream(value.toString().getBytes(StandardCharsets.UTF_8)), null));
    }

    @Test public void rejectsOutOfRangeToolCallIndex() {
        String stream = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":16,\"function\":{\"name\":\"x\",\"arguments\":\"{}\"}}]}}]}\n";
        assertThrows(IOException.class, () -> new OpenAiCompatibleAgentClient().parseStream(
                new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)), null));
    }

    @Test public void rejectsAccumulatedSseBytesAcrossSmallFrames() {
        StringBuilder stream = new StringBuilder();
        String frame = "data: {\"choices\":[]}\n";
        while (stream.length() <= 2 * 1024 * 1024) stream.append(frame);
        assertThrows(IOException.class, () -> new OpenAiCompatibleAgentClient().parseStream(
                new ByteArrayInputStream(stream.toString().getBytes(StandardCharsets.UTF_8)), null));
    }

    @Test public void rejectsAccumulatedContentAcrossChunks() {
        String chunk = repeat('a', 100_000);
        StringBuilder stream = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            stream.append("data: {\"choices\":[{\"delta\":{\"content\":\"")
                    .append(chunk).append("\"}}]}\n");
        }
        assertThrows(IOException.class, () -> new OpenAiCompatibleAgentClient().parseStream(
                new ByteArrayInputStream(stream.toString().getBytes(StandardCharsets.UTF_8)), null));
    }

    @Test public void rejectsAccumulatedToolArgumentsAcrossChunks() {
        String chunk = repeat('b', 90_000);
        StringBuilder stream = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            stream.append("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"")
                    .append(chunk).append("\"}}]}}]}\n");
        }
        assertThrows(IOException.class, () -> new OpenAiCompatibleAgentClient().parseStream(
                new ByteArrayInputStream(stream.toString().getBytes(StandardCharsets.UTF_8)), null));
    }

    @Test public void rejectsOversizedNonStreamingBody() {
        byte[] bytes = new byte[2 * 1024 * 1024 + 1];
        assertThrows(IOException.class, () -> OpenAiCompatibleAgentClient.readLimited(
                new ByteArrayInputStream(bytes), 2 * 1024 * 1024));
    }

    @Test public void truncatesErrorsAndRedactsExactArbitraryApiKey() throws Exception {
        String key = "密钥/with+punctuation?=yes";
        String body = "provider says " + key + " " + repeat('x', 6000);
        String truncated = OpenAiCompatibleAgentClient.readTruncated(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), 4096);
        String safe = OpenAiCompatibleAgentClient.sanitizeError(truncated, key);
        assertFalse(safe.contains(key));
        assertFalse(safe.length() > 4100);
    }

    @Test public void streamingProviderErrorIncludesDiagnosticFields() {
        String stream = "data: {\"error\":{\"message\":\"model not found\",\"type\":\"invalid_request_error\",\"code\":\"model_missing\"}}\n";
        IOException error = assertThrows(IOException.class, () -> new OpenAiCompatibleAgentClient().parseStream(
                new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)), null));
        assertTrue(error.getMessage().contains("model not found"));
        assertTrue(error.getMessage().contains("invalid_request_error"));
        assertTrue(error.getMessage().contains("model_missing"));
    }

    @Test public void completeProviderErrorIsNotMisreportedAsMissingChoices() throws Exception {
        org.json.JSONObject response = new org.json.JSONObject().put("error", new org.json.JSONObject()
                .put("message", "invalid api key").put("type", "authentication_error"));
        IOException error = assertThrows(IOException.class,
                () -> new OpenAiCompatibleAgentClient().parseComplete(response, null));
        assertTrue(error.getMessage().contains("invalid api key"));
        assertTrue(error.getMessage().contains("authentication_error"));
        assertFalse(error.getMessage().contains("缺少 choices"));
    }

    @Test public void separatesReasoningFromFinalContentDuringStream() throws Exception {
        String stream =
                "data: {\"choices\":[{\"delta\":{\"content\":null,\"reasoning_content\":\"thinking\"}}]}\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":null,\"reasoning_content\":\"more\"}}]}\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"最终答案\"}}]}\n" +
                "data: [DONE]\n";
        OpenAiCompatibleAgentClient.Result result = new OpenAiCompatibleAgentClient().parseStream(
                new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)), null);
        assertEquals("最终答案", result.content);
        assertEquals("thinkingmore", result.reasoningContent);
        assertFalse(result.content.contains("null"));
    }

    @Test public void acceptsNullCompleteContentWhenToolCallExists() throws Exception {
        org.json.JSONObject response = new org.json.JSONObject().put("choices", new org.json.JSONArray().put(
                new org.json.JSONObject().put("message", new org.json.JSONObject()
                        .put("content", org.json.JSONObject.NULL)
                        .put("tool_calls", new org.json.JSONArray().put(new org.json.JSONObject()
                                .put("id", "call_1").put("function", new org.json.JSONObject()
                                        .put("name", "list_games").put("arguments", "{\"limit\":5}")))))));
        OpenAiCompatibleAgentClient.Result result = new OpenAiCompatibleAgentClient().parseComplete(response, null);
        assertEquals("", result.content);
        assertEquals(1, result.toolCalls.size());
        assertEquals("list_games", result.toolCalls.get(0).name);
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
}
