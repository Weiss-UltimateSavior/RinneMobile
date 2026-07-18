package com.yuki.yukihub.sync;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WebDavClientTest {

    @Test
    public void allowsExactLoopbackHttpHosts() {
        assertFalse(WebDavClient.isInsecureHttp("http://localhost:8080/dav"));
        assertFalse(WebDavClient.isInsecureHttp("http://127.0.0.1:8080/dav"));
    }

    @Test
    public void allowsHttpsHosts() {
        assertFalse(WebDavClient.isInsecureHttp("https://dav.example.com"));
    }

    @Test
    public void rejectsRemoteAndPrefixLookalikeHttpHosts() {
        assertTrue(WebDavClient.isInsecureHttp("http://dav.example.com"));
        assertTrue(WebDavClient.isInsecureHttp("http://localhost.example.com"));
        assertTrue(WebDavClient.isInsecureHttp("http://127.0.0.10"));
        assertTrue(WebDavClient.isInsecureHttp("http://[::1]:8080/dav"));
    }
}
