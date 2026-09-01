package com.kama.jchatmind.mcp;

import com.kama.jchatmind.mcp.adapter.McpToolCallbackAdapter;
import com.kama.jchatmind.mcp.audit.Slf4jMcpPromptAuditLogger;
import com.kama.jchatmind.mcp.audit.Slf4jMcpResourceAuditLogger;
import com.kama.jchatmind.mcp.audit.Slf4jMcpToolAuditLogger;
import com.kama.jchatmind.mcp.config.ExternalMcpServerProperties;
import com.kama.jchatmind.mcp.config.ExternalMcpServerType;
import com.kama.jchatmind.mcp.config.ExternalMcpToolProperties;
import com.kama.jchatmind.mcp.config.McpClientProperties;
import com.kama.jchatmind.mcp.registry.ExternalMcpCapabilityDiscoveryClient;
import com.kama.jchatmind.mcp.registry.ExternalMcpCapabilityRegistry;
import com.kama.jchatmind.mcp.registry.ExternalMcpDiscoveredPrompt;
import com.kama.jchatmind.mcp.registry.ExternalMcpDiscoveredResource;
import com.kama.jchatmind.mcp.registry.ExternalMcpDiscoveredTool;
import com.kama.jchatmind.mcp.registry.ExternalMcpPromptRegistration;
import com.kama.jchatmind.mcp.registry.ExternalMcpResourceRegistration;
import com.kama.jchatmind.mcp.registry.ExternalMcpServerRegistration;
import com.kama.jchatmind.mcp.registry.ExternalMcpServerRegistry;
import com.kama.jchatmind.mcp.registry.ExternalMcpToolRegistration;
import com.kama.jchatmind.mcp.registry.ExternalMcpToolRegistry;
import com.kama.jchatmind.mcp.safety.McpExternalToolPolicy;
import com.kama.jchatmind.mcp.safety.McpToolRiskLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(OutputCaptureExtension.class)
class McpLogContentSafetyTest {
    private static final String ARGUMENT_SENTINEL = "SECRET_TOKEN_SENTINEL_12345";
    private static final String RESULT_SENTINEL = "PRIVATE_MCP_RESULT_SENTINEL_23456";
    private static final String RESOURCE_SENTINEL = "PRIVATE_RESOURCE_SENTINEL_34567";
    private static final String PROMPT_SENTINEL = "PRIVATE_PROMPT_SENTINEL_45678";
    private static final String EXCEPTION_SENTINEL = "EXCEPTION_SECRET_SENTINEL_56789";

    @Test
    void mcpToolLogsMetadataWithoutArgumentsResultsOrExceptionDetails(CapturedOutput output) {
        McpClientProperties properties = properties();
        ExternalMcpToolRegistry registry = toolRegistry(properties, ignored -> List.of(discoveredTool()));
        Slf4jMcpToolAuditLogger auditLogger = new Slf4jMcpToolAuditLogger(properties);
        String canonical = RESULT_SENTINEL + "-canonical-tail";
        McpToolCallbackAdapter successAdapter = new McpToolCallbackAdapter(
                registry, (tool, argumentsJson) -> canonical, auditLogger);

        String result = successAdapter.toolCallbacks().get(0)
                .call("{\"token\":\"" + ARGUMENT_SENTINEL + "\"}");

        assertEquals(canonical, result);
        McpToolCallbackAdapter failureAdapter = new McpToolCallbackAdapter(
                registry,
                (tool, argumentsJson) -> {
                    throw new IllegalStateException(EXCEPTION_SENTINEL,
                            new IllegalArgumentException("cause-" + EXCEPTION_SENTINEL));
                },
                auditLogger);
        McpToolCallException failure = assertThrows(McpToolCallException.class,
                () -> failureAdapter.toolCallbacks().get(0)
                        .call("{\"token\":\"" + ARGUMENT_SENTINEL + "\"}"));

        assertEquals("MCP_TOOL_CALL_FAILED", failure.getErrorType());
        String logs = output.getAll();
        assertSensitiveContentAbsent(logs, ARGUMENT_SENTINEL, RESULT_SENTINEL, EXCEPTION_SENTINEL);
        assertTrue(logs.contains("serverName=docs-readonly"));
        assertTrue(logs.contains("toolName=search_docs"));
        assertTrue(logs.contains("status=SUCCESS"));
        assertTrue(logs.contains("status=FAILED"));
        assertTrue(logs.contains("argumentCharCount="));
        assertTrue(logs.contains("resultCharCount="));
        assertTrue(logs.contains("exceptionClass=java.lang.IllegalStateException"));
    }

    @Test
    void resourceAndPromptAuditLogsMetadataWithoutBodiesUrisOrFailureDetails(CapturedOutput output) {
        McpClientProperties properties = properties();
        Slf4jMcpResourceAuditLogger resourceLogger = new Slf4jMcpResourceAuditLogger(properties);
        Slf4jMcpPromptAuditLogger promptLogger = new Slf4jMcpPromptAuditLogger(properties);
        String sensitiveUri = "docs://safe?token=" + ARGUMENT_SENTINEL;
        ExternalMcpResourceRegistration resource = ExternalMcpResourceRegistration.builder()
                .serverName("docs-readonly").serverType(ExternalMcpServerType.DOCS)
                .uri(sensitiveUri).name("safe-doc").riskLevel(McpToolRiskLevel.READ_ONLY).build();
        ExternalMcpPromptRegistration prompt = ExternalMcpPromptRegistration.builder()
                .serverName("docs-readonly").serverType(ExternalMcpServerType.DOCS)
                .name("explain-api").riskLevel(McpToolRiskLevel.READ_ONLY).build();

        resourceLogger.success("resource-trace", resource, RESOURCE_SENTINEL, 12, false);
        resourceLogger.failure("resource-trace", resource, EXCEPTION_SENTINEL, 13, "MCP_RESOURCE_FAILED");
        resourceLogger.denied("resource-denied", "docs-readonly", sensitiveUri, "MCP_RESOURCE_DENIED");
        promptLogger.success("prompt-trace", prompt, Set.of("library"), PROMPT_SENTINEL, 14, false);
        promptLogger.failure("prompt-trace", prompt, Set.of("library"), EXCEPTION_SENTINEL,
                15, "MCP_PROMPT_FAILED");

        String logs = output.getAll();
        assertSensitiveContentAbsent(logs, RESOURCE_SENTINEL, PROMPT_SENTINEL, EXCEPTION_SENTINEL,
                ARGUMENT_SENTINEL, sensitiveUri);
        assertTrue(logs.contains("resourceName=safe-doc"));
        assertTrue(logs.contains("promptName=explain-api"));
        assertTrue(logs.contains("contentCharCount="));
        assertTrue(logs.contains("failureDetailCharCount="));
        assertTrue(logs.contains("status=DENIED"));
    }

    @Test
    void discoveryFailuresRemainIsolatedWithoutLoggingExceptionContent(CapturedOutput output) {
        McpClientProperties properties = properties();
        ExternalMcpToolRegistry toolRegistry = toolRegistry(properties,
                ignored -> { throw new IllegalStateException(EXCEPTION_SENTINEL); });
        ExternalMcpCapabilityDiscoveryClient capabilityDiscovery = new ExternalMcpCapabilityDiscoveryClient() {
            @Override
            public List<ExternalMcpDiscoveredTool> discoverTools(ExternalMcpServerRegistration server) {
                throw new IllegalStateException(EXCEPTION_SENTINEL);
            }

            @Override
            public List<ExternalMcpDiscoveredResource> discoverResources(ExternalMcpServerRegistration server) {
                return List.of();
            }

            @Override
            public List<ExternalMcpDiscoveredPrompt> discoverPrompts(ExternalMcpServerRegistration server) {
                return List.of();
            }
        };
        ExternalMcpCapabilityRegistry capabilityRegistry = new ExternalMcpCapabilityRegistry(
                new ExternalMcpServerRegistry(properties), capabilityDiscovery, new McpExternalToolPolicy());

        assertTrue(toolRegistry.exposedTools().isEmpty());
        assertEquals(1, capabilityRegistry.discoverCapabilities().size());
        assertTrue(capabilityRegistry.discoverCapabilities().get(0).getTools().isEmpty());

        String logs = output.getAll();
        assertSensitiveContentAbsent(logs, EXCEPTION_SENTINEL);
        assertTrue(logs.contains("failureType=MCP_DISCOVERY_FAILED"));
        assertTrue(logs.contains("exceptionClass=java.lang.IllegalStateException"));
        assertTrue(logs.contains("serverName=docs-readonly"));
    }

    private McpClientProperties properties() {
        McpClientProperties properties = new McpClientProperties();
        properties.setAuditEnabled(true);
        properties.setServers(List.of(server()));
        return properties;
    }

    private ExternalMcpToolRegistry toolRegistry(
            McpClientProperties properties,
            com.kama.jchatmind.mcp.registry.ExternalMcpToolDiscoveryClient discovery) {
        return new ExternalMcpToolRegistry(
                new ExternalMcpServerRegistry(properties), discovery, new McpExternalToolPolicy());
    }

    private ExternalMcpServerProperties server() {
        ExternalMcpToolProperties tool = new ExternalMcpToolProperties();
        tool.setName("search_docs");
        tool.setRiskLevel(McpToolRiskLevel.READ_ONLY);
        tool.setAutoInvokeAllowed(true);
        ExternalMcpServerProperties server = new ExternalMcpServerProperties();
        server.setName("docs-readonly");
        server.setType(ExternalMcpServerType.DOCS);
        server.setTransport("stdio");
        server.setCommand("mock");
        server.setEnabled(true);
        server.setAllowedTools(List.of(tool));
        return server;
    }

    private ExternalMcpDiscoveredTool discoveredTool() {
        return ExternalMcpDiscoveredTool.builder()
                .name("search_docs").description("Search docs")
                .inputSchema("{\"type\":\"object\"}").build();
    }

    private void assertSensitiveContentAbsent(String logs, String... sentinels) {
        for (String sentinel : sentinels) {
            assertFalse(logs.contains(sentinel), () -> "Sensitive log content found: " + sentinel);
        }
    }
}
