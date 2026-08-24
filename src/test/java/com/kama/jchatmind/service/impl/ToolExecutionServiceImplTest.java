package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.agent.AgentEventPublisher;
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
import com.kama.jchatmind.tool.ToolExecutionContext;
import com.kama.jchatmind.tool.ToolExecutionRecord;
import com.kama.jchatmind.tool.ToolDuplicateCallException;
import com.kama.jchatmind.tool.ToolFailureClassifier;
import com.kama.jchatmind.tool.ToolPolicyRejectedException;
import com.kama.jchatmind.tool.ToolRegistry;
import com.kama.jchatmind.tool.ToolTimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

@ExtendWith(MockitoExtension.class)
class ToolExecutionServiceImplTest {

    @Mock
    private ToolRegistry toolRegistry;

    @Mock
    private AgentTaskLogService agentTaskLogService;

    @Mock
    private AgentEventPublisher agentEventPublisher;

    @Test
    void policyRejectedDatabaseResultIsRecordedAsFailedToolCall() {
        ToolExecutionServiceImpl service = new ToolExecutionServiceImpl(
                toolRegistry,
                agentTaskLogService,
                agentEventPublisher,
                new ToolFailureClassifier(),
                emptyProvider(),
                emptyProvider()
        );
        ToolExecutionContext context = ToolExecutionContext.builder()
                .taskId("task-1")
                .stepId("step-1")
                .sessionId("session-1")
                .build();
        ToolExecutionRecord record = ToolExecutionRecord.builder()
                .toolCallId("call-1")
                .toolCallLogId("log-1")
                .canonicalToolName("databaseQuery")
                .actualToolName("databaseQuery")
                .startedAtMillis(System.currentTimeMillis())
                .build();
        String rejected = "[REJECTED_BY_POLICY] rejected=true reason=Only SELECT is allowed";
        when(toolRegistry.truncateResult("databaseQuery", rejected)).thenReturn(rejected);

        service.afterToolSuccess(context, record, rejected);

        verify(agentTaskLogService).failToolCall(
                eq("log-1"),
                eq(rejected),
                anyLong(),
                eq(AgentTaskLogService.ERROR_TYPE_POLICY_REJECTED),
                eq(true)
        );
        verify(agentTaskLogService, never()).finishToolCall(eq("log-1"), eq(rejected), anyLong(), anyBoolean());
    }

    @Test
    void runtimeTimeoutIsRecordedWithDedicatedErrorType() {
        ToolExecutionServiceImpl service = new ToolExecutionServiceImpl(
                toolRegistry,
                agentTaskLogService,
                agentEventPublisher,
                new ToolFailureClassifier(),
                emptyProvider(),
                emptyProvider()
        );
        ToolExecutionRecord record = ToolExecutionRecord.builder()
                .toolCallId("call-1")
                .toolCallLogId("log-timeout")
                .canonicalToolName("slowTool")
                .actualToolName("slowTool")
                .startedAtMillis(System.currentTimeMillis())
                .build();
        ToolTimeoutException timeout = new ToolTimeoutException(
                "Tool 'slowTool' exceeded runtime timeout of 50 ms; interrupt/cancel requested=true, Agent Task will stop",
                null);

        service.afterToolFailure(context(List.of("slowTool")), record, timeout, false);

        verify(agentTaskLogService).failToolCall(
                eq("log-timeout"),
                eq(timeout.getMessage()),
                anyLong(),
                eq(AgentTaskLogService.ERROR_TYPE_TOOL_TIMEOUT),
                eq(false)
        );
    }

    @Test
    void duplicateRejectionIsRecordedAsFailedWithoutClaimingCallbackFailure() {
        ToolExecutionServiceImpl service = new ToolExecutionServiceImpl(
                toolRegistry,
                agentTaskLogService,
                agentEventPublisher,
                new ToolFailureClassifier(),
                emptyProvider(),
                emptyProvider()
        );
        ToolExecutionRecord record = ToolExecutionRecord.builder()
                .toolCallId("call-3")
                .toolCallLogId("log-duplicate")
                .canonicalToolName("searchProjectCode")
                .actualToolName("searchProjectCode")
                .startedAtMillis(System.currentTimeMillis())
                .build();
        ToolDuplicateCallException duplicate = new ToolDuplicateCallException(
                "searchProjectCode", 3, 2, false);

        service.afterToolFailure(context(List.of("searchProjectCode")), record, duplicate, false);

        verify(agentTaskLogService).failToolCall(
                eq("log-duplicate"),
                org.mockito.ArgumentMatchers.contains("consecutiveCount=3"),
                anyLong(),
                eq(AgentTaskLogService.ERROR_TYPE_DUPLICATE_TOOL_CALL),
                eq(false)
        );
        ArgumentCaptor<java.util.Map<String, Object>> payload = ArgumentCaptor.forClass(java.util.Map.class);
        verify(agentEventPublisher).publish(eq("task-1"), eq("session-1"),
                eq(com.kama.jchatmind.message.AgentSseEvent.Type.TOOL_CALL_RESULT), payload.capture());
        assertEquals(AgentTaskLogService.STATUS_FAILED, payload.getValue().get("status"));
        assertEquals(AgentTaskLogService.ERROR_TYPE_DUPLICATE_TOOL_CALL,
                payload.getValue().get("errorType"));
        assertEquals(false, payload.getValue().get("correctionRequested"));
        assertTrue(String.valueOf(payload.getValue().get("errorMessage"))
                .contains("rejected before execution"));
    }

    @Test
    void runtimeTruncationIsPersistedUsingExistingTraceFlag() {
        ToolExecutionServiceImpl service = new ToolExecutionServiceImpl(
                toolRegistry,
                agentTaskLogService,
                agentEventPublisher,
                new ToolFailureClassifier(),
                emptyProvider(),
                emptyProvider()
        );
        ToolExecutionRecord record = ToolExecutionRecord.builder()
                .toolCallId("call-1")
                .toolCallLogId("log-guarded")
                .canonicalToolName("largeTool")
                .actualToolName("largeTool")
                .startedAtMillis(System.currentTimeMillis())
                .resultGuardApplied(true)
                .originalResultChars(20_000)
                .storedResultChars(8_000)
                .maxResultChars(8_000)
                .runtimeResultTruncated(true)
                .build();
        String guarded = "guarded-result";
        when(toolRegistry.truncateResult("largeTool", guarded)).thenReturn(guarded);

        service.afterToolSuccess(context(List.of("largeTool")), record, guarded);

        verify(agentTaskLogService).finishToolCall(eq("log-guarded"), eq(guarded),
                org.mockito.ArgumentMatchers.anyLong(), eq(true));
        ArgumentCaptor<java.util.Map<String, Object>> payload = ArgumentCaptor.forClass(java.util.Map.class);
        verify(agentEventPublisher).publish(eq("task-1"), eq("session-1"),
                eq(com.kama.jchatmind.message.AgentSseEvent.Type.TOOL_CALL_RESULT), payload.capture());
        assertEquals(20_000, payload.getValue().get("originalChars"));
        assertEquals(8_000, payload.getValue().get("storedChars"));
        assertEquals(true, payload.getValue().get("runtimeResultTruncated"));
    }

    @Test
    void fakeMcpPrefixedToolWithoutRegistryRegistrationIsRejectedAsUnknownTool() {
        ToolExecutionServiceImpl service = new ToolExecutionServiceImpl(
                toolRegistry,
                agentTaskLogService,
                agentEventPublisher,
                new ToolFailureClassifier(),
                provider(registry("real_tool", McpToolRiskLevel.READ_ONLY, true)),
                emptyProvider()
        );
        ToolExecutionContext context = context(List.of("mcp_docs_mcp_fake_tool"));
        ToolCallLog failedLog = ToolCallLog.builder().id("log-unknown").build();
        when(agentTaskLogService.startAndFailToolCall(
                eq("task-1"), eq("step-1"), eq("mcp_docs_mcp_fake_tool"), eq("mcp_docs_mcp_fake_tool"),
                eq("call-1"), eq("{}"), eq(false), eq("Unknown tool: mcp_docs_mcp_fake_tool"),
                eq(0L), eq(AgentTaskLogService.ERROR_TYPE_UNKNOWN_TOOL), eq(false)))
                .thenReturn(failedLog);

        assertThrows(com.kama.jchatmind.tool.ToolUnknownException.class,
                () -> service.beforeToolCall(context, toolCall("mcp_docs_mcp_fake_tool")));
    }

    @Test
    void registeredExternalMcpToolDeniedByPolicyWritesAuditAndIsRejected() {
        RecordingAuditLogger auditLogger = new RecordingAuditLogger();
        ToolExecutionServiceImpl service = new ToolExecutionServiceImpl(
                toolRegistry,
                agentTaskLogService,
                agentEventPublisher,
                new ToolFailureClassifier(),
                provider(registry("write_tool", McpToolRiskLevel.WRITE_OPERATION, true)),
                provider(auditLogger)
        );
        ToolExecutionContext context = context(List.of("mcp_docs_write_tool"));
        ToolCallLog failedLog = ToolCallLog.builder().id("log-denied").build();
        when(agentTaskLogService.startAndFailToolCall(
                eq("task-1"), eq("step-1"), eq("mcp_docs_write_tool"), eq("mcp_docs_write_tool"),
                eq("call-1"), eq("{}"), eq(false),
                eq("External MCP tool is not allowed in current agent runtime: mcp_docs_write_tool"),
                eq(0L), eq(AgentTaskLogService.ERROR_TYPE_POLICY_REJECTED), eq(true)))
                .thenReturn(failedLog);

        assertThrows(ToolPolicyRejectedException.class,
                () -> service.beforeToolCall(context, toolCall("mcp_docs_write_tool")));
        assertEquals(List.of("denied:MCP_TOOL_POLICY_REJECTED"), auditLogger.events);
    }

    @Test
    void registeredAllowedExternalMcpToolCanPassPreflightWithoutPrefixTrust() {
        ToolExecutionServiceImpl service = new ToolExecutionServiceImpl(
                toolRegistry,
                agentTaskLogService,
                agentEventPublisher,
                new ToolFailureClassifier(),
                provider(registry("safe_tool", McpToolRiskLevel.READ_ONLY, true)),
                emptyProvider()
        );
        ToolExecutionContext context = context(List.of("mcp_docs_safe_tool"));
        when(agentTaskLogService.startToolCall(
                eq("task-1"), eq("step-1"), eq("mcp_docs_safe_tool"), eq("mcp_docs_safe_tool"),
                eq("call-1"), eq("{}"), eq(false)))
                .thenReturn(ToolCallLog.builder().id("log-ok").build());

        ToolExecutionRecord record = service.beforeToolCall(context, toolCall("mcp_docs_safe_tool"));

        assertEquals("mcp_docs_safe_tool", record.getCanonicalToolName());
    }

    private ToolExecutionContext context(List<String> runtimeToolNames) {
        return ToolExecutionContext.builder()
                .taskId("task-1")
                .stepId("step-1")
                .sessionId("session-1")
                .runtimeToolNames(runtimeToolNames)
                .build();
    }

    private AssistantMessage.ToolCall toolCall(String name) {
        return new AssistantMessage.ToolCall("call-1", "function", name, "{}");
    }

    private ExternalMcpToolRegistry registry(String toolName, McpToolRiskLevel riskLevel, boolean autoInvokeAllowed) {
        McpClientProperties properties = new McpClientProperties();
        ExternalMcpToolProperties tool = new ExternalMcpToolProperties();
        tool.setName(toolName);
        tool.setRiskLevel(riskLevel);
        tool.setAutoInvokeAllowed(autoInvokeAllowed);
        ExternalMcpServerProperties server = new ExternalMcpServerProperties();
        server.setName("docs");
        server.setType(ExternalMcpServerType.DOCS);
        server.setEnabled(true);
        server.setAllowedTools(List.of(tool));
        properties.setServers(List.of(server));
        return new ExternalMcpToolRegistry(
                new ExternalMcpServerRegistry(properties),
                ignored -> List.of(ExternalMcpDiscoveredTool.builder()
                        .name(toolName)
                        .description("discovered")
                        .inputSchema("{\"type\":\"object\"}")
                        .build()),
                new McpExternalToolPolicy());
    }

    private <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private <T> ObjectProvider<T> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return null;
            }

            @Override
            public T getIfAvailable() {
                return null;
            }

            @Override
            public T getIfUnique() {
                return null;
            }

            @Override
            public T getObject() {
                return null;
            }
        };
    }

    private static class RecordingAuditLogger implements McpToolAuditLogger {
        private final List<String> events = new ArrayList<>();

        @Override
        public void start(String traceId, ExternalMcpToolRegistration tool, String argumentsJson) {
        }

        @Override
        public void success(String traceId, ExternalMcpToolRegistration tool, String resultSummary,
                            long latencyMs, boolean truncated) {
        }

        @Override
        public void failure(String traceId, ExternalMcpToolRegistration tool, String errorMessage,
                            long latencyMs, String errorCode) {
        }

        @Override
        public void denied(String traceId, ExternalMcpToolRegistration tool, String argumentsJson,
                           long latencyMs, String errorCode) {
            events.add("denied:" + errorCode);
        }
    }
}
