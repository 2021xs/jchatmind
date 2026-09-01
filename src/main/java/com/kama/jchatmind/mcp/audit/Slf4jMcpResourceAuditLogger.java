package com.kama.jchatmind.mcp.audit;

import com.kama.jchatmind.mcp.config.McpClientProperties;
import com.kama.jchatmind.mcp.registry.ExternalMcpResourceRegistration;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Slf4jMcpResourceAuditLogger implements McpResourceAuditLogger {
    private final McpClientProperties properties;

    public Slf4jMcpResourceAuditLogger(McpClientProperties properties) {
        this.properties = properties;
    }

    @Override
    public void success(String traceId, ExternalMcpResourceRegistration resource, String contentSummary,
                        long latencyMs, boolean truncated) {
        if (properties.isAuditEnabled()) {
            log.info("external mcp resource success: traceId={}, serverName={}, serverType={}, resourceName={}, riskLevel={}, allowed=true, status=SUCCESS, latencyMs={}, truncated={}, contentPresent={}, contentCharCount={}",
                    traceId, resource.getServerName(), resource.getServerType(), resource.getName(),
                    resource.getRiskLevel(), latencyMs, truncated, hasContent(contentSummary), charCount(contentSummary));
        }
    }

    @Override
    public void failure(String traceId, ExternalMcpResourceRegistration resource, String errorMessage,
                         long latencyMs, String errorCode) {
        if (properties.isAuditEnabled()) {
            log.warn("external mcp resource failure: traceId={}, serverName={}, serverType={}, resourceName={}, riskLevel={}, allowed=true, status=FAILED, latencyMs={}, errorCode={}, failureDetailPresent={}, failureDetailCharCount={}",
                    traceId, resource.getServerName(), resource.getServerType(), resource.getName(),
                    resource.getRiskLevel(), latencyMs, errorCode, hasContent(errorMessage), charCount(errorMessage));
        }
    }

    @Override
    public void denied(String traceId, String serverName, String uri, String errorCode) {
        if (properties.isAuditEnabled()) {
            log.warn("external mcp resource denied: traceId={}, serverName={}, operation=RESOURCE_READ, "
                            + "resourceUriPresent={}, resourceUriCharCount={}, allowed=false, status=DENIED, errorCode={}",
                    traceId, serverName, hasContent(uri), charCount(uri), errorCode);
        }
    }

    private boolean hasContent(String value) {
        return value != null && !value.isEmpty();
    }

    private int charCount(String value) {
        return value == null ? 0 : value.length();
    }
}
