package com.kama.jchatmind.mcp.registry;

import java.util.List;

public interface ExternalMcpCapabilityDiscoveryClient extends ExternalMcpToolDiscoveryClient {
    List<ExternalMcpDiscoveredResource> discoverResources(ExternalMcpServerRegistration server);

    List<ExternalMcpDiscoveredPrompt> discoverPrompts(ExternalMcpServerRegistration server);
}
