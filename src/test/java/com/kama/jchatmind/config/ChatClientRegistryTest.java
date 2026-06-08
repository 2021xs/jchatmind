package com.kama.jchatmind.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class ChatClientRegistryTest {

    @Test
    void exposesDeepSeekClientThroughLegacyAndCanonicalNames() {
        ChatClient client = mock(ChatClient.class);
        ChatClientRegistry registry = new ChatClientRegistry(Map.of("deepseek-official-chat", client));

        assertSame(client, registry.get("deepseek-official-chat"));
        assertSame(client, registry.get("deepseek-chat"));
    }
}
