package com.kama.jchatmind.mcp.audit;

import com.kama.jchatmind.mcp.config.McpClientProperties;
import com.kama.jchatmind.mcp.registry.ExternalMcpPromptRegistration;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

@Slf4j
public class Slf4jMcpPromptAuditLogger implements McpPromptAuditLogger {
    private final McpClientProperties properties;

    public Slf4jMcpPromptAuditLogger(McpClientProperties properties) {
        this.properties = properties;
    }

    @Override
    public void success(String traceId, ExternalMcpPromptRegistration prompt, Set<String> argumentNames,
                         String promptSummary, long latencyMs, boolean truncated) {
        if (properties.isAuditEnabled()) {
            log.info("external mcp prompt success: traceId={}, serverName={}, serverType={}, promptName={}, riskLevel={}, allowed=true, status=SUCCESS, argumentNames={}, latencyMs={}, truncated={}, contentPresent={}, contentCharCount={}",
                    traceId, prompt.getServerName(), prompt.getServerType(), prompt.getName(),
                    prompt.getRiskLevel(), argumentNames, latencyMs, truncated,
                    hasContent(promptSummary), charCount(promptSummary));
        }
    }

    @Override
    public void failure(String traceId, ExternalMcpPromptRegistration prompt, Set<String> argumentNames,
                         String errorMessage, long latencyMs, String errorCode) {
        if (properties.isAuditEnabled()) {
            log.warn("external mcp prompt failure: traceId={}, serverName={}, serverType={}, promptName={}, riskLevel={}, allowed=true, status=FAILED, argumentNames={}, latencyMs={}, errorCode={}, failureDetailPresent={}, failureDetailCharCount={}",
                    traceId, prompt.getServerName(), prompt.getServerType(), prompt.getName(),
                    prompt.getRiskLevel(), argumentNames, latencyMs, errorCode,
                    hasContent(errorMessage), charCount(errorMessage));
        }
    }

    @Override
    public void denied(String traceId, String serverName, String promptName, Set<String> argumentNames,
                       String errorCode) {
        if (properties.isAuditEnabled()) {
            log.warn("external mcp prompt denied: traceId={}, serverName={}, promptName={}, allowed=false, argumentNames={}, errorCode={}",
                    traceId, serverName, promptName, argumentNames, errorCode);
        }
    }

    private boolean hasContent(String value) {
        return value != null && !value.isEmpty();
    }

    private int charCount(String value) {
        return value == null ? 0 : value.length();
    }
}
