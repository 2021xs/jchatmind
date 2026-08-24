package com.kama.jchatmind.mcp.registry;

import com.kama.jchatmind.mcp.config.ExternalMcpToolProperties;
import com.kama.jchatmind.mcp.safety.McpExternalToolPolicy;
import com.kama.jchatmind.mcp.safety.McpToolRiskLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public class ExternalMcpToolRegistry {
    private static final String DEFAULT_INPUT_SCHEMA = "{\"type\":\"object\",\"additionalProperties\":true}";

    private final ExternalMcpServerRegistry serverRegistry;
    private final ExternalMcpToolDiscoveryClient discoveryClient;
    private final McpExternalToolPolicy policy;

    public ExternalMcpToolRegistry(ExternalMcpServerRegistry serverRegistry,
                                   ExternalMcpToolDiscoveryClient discoveryClient,
                                   McpExternalToolPolicy policy) {
        this.serverRegistry = serverRegistry;
        this.discoveryClient = discoveryClient;
        this.policy = policy;
    }

    public List<ExternalMcpToolRegistration> exposedTools() {
        return registeredTools().stream()
                .filter(this::canExposeToModel)
                .toList();
    }

    public List<ExternalMcpToolRegistration> registeredTools() {
        List<ExternalMcpToolRegistration> result = new ArrayList<>();
        for (ExternalMcpServerRegistration server : serverRegistry.enabledServers()) {
            if (!policy.isSupportedServerType(server.getType())) {
                throw new IllegalStateException("Unsupported MCP server type for first version: "
                        + server.getType() + " server=" + server.getName());
            }
            result.addAll(exposedTools(server));
        }
        return List.copyOf(result);
    }

    public Optional<ExternalMcpToolRegistration> findByExposedName(String exposedName) {
        return registeredTools().stream()
                .filter(tool -> tool.getExposedName().equals(exposedName))
                .findFirst();
    }

    public boolean canExposeToModel(ExternalMcpToolRegistration tool) {
        return tool != null
                && policy.isSupportedServerType(tool.getServerType())
                && policy.isToolAllowedByRisk(tool.getRiskLevel())
                && tool.isAutoInvokeAllowed();
    }

    private List<ExternalMcpToolRegistration> exposedTools(ExternalMcpServerRegistration server) {
        Map<String, ExternalMcpToolProperties> allowList = server.getProperties().getAllowedTools().stream()
                .filter(tool -> StringUtils.hasText(tool.getName()))
                .collect(Collectors.toMap(ExternalMcpToolProperties::getName, Function.identity(), (left, right) -> left));
        if (allowList.isEmpty()) {
            return List.of();
        }
        List<ExternalMcpDiscoveredTool> discoveredTools;
        try {
            discoveredTools = discoveryClient.discoverTools(server);
        } catch (RuntimeException e) {
            log.warn("External MCP server unavailable after tool discovery failure: serverName={}, serverType={}, "
                            + "status=UNAVAILABLE, failureType=MCP_DISCOVERY_FAILED, "
                            + "message=External MCP tool discovery failed",
                    server.getName(), server.getType(), e);
            return List.of();
        }
        if (discoveredTools == null) {
            log.warn("External MCP server unavailable after empty tool discovery response: serverName={}, serverType={}, "
                            + "status=UNAVAILABLE, failureType=MCP_DISCOVERY_FAILED, "
                            + "message=External MCP tool discovery returned no response",
                    server.getName(), server.getType());
            return List.of();
        }
        return discoveredTools.stream()
                .filter(tool -> allowList.containsKey(tool.getName()))
                .map(tool -> toRegistration(server, tool, allowList.get(tool.getName())))
                .toList();
    }

    private ExternalMcpToolRegistration toRegistration(ExternalMcpServerRegistration server,
                                                       ExternalMcpDiscoveredTool tool,
                                                       ExternalMcpToolProperties configuredTool) {
        McpToolRiskLevel riskLevel = policy.resolveRiskLevel(configuredTool.getRiskLevel());
        return ExternalMcpToolRegistration.builder()
                .serverName(server.getName())
                .serverType(server.getType())
                .toolName(tool.getName())
                .exposedName(exposedName(server.getName(), tool.getName()))
                .description(description(server, tool, riskLevel))
                .inputSchema(StringUtils.hasText(tool.getInputSchema()) ? tool.getInputSchema() : DEFAULT_INPUT_SCHEMA)
                .riskLevel(riskLevel)
                .autoInvokeAllowed(policy.canAutoInvoke(server.getType(), riskLevel,
                        configuredTool.isAutoInvokeAllowed()))
                .build();
    }

    private String description(ExternalMcpServerRegistration server,
                               ExternalMcpDiscoveredTool tool,
                               McpToolRiskLevel riskLevel) {
        String description = StringUtils.hasText(tool.getDescription()) ? tool.getDescription() : "";
        return "[external MCP " + server.getType() + " / " + riskLevel + "] " + description;
    }

    private String exposedName(String serverName, String toolName) {
        return "mcp_" + sanitize(serverName) + "_" + sanitize(toolName);
    }

    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sanitized = new StringBuilder();
        for (char ch : value.trim().toLowerCase(java.util.Locale.ROOT).toCharArray()) {
            sanitized.append((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_' ? ch : '_');
        }
        return sanitized.toString();
    }
}
