package com.kama.jchatmind.mcp.registry;

import com.kama.jchatmind.mcp.safety.McpExternalToolPolicy;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class ExternalMcpCapabilityRegistry {
    private final ExternalMcpServerRegistry serverRegistry;
    private final ExternalMcpCapabilityDiscoveryClient discoveryClient;
    private final McpExternalToolPolicy policy;

    public ExternalMcpCapabilityRegistry(ExternalMcpServerRegistry serverRegistry,
                                         ExternalMcpCapabilityDiscoveryClient discoveryClient,
                                         McpExternalToolPolicy policy) {
        this.serverRegistry = serverRegistry;
        this.discoveryClient = discoveryClient;
        this.policy = policy;
    }

    public List<ExternalMcpServerCapabilities> discoverCapabilities() {
        return serverRegistry.enabledServers().stream()
                .map(this::discoverCapabilitiesSafely)
                .toList();
    }

    private ExternalMcpServerCapabilities discoverCapabilitiesSafely(ExternalMcpServerRegistration server) {
        if (!policy.isSupportedServerType(server.getType())) {
            throw new IllegalStateException("Unsupported MCP server type for first version: "
                    + server.getType() + " server=" + server.getName());
        }
        try {
            return discoverCapabilities(server);
        } catch (RuntimeException e) {
            log.warn("External MCP server unavailable during capability discovery: serverName={}, serverType={}, "
                            + "status=UNAVAILABLE, failureType=MCP_DISCOVERY_FAILED, "
                            + "exceptionClass={}",
                    server.getName(), server.getType(), e.getClass().getName());
            return ExternalMcpServerCapabilities.builder()
                    .serverName(server.getName())
                    .tools(List.of())
                    .resources(List.of())
                    .prompts(List.of())
                    .build();
        }
    }

    private ExternalMcpServerCapabilities discoverCapabilities(ExternalMcpServerRegistration server) {
        if (!policy.isSupportedServerType(server.getType())) {
            throw new IllegalStateException("Unsupported MCP server type for first version: "
                    + server.getType() + " server=" + server.getName());
        }
        return ExternalMcpServerCapabilities.builder()
                .serverName(server.getName())
                .tools(discoveryClient.discoverTools(server))
                .resources(discoveryClient.discoverResources(server))
                .prompts(discoveryClient.discoverPrompts(server))
                .build();
    }
}
