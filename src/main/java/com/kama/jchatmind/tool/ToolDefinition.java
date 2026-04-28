package com.kama.jchatmind.tool;

import lombok.Builder;
import lombok.Data;
import lombok.Singular;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

@Data
@Builder
public class ToolDefinition {
    private String toolName;
    private String displayName;
    private String description;
    private ToolCategory category;
    private ToolPermissionLevel permissionLevel;
    private boolean enabled;
    private long timeoutMs;
    private int maxResultLength;
    private boolean allowInAgent;
    private String riskDescription;
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
        return safeAliases().stream().map(this::normalize).anyMatch(normalized::equals);
    }

    public Set<String> allNames() {
        Set<String> names = new LinkedHashSet<>();
        if (toolName != null) {
            names.add(toolName);
        }
        names.addAll(safeAliases());
        return names;
    }

    private Set<String> safeAliases() {
        return aliases == null ? Collections.emptySet() : aliases;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
