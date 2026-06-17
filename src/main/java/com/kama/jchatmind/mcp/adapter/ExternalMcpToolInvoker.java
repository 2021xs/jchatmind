package com.kama.jchatmind.mcp.adapter;

import com.kama.jchatmind.mcp.registry.ExternalMcpToolRegistration;

public interface ExternalMcpToolInvoker {
    String invoke(ExternalMcpToolRegistration tool, String argumentsJson);
}
