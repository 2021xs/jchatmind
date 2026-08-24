package com.kama.jchatmind.mcp;

import com.kama.jchatmind.mcp.config.ExternalMcpServerProperties;
import com.kama.jchatmind.mcp.config.ExternalMcpServerType;
import com.kama.jchatmind.mcp.config.ExternalMcpToolProperties;
import com.kama.jchatmind.mcp.config.McpClientProperties;
import com.kama.jchatmind.mcp.registry.ExternalMcpDiscoveredTool;
import com.kama.jchatmind.mcp.registry.ExternalMcpDiscoveredPrompt;
import com.kama.jchatmind.mcp.registry.ExternalMcpDiscoveredResource;
import com.kama.jchatmind.mcp.registry.ExternalMcpCapabilityDiscoveryClient;
import com.kama.jchatmind.mcp.registry.ExternalMcpCapabilityRegistry;
import com.kama.jchatmind.mcp.registry.ExternalMcpServerRegistry;
import com.kama.jchatmind.mcp.registry.ExternalMcpToolDiscoveryClient;
import com.kama.jchatmind.mcp.registry.ExternalMcpToolRegistry;
import com.kama.jchatmind.mcp.safety.McpExternalToolPolicy;
import com.kama.jchatmind.mcp.safety.McpToolRiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalMcpRegistryAndPolicyTest {

    @Test
    void unconfiguredAndDisabledServersAreNotLoaded() {
        assertTrue(registry(new McpClientProperties(), discovery()).exposedTools().isEmpty());

        McpClientProperties properties = new McpClientProperties();
        ExternalMcpServerProperties disabled = server("docs", ExternalMcpServerType.DOCS, false,
                tool("search_docs", null, true));
        properties.setServers(List.of(disabled));

        assertTrue(registry(properties, discovery(toolDefinition("search_docs"))).exposedTools().isEmpty());
    }

    @Test
    void enabledServerRequiresAtLeastOneExplicitAllowList() {
        McpClientProperties properties = new McpClientProperties();
        ExternalMcpServerProperties server = server("docs", ExternalMcpServerType.DOCS, true);
        properties.setServers(List.of(server));

        assertThrows(IllegalStateException.class,
                () -> new ExternalMcpServerRegistry(properties).enabledServers());
    }

    @Test
    void unsupportedServerTypesAreRejectedForFirstVersion() {
        for (ExternalMcpServerType type : List.of(
                ExternalMcpServerType.FILESYSTEM,
                ExternalMcpServerType.DATABASE,
                ExternalMcpServerType.SHELL)) {
            McpClientProperties properties = new McpClientProperties();
            properties.setServers(List.of(server("unsafe-" + type.name(), type, true,
                    tool("list", McpToolRiskLevel.READ_ONLY, true))));

            assertThrows(IllegalStateException.class,
                    () -> registry(properties, discovery(toolDefinition("list"))).exposedTools(),
                    type.name());
        }
    }

    @Test
    void allowListControlsRegistrationButMissingRiskLevelPreventsExposure() {
        McpClientProperties properties = new McpClientProperties();
        properties.setServers(List.of(server("docs", ExternalMcpServerType.DOCS, true,
                tool("search_docs", null, true))));

        var exposed = registry(properties, discovery(
                toolDefinition("search_docs"),
                toolDefinition("fetch_doc")));

        assertEquals(1, exposed.registeredTools().size());
        assertTrue(exposed.exposedTools().isEmpty());
        assertEquals(McpToolRiskLevel.DANGEROUS, exposed.registeredTools().get(0).getRiskLevel());
    }

    @Test
    void explicitlyConfiguredReadOnlyAndNetworkReadToolsCanAutoInvoke() {
        assertAutoInvokes(ExternalMcpServerType.DOCS, "search_docs", McpToolRiskLevel.READ_ONLY);
        assertAutoInvokes(ExternalMcpServerType.GITHUB, "get_issue", McpToolRiskLevel.NETWORK_READ);
        assertAutoInvokes(ExternalMcpServerType.BROWSER, "open_page", McpToolRiskLevel.NETWORK_READ);
    }

    @Test
    void registeredAllowedToolWithoutExplicitAutoInvokeTrueIsNotExposed() {
        McpClientProperties properties = new McpClientProperties();
        properties.setServers(List.of(server("docs", ExternalMcpServerType.DOCS, true,
                tool("safe_tool", McpToolRiskLevel.READ_ONLY, false))));

        ExternalMcpToolRegistry registry = registry(properties, discovery(toolDefinition("safe_tool")));

        assertEquals(1, registry.registeredTools().size());
        assertTrue(registry.exposedTools().isEmpty());
    }

    @Test
    void explicitlyConfiguredWriteAndDangerousToolsAreDisabled() {
        assertBlockedByRisk(ExternalMcpServerType.GITHUB, "create_issue", McpToolRiskLevel.WRITE_OPERATION);
        assertBlockedByRisk(ExternalMcpServerType.BROWSER, "click_button", McpToolRiskLevel.DANGEROUS);
    }

    @Test
    void nameAndDescriptionCannotInferRiskLevel() {
        McpClientProperties properties = new McpClientProperties();
        properties.setServers(List.of(server("docs", ExternalMcpServerType.DOCS, true,
                tool("mcp_safe_search_read_get_list", null, true))));

        ExternalMcpDiscoveredTool discovered = ExternalMcpDiscoveredTool.builder()
                .name("mcp_safe_search_read_get_list")
                .description("safe read-only search get list tool")
                .inputSchema("{\"type\":\"object\"}")
                .build();

        ExternalMcpToolRegistry registry = registry(properties, discovery(discovered));

        assertEquals(1, registry.registeredTools().size());
        assertEquals(McpToolRiskLevel.DANGEROUS, registry.registeredTools().get(0).getRiskLevel());
        assertTrue(registry.exposedTools().isEmpty());
    }

    @Test
    void toolLookingSafeButNotInAllowListIsNotRegistered() {
        McpClientProperties properties = new McpClientProperties();
        properties.setServers(List.of(server("docs", ExternalMcpServerType.DOCS, true,
                tool("explicit_tool", McpToolRiskLevel.READ_ONLY, true))));

        assertTrue(registry(properties, discovery(toolDefinition("mcp_safe_search"))).registeredTools().isEmpty());
    }

    @Test
    void serverNameCommandAndUrlDoNotInferServerType() {
        McpClientProperties noType = new McpClientProperties();
        ExternalMcpServerProperties serverWithoutType = server("github-docs-browser", null, true,
                tool("safe_tool", McpToolRiskLevel.READ_ONLY, true));
        serverWithoutType.setCommand("github docs browser");
        serverWithoutType.setUrl("https://github.example.invalid/browser/docs");
        noType.setServers(List.of(serverWithoutType));

        assertThrows(IllegalStateException.class,
                () -> registry(noType, discovery(toolDefinition("safe_tool"))).exposedTools());
    }

    @Test
    void capabilityRegistryDiscoversToolsResourcesAndPromptsForEnabledServers() {
        McpClientProperties properties = new McpClientProperties();
        properties.setServers(List.of(server("docs", ExternalMcpServerType.DOCS, true,
                tool("search_docs", null, true))));
        ExternalMcpCapabilityDiscoveryClient discovery = new ExternalMcpCapabilityDiscoveryClient() {
            @Override
            public List<ExternalMcpDiscoveredTool> discoverTools(com.kama.jchatmind.mcp.registry.ExternalMcpServerRegistration server) {
                return List.of(toolDefinition("search_docs"));
            }

            @Override
            public List<ExternalMcpDiscoveredResource> discoverResources(com.kama.jchatmind.mcp.registry.ExternalMcpServerRegistration server) {
                return List.of(ExternalMcpDiscoveredResource.builder().name("reference").uri("docs://reference").build());
            }

            @Override
            public List<ExternalMcpDiscoveredPrompt> discoverPrompts(com.kama.jchatmind.mcp.registry.ExternalMcpServerRegistration server) {
                return List.of(ExternalMcpDiscoveredPrompt.builder().name("explain-api").build());
            }
        };

        var capabilities = new ExternalMcpCapabilityRegistry(
                new ExternalMcpServerRegistry(properties),
                discovery,
                new McpExternalToolPolicy()).discoverCapabilities();

        assertEquals(1, capabilities.size());
        assertEquals(1, capabilities.get(0).getTools().size());
        assertEquals(1, capabilities.get(0).getResources().size());
        assertEquals(1, capabilities.get(0).getPrompts().size());
    }

    @Test
    void discoveryFailureIsolatedToUnavailableServer() {
        McpClientProperties properties = new McpClientProperties();
        properties.setServers(List.of(
                server("docs-a", ExternalMcpServerType.DOCS, true,
                        tool("search_a", McpToolRiskLevel.READ_ONLY, true)),
                server("docs-b", ExternalMcpServerType.DOCS, true,
                        tool("search_b", McpToolRiskLevel.READ_ONLY, true))));

        ExternalMcpToolRegistry registry = registry(properties, server -> {
            if ("docs-b".equals(server.getName())) {
                throw new IllegalStateException("credential=secret-token command=/private/path");
            }
            return List.of(toolDefinition("search_a"));
        });

        assertEquals(List.of("mcp_docs_a_search_a"),
                registry.exposedTools().stream().map(tool -> tool.getExposedName()).toList());
        assertEquals(List.of("mcp_docs_a_search_a"),
                registry.exposedTools().stream().map(tool -> tool.getExposedName()).toList());
    }

    @Test
    void capabilityDiscoveryFailureReturnsUnavailableServerWithoutFailingOtherServers() {
        McpClientProperties properties = new McpClientProperties();
        properties.setServers(List.of(
                server("docs-a", ExternalMcpServerType.DOCS, true,
                        tool("search_a", McpToolRiskLevel.READ_ONLY, true)),
                server("docs-b", ExternalMcpServerType.DOCS, true,
                        tool("search_b", McpToolRiskLevel.READ_ONLY, true))));
        ExternalMcpCapabilityDiscoveryClient discovery = new ExternalMcpCapabilityDiscoveryClient() {
            @Override
            public List<ExternalMcpDiscoveredTool> discoverTools(com.kama.jchatmind.mcp.registry.ExternalMcpServerRegistration server) {
                if ("docs-b".equals(server.getName())) {
                    throw new IllegalStateException("server unavailable");
                }
                return List.of(toolDefinition("search_a"));
            }

            @Override
            public List<ExternalMcpDiscoveredResource> discoverResources(com.kama.jchatmind.mcp.registry.ExternalMcpServerRegistration server) {
                return List.of();
            }

            @Override
            public List<ExternalMcpDiscoveredPrompt> discoverPrompts(com.kama.jchatmind.mcp.registry.ExternalMcpServerRegistration server) {
                return List.of();
            }
        };

        var capabilities = new ExternalMcpCapabilityRegistry(
                new ExternalMcpServerRegistry(properties), discovery, new McpExternalToolPolicy())
                .discoverCapabilities();

        assertEquals(2, capabilities.size());
        assertEquals(1, capabilities.get(0).getTools().size());
        assertTrue(capabilities.get(1).getTools().isEmpty());
    }

    private void assertAutoInvokes(ExternalMcpServerType type, String toolName, McpToolRiskLevel expectedRisk) {
        McpClientProperties properties = new McpClientProperties();
        properties.setServers(List.of(server(type.name().toLowerCase(), type, true,
                tool(toolName, expectedRisk, true))));

        var exposed = registry(properties, discovery(toolDefinition(toolName))).exposedTools();

        assertEquals(1, exposed.size());
        assertEquals(expectedRisk, exposed.get(0).getRiskLevel());
        assertTrue(exposed.get(0).isAutoInvokeAllowed());
    }

    private void assertBlockedByRisk(ExternalMcpServerType type, String toolName, McpToolRiskLevel expectedRisk) {
        McpClientProperties properties = new McpClientProperties();
        properties.setServers(List.of(server(type.name().toLowerCase(), type, true,
                tool(toolName, expectedRisk, true))));
        ExternalMcpToolRegistry registry = registry(properties, discovery(toolDefinition(toolName)));

        assertEquals(expectedRisk, registry.registeredTools().get(0).getRiskLevel());
        assertTrue(registry.exposedTools().isEmpty());
    }

    private ExternalMcpToolRegistry registry(McpClientProperties properties, ExternalMcpToolDiscoveryClient discovery) {
        return new ExternalMcpToolRegistry(
                new ExternalMcpServerRegistry(properties),
                discovery,
                new McpExternalToolPolicy());
    }

    private ExternalMcpToolDiscoveryClient discovery(ExternalMcpDiscoveredTool... tools) {
        return server -> List.of(tools);
    }

    private ExternalMcpDiscoveredTool toolDefinition(String name) {
        return ExternalMcpDiscoveredTool.builder()
                .name(name)
                .description("description")
                .inputSchema("{\"type\":\"object\"}")
                .build();
    }

    private ExternalMcpServerProperties server(String name,
                                               ExternalMcpServerType type,
                                               boolean enabled,
                                               ExternalMcpToolProperties... tools) {
        ExternalMcpServerProperties server = new ExternalMcpServerProperties();
        server.setName(name);
        server.setType(type);
        server.setTransport("stdio");
        server.setCommand("mock");
        server.setEnabled(enabled);
        server.setAllowedTools(List.of(tools));
        return server;
    }

    private ExternalMcpToolProperties tool(String name,
                                           McpToolRiskLevel riskLevel,
                                           boolean autoInvokeAllowed) {
        ExternalMcpToolProperties tool = new ExternalMcpToolProperties();
        tool.setName(name);
        tool.setRiskLevel(riskLevel);
        tool.setAutoInvokeAllowed(autoInvokeAllowed);
        return tool;
    }
}
