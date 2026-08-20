package com.kama.jchatmind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "jchatmind.agent.tool-timeout")
public class ToolTimeoutProperties {
    private Duration defaultTimeout = Duration.ofSeconds(60);
    private Map<String, Duration> overrides = new HashMap<>();

    public Duration timeoutFor(String actualToolName, String canonicalToolName) {
        Duration override = findOverride(actualToolName);
        if (override == null) {
            override = findOverride(canonicalToolName);
        }
        Duration timeout = override == null ? defaultTimeout : override;
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException("Tool runtime timeout must be greater than zero");
        }
        return timeout;
    }

    private Duration findOverride(String toolName) {
        if (!StringUtils.hasText(toolName) || overrides == null || overrides.isEmpty()) {
            return null;
        }
        return overrides.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getKey().equalsIgnoreCase(toolName))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}
