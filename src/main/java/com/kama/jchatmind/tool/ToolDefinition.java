package com.kama.jchatmind.tool;

import lombok.Builder;
import lombok.Data;
import lombok.Singular;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;

@Data
@Builder
public class ToolDefinition {
    private String toolName;
    private boolean enabled;
    private int maxResultLength;
    private boolean allowInAgent;
    @Singular
    private Set<String> aliases;

    public boolean matches(String candidate) {
        if (candidate == null) {
            return false;
        }
        String normalized = normalize(candidate);
        if (normalize(toolName).equals(normalized)) {
            return true;
        }
        // 兼容旧名：历史配置或模型输出可能仍使用旧工具名。
        return safeAliases().stream().map(this::normalize).anyMatch(normalized::equals);
    }

    private Set<String> safeAliases() {
        return aliases == null ? Collections.emptySet() : aliases;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
