package com.kama.jchatmind.mcp.registry;

import java.util.List;

public interface ExternalMcpToolDiscoveryClient {
    List<ExternalMcpDiscoveredTool> discoverTools(ExternalMcpServerRegistration server);
}
