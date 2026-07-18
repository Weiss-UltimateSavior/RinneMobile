package com.apps.agent;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class McpServerStoreTest {
    @Test public void acceptsHttpsAndLoopbackHttpEndpoints() {
        assertEquals("https://mcp.example.com/api", McpServerStore.validateEndpoint("https://mcp.example.com/api/"));
        assertEquals("http://localhost:3000/mcp", McpServerStore.validateEndpoint("http://localhost:3000/mcp"));
        assertEquals("http://127.0.0.1:8787/mcp", McpServerStore.validateEndpoint("http://127.0.0.1:8787/mcp"));
    }

    @Test public void rejectsInsecureRemoteAndCredentialedEndpoints() {
        assertThrows(IllegalArgumentException.class, () -> McpServerStore.validateEndpoint("http://mcp.example.com"));
        assertThrows(IllegalArgumentException.class, () -> McpServerStore.validateEndpoint("http://8.8.8.8/mcp"));
        assertThrows(IllegalArgumentException.class, () -> McpServerStore.validateEndpoint("http://192.168.1.4/mcp"));
        assertThrows(IllegalArgumentException.class, () -> McpServerStore.validateEndpoint("http://fc.evil.com/mcp"));
        assertThrows(IllegalArgumentException.class, () -> McpServerStore.validateEndpoint("https://user:pass@mcp.example.com"));
        assertThrows(IllegalArgumentException.class, () -> McpServerStore.validateEndpoint("https://mcp.example.com/mcp?token=x"));
    }

    @Test public void privateHostDetectionDoesNotTreatDnsNamesAsIpv6Literals() {
        org.junit.Assert.assertFalse(McpServerStore.isPrivateNetworkHost("fc.evil.com"));
        org.junit.Assert.assertTrue(McpServerStore.isPrivateNetworkHost("fd00::1"));
    }

    @Test public void rejectsControlCharactersInServerName() {
        assertThrows(IllegalArgumentException.class, () -> McpServerStore.validateName("bad\nname"));
    }

    @Test public void storageRoundTripPreservesServerId() throws Exception {
        String id = "d93c72bb-c469-4c26-892b-970694c4bbaf";
        McpServerStore.Server original = new McpServerStore.Server(
                id, "mt管理器", "http://127.0.0.1:8787/mcp", 123L);
        String encoded = McpServerStore.encode(Collections.singletonList(original));

        List<McpServerStore.Server> decoded = McpServerStore.decode(encoded);

        assertEquals(1, decoded.size());
        assertEquals(id, decoded.get(0).id);
        assertEquals("mt管理器", decoded.get(0).name);
        assertEquals("http://127.0.0.1:8787/mcp", decoded.get(0).endpoint);
    }

    @Test public void decoderAcceptsLegacyIdField() throws Exception {
        String legacy = "[{\"id\":\"00000000-0000-0000-0000-000000000001\","
                + "\"name\":\"Legacy\",\"endpoint\":\"https://example.com/mcp\",\"created_at\":1}]";
        assertEquals(1, McpServerStore.decode(legacy).size());
    }
}
