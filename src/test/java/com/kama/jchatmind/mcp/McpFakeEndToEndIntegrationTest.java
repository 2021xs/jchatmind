package com.kama.jchatmind.mcp;

import com.kama.jchatmind.agent.AgentEventPublisher;
import com.kama.jchatmind.agent.AgentRunFailureHandler;
import com.kama.jchatmind.agent.JChatMind;
import com.kama.jchatmind.agent.JChatMindSafeFinalTestSupport;
import com.kama.jchatmind.agent.ToolCallBatchExecutorFixture;
import com.kama.jchatmind.agent.ToolCallBatchExecutor;
import com.kama.jchatmind.config.ToolCorrectionProperties;
import com.kama.jchatmind.converter.ChatMessageConverter;
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
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.AgentStep;
import com.kama.jchatmind.model.entity.AgentTask;
import com.kama.jchatmind.model.entity.ToolCallLog;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.service.AgentTaskLogService;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.ConversationContextCompressor;
import com.kama.jchatmind.service.FinalCompletionService;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.impl.ToolExecutionServiceImpl;
import com.kama.jchatmind.tool.ToolDefinition;
import com.kama.jchatmind.tool.ToolExecutionContext;
import com.kama.jchatmind.tool.ToolExecutionRecord;
import com.kama.jchatmind.tool.ToolFailureClassifier;
import com.kama.jchatmind.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
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

    @Test
    void fakeExternalMcpInvocationFailureKeepsAuditAndUnifiedTraceFailed() {
        McpClientProperties properties = new McpClientProperties();
        properties.setMaxResultLength(80);
        properties.setAuditEnabled(true);
        properties.setServers(List.of(server()));
        ExternalMcpToolRegistry externalRegistry = new ExternalMcpToolRegistry(
                new ExternalMcpServerRegistry(properties),
                ignored -> List.of(discoveredTool("search_docs")),
                new McpExternalToolPolicy());
        RecordingAuditLogger auditLogger = new RecordingAuditLogger();
        McpToolCallbackAdapter adapter = new McpToolCallbackAdapter(
                externalRegistry,
                (tool, argumentsJson) -> {
                    throw new IllegalStateException("credential=secret-token command=/private/path");
                },
                auditLogger,
                properties);
        List<ToolCallback> callbacks = adapter.toolCallbacks();
        List<String> runtimeNames = adapter.exposedToolNames();

        AgentTaskLogService logService = mock(AgentTaskLogService.class);
        when(logService.startToolCall(
                eq("task-1"), eq("step-1"), eq("mcp_docs_readonly_search_docs"),
                eq("mcp_docs_readonly_search_docs"), eq("call-1"), eq("{\"query\":\"spring ai\"}"), eq(false)))
                .thenReturn(ToolCallLog.builder().id("log-failure-1").build());
        ToolExecutionServiceImpl executionService = new ToolExecutionServiceImpl(
                new NoLocalToolRegistry(), logService, mock(AgentEventPublisher.class),
                new ToolFailureClassifier(), provider(externalRegistry), provider(auditLogger));
        ToolExecutionContext context = ToolExecutionContext.builder()
                .taskId("task-1").stepId("step-1").sessionId("session-1")
                .runtimeToolNames(runtimeNames).build();
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "call-1", "function", "mcp_docs_readonly_search_docs", "{\"query\":\"spring ai\"}");

        ToolExecutionRecord record = executionService.beforeToolCall(context, toolCall);
        McpToolCallException failure = assertThrows(McpToolCallException.class,
                () -> callbacks.get(0).call(toolCall.arguments()));
        executionService.afterToolFailure(context, record, failure, false);

        assertEquals("MCP_TOOL_CALL_FAILED", failure.getErrorType());
        assertEquals(List.of("start:search_docs", "failure:MCP_TOOL_CALL_FAILED"), auditLogger.events);
        verify(logService).failToolCall(eq("log-failure-1"),
                org.mockito.ArgumentMatchers.contains("MCP_TOOL_CALL_FAILED"), anyLong(),
                eq("MCP_TOOL_CALL_FAILED"), eq(false));
        verify(logService, never()).finishToolCall(anyString(), anyString(), anyLong(), anyBoolean());
    }

    @Test
    void fakeAgentConversationRunsExternalMcpToolThroughJChatMindRuntime() {
        McpClientProperties properties = new McpClientProperties();
        properties.setMaxResultLength(80);
        properties.setAuditEnabled(true);
        properties.setServers(List.of(server()));
        ExternalMcpToolRegistry externalRegistry = new ExternalMcpToolRegistry(
                new ExternalMcpServerRegistry(properties),
                ignored -> List.of(discoveredTool("search_docs")),
                new McpExternalToolPolicy());
        RecordingAuditLogger auditLogger = new RecordingAuditLogger();
        McpToolCallbackAdapter adapter = new McpToolCallbackAdapter(
                externalRegistry,
                (tool, argumentsJson) -> "external docs answer",
                auditLogger,
                properties);
        List<ToolCallback> callbacks = adapter.toolCallbacks();
        List<String> runtimeNames = adapter.exposedToolNames();

        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatResponse toolCallResponse = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-1",
                                "function",
                                runtimeNames.get(0),
                                "{\"query\":\"spring ai\"}"
                        )))
                        .build()
        )));
        ChatResponse finalResponse = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder()
                        .content("Final answer includes external docs answer")
                        .toolCalls(List.of())
                        .build()
        )));
        when(chatClient.prompt(any(Prompt.class))
                .system(anyString())
                .toolCallbacks(any(ToolCallback[].class))
                .call()
                .chatClientResponse())
                .thenReturn(new ChatClientResponse(toolCallResponse, Map.of()))
                .thenReturn(new ChatClientResponse(finalResponse, Map.of()));
        ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt(
                Prompt.builder().messages(List.of(new UserMessage("fixture"))).build());

        AgentTaskLogService logService = mockAgentTaskLogService();
        ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        when(chatMessageFacadeService.createChatMessage(org.mockito.ArgumentMatchers.any(ChatMessageDTO.class)))
                .thenReturn(CreateChatMessageResponse.builder().chatMessageId("message-1").build());
        when(chatMessageFacadeService.getChatMessageDTOsBySessionId(anyString())).thenReturn(List.of());
        ConversationContextCompressor compressor = mock(ConversationContextCompressor.class);
        when(compressor.check(anyString(), anyString(), any()))
                .thenReturn(new ConversationContextCompressor.CompressionCheck(false, "not_needed", 0, 0, 0, 0, "TEST", 0, 0));

        ToolExecutionServiceImpl executionService = new ToolExecutionServiceImpl(
                new NoLocalToolRegistry(),
                logService,
                mock(AgentEventPublisher.class),
                new ToolFailureClassifier(),
                provider(externalRegistry),
                provider(auditLogger));
        try (ToolCallBatchExecutorFixture toolRuntime =
                     new ToolCallBatchExecutorFixture(executionService, new NoLocalToolRegistry())) {
            JChatMind agent = new JChatMind(
                "agent-1",
                "test-model",
                "test-agent",
                "test",
                "system",
                chatClient,
                20,
                List.of(new UserMessage("use external docs")),
                callbacks,
                List.of(),
                "session-1",
                mock(SseService.class),
                executionService,
                chatMessageFacadeService,
                mock(ChatMessageConverter.class),
                logService,
                compressor,
                "user-message-1",
                runtimeNames,
                new ToolCorrectionProperties(),
                new ToolFailureClassifier(),
                toolRuntime.batchExecutor()
            );
            ReflectionTestUtils.setField(agent, "toolCallingManager", new FakeToolCallingManager(callbacks));
            FinalCompletionService finalCompletionService = JChatMindSafeFinalTestSupport.configure(
                    agent, requestSpec, "validated final answer");

            agent.run();

        verify(logService, atLeastOnce()).finishToolCall(eq("tool-log-1"), eq("external docs answer"),
                anyLong(), eq(false));
        verify(finalCompletionService).complete(any());
        verify(logService, never()).finishTask(anyString(), anyString(), anyInt(), anyInt());
        verify(logService, never()).failTask(anyString(), anyString(), anyInt(), anyInt());
        ArgumentCaptor<AssistantMessage> assistantCaptor = ArgumentCaptor.forClass(AssistantMessage.class);
        ArgumentCaptor<ToolResponseMessage> responseCaptor = ArgumentCaptor.forClass(ToolResponseMessage.class);
        verify(chatMessageFacadeService).createToolProtocolBatch(
                eq("session-1"), eq("task-1"), assistantCaptor.capture(), responseCaptor.capture());
        assertEquals(1, assistantCaptor.getValue().getToolCalls().size());
        assertEquals(1, responseCaptor.getValue().getResponses().size());
        AssistantMessage.ToolCall requestedCall = assistantCaptor.getValue().getToolCalls().get(0);
        ToolResponseMessage.ToolResponse terminalResponse = responseCaptor.getValue().getResponses().get(0);
        assertEquals(requestedCall.id(), terminalResponse.id());
        assertEquals(runtimeNames.get(0), requestedCall.name());
        assertEquals(requestedCall.name(), terminalResponse.name());
        assertEquals("external docs answer", terminalResponse.responseData());
            assertEquals(List.of("start:search_docs", "success:search_docs:false"), auditLogger.events);
        }
    }

    @Test
    void fakeAgentConversationMcpFailureReachesUnifiedFailureTrace() {
        McpClientProperties properties = new McpClientProperties();
        properties.setMaxResultLength(80);
        properties.setAuditEnabled(true);
        properties.setServers(List.of(server()));
        ExternalMcpToolRegistry externalRegistry = new ExternalMcpToolRegistry(
                new ExternalMcpServerRegistry(properties),
                ignored -> List.of(discoveredTool("search_docs")),
                new McpExternalToolPolicy());
        RecordingAuditLogger auditLogger = new RecordingAuditLogger();
        McpToolCallbackAdapter adapter = new McpToolCallbackAdapter(
                externalRegistry,
                (tool, argumentsJson) -> {
                    throw new IllegalStateException("credential=secret-token command=/private/path");
                },
                auditLogger,
                properties);
        List<ToolCallback> callbacks = adapter.toolCallbacks();
        List<String> runtimeNames = adapter.exposedToolNames();

        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatResponse toolCallResponse = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", runtimeNames.get(0), "{\"query\":\"spring ai\"}"))).build())));
        when(chatClient.prompt(any(Prompt.class)).system(anyString())
                .toolCallbacks(any(ToolCallback[].class)).call().chatClientResponse())
                .thenReturn(new ChatClientResponse(toolCallResponse, Map.of()));

        AgentTaskLogService logService = mockAgentTaskLogService();
        ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        when(chatMessageFacadeService.createChatMessage(org.mockito.ArgumentMatchers.any(ChatMessageDTO.class)))
                .thenReturn(CreateChatMessageResponse.builder().chatMessageId("message-failure-1").build());
        when(chatMessageFacadeService.getChatMessageDTOsBySessionId(anyString())).thenReturn(List.of());
        ConversationContextCompressor compressor = mock(ConversationContextCompressor.class);
        when(compressor.check(anyString(), anyString(), any()))
                .thenReturn(new ConversationContextCompressor.CompressionCheck(false, "not_needed", 0, 0, 0, 0, "TEST", 0, 0));
        ToolExecutionServiceImpl executionService = new ToolExecutionServiceImpl(
                new NoLocalToolRegistry(), logService, mock(AgentEventPublisher.class),
                new ToolFailureClassifier(), provider(externalRegistry), provider(auditLogger));

        try (ToolCallBatchExecutorFixture toolRuntime =
                     new ToolCallBatchExecutorFixture(executionService, new NoLocalToolRegistry())) {
            JChatMind agent = new JChatMind(
                    "agent-1", "test-model", "test-agent", "test", "system", chatClient, 20,
                    List.of(new UserMessage("use external docs")), callbacks, List.of(), "session-1",
                    mock(SseService.class), mock(AgentEventPublisher.class), executionService, chatMessageFacadeService,
                    mock(ChatMessageConverter.class), logService, compressor, "user-message-1", runtimeNames,
                    new ToolCorrectionProperties(), new ToolFailureClassifier(),
                    mock(AgentRunFailureHandler.class), toolRuntime.batchExecutor());
            ReflectionTestUtils.setField(agent, "toolCallingManager", new FakeToolCallingManager(callbacks));

            assertThrows(RuntimeException.class, agent::run);
        }

        verify(logService, atLeastOnce()).failToolCall(anyString(),
                org.mockito.ArgumentMatchers.contains("MCP_TOOL_CALL_FAILED"), anyLong(),
                eq("MCP_TOOL_CALL_FAILED"), eq(false));
        verify(logService, never()).finishToolCall(anyString(), anyString(), anyLong(), anyBoolean());
        assertEquals(List.of("start:search_docs", "failure:MCP_TOOL_CALL_FAILED"), auditLogger.events);
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

    private AgentTaskLogService mockAgentTaskLogService() {
        AgentTaskLogService logService = mock(AgentTaskLogService.class);
        when(logService.startTask(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(AgentTask.builder().id("task-1").build());
        AtomicInteger stepNo = new AtomicInteger(1);
        when(logService.startStep(anyString(), anyInt(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> AgentStep.builder()
                        .id("step-" + stepNo.getAndIncrement())
                        .stepNo(invocation.getArgument(1))
                        .stepType(invocation.getArgument(2))
                        .build());
        when(logService.startToolCall(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(ToolCallLog.builder().id("tool-log-1").build());
        return logService;
    }

    private static class FakeToolCallingManager implements ToolCallingManager {
        private final List<ToolCallback> callbacks;

        private FakeToolCallingManager(List<ToolCallback> callbacks) {
            this.callbacks = callbacks;
        }

        @Override
        public List<org.springframework.ai.tool.definition.ToolDefinition> resolveToolDefinitions(
                org.springframework.ai.model.tool.ToolCallingChatOptions chatOptions) {
            return callbacks.stream()
                    .map(ToolCallback::getToolDefinition)
                    .toList();
        }

        @Override
        public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
            List<ToolCallback> executionCallbacks =
                    ((org.springframework.ai.model.tool.ToolCallingChatOptions) prompt.getOptions())
                            .getToolCallbacks();
            List<AssistantMessage.ToolCall> toolCalls = chatResponse.getResult().getOutput().getToolCalls();
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            List<Message> history = new ArrayList<>(prompt.getInstructions());
            history.add(chatResponse.getResult().getOutput());
            for (AssistantMessage.ToolCall toolCall : toolCalls) {
                ToolCallback callback = executionCallbacks.stream()
                        .filter(candidate -> candidate.getToolDefinition().name().equals(toolCall.name()))
                        .findFirst()
                        .orElseThrow();
                responses.add(new ToolResponseMessage.ToolResponse(
                        toolCall.id(),
                        toolCall.name(),
                        callback.call(toolCall.arguments())
                ));
            }
            ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder()
                    .responses(responses)
                    .build();
            history.add(toolResponseMessage);
            return ToolExecutionResult.builder()
                    .conversationHistory(history)
                    .returnDirect(false)
                    .build();
        }
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
