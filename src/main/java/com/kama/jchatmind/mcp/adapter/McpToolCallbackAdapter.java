package com.kama.jchatmind.mcp.adapter;

import com.kama.jchatmind.mcp.McpToolCallException;
import com.kama.jchatmind.mcp.audit.McpToolAuditLogger;
import com.kama.jchatmind.mcp.config.McpClientProperties;
import com.kama.jchatmind.mcp.registry.ExternalMcpToolRegistration;
import com.kama.jchatmind.mcp.registry.ExternalMcpToolRegistry;
import com.kama.jchatmind.tool.ToolFailureException;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

public class McpToolCallbackAdapter {
    private static final Logger log = LoggerFactory.getLogger(McpToolCallbackAdapter.class);
    private final ExternalMcpToolRegistry toolRegistry;
    private final ExternalMcpToolInvoker toolInvoker;
    private final McpToolAuditLogger auditLogger;
    private final McpClientProperties properties;

    public McpToolCallbackAdapter(ExternalMcpToolRegistry toolRegistry,
                                  ExternalMcpToolInvoker toolInvoker,
                                  McpToolAuditLogger auditLogger,
                                  McpClientProperties properties) {
        this.toolRegistry = toolRegistry;
        this.toolInvoker = toolInvoker;
        this.auditLogger = auditLogger;
        this.properties = properties;
    }

    public List<ToolCallback> toolCallbacks() {
        return toolRegistry.exposedTools().stream()
                .filter(ExternalMcpToolRegistration::isAutoInvokeAllowed)
                .map(this::toToolCallback)
                .toList();
    }

    public List<String> exposedToolNames() {
        return toolRegistry.exposedTools().stream()
                .filter(ExternalMcpToolRegistration::isAutoInvokeAllowed)
                .map(ExternalMcpToolRegistration::getExposedName)
                .toList();
    }

    private ToolCallback toToolCallback(ExternalMcpToolRegistration registration) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(registration.getExposedName())
                .description(registration.getDescription())
                .inputSchema(registration.getInputSchema())
                .build();
        ToolMetadata metadata = ToolMetadata.builder()
                .returnDirect(false)
                .build();
        return new ExternalMcpToolCallback(registration, definition, metadata);
    }

    private class ExternalMcpToolCallback implements ToolCallback {
        private final ExternalMcpToolRegistration registration;
        private final ToolDefinition definition;
        private final ToolMetadata metadata;

        private ExternalMcpToolCallback(ExternalMcpToolRegistration registration,
                                        ToolDefinition definition,
                                        ToolMetadata metadata) {
            this.registration = registration;
            this.definition = definition;
            this.metadata = metadata;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return metadata;
        }

        @Override
        public String call(String toolInput) {
            String traceId = UUID.randomUUID().toString();
            long started = System.currentTimeMillis();
            auditLogger.start(traceId, registration, toolInput);
            try {
                String result = toolInvoker.invoke(registration, toolInput);
                TruncatedValue truncated = truncate(result, properties.getMaxResultLength());
                auditLogger.success(traceId, registration, truncated.value(),
                        System.currentTimeMillis() - started, truncated.truncated());
                return truncated.value();
            } catch (ToolFailureException e) {
                String safeFailure = e.getErrorType() + ": External MCP tool request was rejected before invocation.";
                auditLogger.failure(traceId, registration, safeFailure,
                        System.currentTimeMillis() - started, e.getErrorType());
                log.warn("External MCP tool request failed before invocation: serverName={}, serverType={}, "
                                + "toolName={}, errorType={}",
                        registration.getServerName(), registration.getServerType(), registration.getToolName(),
                        e.getErrorType(), e);
                throw e;
            } catch (RuntimeException e) {
                String safeFailure = "MCP_TOOL_CALL_FAILED: External MCP tool invocation failed.";
                auditLogger.failure(traceId, registration, safeFailure,
                        System.currentTimeMillis() - started, "MCP_TOOL_CALL_FAILED");
                log.warn("External MCP tool invocation failed: serverName={}, serverType={}, toolName={}, "
                                + "errorType=MCP_TOOL_CALL_FAILED",
                        registration.getServerName(), registration.getServerType(), registration.getToolName(), e);
                throw new McpToolCallException(registration.getExposedName(), e);
            }
        }
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
