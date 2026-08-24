package com.kama.jchatmind.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "JCHATMIND_REAL_CHAT_CLIENT_ENABLED", matches = "true")
class GptCompatibleChatClientManualIntegrationTest {

    @Autowired
    @Qualifier("gpt-compatible-chat")
    private ChatClient chatClient;

    @Autowired
    private ChatClientRegistry chatClientRegistry;

    @Value("${jchatmind.ai.gpt.compatible.model}")
    private String configuredModel;

    @Test
    void configuredGptCompatibleChatClientCanCompleteSimpleConversation() {
        String answer = chatClient.prompt()
                .system("You are a configuration smoke test. Follow the user's exact output instruction.")
                .user("Reply with exactly: JChatMind GPT 5.5 ok")
                .call()
                .content();

        assertThat(configuredModel).isEqualTo("gpt-5.5");
        assertThat(chatClientRegistry.get("gpt-5.5")).isSameAs(chatClient);
        assertThat(chatClientRegistry.get("deepseek-chat")).isNotSameAs(chatClient);
        assertThat(answer).contains("JChatMind GPT 5.5 ok");
    }
}
