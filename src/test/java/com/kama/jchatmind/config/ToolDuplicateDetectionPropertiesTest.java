package com.kama.jchatmind.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolDuplicateDetectionPropertiesTest {

    @Test
    void bindsEnabledAndThresholdFromSpringEnvironment() {
        new ApplicationContextRunner()
                .withUserConfiguration(BindingConfig.class)
                .withPropertyValues(
                        "jchatmind.agent.duplicate-detection.enabled=false",
                        "jchatmind.agent.duplicate-detection.max-consecutive-same-calls=3")
                .run(context -> {
                    ToolDuplicateDetectionProperties properties =
                            context.getBean(ToolDuplicateDetectionProperties.class);
                    assertFalse(properties.isEnabled());
                    assertEquals(3, properties.validatedMaxConsecutiveSameCalls());
                });
    }

    @Test
    void rejectsThresholdBelowOne() {
        ToolDuplicateDetectionProperties properties = new ToolDuplicateDetectionProperties();
        properties.setMaxConsecutiveSameCalls(0);

        assertThrows(IllegalStateException.class, properties::validatedMaxConsecutiveSameCalls);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ToolDuplicateDetectionProperties.class)
    static class BindingConfig {
    }
}
