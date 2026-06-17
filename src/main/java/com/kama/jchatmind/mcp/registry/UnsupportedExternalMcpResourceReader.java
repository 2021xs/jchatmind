package com.kama.jchatmind.mcp.registry;

public class UnsupportedExternalMcpResourceReader implements ExternalMcpResourceReader {
    @Override
    public String read(ExternalMcpResourceRegistration resource) {
        throw new IllegalStateException("External MCP resource reader is not configured");
    }
}
