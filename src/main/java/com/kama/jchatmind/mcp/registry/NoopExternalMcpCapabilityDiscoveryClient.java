package com.kama.jchatmind.mcp.registry;

import java.util.List;

public class NoopExternalMcpCapabilityDiscoveryClient implements ExternalMcpCapabilityDiscoveryClient {
    @Override
    public List<ExternalMcpDiscoveredTool> discoverTools(ExternalMcpServerRegistration server) {
        return List.of();
    }

    @Override
    public List<ExternalMcpDiscoveredResource> discoverResources(ExternalMcpServerRegistration server) {
        return List.of();
    }

    @Override
    public List<ExternalMcpDiscoveredPrompt> discoverPrompts(ExternalMcpServerRegistration server) {
        return List.of();
    }
}
