package com.kama.jchatmind.mcp.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mcp.registry.ExternalMcpDiscoveredTool;
import com.kama.jchatmind.mcp.registry.ExternalMcpDiscoveredPrompt;
import com.kama.jchatmind.mcp.registry.ExternalMcpDiscoveredResource;
import com.kama.jchatmind.mcp.registry.ExternalMcpCapabilityDiscoveryClient;
import com.kama.jchatmind.mcp.registry.ExternalMcpServerRegistration;
import com.kama.jchatmind.mcp.registry.ExternalMcpToolRegistration;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class SpringAiExternalMcpClientAdapter implements ExternalMcpCapabilityDiscoveryClient, ExternalMcpToolInvoker {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final List<McpSyncClient> mcpClients;
    private final ObjectMapper objectMapper;

    public SpringAiExternalMcpClientAdapter(List<McpSyncClient> mcpClients, ObjectMapper objectMapper) {
        this.mcpClients = mcpClients == null ? List.of() : List.copyOf(mcpClients);
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ExternalMcpDiscoveredTool> discoverTools(ExternalMcpServerRegistration server) {
        McpSyncClient client = clientFor(server)
                .orElseThrow(() -> new IllegalStateException("No MCP client found for server: " + server.getName()));
        return client.listTools().tools().stream()
                .map(tool -> ExternalMcpDiscoveredTool.builder()
                        .name(tool.name())
                        .description(tool.description())
                        .inputSchema(toJson(tool.inputSchema()))
                        .build())
                .toList();
    }

    @Override
    public List<ExternalMcpDiscoveredResource> discoverResources(ExternalMcpServerRegistration server) {
        McpSyncClient client = clientFor(server)
                .orElseThrow(() -> new IllegalStateException("No MCP client found for server: " + server.getName()));
        return client.listResources().resources().stream()
                .map(resource -> ExternalMcpDiscoveredResource.builder()
                        .uri(resource.uri())
                        .name(resource.name())
                        .description(resource.description())
                        .mimeType(resource.mimeType())
                        .build())
                .toList();
    }

    @Override
    public List<ExternalMcpDiscoveredPrompt> discoverPrompts(ExternalMcpServerRegistration server) {
        McpSyncClient client = clientFor(server)
                .orElseThrow(() -> new IllegalStateException("No MCP client found for server: " + server.getName()));
        return client.listPrompts().prompts().stream()
                .map(prompt -> ExternalMcpDiscoveredPrompt.builder()
                        .name(prompt.name())
                        .description(prompt.description())
                        .build())
                .toList();
    }

    @Override
    public String invoke(ExternalMcpToolRegistration tool, String argumentsJson) {
        McpSyncClient client = clientFor(tool.getServerName())
                .orElseThrow(() -> new IllegalStateException("No MCP client found for server: " + tool.getServerName()));
        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name(tool.getToolName())
                .arguments(readArguments(argumentsJson))
                .build();
        McpSchema.CallToolResult result = client.callTool(request);
        if (Boolean.TRUE.equals(result.isError())) {
            throw new IllegalStateException("External MCP tool returned error: " + result);
        }
        return toJson(result);
    }

    private Optional<McpSyncClient> clientFor(ExternalMcpServerRegistration server) {
        return clientFor(server.getName());
    }

    private Optional<McpSyncClient> clientFor(String serverName) {
        String expected = normalize(serverName);
        return mcpClients.stream()
                .filter(client -> expected.equals(normalize(serverName(client))))
                .findFirst()
                .or(() -> mcpClients.size() == 1 ? Optional.of(mcpClients.get(0)) : Optional.empty());
    }

    private String serverName(McpSyncClient client) {
        if (client.getServerInfo() == null || !StringUtils.hasText(client.getServerInfo().name())) {
            return "";
        }
        return client.getServerInfo().name();
    }

    private Map<String, Object> readArguments(String argumentsJson) {
        if (!StringUtils.hasText(argumentsJson)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(argumentsJson, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid MCP tool arguments JSON: " + e.getMessage(), e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
