package com.kama.jchatmind.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.AgentEventPublisher;
import com.kama.jchatmind.agent.JChatMind;
import com.kama.jchatmind.agent.ToolCallBatchExecutorFixture;
import com.kama.jchatmind.config.ToolCorrectionProperties;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.message.AgentSseEvent;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.mcp.adapter.McpToolCallbackAdapter;
import com.kama.jchatmind.mcp.adapter.SpringAiExternalMcpClientAdapter;
import com.kama.jchatmind.mcp.audit.McpToolAuditLogger;
import com.kama.jchatmind.mcp.config.ExternalMcpServerProperties;
import com.kama.jchatmind.mcp.config.ExternalMcpServerType;
import com.kama.jchatmind.mcp.config.ExternalMcpToolProperties;
import com.kama.jchatmind.mcp.config.McpClientProperties;
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
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.impl.ToolExecutionServiceImpl;
import com.kama.jchatmind.tool.ToolDefinition;
import com.kama.jchatmind.tool.ToolFailureClassifier;
import com.kama.jchatmind.tool.ToolRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JChatMindExternalMcpMultiToolManualIntegrationTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String CONTEXT7_SERVER_NAME = "Context7";
    private static final String PLAYWRIGHT_SERVER_NAME = "Playwright";
    private static final String RESOLVE_LIBRARY_TOOL = "resolve-library-id";
    private static final String QUERY_DOCS_TOOL = "query-docs";
    private static final String BROWSER_NAVIGATE_TOOL = "browser_navigate";

    @Test
    @EnabledIfEnvironmentVariable(named = "JCHATMIND_MCP_MULTI_EXTERNAL_REAL_AGENT_ENABLED", matches = "true")
    void realAgentConversationUsesMultipleExternalMcpTools() {
        requireEnv("DEEPSEEK_OFFICIAL_API_KEY");

        List<McpServerSpec> specs = List.of(context7Docs(), playwrightBrowser());
        List<McpSyncClient> clients = new ArrayList<>();
        try {
            for (McpServerSpec spec : specs) {
                clients.add(newMcpClient(spec));
            }

            SpringAiExternalMcpClientAdapter springAiAdapter =
                    new SpringAiExternalMcpClientAdapter(clients, OBJECT_MAPPER);
            McpClientProperties properties = mcpProperties(specs);
            ExternalMcpToolRegistry registry = new ExternalMcpToolRegistry(
                    new ExternalMcpServerRegistry(properties),
                    springAiAdapter,
                    new McpExternalToolPolicy());
            RecordingAuditLogger auditLogger = new RecordingAuditLogger();
            McpToolCallbackAdapter mcpAdapter = new McpToolCallbackAdapter(
                    registry, springAiAdapter, auditLogger, properties);
            List<ToolCallback> toolCallbacks = mcpAdapter.toolCallbacks();
            List<String> runtimeToolNames = mcpAdapter.exposedToolNames();

            String resolveTool = exposedName(CONTEXT7_SERVER_NAME, RESOLVE_LIBRARY_TOOL);
            String queryDocsTool = exposedName(CONTEXT7_SERVER_NAME, QUERY_DOCS_TOOL);
            String browserNavigateTool = exposedName(PLAYWRIGHT_SERVER_NAME, BROWSER_NAVIGATE_TOOL);

            assertTrue(runtimeToolNames.contains(resolveTool), "Context7 resolve tool was not exposed");
            assertTrue(runtimeToolNames.contains(queryDocsTool), "Context7 docs query tool was not exposed");
            assertTrue(runtimeToolNames.contains(browserNavigateTool), "Playwright navigate tool was not exposed");
            assertFalse(runtimeToolNames.contains("searchProjectCode"), "local code tool must not be exposed");
            assertFalse(runtimeToolNames.contains("databaseQuery"), "local database tool must not be exposed");
            assertFalse(runtimeToolNames.contains("knowledgeQuery"), "local knowledge tool must not be exposed");

            RecordingAgentTaskLogService logService = new RecordingAgentTaskLogService();
            RecordingSseService sseService = new RecordingSseService();
            AgentEventPublisher eventPublisher = new AgentEventPublisher(sseService);
            ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
            when(chatMessageFacadeService.createChatMessage(any(ChatMessageDTO.class)))
                    .thenAnswer(invocation -> CreateChatMessageResponse.builder()
                            .chatMessageId("message-external-" + logService.nextMessageId())
                            .build());
            when(chatMessageFacadeService.getChatMessageDTOsBySessionId(anyString())).thenReturn(List.of());
            ConversationContextCompressor compressor = mock(ConversationContextCompressor.class);
            when(compressor.check(anyString(), anyString(), any()))
                    .thenReturn(new ConversationContextCompressor.CompressionCheck(
                            false, "not_needed", 0, 0, 0, 0, "TEST", 0, 0));

            ToolExecutionServiceImpl executionService = new ToolExecutionServiceImpl(
                    new NoLocalToolRegistry(),
                    logService,
                    eventPublisher,
                    new ToolFailureClassifier(),
                    provider(registry),
                    provider(auditLogger));

            try (ToolCallBatchExecutorFixture toolRuntime =
                         new ToolCallBatchExecutorFixture(executionService, new NoLocalToolRegistry())) {
                JChatMind agent = new JChatMind(
                    "multi-external-mcp-agent",
                    model(),
                    "multi-external-mcp-agent",
                    "manual real external MCP multi-tool conversation",
                    systemPrompt(resolveTool, queryDocsTool, browserNavigateTool),
                    realChatClient(),
                    30,
                    List.of(new UserMessage(userQuestion(resolveTool, queryDocsTool, browserNavigateTool))),
                    toolCallbacks,
                    List.of(),
                    "multi-external-mcp-session",
                    sseService,
                    eventPublisher,
                    executionService,
                    chatMessageFacadeService,
                    mock(ChatMessageConverter.class),
                    logService,
                    compressor,
                    "multi-external-mcp-user-message",
                    runtimeToolNames,
                    new ToolCorrectionProperties(),
                    new ToolFailureClassifier(),
                    null,
                    toolRuntime.batchExecutor()
                );
                agent.setMaxLoopSteps(8);

            agent.run();

            ArgumentCaptor<ChatMessageDTO> messages = ArgumentCaptor.forClass(ChatMessageDTO.class);
            verify(chatMessageFacadeService, atLeastOnce()).createChatMessage(messages.capture());
            List<ChatMessageDTO> capturedMessages = messages.getAllValues();
            int lastToolMessageIndex = lastMessageIndex(capturedMessages, ChatMessageDTO.RoleType.TOOL);
            int finalAssistantIndex = lastMessageIndex(capturedMessages, ChatMessageDTO.RoleType.ASSISTANT);

            assertTrue(lastToolMessageIndex >= 0, "Agent did not persist any external MCP tool result");
            assertTrue(finalAssistantIndex > lastToolMessageIndex,
                    "Agent did not produce a final assistant answer after external MCP tool results; messages="
                            + messageSummary(capturedMessages));
            assertToolSucceeded(logService, auditLogger, sseService, resolveTool, RESOLVE_LIBRARY_TOOL);
            assertToolSucceeded(logService, auditLogger, sseService, queryDocsTool, QUERY_DOCS_TOOL);
            assertToolSucceeded(logService, auditLogger, sseService, browserNavigateTool, BROWSER_NAVIGATE_TOOL);

                System.out.println("real external MCP multi-tool agent conversation ok: model=" + model()
                    + ", runtimeTools=" + runtimeToolNames
                    + ", startedTools=" + logService.startedToolNames()
                    + ", successfulTools=" + logService.successfulToolNames()
                    + ", auditEvents=" + auditLogger.events()
                    + ", sseEvents=" + sseService.eventSummary()
                        + ", finalAnswer=" + sanitize(capturedMessages.get(finalAssistantIndex).getContent()));
            }
        } finally {
            closeClients(clients);
        }
    }

    private static void assertToolSucceeded(RecordingAgentTaskLogService logService,
                                            RecordingAuditLogger auditLogger,
                                            RecordingSseService sseService,
                                            String exposedToolName,
                                            String rawToolName) {
        assertTrue(logService.startedToolNames().contains(exposedToolName),
                "ToolExecutionServiceImpl preflight did not start tool: " + exposedToolName);
        assertTrue(logService.successfulToolNames().contains(exposedToolName),
                "Agent runtime did not record successful tool result: " + exposedToolName);
        assertTrue(auditLogger.events().stream().anyMatch(event -> event.equals("start:" + rawToolName)),
                "MCP audit did not record tool start: " + rawToolName);
        assertTrue(auditLogger.events().stream().anyMatch(event -> event.startsWith("success:" + rawToolName)),
                "MCP audit did not record successful tool call: " + rawToolName);
        assertTrue(sseService.hasToolStart(exposedToolName),
                "SSE did not publish tool_call_start for: " + exposedToolName);
        assertTrue(sseService.hasToolSuccess(exposedToolName),
                "SSE did not publish successful tool_call_result for: " + exposedToolName);
    }

    private McpSyncClient newMcpClient(McpServerSpec spec) {
        ServerParameters parameters = ServerParameters.builder(spec.command())
                .args(spec.args())
                .env(spec.childEnv())
                .build();
        McpSyncClient client = McpClient.sync(new StdioClientTransport(
                        parameters, new JacksonMcpJsonMapper(OBJECT_MAPPER)))
                .clientInfo(new McpSchema.Implementation("jchatmind-real-agent-multi-external-mcp-test", "1.0.0"))
                .requestTimeout(Duration.ofSeconds(60))
                .initializationTimeout(Duration.ofSeconds(60))
                .build();
        client.initialize();
        return client;
    }

    private ChatClient realChatClient() {
        DeepSeekApi deepSeekApi = DeepSeekApi.builder()
                .apiKey(requireEnv("DEEPSEEK_OFFICIAL_API_KEY"))
                .baseUrl(env("DEEPSEEK_OFFICIAL_BASE_URL", "https://api.deepseek.com"))
                .build();
        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .model(model())
                .build();
        DeepSeekChatModel chatModel = DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .defaultOptions(options)
                .retryTemplate(RetryUtils.DEFAULT_RETRY_TEMPLATE)
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
        return ChatClient.create(chatModel);
    }

    private McpClientProperties mcpProperties(List<McpServerSpec> specs) {
        McpClientProperties properties = new McpClientProperties();
        properties.setEnabled(true);
        properties.setAuditEnabled(true);
        properties.setMaxResultLength(7000);
        properties.setServers(specs.stream().map(this::serverProperties).toList());
        return properties;
    }

    private ExternalMcpServerProperties serverProperties(McpServerSpec spec) {
        ExternalMcpServerProperties server = new ExternalMcpServerProperties();
        server.setName(spec.serverName());
        server.setType(spec.serverType());
        server.setTransport("stdio");
        server.setCommand(spec.command());
        server.setEnabled(true);
        server.setAllowedTools(spec.allowedTools().stream()
                .map(tool -> toolProperties(tool.name(), tool.riskLevel()))
                .toList());
        return server;
    }

    private ExternalMcpToolProperties toolProperties(String name, McpToolRiskLevel riskLevel) {
        ExternalMcpToolProperties tool = new ExternalMcpToolProperties();
        tool.setName(name);
        tool.setRiskLevel(riskLevel);
        tool.setAutoInvokeAllowed(true);
        return tool;
    }

    private McpServerSpec context7Docs() {
        return new McpServerSpec(
                CONTEXT7_SERVER_NAME,
                ExternalMcpServerType.DOCS,
                env("JCHATMIND_MCP_MULTI_CONTEXT7_COMMAND", "cmd"),
                List.of("/c", "npx", "-y", "@upstash/context7-mcp@3.2.1"),
                optionalChildEnv("CONTEXT7_API_KEY"),
                List.of(
                        new McpToolSpec(RESOLVE_LIBRARY_TOOL, McpToolRiskLevel.NETWORK_READ),
                        new McpToolSpec(QUERY_DOCS_TOOL, McpToolRiskLevel.NETWORK_READ)
                ));
    }

    private McpServerSpec playwrightBrowser() {
        return new McpServerSpec(
                PLAYWRIGHT_SERVER_NAME,
                ExternalMcpServerType.BROWSER,
                env("JCHATMIND_MCP_MULTI_PLAYWRIGHT_COMMAND", "cmd"),
                List.of("/c", "npx", "-y", "@playwright/mcp@0.0.76", "--headless", "--isolated",
                        "--browser", "chromium", "--output-dir",
                        System.getProperty("java.io.tmpdir") + "/jchatmind-playwright-mcp-multi-external"),
                Map.of(),
                List.of(new McpToolSpec(BROWSER_NAVIGATE_TOOL, McpToolRiskLevel.NETWORK_READ)));
    }

    private String systemPrompt(String resolveTool, String queryDocsTool, String browserNavigateTool) {
        return """
                You are a strict real-run verification agent.
                This run is external-MCP-only. No local code search, database, knowledge base, filesystem,
                shell, or write-capable tool is available or allowed.

                Before producing the final answer, you must call these external MCP tools:
                1. %s with libraryName "Spring AI" and a query about Spring AI MCP client tool callbacks.
                2. %s using the Context7 libraryId returned by the first tool, querying Spring AI MCP client tools.
                3. %s with url "https://example.com".

                Do not answer from memory only. Do not click, submit forms, download files, upload files,
                run browser code, access local files, or perform any write operation.
                After all three tools return, answer in Chinese and list the external tools that were used.
                """.formatted(resolveTool, queryDocsTool, browserNavigateTool);
    }

    private String userQuestion(String resolveTool, String queryDocsTool, String browserNavigateTool) {
        return """
                Run one real external MCP comprehensive Q&A flow.
                Use %s, then %s, then %s before answering.
                The final answer should briefly state:
                - what Context7 says is relevant about Spring AI MCP/tool-callback integration
                - what was observed after opening https://example.com
                - which external MCP tools were actually used
                """.formatted(resolveTool, queryDocsTool, browserNavigateTool);
    }

    private static int lastMessageIndex(List<ChatMessageDTO> messages, ChatMessageDTO.RoleType role) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessageDTO message = messages.get(i);
            if (message.getRole() == role && StringUtils.hasText(message.getContent())) {
                return i;
            }
        }
        return -1;
    }

    private static String messageSummary(List<ChatMessageDTO> messages) {
        List<String> summary = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            ChatMessageDTO message = messages.get(i);
            String content = message.getContent() == null ? "" : message.getContent();
            summary.add(i + ":" + message.getRole() + ":" + sanitize(content).replace('\n', ' '));
        }
        return summary.toString();
    }

    private static void closeClients(List<McpSyncClient> clients) {
        for (int i = clients.size() - 1; i >= 0; i--) {
            try {
                clients.get(i).close();
            } catch (Exception ignored) {
            }
        }
    }

    private static Map<String, String> optionalChildEnv(String key) {
        String value = System.getenv(key);
        return StringUtils.hasText(value) ? Map.of(key, value) : Map.of();
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (!StringUtils.hasText(value) || "your-api-key".equals(value)) {
            throw new IllegalStateException("Required environment variable is not configured: " + name);
        }
        return value;
    }

    private static String model() {
        return env("DEEPSEEK_OFFICIAL_MODEL", "gpt-5.5");
    }

    private static String exposedName(String serverName, String toolName) {
        return "mcp_" + sanitizeName(serverName) + "_" + sanitizeName(toolName);
    }

    private static String sanitizeName(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sanitized = new StringBuilder();
        for (char ch : value.trim().toLowerCase(Locale.ROOT).toCharArray()) {
            sanitized.append((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_' ? ch : '_');
        }
        return sanitized.toString();
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 1200 ? value : value.substring(0, 1168) + "\n...[truncated]";
    }

    private static <T> ObjectProvider<T> provider(T value) {
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

    private record McpServerSpec(String serverName,
                                 ExternalMcpServerType serverType,
                                 String command,
                                 List<String> args,
                                 Map<String, String> childEnv,
                                 List<McpToolSpec> allowedTools) {
    }

    private record McpToolSpec(String name, McpToolRiskLevel riskLevel) {
    }

    private static class RecordingSseService implements SseService {
        private final List<AgentSseEvent> events = new ArrayList<>();
        private final List<SseMessage> messages = new ArrayList<>();

        @Override
        public SseEmitter connect(String chatSessionId) {
            return new SseEmitter();
        }

        @Override
        public void send(String chatSessionId, SseMessage message) {
            messages.add(message);
        }

        @Override
        public void sendEvent(String chatSessionId, AgentSseEvent event) {
            events.add(event);
        }

        @Override
        public void complete(String chatSessionId) {
        }

        @Override
        public void completeWithError(String chatSessionId, Throwable error) {
        }

        private boolean hasToolStart(String toolName) {
            return events.stream().anyMatch(event -> event.getType() == AgentSseEvent.Type.TOOL_CALL_START
                    && toolName.equals(payloadValue(event, "toolName")));
        }

        private boolean hasToolSuccess(String toolName) {
            return events.stream().anyMatch(event -> event.getType() == AgentSseEvent.Type.TOOL_CALL_RESULT
                    && toolName.equals(payloadValue(event, "toolName"))
                    && AgentTaskLogService.STATUS_SUCCESS.equals(payloadValue(event, "status")));
        }

        private String eventSummary() {
            return events.stream()
                    .map(event -> event.getType() + ":" + payloadValue(event, "toolName")
                            + ":" + payloadValue(event, "status"))
                    .toList()
                    .toString();
        }

        private Object payloadValue(AgentSseEvent event, String key) {
            return event.getPayload() == null ? null : event.getPayload().get(key);
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
            events.add("failure:" + tool.getToolName() + ":" + errorCode);
        }

        @Override
        public void denied(String traceId, ExternalMcpToolRegistration tool, String argumentsJson,
                           long latencyMs, String errorCode) {
            events.add("denied:" + tool.getToolName() + ":" + errorCode);
        }

        private List<String> events() {
            return List.copyOf(events);
        }
    }

    private static class RecordingAgentTaskLogService implements AgentTaskLogService {
        private final AtomicInteger stepIds = new AtomicInteger(1);
        private final AtomicInteger toolLogIds = new AtomicInteger(1);
        private final AtomicInteger messageIds = new AtomicInteger(1);
        private final Map<String, String> toolNameByLogId = new LinkedHashMap<>();
        private final List<String> startedToolNames = new ArrayList<>();
        private final List<String> successfulToolNames = new ArrayList<>();

        @Override
        public AgentTask startTask(String sessionId, String agentId, String userMessageId, String goal) {
            return AgentTask.builder().id("task-real-external-mcp").build();
        }

        @Override
        public AgentTask startTask(String sessionId, String agentId, String userMessageId, String goal,
                                   String modelName, Integer maxSteps, String traceId) {
            return AgentTask.builder().id("task-real-external-mcp").build();
        }

        @Override
        public void finishTask(String taskId) {
        }

        @Override
        public void finishTask(String taskId, String finishReason, Integer actualSteps, Integer toolCallCount) {
        }

        @Override
        public void failTask(String taskId, String errorMessage) {
            throw new AssertionError("Agent task failed: " + errorMessage);
        }

        @Override
        public void failTask(String taskId, String errorMessage, Integer actualSteps, Integer toolCallCount) {
            throw new AssertionError("Agent task failed: " + errorMessage);
        }

        @Override
        public void failStepAndTask(String stepId, String taskId, String errorMessage,
                                    Integer actualSteps, Integer toolCallCount) {
            throw new AssertionError("Agent task failed: " + errorMessage);
        }

        @Override
        public void heartbeatTask(String taskId) {
        }

        @Override
        public AgentStep startStep(String taskId, int stepNo, String stepType, String inputSummary) {
            return startStep(taskId, stepNo, stepType, inputSummary, null);
        }

        @Override
        public AgentStep startStep(String taskId, int stepNo, String stepType, String inputSummary, String modelName) {
            return AgentStep.builder()
                    .id("step-real-external-mcp-" + stepIds.getAndIncrement())
                    .stepNo(stepNo)
                    .stepType(stepType)
                    .modelName(modelName)
                    .build();
        }

        @Override
        public void finishStep(String stepId, String outputSummary) {
        }

        @Override
        public void finishStep(String stepId, String outputSummary, String finishReason, Long llmLatencyMs) {
        }

        @Override
        public void failStep(String stepId, String errorMessage) {
            throw new AssertionError("Agent step failed: " + errorMessage);
        }

        @Override
        public void failStep(String stepId, String errorMessage, String finishReason) {
            throw new AssertionError("Agent step failed: " + errorMessage);
        }

        @Override
        public ToolCallLog startToolCall(String taskId, String stepId, String toolName, String argumentsJson) {
            return startToolCall(taskId, stepId, toolName, toolName, null, argumentsJson, false);
        }

        @Override
        public ToolCallLog startToolCall(String taskId, String stepId, String toolName, String actualToolName,
                                         String toolCallId, String argumentsJson, boolean argumentTruncated) {
            String logId = "tool-log-real-external-mcp-" + toolLogIds.getAndIncrement();
            startedToolNames.add(toolName);
            toolNameByLogId.put(logId, toolName);
            return ToolCallLog.builder().id(logId).build();
        }

        @Override
        public ToolCallLog startAndFailToolCall(String taskId, String stepId, String toolName, String actualToolName,
                                                String toolCallId, String argumentsJson, boolean argumentTruncated,
                                                String errorMessage, long latencyMs, String errorType,
                                                boolean blockedByPolicy) {
            throw new AssertionError("Tool call rejected: " + errorMessage);
        }

        @Override
        public void finishToolCall(String toolCallLogId, String resultSummary, long latencyMs) {
            finishToolCall(toolCallLogId, resultSummary, latencyMs, false);
        }

        @Override
        public void finishToolCall(String toolCallLogId, String resultSummary, long latencyMs, boolean resultTruncated) {
            String toolName = toolNameByLogId.get(toolCallLogId);
            if (toolName != null) {
                successfulToolNames.add(toolName);
            }
        }

        @Override
        public void failToolCall(String toolCallLogId, String errorMessage, long latencyMs) {
            throw new AssertionError("Tool call failed: " + errorMessage);
        }

        @Override
        public void failToolCall(String toolCallLogId, String errorMessage, long latencyMs,
                                 String errorType, boolean blockedByPolicy) {
            throw new AssertionError("Tool call failed: " + errorMessage);
        }

        @Override
        public int recoverStaleRunningTasks(int thresholdMinutes) {
            return 0;
        }

        private int nextMessageId() {
            return messageIds.getAndIncrement();
        }

        private List<String> startedToolNames() {
            return List.copyOf(startedToolNames);
        }

        private List<String> successfulToolNames() {
            return successfulToolNames.stream()
                    .filter(Objects::nonNull)
                    .toList();
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
            return 7000;
        }

        @Override
        public String truncateResult(String toolNameOrAlias, String result) {
            return result;
        }
    }
}
