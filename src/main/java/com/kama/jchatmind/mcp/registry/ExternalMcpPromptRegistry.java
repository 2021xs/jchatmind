package com.kama.jchatmind.mcp.registry;

import com.kama.jchatmind.mcp.config.ExternalMcpPromptProperties;
import com.kama.jchatmind.mcp.safety.McpExternalToolPolicy;
import com.kama.jchatmind.mcp.safety.McpToolRiskLevel;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ExternalMcpPromptRegistry {
    private final ExternalMcpServerRegistry serverRegistry;
    private final ExternalMcpCapabilityDiscoveryClient discoveryClient;
    private final McpExternalToolPolicy policy;

    public ExternalMcpPromptRegistry(ExternalMcpServerRegistry serverRegistry,
                                     ExternalMcpCapabilityDiscoveryClient discoveryClient,
                                     McpExternalToolPolicy policy) {
        this.serverRegistry = serverRegistry;
        this.discoveryClient = discoveryClient;
        this.policy = policy;
    }

    public List<ExternalMcpPromptRegistration> registeredPrompts() {
        List<ExternalMcpPromptRegistration> result = new ArrayList<>();
        for (ExternalMcpServerRegistration server : serverRegistry.enabledServers()) {
            if (!policy.isSupportedServerType(server.getType())) {
                throw new IllegalStateException("Unsupported MCP server type for first version: "
                        + server.getType() + " server=" + server.getName());
            }
            result.addAll(registeredPrompts(server));
        }
        return List.copyOf(result);
    }

    public Optional<ExternalMcpPromptRegistration> findByName(String name) {
        return registeredPrompts().stream()
                .filter(prompt -> prompt.getName().equals(name))
                .findFirst();
    }

    public boolean canUse(ExternalMcpPromptRegistration prompt) {
        return prompt != null
                && policy.canUsePrompt(prompt.getServerType(), prompt.getRiskLevel());
    }

    private List<ExternalMcpPromptRegistration> registeredPrompts(ExternalMcpServerRegistration server) {
        Map<String, ExternalMcpPromptProperties> allowList = server.getProperties().getAllowedPrompts().stream()
                .filter(prompt -> StringUtils.hasText(prompt.getName()))
                .collect(Collectors.toMap(ExternalMcpPromptProperties::getName, Function.identity(),
                        (left, right) -> left));
        if (allowList.isEmpty()) {
            return List.of();
        }
        return discoveryClient.discoverPrompts(server).stream()
                .filter(prompt -> allowList.containsKey(prompt.getName()))
                .map(prompt -> toRegistration(server, prompt, allowList.get(prompt.getName())))
                .toList();
    }

    private ExternalMcpPromptRegistration toRegistration(ExternalMcpServerRegistration server,
                                                         ExternalMcpDiscoveredPrompt prompt,
                                                         ExternalMcpPromptProperties configuredPrompt) {
        McpToolRiskLevel riskLevel = policy.resolveRiskLevel(configuredPrompt.getRiskLevel());
        return ExternalMcpPromptRegistration.builder()
                .serverName(server.getName())
                .serverType(server.getType())
                .name(prompt.getName())
                .description(prompt.getDescription())
                .requiredArguments(prompt.getRequiredArguments() == null
                        ? List.of()
                        : List.copyOf(prompt.getRequiredArguments()))
                .riskLevel(riskLevel)
                .autoAttachAllowed(policy.canAutoAttachResource(server.getType(), riskLevel,
                        configuredPrompt.isAutoAttachAllowed()))
                .build();
    }
}
