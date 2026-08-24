package com.kama.jchatmind.mcp;

import com.kama.jchatmind.mcp.McpToolCallException;
import com.kama.jchatmind.mcp.adapter.McpToolCallbackAdapter;
import com.kama.jchatmind.mcp.audit.McpToolAuditLogger;
import com.kama.jchatmind.mcp.config.ExternalMcpServerProperties;
import com.kama.jchatmind.mcp.config.ExternalMcpServerType;
import com.kama.jchatmind.mcp.config.ExternalMcpToolProperties;
import com.kama.jchatmind.mcp.config.McpClientProperties;
import com.kama.jchatmind.mcp.registry.ExternalMcpDiscoveredTool;
import com.kama.jchatmind.mcp.registry.ExternalMcpServerRegistry;
import com.kama.jchatmind.mcp.registry.ExternalMcpToolRegistration;
import com.kama.jchatmind.mcp.registry.ExternalMcpToolRegistry;
import com.kama.jchatmind.mcp.safety.McpExternalToolPolicy;
import com.kama.jchatmind.mcp.safety.McpToolRiskLevel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolCallbackAdapterTest {

    @Test
    void callbackTruncatesExternalMcpResultAndWritesAudit() {
        McpClientProperties properties = new McpClientProperties();
        properties.setMaxResultLength(40);
        properties.setAuditEnabled(true);
        ExternalMcpServerProperties server = server();
        properties.setServers(List.of(server));
        RecordingAuditLogger auditLogger = new RecordingAuditLogger();
        ExternalMcpToolRegistry registry = new ExternalMcpToolRegistry(
                new ExternalMcpServerRegistry(properties),
                ignored -> List.of(ExternalMcpDiscoveredTool.builder()
                        .name("search_docs")
                        .description("Search docs")
                        .inputSchema("{\"type\":\"object\"}")
                        .build()),
                new McpExternalToolPolicy());

        McpToolCallbackAdapter adapter = new McpToolCallbackAdapter(
                registry,
                (tool, argumentsJson) -> "x".repeat(100),
                auditLogger,
                properties);

        List<ToolCallback> callbacks = adapter.toolCallbacks();
        String result = callbacks.get(0).call("{\"query\":\"java\"}");

        assertEquals(1, callbacks.size());
        assertTrue(result.length() <= 40);
        assertTrue(result.endsWith("...[truncated]"));
        assertEquals(List.of("start", "success:true"), auditLogger.events);
    }

    @Test
    void callbackPropagatesTypedFailureAndKeepsAuditFailureSemantics() {
        McpClientProperties properties = new McpClientProperties();
        properties.setMaxResultLength(64);
        ExternalMcpServerProperties server = server();
        properties.setServers(List.of(server));
        RecordingAuditLogger auditLogger = new RecordingAuditLogger();
        ExternalMcpToolRegistry registry = new ExternalMcpToolRegistry(
                new ExternalMcpServerRegistry(properties),
                ignored -> List.of(ExternalMcpDiscoveredTool.builder()
                        .name("search_docs")
                        .description("Search docs")
                        .inputSchema("{\"type\":\"object\"}")
                        .build()),
                new McpExternalToolPolicy());

        McpToolCallbackAdapter adapter = new McpToolCallbackAdapter(
                registry,
                (tool, argumentsJson) -> {
                    throw new IllegalStateException("server unavailable");
                },
                auditLogger,
                properties);

        McpToolCallException failure = assertThrows(McpToolCallException.class,
                () -> adapter.toolCallbacks().get(0).call("{\"query\":\"java\"}"));

        assertEquals("MCP_TOOL_CALL_FAILED", failure.getErrorType());
        assertTrue(failure.getSafeMessage().contains("MCP_TOOL_CALL_FAILED"));
        assertTrue(!failure.getSafeMessage().contains("server unavailable"));
        assertEquals(List.of("start", "failure"), auditLogger.events);
    }

    private ExternalMcpServerProperties server() {
        ExternalMcpToolProperties tool = new ExternalMcpToolProperties();
        tool.setName("search_docs");
        tool.setRiskLevel(McpToolRiskLevel.READ_ONLY);
        tool.setAutoInvokeAllowed(true);

        ExternalMcpServerProperties server = new ExternalMcpServerProperties();
        server.setName("docs");
        server.setType(ExternalMcpServerType.DOCS);
        server.setTransport("stdio");
        server.setCommand("mock");
        server.setEnabled(true);
        server.setAllowedTools(List.of(tool));
        return server;
    }

    private static class RecordingAuditLogger implements McpToolAuditLogger {
        private final List<String> events = new ArrayList<>();

        @Override
        public void start(String traceId, ExternalMcpToolRegistration tool, String argumentsJson) {
            events.add("start");
        }

        @Override
        public void success(String traceId, ExternalMcpToolRegistration tool, String resultSummary,
                            long latencyMs, boolean truncated) {
            events.add("success:" + truncated);
        }

        @Override
        public void failure(String traceId, ExternalMcpToolRegistration tool, String errorMessage,
                            long latencyMs, String errorCode) {
            events.add("failure");
        }

        @Override
        public void denied(String traceId, ExternalMcpToolRegistration tool, String argumentsJson,
                           long latencyMs, String errorCode) {
            events.add("denied");
        }
    }
}
