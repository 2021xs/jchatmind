package com.kama.jchatmind.mcp.audit;

import com.kama.jchatmind.mcp.registry.ExternalMcpToolRegistration;

public interface McpToolAuditLogger {
    void start(String traceId, ExternalMcpToolRegistration tool, String argumentsJson);

    void success(String traceId, ExternalMcpToolRegistration tool, String resultSummary,
                 long latencyMs, boolean truncated);

    void failure(String traceId, ExternalMcpToolRegistration tool, String errorMessage,
                 long latencyMs, String errorCode);

    void denied(String traceId, ExternalMcpToolRegistration tool, String argumentsJson,
                long latencyMs, String errorCode);
}
