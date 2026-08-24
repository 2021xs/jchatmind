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

class FinalSynthesisPropertiesTest {

    @Test
    void applicationDefaultsBindToFinalInputBudget() throws IOException {
        MockEnvironment environment = applicationEnvironment();

        FinalSynthesisProperties properties = Binder.get(environment)
                .bind("jchatmind.agent.final-synthesis", Bindable.of(FinalSynthesisProperties.class))
                .orElseThrow(() -> new IllegalStateException("Final synthesis properties were not bound"));

        assertThat(properties.getMaxInputTokens()).isEqualTo(64_000);
        assertThat(properties.getCharsPerToken()).isEqualTo(3);
    }

    @Test
    void explicitRuntimePropertiesCanReduceTheBudgetForRollback() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("jchatmind.agent.final-synthesis.max-input-tokens", "24000")
                .withProperty("jchatmind.agent.final-synthesis.chars-per-token", "4");

        FinalSynthesisProperties properties = Binder.get(environment)
                .bind("jchatmind.agent.final-synthesis", Bindable.of(FinalSynthesisProperties.class))
                .orElseThrow(() -> new IllegalStateException("Final synthesis properties were not bound"));

        assertThat(properties.getMaxInputTokens()).isEqualTo(24_000);
        assertThat(properties.getCharsPerToken()).isEqualTo(4);
    }

    private MockEnvironment applicationEnvironment() throws IOException {
        MockEnvironment environment = new MockEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (PropertySource<?> propertySource : loader.load(
                "application", new ClassPathResource("application.yaml"))) {
            environment.getPropertySources().addLast(propertySource);
        }
        return environment;
    }
}
