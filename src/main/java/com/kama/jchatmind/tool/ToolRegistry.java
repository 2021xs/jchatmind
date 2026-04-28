package com.kama.jchatmind.tool;

import java.util.Collection;
import java.util.Optional;

public interface ToolRegistry {
    Optional<ToolDefinition> find(String toolNameOrAlias);

    String canonicalName(String toolNameOrAlias);

    boolean canExposeToAgent(String toolNameOrAlias);

    boolean isAllowedForRuntime(String toolNameOrAlias, Collection<String> runtimeToolNames);

    int maxResultLength(String toolNameOrAlias);

    String truncateResult(String toolNameOrAlias, String result);
}
