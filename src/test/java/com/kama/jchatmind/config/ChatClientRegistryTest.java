package com.kama.jchatmind.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.lang.reflect.Constructor;
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

    @Test
    void exposesConfiguredOfficialModelNameAsAlias() throws Exception {
        ChatClient client = mock(ChatClient.class);
        Constructor<ChatClientRegistry> constructor =
                ChatClientRegistry.class.getDeclaredConstructor(Map.class, String.class, boolean.class);
        constructor.setAccessible(true);
        ChatClientRegistry registry = constructor.newInstance(
                Map.of("deepseek-official-chat", client), "gpt-5.5", true);

        assertSame(client, registry.get("gpt-5.5"));
        assertSame(client, registry.get("deepseek-official-chat"));
    }
}
