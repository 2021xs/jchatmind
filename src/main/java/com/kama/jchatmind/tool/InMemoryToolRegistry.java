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
                .toolName("KnowledgeTool")
                .displayName("Knowledge RAG Search")
                .description("Search document knowledge base snippets.")
                .category(ToolCategory.KNOWLEDGE)
                .permissionLevel(ToolPermissionLevel.SAFE_READ)
                .enabled(true)
                .timeoutMs(10000)
                .maxResultLength(6000)
                .allowInAgent(true)
                .riskDescription("Read-only retrieval over imported documents.")
                .alias("KnowledgeTool")
                .alias("knowledgeQuery")
                .build());

        definitions.add(ToolDefinition.builder()
                .toolName("searchProjectCode")
                .displayName("Code RAG Search")
                .description("Search imported Java backend project code snippets.")
                .category(ToolCategory.CODE_SEARCH)
                .permissionLevel(ToolPermissionLevel.SAFE_READ)
                .enabled(true)
                .timeoutMs(10000)
                .maxResultLength(7000)
                .allowInAgent(true)
                .riskDescription("Read-only retrieval over imported code chunks.")
                .alias("searchProjectCode")
                .build());

        definitions.add(ToolDefinition.builder()
                .toolName("dataBaseTool")
                .displayName("Database Read Query")
                .description("Execute safe PostgreSQL SELECT or EXPLAIN SELECT queries.")
                .category(ToolCategory.DATABASE)
                .permissionLevel(ToolPermissionLevel.SENSITIVE_READ)
                .enabled(true)
                .timeoutMs(5000)
                .maxResultLength(4000)
                .allowInAgent(true)
                .riskDescription("Can expose database content; writes and dangerous statements are blocked by policy.")
                .alias("dataBaseTool")
                .alias("databaseQuery")
                .build());

        definitions.add(ToolDefinition.builder()
                .toolName("fileSystemTool")
                .displayName("File System Tool")
                .description("Disabled legacy filesystem operations.")
                .category(ToolCategory.FILESYSTEM)
                .permissionLevel(ToolPermissionLevel.DANGEROUS)
                .enabled(false)
                .timeoutMs(3000)
                .maxResultLength(1000)
                .allowInAgent(false)
                .riskDescription("Disabled because it contains read/write/delete filesystem operations.")
                .alias("fileSystemTool")
                .alias("readFile")
                .alias("writeFile")
                .alias("appendToFile")
                .alias("listFiles")
                .alias("deleteFile")
                .alias("createDirectory")
                .build());

        definitions.add(ToolDefinition.builder()
                .toolName("terminate")
                .displayName("Terminate")
                .description("Stop the current agent loop.")
                .category(ToolCategory.UTILITY)
                .permissionLevel(ToolPermissionLevel.SAFE_READ)
                .enabled(true)
                .timeoutMs(1000)
                .maxResultLength(1000)
                .allowInAgent(true)
                .riskDescription("Stops only the current agent loop.")
                .alias("terminate")
                .build());

        definitions.add(ToolDefinition.builder()
                .toolName("dateTool")
                .displayName("Date Tool")
                .description("Return current date.")
                .category(ToolCategory.UTILITY)
                .permissionLevel(ToolPermissionLevel.SAFE_READ)
                .enabled(true)
                .timeoutMs(1000)
                .maxResultLength(1000)
                .allowInAgent(true)
                .riskDescription("Local utility tool.")
                .alias("dateTool")
                .alias("getDate")
                .build());

        definitions.add(ToolDefinition.builder()
                .toolName("cityTool")
                .displayName("City Tool")
                .description("Return configured city.")
                .category(ToolCategory.UTILITY)
                .permissionLevel(ToolPermissionLevel.SAFE_READ)
                .enabled(true)
                .timeoutMs(1000)
                .maxResultLength(1000)
                .allowInAgent(true)
                .riskDescription("Local utility tool.")
                .alias("cityTool")
                .alias("getCity")
                .build());

        definitions.add(ToolDefinition.builder()
                .toolName("weatherTool")
                .displayName("Weather Tool")
                .description("Return demo weather text.")
                .category(ToolCategory.UTILITY)
                .permissionLevel(ToolPermissionLevel.SAFE_READ)
                .enabled(true)
                .timeoutMs(1000)
                .maxResultLength(1000)
                .allowInAgent(true)
                .riskDescription("Demo utility tool.")
                .alias("weatherTool")
                .alias("weather")
                .build());

        definitions.add(ToolDefinition.builder()
                .toolName("emailTool")
                .displayName("Email Tool")
                .description("Send email through configured SMTP.")
                .category(ToolCategory.COMMUNICATION)
                .permissionLevel(ToolPermissionLevel.WRITE)
                .enabled(true)
                .timeoutMs(10000)
                .maxResultLength(2000)
                .allowInAgent(true)
                .riskDescription("Sends external email and should only be enabled per agent intentionally.")
                .alias("emailTool")
                .alias("sendEmail")
                .build());
    }

    private boolean equalsIgnoreCase(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
