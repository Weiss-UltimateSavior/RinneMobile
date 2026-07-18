package com.apps.agent;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class McpHttpClientTest {
    @Test public void compactsToolListWithoutLargeOutputSchema() throws Exception {
        JSONObject tool = new JSONObject().put("name", "demo")
                .put("description", "Demo tool")
                .put("inputSchema", new JSONObject().put("type", "object"))
                .put("outputSchema", new JSONObject().put("huge", "x".repeat(10000)));
        JSONArray compact = McpHttpClient.compactTools(new JSONArray().put(tool));
        JSONObject result = compact.getJSONObject(0);
        org.junit.Assert.assertEquals("demo", result.getString("name"));
        org.junit.Assert.assertTrue(result.has("inputSchema"));
        org.junit.Assert.assertFalse(result.has("outputSchema"));
        org.junit.Assert.assertTrue(compact.toString().length() < 1000);
    }

    @Test public void parsesJsonRpcJsonAndSseBodies() throws Exception {
        assertEquals(1, McpHttpClient.parseRpcPayload("{\"result\":{\"value\":1}}").getJSONObject("result").getInt("value"));
        JSONObject sse = McpHttpClient.parseRpcPayload("event: message\ndata: {\"result\":{\"value\":2}}\n\n");
        assertEquals(2, sse.getJSONObject("result").getInt("value"));
    }

    @Test public void skipsSseProgressEventsAndReturnsRpcResult() throws Exception {
        String body = "event: progress\n"
                + "data: {\"method\":\"notifications/progress\",\"params\":{\"progress\":1}}\n\n"
                + "event: message\n"
                + "data: {\"result\":{\"value\":3}}\n\n";
        assertEquals(3, McpHttpClient.parseRpcPayload(body)
                .getJSONObject("result").getInt("value"));
    }

    @Test public void rejectsEmptyMcpResponse() {
        assertThrows(Exception.class, () -> McpHttpClient.parseRpcPayload("  "));
    }

    @Test public void cancellationPreventsFutureSessionRequests() {
        McpHttpClient client = new McpHttpClient();
        client.cancel();
        McpServerStore.Server server = new McpServerStore.Server(
                "00000000-0000-0000-0000-000000000001", "Demo", "https://example.com/mcp", 1L);
        assertThrows(InterruptedException.class, () -> client.open(server));
    }

    @Test public void cancellationAbortsActiveHttpCall() throws Exception {
        AtomicInteger toolRequests = new AtomicInteger();
        McpHttpClient client = new McpHttpClient(toolRequests::incrementAndGet);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch accepted = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            Future<?> server = executor.submit(() -> {
                try (Socket ignored = serverSocket.accept()) {
                    accepted.countDown();
                    releaseServer.await(3, TimeUnit.SECONDS);
                }
                return null;
            });
            McpServerStore.Server endpoint = new McpServerStore.Server(
                    "00000000-0000-0000-0000-000000000001", "Demo",
                    "http://127.0.0.1:" + serverSocket.getLocalPort() + "/mcp", 1L);
            McpHttpClient.Session session = new McpHttpClient.Session(
                    endpoint, "test-session", "2025-06-18");
            Future<?> request = executor.submit(() -> client.callTool(session, "demo", new JSONObject()));
            assertTrue(accepted.await(2, TimeUnit.SECONDS));
            assertEquals(1, toolRequests.get());
            client.cancel();
            assertThrows(ExecutionException.class, () -> request.get(2, TimeUnit.SECONDS));
            releaseServer.countDown();
            server.get(2, TimeUnit.SECONDS);
        } finally {
            releaseServer.countDown();
            executor.shutdownNow();
        }
    }

    @Test public void cancellationBeforeToolRequestDoesNotEmitStartedAudit() {
        AtomicInteger toolRequests = new AtomicInteger();
        McpHttpClient client = new McpHttpClient(toolRequests::incrementAndGet);
        client.cancel();
        McpServerStore.Server endpoint = new McpServerStore.Server(
                "00000000-0000-0000-0000-000000000001", "Demo", "https://example.com/mcp", 1L);
        McpHttpClient.Session session = new McpHttpClient.Session(endpoint, "session", "2025-06-18");
        assertThrows(InterruptedException.class,
                () -> client.callTool(session, "demo", new JSONObject()));
        assertEquals(0, toolRequests.get());
    }

    @Test public void formatsObjectAndStringRpcErrors() throws Exception {
        assertEquals("bad request", McpHttpClient.rpcErrorMessage(
                new JSONObject().put("message", "bad request")));
        assertEquals("server unavailable", McpHttpClient.rpcErrorMessage("server unavailable"));
        assertEquals("未知错误", McpHttpClient.rpcErrorMessage(JSONObject.NULL));
    }
}
