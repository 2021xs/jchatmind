package com.kama.jchatmind.mcp.adapter;

import com.kama.jchatmind.mcp.registry.ExternalMcpToolRegistration;

public class UnsupportedExternalMcpToolInvoker implements ExternalMcpToolInvoker {
    @Override
    public String invoke(ExternalMcpToolRegistration tool, String argumentsJson) {
        throw new IllegalStateException("No external MCP tool invoker is configured");
    }
}
