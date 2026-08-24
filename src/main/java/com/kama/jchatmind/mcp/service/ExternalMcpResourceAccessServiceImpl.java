package com.kama.jchatmind.mcp.service;

import com.kama.jchatmind.mcp.audit.McpResourceAuditLogger;
import com.kama.jchatmind.mcp.config.McpClientProperties;
import com.kama.jchatmind.mcp.registry.ExternalMcpResourceReader;
import com.kama.jchatmind.mcp.registry.ExternalMcpResourceRegistration;
import com.kama.jchatmind.mcp.registry.ExternalMcpResourceRegistry;

import java.util.UUID;

public class ExternalMcpResourceAccessServiceImpl implements ExternalMcpResourceAccessService {
    private static final String DENIED_RESOURCE = "MCP_RESOURCE_POLICY_REJECTED";
    private static final String READ_FAILED = "MCP_RESOURCE_READ_FAILED";

    private final ExternalMcpResourceRegistry registry;
    private final ExternalMcpResourceReader reader;
    private final McpResourceAuditLogger auditLogger;
    private final McpClientProperties properties;

    public ExternalMcpResourceAccessServiceImpl(ExternalMcpResourceRegistry registry,
                                                ExternalMcpResourceReader reader,
                                                McpResourceAuditLogger auditLogger,
                                                McpClientProperties properties) {
        this.registry = registry;
        this.reader = reader;
        this.auditLogger = auditLogger;
        this.properties = properties;
    }

    @Override
    public String read(String uri) {
        String traceId = UUID.randomUUID().toString();
        ExternalMcpResourceRegistration resource = registry.findByUri(uri).orElse(null);
        if (resource == null || !registry.canRead(resource)) {
            auditLogger.denied(traceId, resource == null ? null : resource.getServerName(), uri, DENIED_RESOURCE);
            throw new IllegalArgumentException("External MCP resource is not allowed: " + uri);
        }

        long started = System.currentTimeMillis();
        try {
            String result = reader.read(resource);
            TruncatedValue truncated = truncate(result, properties.getMaxResultLength());
            auditLogger.success(traceId, resource, truncated.value(),
                    System.currentTimeMillis() - started, truncated.truncated());
            return truncated.value();
        } catch (RuntimeException e) {
            auditLogger.failure(traceId, resource, e.getMessage(),
                    System.currentTimeMillis() - started, READ_FAILED);
            throw e;
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
