package com.kama.jchatmind.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolResultPropertiesTest {

    @Test
    void resolvesActualThenCanonicalOverrideCaseInsensitively() {
        ToolResultProperties properties = new ToolResultProperties();
        properties.setDefaultMaxResultChars(8000);
        properties.setOverrides(Map.of("AliasTool", 9000, "canonicalTool", 10000));

        assertEquals(9000, properties.maxResultCharsFor("aliasTOOL", "canonicalTool"));
        assertEquals(10000, properties.maxResultCharsFor("other", "CANONICALTOOL"));
        assertEquals(8000, properties.maxResultCharsFor("other", "other"));
    }

    @Test
    void rejectsLimitTooSmallForStableMarker() {
        ToolResultProperties properties = new ToolResultProperties();
        properties.setDefaultMaxResultChars(10);

        assertThrows(IllegalStateException.class,
                () -> properties.maxResultCharsFor("tool", "tool"));
    }

    @Test
    void bindsDefaultAndPerToolOverrideFromSpringEnvironment() {
        new ApplicationContextRunner()
                .withUserConfiguration(BindingConfig.class)
                .withPropertyValues(
                        "jchatmind.agent.tool-result.default-max-result-chars=9000",
                        "jchatmind.agent.tool-result.overrides.largeTool=12000")
                .run(context -> {
                    ToolResultProperties properties = context.getBean(ToolResultProperties.class);
                    assertEquals(9000, properties.maxResultCharsFor("otherTool", "otherTool"));
                    assertEquals(12000, properties.maxResultCharsFor("largeTool", "largeTool"));
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ToolResultProperties.class)
    static class BindingConfig {
    }
}
