package com.kama.jchatmind.mcp.registry;

import java.util.Map;

public interface ExternalMcpPromptClient {
    String get(ExternalMcpPromptRegistration prompt, Map<String, Object> arguments);
}
