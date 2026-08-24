package com.kama.jchatmind.config;

import com.kama.jchatmind.service.impl.WebConsoleChatServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.lang.reflect.Parameter;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class WebConsoleFinalStreamingFeatureFlagTest {

    private static final String PROPERTY = "jchatmind.web-console.final-streaming-enabled";
    private static final String ENVIRONMENT_VARIABLE = "JCHATMIND_WEB_CONSOLE_FINAL_STREAMING_ENABLED";
    private static final String VALUE_EXPRESSION = "${" + PROPERTY + ":true}";

    @Test
    void finalStreamingIsEnabledByDefaultWhenEnvironmentOverrideIsAbsent() throws IOException {
        MockEnvironment environment = applicationEnvironment();

        assertThat(environment.getProperty(PROPERTY, Boolean.class)).isTrue();
    }

    @Test
    void explicitFalseEnvironmentOverrideDisablesPostCommitTokenReplayOnly() throws IOException {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(ENVIRONMENT_VARIABLE, "false");
        addApplicationYaml(environment);

        assertThat(environment.getProperty(PROPERTY, Boolean.class)).isFalse();
    }

    @Test
    void webConsoleServiceFallbackMatchesApplicationDefault() {
        String[] valueExpressions = Arrays.stream(WebConsoleChatServiceImpl.class.getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameters()))
                .map(Parameter::getAnnotations)
                .flatMap(Arrays::stream)
                .filter(Value.class::isInstance)
                .map(Value.class::cast)
                .map(Value::value)
                .toArray(String[]::new);

        assertThat(valueExpressions).containsExactly(VALUE_EXPRESSION);
    }

    private MockEnvironment applicationEnvironment() throws IOException {
        MockEnvironment environment = new MockEnvironment();
        addApplicationYaml(environment);
        return environment;
    }

    private void addApplicationYaml(MockEnvironment environment) throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (PropertySource<?> propertySource : loader.load(
                "application", new ClassPathResource("application.yaml"))) {
            environment.getPropertySources().addLast(propertySource);
        }
    }
}
