package com.kama.jchatmind.mcp;

import com.kama.jchatmind.agent.AgentEventPublisher;
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
import com.kama.jchatmind.model.entity.ToolCallLog;
import com.kama.jchatmind.service.AgentTaskLogService;
import com.kama.jchatmind.service.impl.ToolExecutionServiceImpl;
import com.kama.jchatmind.tool.ToolDefinition;
import com.kama.jchatmind.tool.ToolExecutionContext;
import com.kama.jchatmind.tool.ToolExecutionRecord;
import com.kama.jchatmind.tool.ToolFailureClassifier;
import com.kama.jchatmind.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpFakeEndToEndIntegrationTest {

    @Test
    void fakeExternalMcpToolPassesDiscoveryPolicyCallbackPreflightInvokeTruncationAndAudit() {
        McpClientProperties properties = new McpClientProperties();
        properties.setMaxResultLength(48);
        properties.setAuditEnabled(true);
        properties.setServers(List.of(server()));
        ExternalMcpToolRegistry externalRegistry = new ExternalMcpToolRegistry(
                new ExternalMcpServerRegistry(properties),
                ignored -> List.of(
                        discoveredTool("search_docs"),
                        discoveredTool("write_docs")
                ),
                new McpExternalToolPolicy());
        RecordingAuditLogger auditLogger = new RecordingAuditLogger();
        McpToolCallbackAdapter adapter = new McpToolCallbackAdapter(
                externalRegistry,
                (tool, argumentsJson) -> "real-read-only-result: " + "x".repeat(100),
                auditLogger,
                properties);

        List<ToolCallback> callbacks = adapter.toolCallbacks();
        List<String> runtimeNames = adapter.exposedToolNames();

        assertEquals(1, callbacks.size());
        assertEquals(List.of("mcp_docs_readonly_search_docs"), runtimeNames);
        assertEquals("mcp_docs_readonly_search_docs", callbacks.get(0).getToolDefinition().name());

        AgentTaskLogService logService = mock(AgentTaskLogService.class);
        when(logService.startToolCall(
                eq("task-1"), eq("step-1"), eq("mcp_docs_readonly_search_docs"),
                eq("mcp_docs_readonly_search_docs"), eq("call-1"), eq("{\"query\":\"spring ai\"}"), eq(false)))
                .thenReturn(ToolCallLog.builder().id("log-1").build());
        ToolExecutionServiceImpl executionService = new ToolExecutionServiceImpl(
                new NoLocalToolRegistry(),
                logService,
                mock(AgentEventPublisher.class),
                new ToolFailureClassifier(),
                provider(externalRegistry),
                provider(auditLogger));
        ToolExecutionContext context = ToolExecutionContext.builder()
                .taskId("task-1")
                .stepId("step-1")
                .sessionId("session-1")
                .runtimeToolNames(runtimeNames)
                .build();
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "call-1", "function", "mcp_docs_readonly_search_docs", "{\"query\":\"spring ai\"}");

        ToolExecutionRecord record = executionService.beforeToolCall(context, toolCall);
        String result = callbacks.get(0).call(toolCall.arguments());
        executionService.afterToolSuccess(context, record, result);

        assertEquals("mcp_docs_readonly_search_docs", record.getCanonicalToolName());
        assertTrue(result.length() <= 48);
        assertTrue(result.endsWith("...[truncated]"));
        assertEquals(List.of("start:search_docs", "success:search_docs:true"), auditLogger.events);
        verify(logService).finishToolCall(eq("log-1"), eq(result), anyLong(), eq(false));
    }

    private ExternalMcpServerProperties server() {
        ExternalMcpServerProperties server = new ExternalMcpServerProperties();
        server.setName("docs-readonly");
        server.setType(ExternalMcpServerType.DOCS);
        server.setTransport("stdio");
        server.setCommand("mock-docs-server");
        server.setEnabled(true);
        server.setAllowedTools(List.of(
                tool("search_docs", McpToolRiskLevel.READ_ONLY, true),
                tool("write_docs", McpToolRiskLevel.WRITE_OPERATION, true)
        ));
        return server;
    }

    private ExternalMcpToolProperties tool(String name, McpToolRiskLevel riskLevel, boolean autoInvokeAllowed) {
        ExternalMcpToolProperties tool = new ExternalMcpToolProperties();
        tool.setName(name);
        tool.setRiskLevel(riskLevel);
        tool.setAutoInvokeAllowed(autoInvokeAllowed);
        return tool;
    }

    private ExternalMcpDiscoveredTool discoveredTool(String name) {
        return ExternalMcpDiscoveredTool.builder()
                .name(name)
                .description("Discovered schema only")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}")
                .build();
    }

    private <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }
        };
    }

    private static class NoLocalToolRegistry implements ToolRegistry {
        @Override
        public Optional<ToolDefinition> find(String toolNameOrAlias) {
            return Optional.empty();
        }

        @Override
        public String canonicalName(String toolNameOrAlias) {
            return toolNameOrAlias;
        }

        @Override
        public boolean canExposeToAgent(String toolNameOrAlias) {
            return false;
        }

        @Override
        public boolean isAllowedForRuntime(String toolNameOrAlias, Collection<String> runtimeToolNames) {
            return false;
        }

        @Override
        public int maxResultLength(String toolNameOrAlias) {
            return 6000;
        }

        @Override
        public String truncateResult(String toolNameOrAlias, String result) {
            return result;
        }
    }

    private static class RecordingAuditLogger implements McpToolAuditLogger {
        private final List<String> events = new ArrayList<>();

        @Override
        public void start(String traceId, ExternalMcpToolRegistration tool, String argumentsJson) {
            events.add("start:" + tool.getToolName());
        }

        @Override
        public void success(String traceId, ExternalMcpToolRegistration tool, String resultSummary,
                            long latencyMs, boolean truncated) {
            events.add("success:" + tool.getToolName() + ":" + truncated);
        }

        @Override
        public void failure(String traceId, ExternalMcpToolRegistration tool, String errorMessage,
                            long latencyMs, String errorCode) {
            events.add("failure:" + errorCode);
        }

        @Override
        public void denied(String traceId, ExternalMcpToolRegistration tool, String argumentsJson,
                           long latencyMs, String errorCode) {
            events.add("denied:" + errorCode);
        }
    }
}
