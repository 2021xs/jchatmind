package com.kama.jchatmind.mcp.registry;

import java.util.Map;

public class UnsupportedExternalMcpPromptClient implements ExternalMcpPromptClient {
    @Override
    public String get(ExternalMcpPromptRegistration prompt, Map<String, Object> arguments) {
        throw new IllegalStateException("External MCP prompt client is not configured");
    }
}
