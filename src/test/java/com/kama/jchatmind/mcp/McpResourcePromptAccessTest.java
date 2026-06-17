package com.kama.jchatmind.mcp;

import com.kama.jchatmind.mcp.audit.McpPromptAuditLogger;
import com.kama.jchatmind.mcp.audit.McpResourceAuditLogger;
import com.kama.jchatmind.mcp.config.ExternalMcpPromptProperties;
import com.kama.jchatmind.mcp.config.ExternalMcpResourceProperties;
import com.kama.jchatmind.mcp.config.ExternalMcpServerProperties;
import com.kama.jchatmind.mcp.config.ExternalMcpServerType;
import com.kama.jchatmind.mcp.config.McpClientProperties;
import com.kama.jchatmind.mcp.registry.ExternalMcpCapabilityDiscoveryClient;
import com.kama.jchatmind.mcp.registry.ExternalMcpDiscoveredPrompt;
import com.kama.jchatmind.mcp.registry.ExternalMcpDiscoveredResource;
import com.kama.jchatmind.mcp.registry.ExternalMcpDiscoveredTool;
import com.kama.jchatmind.mcp.registry.ExternalMcpPromptRegistration;
import com.kama.jchatmind.mcp.registry.ExternalMcpPromptRegistry;
import com.kama.jchatmind.mcp.registry.ExternalMcpResourceRegistration;
import com.kama.jchatmind.mcp.registry.ExternalMcpResourceRegistry;
import com.kama.jchatmind.mcp.registry.ExternalMcpServerRegistry;
import com.kama.jchatmind.mcp.registry.ExternalMcpToolRegistration;
import com.kama.jchatmind.mcp.safety.McpExternalToolPolicy;
import com.kama.jchatmind.mcp.safety.McpToolRiskLevel;
import com.kama.jchatmind.mcp.service.ExternalMcpPromptAccessServiceImpl;
import com.kama.jchatmind.mcp.service.ExternalMcpResourceAccessServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpResourcePromptAccessTest {

    @Test
    void resourceAllowListRiskAutoAttachTruncationAndAuditAreEnforced() {
        McpClientProperties properties = new McpClientProperties();
        properties.setMaxResultLength(42);
        properties.setServers(List.of(server(
                List.of(resource("docs://safe", McpToolRiskLevel.READ_ONLY, true),
                        resource("docs://missing-risk", null, true)),
                List.of())));
        ExternalMcpResourceRegistry registry = resourceRegistry(properties, discovery(
                List.of(discoveredResource("docs://safe", "safe read-only resource"),
                        discoveredResource("docs://missing-risk", "description says safe read-only"),
                        discoveredResource("docs://not-allowed", "safe read-only")),
                List.of()));

        assertEquals(2, registry.registeredResources().size());
        assertEquals(List.of("docs://safe"), registry.autoAttachResources().stream()
                .map(ExternalMcpResourceRegistration::getUri)
                .toList());
        assertEquals(McpToolRiskLevel.DANGEROUS,
                registry.findByUri("docs://missing-risk").orElseThrow().getRiskLevel());

        RecordingResourceAudit audit = new RecordingResourceAudit();
        ExternalMcpResourceAccessServiceImpl service = new ExternalMcpResourceAccessServiceImpl(
                registry,
                resource -> "resource-content-" + "x".repeat(100),
                audit,
                properties);

        String result = service.read("docs://safe");

        assertTrue(result.length() <= 42);
        assertTrue(result.endsWith("...[truncated]"));
        assertEquals(List.of("success:docs://safe:true"), audit.events);

        assertThrows(IllegalArgumentException.class, () -> service.read("docs://missing-risk"));
        assertThrows(IllegalArgumentException.class, () -> service.read("docs://not-allowed"));
        assertTrue(audit.events.contains("denied:docs://missing-risk:MCP_RESOURCE_POLICY_REJECTED"));
        assertTrue(audit.events.contains("denied:docs://not-allowed:MCP_RESOURCE_POLICY_REJECTED"));
    }

    @Test
    void promptAllowListRiskArgumentsTruncationAndAuditAreEnforcedWithoutLoggingValues() {
        McpClientProperties properties = new McpClientProperties();
        properties.setMaxResultLength(46);
        properties.setServers(List.of(server(
                List.of(),
                List.of(prompt("explain-api", McpToolRiskLevel.READ_ONLY, false),
                        prompt("safe-looking", null, false)))));
        ExternalMcpPromptRegistry registry = promptRegistry(properties, discovery(
                List.of(),
                List.of(discoveredPrompt("explain-api", "safe template", List.of("library")),
                        discoveredPrompt("safe-looking", "safe read-only prompt", List.of()))));

        assertEquals(2, registry.registeredPrompts().size());
        assertTrue(registry.canUse(registry.findByName("explain-api").orElseThrow()));
        assertEquals(McpToolRiskLevel.DANGEROUS,
                registry.findByName("safe-looking").orElseThrow().getRiskLevel());

        RecordingPromptAudit audit = new RecordingPromptAudit();
        ExternalMcpPromptAccessServiceImpl service = new ExternalMcpPromptAccessServiceImpl(
                registry,
                (prompt, arguments) -> "prompt-content-" + arguments.get("library") + "-" + "x".repeat(100),
                audit,
                properties);

        String result = service.get("explain-api", Map.of("library", "sensitive-argument-value"));

        assertTrue(result.length() <= 46);
        assertTrue(result.endsWith("...[truncated]"));
        assertEquals(List.of("success:explain-api:[library]:true"), audit.events);

        assertThrows(IllegalArgumentException.class, () -> service.get("explain-api", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> service.get("safe-looking", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> service.get("not-allowed", Map.of("library", "x")));
        assertTrue(audit.events.stream().anyMatch(event -> event.startsWith("failure:explain-api:")
                && event.endsWith(":MCP_PROMPT_INVALID_ARGUMENTS")));
        assertTrue(audit.events.contains("denied:safe-looking:[]:MCP_PROMPT_POLICY_REJECTED"));
        assertTrue(audit.events.contains("denied:not-allowed:[library]:MCP_PROMPT_POLICY_REJECTED"));
        assertTrue(audit.events.stream().noneMatch(event -> event.contains("sensitive-argument-value")));
    }

    private ExternalMcpResourceRegistry resourceRegistry(McpClientProperties properties,
                                                         ExternalMcpCapabilityDiscoveryClient discovery) {
        return new ExternalMcpResourceRegistry(
                new ExternalMcpServerRegistry(properties),
                discovery,
                new McpExternalToolPolicy());
    }

    private ExternalMcpPromptRegistry promptRegistry(McpClientProperties properties,
                                                     ExternalMcpCapabilityDiscoveryClient discovery) {
        return new ExternalMcpPromptRegistry(
                new ExternalMcpServerRegistry(properties),
                discovery,
                new McpExternalToolPolicy());
    }

    private ExternalMcpCapabilityDiscoveryClient discovery(List<ExternalMcpDiscoveredResource> resources,
                                                           List<ExternalMcpDiscoveredPrompt> prompts) {
        return new ExternalMcpCapabilityDiscoveryClient() {
            @Override
            public List<ExternalMcpDiscoveredTool> discoverTools(com.kama.jchatmind.mcp.registry.ExternalMcpServerRegistration server) {
                return List.of();
            }

            @Override
            public List<ExternalMcpDiscoveredResource> discoverResources(com.kama.jchatmind.mcp.registry.ExternalMcpServerRegistration server) {
                return resources;
            }

            @Override
            public List<ExternalMcpDiscoveredPrompt> discoverPrompts(com.kama.jchatmind.mcp.registry.ExternalMcpServerRegistration server) {
                return prompts;
            }
        };
    }

    private ExternalMcpServerProperties server(List<ExternalMcpResourceProperties> resources,
                                               List<ExternalMcpPromptProperties> prompts) {
        ExternalMcpServerProperties server = new ExternalMcpServerProperties();
        server.setName("docs");
        server.setType(ExternalMcpServerType.DOCS);
        server.setTransport("stdio");
        server.setCommand("mock");
        server.setEnabled(true);
        server.setAllowedResources(resources);
        server.setAllowedPrompts(prompts);
        return server;
    }

    private ExternalMcpResourceProperties resource(String uri,
                                                   McpToolRiskLevel riskLevel,
                                                   boolean autoAttachAllowed) {
        ExternalMcpResourceProperties resource = new ExternalMcpResourceProperties();
        resource.setUri(uri);
        resource.setRiskLevel(riskLevel);
        resource.setAutoAttachAllowed(autoAttachAllowed);
        return resource;
    }

    private ExternalMcpPromptProperties prompt(String name,
                                               McpToolRiskLevel riskLevel,
                                               boolean autoAttachAllowed) {
        ExternalMcpPromptProperties prompt = new ExternalMcpPromptProperties();
        prompt.setName(name);
        prompt.setRiskLevel(riskLevel);
        prompt.setAutoAttachAllowed(autoAttachAllowed);
        return prompt;
    }

    private ExternalMcpDiscoveredResource discoveredResource(String uri, String description) {
        return ExternalMcpDiscoveredResource.builder()
                .uri(uri)
                .name(uri)
                .description(description)
                .mimeType("text/plain")
                .build();
    }

    private ExternalMcpDiscoveredPrompt discoveredPrompt(String name, String description, List<String> required) {
        return ExternalMcpDiscoveredPrompt.builder()
                .name(name)
                .description(description)
                .requiredArguments(required)
                .build();
    }

    private static class RecordingResourceAudit implements McpResourceAuditLogger {
        private final List<String> events = new ArrayList<>();

        @Override
        public void success(String traceId, ExternalMcpResourceRegistration resource, String contentSummary,
                            long latencyMs, boolean truncated) {
            events.add("success:" + resource.getUri() + ":" + truncated);
        }

        @Override
        public void failure(String traceId, ExternalMcpResourceRegistration resource, String errorMessage,
                            long latencyMs, String errorCode) {
            events.add("failure:" + resource.getUri() + ":" + errorCode);
        }

        @Override
        public void denied(String traceId, String serverName, String uri, String errorCode) {
            events.add("denied:" + uri + ":" + errorCode);
        }
    }

    private static class RecordingPromptAudit implements McpPromptAuditLogger {
        private final List<String> events = new ArrayList<>();

        @Override
        public void success(String traceId, ExternalMcpPromptRegistration prompt, Set<String> argumentNames,
                            String promptSummary, long latencyMs, boolean truncated) {
            events.add("success:" + prompt.getName() + ":" + argumentNames + ":" + truncated);
        }

        @Override
        public void failure(String traceId, ExternalMcpPromptRegistration prompt, Set<String> argumentNames,
                            String errorMessage, long latencyMs, String errorCode) {
            events.add("failure:" + prompt.getName() + ":" + argumentNames + ":" + errorCode);
        }

        @Override
        public void denied(String traceId, String serverName, String promptName, Set<String> argumentNames,
                           String errorCode) {
            events.add("denied:" + promptName + ":" + argumentNames + ":" + errorCode);
        }
    }
}
