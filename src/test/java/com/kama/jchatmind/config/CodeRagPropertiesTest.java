package com.kama.jchatmind.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeRagPropertiesTest {

    @Test
    void bindsAnswerEvidenceAtCodeRagLevelWithoutChangingSelectorBinding() {
        new ApplicationContextRunner()
                .withUserConfiguration(BindingConfig.class)
                .withPropertyValues(
                        "jchatmind.code-rag.answer-evidence.raw-top-k=20",
                        "jchatmind.code-rag.answer-evidence.final-top-k=5",
                        "jchatmind.code-rag.allowed-roots=./workspace,/srv/jchatmind/repos",
                        "jchatmind.code-rag.llm-selector.client-type=DEEPSEEK_HTTP",
                        "jchatmind.code-rag.llm-selector.model=deepseek-chat",
                        "jchatmind.code-rag.llm-selector.max-candidate-chars=600")
                .run(context -> {
                    CodeRagProperties properties = context.getBean(CodeRagProperties.class);

                    assertEquals(20, properties.getAnswerEvidence().getRawTopK());
                    assertEquals(5, properties.getAnswerEvidence().getFinalTopK());
                    assertEquals(List.of("./workspace", "/srv/jchatmind/repos"), properties.getAllowedRoots());
                    assertEquals(CodeRagProperties.SelectorClientType.DEEPSEEK_HTTP,
                            properties.getLlmSelector().getClientType());
                    assertEquals("deepseek-chat", properties.getLlmSelector().getModel());
                    assertEquals(600, properties.getLlmSelector().getMaxCandidateChars());
                    assertTrue(properties.getEmbeddingCache().isEnabled());
                });
    }

    @Test
    void defaultsSelectorClientTypeToDeepSeekHttp() {
        assertEquals(CodeRagProperties.SelectorClientType.DEEPSEEK_HTTP,
                new CodeRagProperties().getLlmSelector().getClientType());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CodeRagProperties.class)
    static class BindingConfig {
    }
}
