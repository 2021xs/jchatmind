package com.kama.jchatmind.mcp.audit;

import com.kama.jchatmind.mcp.config.McpClientProperties;
import com.kama.jchatmind.mcp.registry.ExternalMcpResourceRegistration;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Slf4jMcpResourceAuditLogger implements McpResourceAuditLogger {
    private static final int MAX_SUMMARY = 800;

    private final McpClientProperties properties;

    public Slf4jMcpResourceAuditLogger(McpClientProperties properties) {
        this.properties = properties;
    }

    @Override
    public void success(String traceId, ExternalMcpResourceRegistration resource, String contentSummary,
                        long latencyMs, boolean truncated) {
        if (properties.isAuditEnabled()) {
            log.info("external mcp resource success: traceId={}, serverName={}, serverType={}, uri={}, riskLevel={}, allowed=true, latencyMs={}, truncated={}, contentSummary={}",
                    traceId, resource.getServerName(), resource.getServerType(), resource.getUri(),
                    resource.getRiskLevel(), latencyMs, truncated, truncate(contentSummary));
        }
    }

    @Override
    public void failure(String traceId, ExternalMcpResourceRegistration resource, String errorMessage,
                        long latencyMs, String errorCode) {
        if (properties.isAuditEnabled()) {
            log.warn("external mcp resource failure: traceId={}, serverName={}, serverType={}, uri={}, riskLevel={}, allowed=true, latencyMs={}, errorCode={}, errorMessage={}",
                    traceId, resource.getServerName(), resource.getServerType(), resource.getUri(),
                    resource.getRiskLevel(), latencyMs, errorCode, truncate(errorMessage));
        }
    }

    @Override
    public void denied(String traceId, String serverName, String uri, String errorCode) {
        if (properties.isAuditEnabled()) {
            log.warn("external mcp resource denied: traceId={}, serverName={}, uri={}, allowed=false, errorCode={}",
                    traceId, serverName, uri, errorCode);
        }
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MAX_SUMMARY) {
            return value;
        }
        return value.substring(0, MAX_SUMMARY - 32) + "\n...[truncated]";
    }
}
