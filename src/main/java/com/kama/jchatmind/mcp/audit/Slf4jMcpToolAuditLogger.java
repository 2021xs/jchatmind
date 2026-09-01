package com.kama.jchatmind.mcp.audit;

import com.kama.jchatmind.mcp.config.McpClientProperties;
import com.kama.jchatmind.mcp.registry.ExternalMcpToolRegistration;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Slf4jMcpToolAuditLogger implements McpToolAuditLogger {
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
            log.info("external mcp tool start: traceId={}, serverName={}, serverType={}, toolName={}, riskLevel={}, allowed={}, argumentsPresent={}, argumentCharCount={}",
                    traceId, tool.getServerName(), tool.getServerType(), tool.getToolName(), tool.getRiskLevel(),
                    tool.isAutoInvokeAllowed(), hasContent(argumentsJson), charCount(argumentsJson));
        } catch (RuntimeException e) {
            log.debug("external mcp audit start failed: exceptionClass={}", e.getClass().getName());
        }
    }

    @Override
    public void success(String traceId, ExternalMcpToolRegistration tool, String resultSummary,
                        long latencyMs, boolean truncated) {
        if (!properties.isAuditEnabled()) {
            return;
        }
        try {
            log.info("external mcp tool success: traceId={}, serverName={}, serverType={}, toolName={}, riskLevel={}, allowed=true, status=SUCCESS, latencyMs={}, truncated={}, resultPresent={}, resultCharCount={}",
                    traceId, tool.getServerName(), tool.getServerType(), tool.getToolName(), tool.getRiskLevel(),
                    latencyMs, truncated, hasContent(resultSummary), charCount(resultSummary));
        } catch (RuntimeException e) {
            log.debug("external mcp audit success failed: exceptionClass={}", e.getClass().getName());
        }
    }

    @Override
    public void failure(String traceId, ExternalMcpToolRegistration tool, String errorMessage,
                        long latencyMs, String errorCode) {
        if (!properties.isAuditEnabled()) {
            return;
        }
        try {
            log.warn("external mcp tool failure: traceId={}, serverName={}, serverType={}, toolName={}, riskLevel={}, allowed=true, status=FAILED, latencyMs={}, errorCode={}, failureDetailPresent={}, failureDetailCharCount={}",
                    traceId, tool.getServerName(), tool.getServerType(), tool.getToolName(), tool.getRiskLevel(),
                    latencyMs, errorCode, hasContent(errorMessage), charCount(errorMessage));
        } catch (RuntimeException e) {
            log.debug("external mcp audit failure failed: exceptionClass={}", e.getClass().getName());
        }
    }

    @Override
    public void denied(String traceId, ExternalMcpToolRegistration tool, String argumentsJson,
                       long latencyMs, String errorCode) {
        if (!properties.isAuditEnabled()) {
            return;
        }
        try {
            log.warn("external mcp tool denied: traceId={}, serverName={}, serverType={}, toolName={}, riskLevel={}, allowed=false, status=DENIED, latencyMs={}, errorCode={}, argumentsPresent={}, argumentCharCount={}",
                    traceId, tool.getServerName(), tool.getServerType(), tool.getToolName(), tool.getRiskLevel(),
                    latencyMs, errorCode, hasContent(argumentsJson), charCount(argumentsJson));
        } catch (RuntimeException e) {
            log.debug("external mcp audit denied failed: exceptionClass={}", e.getClass().getName());
        }
    }

    private boolean hasContent(String value) {
        return value != null && !value.isEmpty();
    }

    private int charCount(String value) {
        return value == null ? 0 : value.length();
    }
}
