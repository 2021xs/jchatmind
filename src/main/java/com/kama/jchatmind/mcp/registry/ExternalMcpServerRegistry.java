package com.kama.jchatmind.mcp.registry;

import com.kama.jchatmind.mcp.config.ExternalMcpServerProperties;
import com.kama.jchatmind.mcp.config.McpClientProperties;
import org.springframework.util.StringUtils;

import java.util.List;

public class ExternalMcpServerRegistry {
    private final McpClientProperties properties;

    public ExternalMcpServerRegistry(McpClientProperties properties) {
        this.properties = properties;
    }

    public List<ExternalMcpServerRegistration> enabledServers() {
        if (properties.getServers() == null || properties.getServers().isEmpty()) {
            return List.of();
        }
        return properties.getServers().stream()
                .filter(ExternalMcpServerProperties::isEnabled)
                .map(this::toRegistration)
                .toList();
    }

    private ExternalMcpServerRegistration toRegistration(ExternalMcpServerProperties server) {
        validate(server);
        return ExternalMcpServerRegistration.builder()
                .name(server.getName())
                .type(server.getType())
                .transport(server.getTransport())
                .command(server.getCommand())
                .url(server.getUrl())
                .properties(server)
                .build();
    }

    private void validate(ExternalMcpServerProperties server) {
        if (!StringUtils.hasText(server.getName())) {
            throw new IllegalStateException("enabled MCP server must declare name");
        }
        if (server.getType() == null) {
            throw new IllegalStateException("enabled MCP server must declare type: " + server.getName());
        }
        if (server.getAllowedTools() == null || server.getAllowedTools().isEmpty()) {
            throw new IllegalStateException("enabled MCP server must declare allowed-tools: " + server.getName());
        }
    }
}
