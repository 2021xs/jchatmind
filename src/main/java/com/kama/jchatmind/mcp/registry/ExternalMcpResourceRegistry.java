package com.kama.jchatmind.mcp.registry;

import com.kama.jchatmind.mcp.config.ExternalMcpResourceProperties;
import com.kama.jchatmind.mcp.safety.McpExternalToolPolicy;
import com.kama.jchatmind.mcp.safety.McpToolRiskLevel;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ExternalMcpResourceRegistry {
    private final ExternalMcpServerRegistry serverRegistry;
    private final ExternalMcpCapabilityDiscoveryClient discoveryClient;
    private final McpExternalToolPolicy policy;

    public ExternalMcpResourceRegistry(ExternalMcpServerRegistry serverRegistry,
                                       ExternalMcpCapabilityDiscoveryClient discoveryClient,
                                       McpExternalToolPolicy policy) {
        this.serverRegistry = serverRegistry;
        this.discoveryClient = discoveryClient;
        this.policy = policy;
    }

    public List<ExternalMcpResourceRegistration> registeredResources() {
        List<ExternalMcpResourceRegistration> result = new ArrayList<>();
        for (ExternalMcpServerRegistration server : serverRegistry.enabledServers()) {
            if (!policy.isSupportedServerType(server.getType())) {
                throw new IllegalStateException("Unsupported MCP server type for first version: "
                        + server.getType() + " server=" + server.getName());
            }
            result.addAll(registeredResources(server));
        }
        return List.copyOf(result);
    }

    public List<ExternalMcpResourceRegistration> autoAttachResources() {
        return registeredResources().stream()
                .filter(this::canAutoAttach)
                .toList();
    }

    public Optional<ExternalMcpResourceRegistration> findByUri(String uri) {
        return registeredResources().stream()
                .filter(resource -> resource.getUri().equals(uri))
                .findFirst();
    }

    public boolean canRead(ExternalMcpResourceRegistration resource) {
        return resource != null
                && policy.isSupportedServerType(resource.getServerType())
                && policy.isReadOnlyRisk(resource.getRiskLevel());
    }

    public boolean canAutoAttach(ExternalMcpResourceRegistration resource) {
        return resource != null
                && policy.canAutoAttachResource(resource.getServerType(), resource.getRiskLevel(),
                resource.isAutoAttachAllowed());
    }

    private List<ExternalMcpResourceRegistration> registeredResources(ExternalMcpServerRegistration server) {
        Map<String, ExternalMcpResourceProperties> allowList = server.getProperties().getAllowedResources().stream()
                .filter(resource -> StringUtils.hasText(resource.getUri()))
                .collect(Collectors.toMap(ExternalMcpResourceProperties::getUri, Function.identity(),
                        (left, right) -> left));
        if (allowList.isEmpty()) {
            return List.of();
        }
        return discoveryClient.discoverResources(server).stream()
                .filter(resource -> allowList.containsKey(resource.getUri()))
                .map(resource -> toRegistration(server, resource, allowList.get(resource.getUri())))
                .toList();
    }

    private ExternalMcpResourceRegistration toRegistration(ExternalMcpServerRegistration server,
                                                           ExternalMcpDiscoveredResource resource,
                                                           ExternalMcpResourceProperties configuredResource) {
        McpToolRiskLevel riskLevel = policy.resolveRiskLevel(configuredResource.getRiskLevel());
        return ExternalMcpResourceRegistration.builder()
                .serverName(server.getName())
                .serverType(server.getType())
                .uri(resource.getUri())
                .name(resource.getName())
                .description(resource.getDescription())
                .mimeType(resource.getMimeType())
                .riskLevel(riskLevel)
                .autoAttachAllowed(policy.canAutoAttachResource(server.getType(), riskLevel,
                        configuredResource.isAutoAttachAllowed()))
                .build();
    }
}
