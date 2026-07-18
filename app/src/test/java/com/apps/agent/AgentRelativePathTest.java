package com.apps.agent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class AgentRelativePathTest {
    @Test public void acceptsNormalGameRelativePaths() {
        assertEquals("data/config.ini", AgentRelativePath.normalize("data/config.ini", false));
        assertEquals("", AgentRelativePath.normalize("", true));
    }

    @Test public void rejectsTraversalUrisAndAmbiguousSegments() {
        String[] values = {"../config.ini", "data/../config.ini", "/etc/passwd", "content://x",
                "C:/game", "data\\config.ini", "data//config.ini", "./config.ini", "data/%2e%2e/x"};
        for (String value : values) {
            assertThrows(value, IllegalArgumentException.class, () -> AgentRelativePath.normalize(value, false));
        }
        assertThrows(IllegalArgumentException.class, () -> AgentRelativePath.normalize(" config.ini ", false));
    }

    @Test public void blocksCommonSecretAndAccountPaths() {
        org.junit.Assert.assertTrue(AgentRelativePath.isSensitive(".env"));
        org.junit.Assert.assertTrue(AgentRelativePath.isSensitive("config/private.key"));
        org.junit.Assert.assertTrue(AgentRelativePath.isSensitive("savedata/slot1.dat"));
        org.junit.Assert.assertTrue(AgentRelativePath.isSensitive("www/save/file1.rpgsave"));
        org.junit.Assert.assertTrue(AgentRelativePath.isSensitive("config/credentials.json"));
        org.junit.Assert.assertTrue(AgentRelativePath.isSensitive("auth/token.json"));
        org.junit.Assert.assertTrue(AgentRelativePath.isSensitive("account.json"));
        org.junit.Assert.assertTrue(AgentRelativePath.isSensitive("config/api_key.json"));
        org.junit.Assert.assertTrue(AgentRelativePath.isSensitive("client-secret.txt"));
        org.junit.Assert.assertTrue(AgentRelativePath.isSensitive("session.json"));
        org.junit.Assert.assertTrue(AgentRelativePath.isSensitive("cookies.sqlite"));
        org.junit.Assert.assertTrue(AgentRelativePath.isSensitive("access_token.json"));
        org.junit.Assert.assertTrue(AgentRelativePath.isSensitive("refresh_token.txt"));
        org.junit.Assert.assertTrue(AgentRelativePath.isSensitive("authToken.json"));
        org.junit.Assert.assertTrue(AgentRelativePath.isSensitive("user_password.ini"));
        org.junit.Assert.assertTrue(AgentRelativePath.isSensitive("service_account.json"));
        org.junit.Assert.assertTrue(AgentRelativePath.isSensitive("account_backup.json"));
        org.junit.Assert.assertFalse(AgentRelativePath.isSensitive("data/config.ini"));
    }
}
