package com.kama.jchatmind.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolTimeoutPropertiesTest {

    @Test
    void usesGlobalDefaultWhenToolHasNoOverride() {
        ToolTimeoutProperties properties = new ToolTimeoutProperties();
        properties.setDefaultTimeout(Duration.ofSeconds(7));

        assertEquals(Duration.ofSeconds(7), properties.timeoutFor("fastTool", "fastTool"));
    }

    @Test
    void actualOrCanonicalToolNameCanOverrideGlobalDefault() {
        ToolTimeoutProperties properties = new ToolTimeoutProperties();
        properties.setDefaultTimeout(Duration.ofMillis(20));
        properties.setOverrides(Map.of("slowTool", Duration.ofMillis(200)));

        assertEquals(Duration.ofMillis(200), properties.timeoutFor("SLOWTOOL", "slowTool"));
        assertEquals(Duration.ofMillis(200), properties.timeoutFor("legacySlowTool", "slowTool"));
    }

    @Test
    void rejectsNonPositiveTimeout() {
        ToolTimeoutProperties properties = new ToolTimeoutProperties();
        properties.setDefaultTimeout(Duration.ZERO);

        assertThrows(IllegalStateException.class,
                () -> properties.timeoutFor("fastTool", "fastTool"));
    }

    @Test
    void bindsDefaultAndPerToolOverrideFromSpringEnvironment() {
        new ApplicationContextRunner()
                .withUserConfiguration(BindingConfig.class)
                .withPropertyValues(
                        "jchatmind.agent.tool-timeout.default-timeout=2s",
                        "jchatmind.agent.tool-timeout.overrides.slowTool=250ms")
                .run(context -> {
                    ToolTimeoutProperties properties = context.getBean(ToolTimeoutProperties.class);
                    assertEquals(Duration.ofSeconds(2), properties.timeoutFor("fastTool", "fastTool"));
                    assertEquals(Duration.ofMillis(250), properties.timeoutFor("slowTool", "slowTool"));
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ToolTimeoutProperties.class)
    static class BindingConfig {
    }
}
