package com.kama.jchatmind.mcp.service;

import java.util.Map;

public interface ExternalMcpPromptAccessService {
    String get(String promptName, Map<String, Object> arguments);
}
