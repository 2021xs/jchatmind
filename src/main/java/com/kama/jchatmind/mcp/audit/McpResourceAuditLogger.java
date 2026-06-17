package com.kama.jchatmind.mcp.audit;

import com.kama.jchatmind.mcp.registry.ExternalMcpResourceRegistration;

public interface McpResourceAuditLogger {
    void success(String traceId, ExternalMcpResourceRegistration resource, String contentSummary,
                 long latencyMs, boolean truncated);

    void failure(String traceId, ExternalMcpResourceRegistration resource, String errorMessage,
                 long latencyMs, String errorCode);

    void denied(String traceId, String serverName, String uri, String errorCode);
}
