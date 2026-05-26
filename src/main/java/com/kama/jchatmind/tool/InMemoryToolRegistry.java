package com.kama.jchatmind.tool;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Component
public class InMemoryToolRegistry implements ToolRegistry {
    private static final int DEFAULT_MAX_RESULT_LENGTH = 4000;

    private final List<ToolDefinition> definitions;

    public InMemoryToolRegistry() {
        this.definitions = new ArrayList<>();
        registerDefaults();
    }

    @Override
    public Optional<ToolDefinition> find(String toolNameOrAlias) {
        return definitions.stream()
                .filter(definition -> definition.matches(toolNameOrAlias))
                .findFirst();
    }

    @Override
    public String canonicalName(String toolNameOrAlias) {
        return find(toolNameOrAlias)
                .map(ToolDefinition::getToolName)
                .orElse(toolNameOrAlias);
    }

    @Override
    public boolean canExposeToAgent(String toolNameOrAlias) {
        return find(toolNameOrAlias)
                .map(definition -> definition.isEnabled() && definition.isAllowInAgent())
                .orElse(false);
    }

    @Override
    public boolean isAllowedForRuntime(String toolNameOrAlias, Collection<String> runtimeToolNames) {
        String requestedCanonical = canonicalName(toolNameOrAlias);
        return runtimeToolNames != null && runtimeToolNames.stream()
                .map(this::canonicalName)
                .anyMatch(name -> equalsIgnoreCase(name, requestedCanonical));
    }

    @Override
    public int maxResultLength(String toolNameOrAlias) {
        return find(toolNameOrAlias)
                .map(ToolDefinition::getMaxResultLength)
                .filter(length -> length > 0)
                .orElse(DEFAULT_MAX_RESULT_LENGTH);
    }

    @Override
    public String truncateResult(String toolNameOrAlias, String result) {
        if (result == null) {
            return null;
        }
        int maxLength = maxResultLength(toolNameOrAlias);
        if (result.length() <= maxLength) {
            return result;
        }
        int keep = Math.max(0, maxLength - 32);
        return result.substring(0, keep) + "\n...[truncated]";
    }

    private void registerDefaults() {
        definitions.add(ToolDefinition.builder()
                .toolName("knowledgeQuery")
                .enabled(true)
                .maxResultLength(6000)
                .allowInAgent(true)
                .alias("KnowledgeTool")
                .build());

        definitions.add(ToolDefinition.builder()
                .toolName("searchProjectCode")
                .enabled(true)
                .maxResultLength(7000)
                .allowInAgent(true)
                .build());

        definitions.add(ToolDefinition.builder()
                .toolName("databaseQuery")
                .enabled(true)
                .maxResultLength(4000)
                .allowInAgent(true)
                .alias("dataBaseTool")
                .build());

        definitions.add(ToolDefinition.builder()
                .toolName("terminate")
                .enabled(true)
                .maxResultLength(1000)
                .allowInAgent(true)
                .build());

    }

    private boolean equalsIgnoreCase(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
