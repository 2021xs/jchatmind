package com.kama.jchatmind.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextCompressionPropertiesTest {

    @Test
    void applicationDefaultsBindToCurrentCompressionContract() throws IOException {
        MockEnvironment environment = new MockEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (PropertySource<?> propertySource : loader.load(
                "application", new ClassPathResource("application.yaml"))) {
            environment.getPropertySources().addLast(propertySource);
        }

        ContextCompressionProperties properties = Binder.get(environment)
                .bind("jchatmind.context-compression",
                        Bindable.of(ContextCompressionProperties.class))
                .orElseThrow(() -> new IllegalStateException(
                        "Context compression properties were not bound"));

        assertThat(properties.getMaxHistoryMessages()).isEqualTo(20);
        assertThat(properties.getMaxSummaryChars()).isEqualTo(1200);
        ContextCompressionProperties.TokenThreshold threshold =
                properties.thresholdFor("deepseek-chat");
        assertThat(threshold.getCompressionTriggerTokens()).isEqualTo(200000);
        assertThat(threshold.getWorkingContextHardLimitTokens()).isEqualTo(256000);
        assertThat(threshold.getMaxSingleToolResultTokens()).isEqualTo(5000);
    }

    @Test
    void hardLimitCannotBeLowerThanCompressionTrigger() {
        ContextCompressionProperties properties = new ContextCompressionProperties();
        properties.setCompressionTriggerTokens(200000);
        properties.setWorkingContextHardLimitTokens(199999);

        assertThatThrownBy(() -> properties.thresholdFor("deepseek-chat"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("workingContextHardLimitTokens");
    }
}
