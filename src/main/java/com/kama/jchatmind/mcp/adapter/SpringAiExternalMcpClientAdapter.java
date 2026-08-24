package com.kama.jchatmind.mcp.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mcp.registry.ExternalMcpDiscoveredTool;
import com.kama.jchatmind.mcp.registry.ExternalMcpDiscoveredPrompt;
import com.kama.jchatmind.mcp.registry.ExternalMcpDiscoveredResource;
import com.kama.jchatmind.mcp.registry.ExternalMcpCapabilityDiscoveryClient;
import com.kama.jchatmind.mcp.registry.ExternalMcpPromptClient;
import com.kama.jchatmind.mcp.registry.ExternalMcpPromptRegistration;
import com.kama.jchatmind.mcp.registry.ExternalMcpResourceReader;
import com.kama.jchatmind.mcp.registry.ExternalMcpResourceRegistration;
import com.kama.jchatmind.mcp.registry.ExternalMcpServerRegistration;
import com.kama.jchatmind.mcp.registry.ExternalMcpToolRegistration;
import com.kama.jchatmind.tool.ToolArgumentException;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class SpringAiExternalMcpClientAdapter implements ExternalMcpCapabilityDiscoveryClient, ExternalMcpToolInvoker,
        ExternalMcpResourceReader, ExternalMcpPromptClient {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final List<McpSyncClient> fixedMcpClients;
    private final ObjectProvider<List<McpSyncClient>> mcpClientsProvider;
    private final ObjectMapper objectMapper;

    public SpringAiExternalMcpClientAdapter(List<McpSyncClient> mcpClients, ObjectMapper objectMapper) {
        this.fixedMcpClients = mcpClients == null ? List.of() : List.copyOf(mcpClients);
        this.mcpClientsProvider = null;
        this.objectMapper = objectMapper;
    }

    public SpringAiExternalMcpClientAdapter(ObjectProvider<List<McpSyncClient>> mcpClientsProvider,
                                            ObjectMapper objectMapper) {
        this.fixedMcpClients = null;
        this.mcpClientsProvider = mcpClientsProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ExternalMcpDiscoveredTool> discoverTools(ExternalMcpServerRegistration server) {
        Optional<McpSyncClient> maybeClient = clientFor(server);
        if (maybeClient.isEmpty()) {
            return List.of();
        }
        McpSyncClient client = maybeClient.get();
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
        Optional<McpSyncClient> maybeClient = clientFor(server);
        if (maybeClient.isEmpty()) {
            return List.of();
        }
        McpSyncClient client = maybeClient.get();
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
        Optional<McpSyncClient> maybeClient = clientFor(server);
        if (maybeClient.isEmpty()) {
            return List.of();
        }
        McpSyncClient client = maybeClient.get();
        return client.listPrompts().prompts().stream()
                .map(prompt -> ExternalMcpDiscoveredPrompt.builder()
                        .name(prompt.name())
                        .description(prompt.description())
                        .requiredArguments(prompt.arguments() == null
                                ? List.of()
                                : prompt.arguments().stream()
                                .filter(argument -> Boolean.TRUE.equals(argument.required()))
                                .map(McpSchema.PromptArgument::name)
                                .toList())
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

    @Override
    public String read(ExternalMcpResourceRegistration resource) {
        McpSyncClient client = clientFor(resource.getServerName())
                .orElseThrow(() -> new IllegalStateException("No MCP client found for server: " + resource.getServerName()));
        McpSchema.ReadResourceResult result = client.readResource(new McpSchema.ReadResourceRequest(resource.getUri()));
        return toJson(result);
    }

    @Override
    public String get(ExternalMcpPromptRegistration prompt, Map<String, Object> arguments) {
        McpSyncClient client = clientFor(prompt.getServerName())
                .orElseThrow(() -> new IllegalStateException("No MCP client found for server: " + prompt.getServerName()));
        McpSchema.GetPromptResult result = client.getPrompt(new McpSchema.GetPromptRequest(prompt.getName(), arguments));
        return toJson(result);
    }

    private Optional<McpSyncClient> clientFor(ExternalMcpServerRegistration server) {
        return clientFor(server.getName());
    }

    private Optional<McpSyncClient> clientFor(String serverName) {
        String expected = normalize(serverName);
        List<McpSyncClient> clients = mcpClients();
        return clients.stream()
                .filter(client -> expected.equals(normalize(serverName(client))))
                .findFirst()
                .or(() -> clients.size() == 1 ? Optional.of(clients.get(0)) : Optional.empty());
    }

    private List<McpSyncClient> mcpClients() {
        if (fixedMcpClients != null) {
            return fixedMcpClients;
        }
        List<McpSyncClient> provided = mcpClientsProvider == null ? null : mcpClientsProvider.getIfAvailable();
        return provided == null ? List.of() : provided;
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
            throw new ToolArgumentException("Invalid MCP tool arguments JSON", e);
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
