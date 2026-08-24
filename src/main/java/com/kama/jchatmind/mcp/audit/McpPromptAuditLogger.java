package com.kama.jchatmind.mcp.audit;

import com.kama.jchatmind.mcp.registry.ExternalMcpPromptRegistration;

import java.util.Set;

public interface McpPromptAuditLogger {
    void success(String traceId, ExternalMcpPromptRegistration prompt, Set<String> argumentNames,
                 String promptSummary, long latencyMs, boolean truncated);

    void failure(String traceId, ExternalMcpPromptRegistration prompt, Set<String> argumentNames,
                 String errorMessage, long latencyMs, String errorCode);

    void denied(String traceId, String serverName, String promptName, Set<String> argumentNames,
                String errorCode);
}
