package com.kama.jchatmind.mcp.registry;

import java.util.List;

public class NoopExternalMcpToolDiscoveryClient implements ExternalMcpToolDiscoveryClient {
    @Override
    public List<ExternalMcpDiscoveredTool> discoverTools(ExternalMcpServerRegistration server) {
        return List.of();
    }
}
