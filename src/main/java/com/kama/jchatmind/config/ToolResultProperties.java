package com.kama.jchatmind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "jchatmind.agent.tool-result")
public class ToolResultProperties {
    public static final int MIN_MAX_RESULT_CHARS = 64;

    private int defaultMaxResultChars = 8000;
    private Map<String, Integer> overrides = new HashMap<>();

    public int maxResultCharsFor(String actualToolName, String canonicalToolName) {
        Integer override = findOverride(actualToolName);
        if (override == null) {
            override = findOverride(canonicalToolName);
        }
        int maxResultChars = override == null ? defaultMaxResultChars : override;
        if (maxResultChars < MIN_MAX_RESULT_CHARS) {
            throw new IllegalStateException("Tool maxResultChars must be at least " + MIN_MAX_RESULT_CHARS);
        }
        return maxResultChars;
    }

    private Integer findOverride(String toolName) {
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
