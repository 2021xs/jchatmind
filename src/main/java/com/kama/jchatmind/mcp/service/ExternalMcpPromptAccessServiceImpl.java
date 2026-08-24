package com.kama.jchatmind.mcp.service;

import com.kama.jchatmind.mcp.audit.McpPromptAuditLogger;
import com.kama.jchatmind.mcp.config.McpClientProperties;
import com.kama.jchatmind.mcp.registry.ExternalMcpPromptClient;
import com.kama.jchatmind.mcp.registry.ExternalMcpPromptRegistration;
import com.kama.jchatmind.mcp.registry.ExternalMcpPromptRegistry;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class ExternalMcpPromptAccessServiceImpl implements ExternalMcpPromptAccessService {
    private static final String DENIED_PROMPT = "MCP_PROMPT_POLICY_REJECTED";
    private static final String INVALID_ARGUMENTS = "MCP_PROMPT_INVALID_ARGUMENTS";
    private static final String GET_FAILED = "MCP_PROMPT_GET_FAILED";

    private final ExternalMcpPromptRegistry registry;
    private final ExternalMcpPromptClient promptClient;
    private final McpPromptAuditLogger auditLogger;
    private final McpClientProperties properties;

    public ExternalMcpPromptAccessServiceImpl(ExternalMcpPromptRegistry registry,
                                              ExternalMcpPromptClient promptClient,
                                              McpPromptAuditLogger auditLogger,
                                              McpClientProperties properties) {
        this.registry = registry;
        this.promptClient = promptClient;
        this.auditLogger = auditLogger;
        this.properties = properties;
    }

    @Override
    public String get(String promptName, Map<String, Object> arguments) {
        Map<String, Object> safeArguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        Set<String> argumentNames = safeArguments.keySet();
        String traceId = UUID.randomUUID().toString();
        ExternalMcpPromptRegistration prompt = registry.findByName(promptName).orElse(null);
        if (prompt == null || !registry.canUse(prompt)) {
            auditLogger.denied(traceId, prompt == null ? null : prompt.getServerName(), promptName,
                    argumentNames, DENIED_PROMPT);
            throw new IllegalArgumentException("External MCP prompt is not allowed: " + promptName);
        }
        List<String> missing = missingRequiredArguments(prompt, safeArguments);
        if (!missing.isEmpty()) {
            auditLogger.failure(traceId, prompt, argumentNames, "Missing required prompt arguments: " + missing,
                    0, INVALID_ARGUMENTS);
            throw new IllegalArgumentException("Missing required prompt arguments: " + missing);
        }

        long started = System.currentTimeMillis();
        try {
            String result = promptClient.get(prompt, safeArguments);
            TruncatedValue truncated = truncate(result, properties.getMaxResultLength());
            auditLogger.success(traceId, prompt, argumentNames, truncated.value(),
                    System.currentTimeMillis() - started, truncated.truncated());
            return truncated.value();
        } catch (RuntimeException e) {
            auditLogger.failure(traceId, prompt, argumentNames, e.getMessage(),
                    System.currentTimeMillis() - started, GET_FAILED);
            throw e;
        }
    }

    private List<String> missingRequiredArguments(ExternalMcpPromptRegistration prompt,
                                                  Map<String, Object> arguments) {
        return prompt.getRequiredArguments().stream()
                .filter(StringUtils::hasText)
                .filter(name -> !arguments.containsKey(name) || isBlank(arguments.get(name)))
                .collect(Collectors.toList());
    }

    private boolean isBlank(Object value) {
        return value == null || (value instanceof String stringValue && !StringUtils.hasText(stringValue));
    }

    private TruncatedValue truncate(String value, int maxLength) {
        if (value == null) {
            return new TruncatedValue(null, false);
        }
        int effectiveMaxLength = maxLength <= 0 ? 6000 : maxLength;
        if (value.length() <= effectiveMaxLength) {
            return new TruncatedValue(value, false);
        }
        int keep = Math.max(0, effectiveMaxLength - 32);
        return new TruncatedValue(value.substring(0, keep) + "\n...[truncated]", true);
    }

    private record TruncatedValue(String value, boolean truncated) {
    }
}
