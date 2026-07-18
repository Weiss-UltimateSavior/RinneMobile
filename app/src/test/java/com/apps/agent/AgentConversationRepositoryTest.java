package com.apps.agent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AgentConversationRepositoryTest {
    @Test public void stripsLegacyRepeatedNullPrefixOnlyFromAssistantResults() {
        assertEquals("最终答案", AgentConversationRepository.sanitizeLegacyNullPrefix(
                "assistant", "nullnullnull最终答案"));
        assertEquals("null value", AgentConversationRepository.sanitizeLegacyNullPrefix(
                "assistant", "null value"));
        assertEquals("nullnull用户原文", AgentConversationRepository.sanitizeLegacyNullPrefix(
                "user", "nullnull用户原文"));
    }
}
