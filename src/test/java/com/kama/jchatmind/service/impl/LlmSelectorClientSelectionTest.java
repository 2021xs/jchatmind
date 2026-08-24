package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.service.LlmSelectorClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LlmSelectorClientSelectionTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ClientBeans.class)
            .withPropertyValues(
                    "jchatmind.ai.deepseek.official.base-url=https://deepseek.example/v1",
                    "jchatmind.ai.deepseek.official.api-key=test-api-key",
                    "jchatmind.ai.deepseek.official.model=deepseek-v4-flash");

    @Test
    void defaultsToSingleDeepSeekHttpClient() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(LlmSelectorClient.class);
            assertThat(context).hasSingleBean(DeepSeekLlmSelectorClient.class);
            assertThat(context).doesNotHaveBean(SpringAiLlmSelectorClient.class);
        });
    }

    @Test
    void selectsSingleDeepSeekHttpClientWhenConfigured() {
        contextRunner.withPropertyValues("jchatmind.code-rag.llm-selector.client-type=DEEPSEEK_HTTP")
                .run(context -> {
                    assertThat(context).hasSingleBean(LlmSelectorClient.class);
                    assertThat(context).hasSingleBean(DeepSeekLlmSelectorClient.class);
                    assertThat(context).doesNotHaveBean(SpringAiLlmSelectorClient.class);
                });
    }

    @Test
    void selectsSingleSpringAiClientWhenConfiguredForRollback() {
        contextRunner.withPropertyValues("jchatmind.code-rag.llm-selector.client-type=SPRING_AI")
                .run(context -> {
                    assertThat(context).hasSingleBean(LlmSelectorClient.class);
                    assertThat(context).hasSingleBean(SpringAiLlmSelectorClient.class);
                    assertThat(context).doesNotHaveBean(DeepSeekLlmSelectorClient.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({SpringAiLlmSelectorClient.class, DeepSeekLlmSelectorClient.class})
    static class ClientBeans {
        @Bean
        CodeRagProperties codeRagProperties() {
            return new CodeRagProperties();
        }

        @Bean
        ChatClientRegistry chatClientRegistry() {
            return mock(ChatClientRegistry.class);
        }

        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
