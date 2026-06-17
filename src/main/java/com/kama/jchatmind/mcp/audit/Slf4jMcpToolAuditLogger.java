package com.kama.jchatmind.mcp.audit;

import com.kama.jchatmind.mcp.config.McpClientProperties;
import com.kama.jchatmind.mcp.registry.ExternalMcpToolRegistration;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Slf4jMcpToolAuditLogger implements McpToolAuditLogger {
    private static final int MAX_ARGUMENT_PREVIEW = 2000;
    private static final int MAX_RESULT_SUMMARY = 2000;

    private final McpClientProperties properties;

    public Slf4jMcpToolAuditLogger(McpClientProperties properties) {
        this.properties = properties;
    }

    @Override
    public void start(String traceId, ExternalMcpToolRegistration tool, String argumentsJson) {
        if (!properties.isAuditEnabled()) {
            return;
        }
        try {
            log.info("external mcp tool start: traceId={}, serverName={}, serverType={}, toolName={}, riskLevel={}, allowed={}, argumentsPreview={}",
                    traceId, tool.getServerName(), tool.getServerType(), tool.getToolName(), tool.getRiskLevel(),
                    tool.isAutoInvokeAllowed(), truncate(argumentsJson, MAX_ARGUMENT_PREVIEW));
        } catch (RuntimeException e) {
            log.debug("external mcp audit start failed: {}", e.getMessage());
        }
    }

    @Override
    public void success(String traceId, ExternalMcpToolRegistration tool, String resultSummary,
                        long latencyMs, boolean truncated) {
        if (!properties.isAuditEnabled()) {
            return;
        }
        try {
            log.info("external mcp tool success: traceId={}, serverName={}, serverType={}, toolName={}, riskLevel={}, allowed=true, latencyMs={}, truncated={}, resultSummary={}",
                    traceId, tool.getServerName(), tool.getServerType(), tool.getToolName(), tool.getRiskLevel(),
                    latencyMs, truncated, truncate(resultSummary, MAX_RESULT_SUMMARY));
        } catch (RuntimeException e) {
            log.debug("external mcp audit success failed: {}", e.getMessage());
        }
    }

    @Override
    public void failure(String traceId, ExternalMcpToolRegistration tool, String errorMessage,
                        long latencyMs, String errorCode) {
        if (!properties.isAuditEnabled()) {
            return;
        }
        try {
            log.warn("external mcp tool failure: traceId={}, serverName={}, serverType={}, toolName={}, riskLevel={}, allowed=true, latencyMs={}, errorCode={}, errorMessage={}",
                    traceId, tool.getServerName(), tool.getServerType(), tool.getToolName(), tool.getRiskLevel(),
                    latencyMs, errorCode, truncate(errorMessage, MAX_RESULT_SUMMARY));
        } catch (RuntimeException e) {
            log.debug("external mcp audit failure failed: {}", e.getMessage());
        }
    }

    @Override
    public void denied(String traceId, ExternalMcpToolRegistration tool, String argumentsJson,
                       long latencyMs, String errorCode) {
        if (!properties.isAuditEnabled()) {
            return;
        }
        try {
            log.warn("external mcp tool denied: traceId={}, serverName={}, serverType={}, toolName={}, riskLevel={}, allowed=false, latencyMs={}, errorCode={}, argumentsPreview={}",
                    traceId, tool.getServerName(), tool.getServerType(), tool.getToolName(), tool.getRiskLevel(),
                    latencyMs, errorCode, truncate(argumentsJson, MAX_ARGUMENT_PREVIEW));
        } catch (RuntimeException e) {
            log.debug("external mcp audit denied failed: {}", e.getMessage());
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        int keep = Math.max(0, maxLength - 32);
        return value.substring(0, keep) + "\n...[truncated]";
    }
}
