package com.kama.jchatmind.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotSame;
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
    void exposesConfiguredDeepSeekModelNameAsDeepSeekAlias() {
        ChatClient client = mock(ChatClient.class);
        ChatClientRegistry registry = new ChatClientRegistry(
                Map.of("deepseek-official-chat", client), "deepseek-reasoner", null, true);

        assertSame(client, registry.get("deepseek-reasoner"));
        assertSame(client, registry.get("deepseek-official-chat"));
    }

    @Test
    void exposesConfiguredGptModelNameAsGptAlias() {
        ChatClient client = mock(ChatClient.class);
        ChatClientRegistry registry = new ChatClientRegistry(
                Map.of("gpt-compatible-chat", client), null, "gpt-5.5", true);

        assertSame(client, registry.get("gpt-5.5"));
        assertSame(client, registry.get("gpt-compatible-chat"));
    }

    @Test
    void exposesWebConsoleGptOptionEvenWhenConfiguredGptModelNameDiffers() {
        ChatClient client = mock(ChatClient.class);
        ChatClientRegistry registry = new ChatClientRegistry(
                Map.of("gpt-compatible-chat", client), null, "gpt-4.1", true);

        assertSame(client, registry.get("gpt-5.5"));
        assertSame(client, registry.get("gpt-4.1"));
        assertSame(client, registry.get("gpt-compatible-chat"));
    }

    @Test
    void keepsDeepSeekAndGptClientsIndependent() {
        ChatClient deepSeekClient = mock(ChatClient.class);
        ChatClient gptClient = mock(ChatClient.class);
        ChatClientRegistry registry = new ChatClientRegistry(
                Map.of(
                        "deepseek-official-chat", deepSeekClient,
                        "gpt-compatible-chat", gptClient
                ),
                "deepseek-chat",
                "gpt-5.5",
                true);

        assertSame(deepSeekClient, registry.get("deepseek-chat"));
        assertSame(gptClient, registry.get("gpt-5.5"));
        assertNotSame(registry.get("deepseek-chat"), registry.get("gpt-5.5"));
    }
}
