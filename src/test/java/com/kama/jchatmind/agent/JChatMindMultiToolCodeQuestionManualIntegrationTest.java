package com.kama.jchatmind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.tools.Tool;
import com.kama.jchatmind.config.ToolCorrectionProperties;
import com.kama.jchatmind.converter.ChatMessageConverter;
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
import com.kama.jchatmind.service.ToolExecutionService;
import com.kama.jchatmind.service.ToolFacadeService;
import com.kama.jchatmind.service.impl.ToolExecutionServiceImpl;
import com.kama.jchatmind.tool.ToolFailureClassifier;
import com.kama.jchatmind.tool.ToolRegistry;
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
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "JCHATMIND_MULTI_TOOL_REAL_AGENT_ENABLED", matches = "true")
@TestPropertySource(properties = {
        "jchatmind.agent.observability.recovery-enabled=false",
        "jchatmind.code-rag.embedding-warmup.enabled=false"
})
class JChatMindMultiToolCodeQuestionManualIntegrationTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DEFAULT_REPO_ID = "f684db66-31fa-4099-803c-40c522c10191";
    private static final String DEFAULT_KB_ID = "7e63b815-cc0e-40bd-8139-47a32a7dbb4c";
    private static final String BROWSER_SERVER_NAME = "playwright-browser";
    private static final String BROWSER_TOOL_NAME = "browser_navigate";

    @Autowired
    @Qualifier("deepseek-official-chat")
    private ChatClient chatClient;

    @Autowired
    private ToolFacadeService toolFacadeService;

    @Autowired
    private ToolRegistry toolRegistry;

    @Test
    void realCodeQuestionCanUseLocalAndExternalTools() {
        requireEnv("DEEPSEEK_OFFICIAL_API_KEY");

        String repoId = env("JCHATMIND_MULTI_TOOL_REPO_ID", DEFAULT_REPO_ID);
        String kbId = env("JCHATMIND_MULTI_TOOL_KB_ID", DEFAULT_KB_ID);
        try (McpSyncClient mcpClient = newBrowserMcpClient()) {
            SpringAiExternalMcpClientAdapter springAiAdapter =
                    new SpringAiExternalMcpClientAdapter(List.of(mcpClient), OBJECT_MAPPER);
            McpClientProperties mcpProperties = browserMcpProperties();
            ExternalMcpToolRegistry externalMcpToolRegistry = new ExternalMcpToolRegistry(
                    new ExternalMcpServerRegistry(mcpProperties),
                    springAiAdapter,
                    new McpExternalToolPolicy());
            RecordingMcpAuditLogger mcpAuditLogger = new RecordingMcpAuditLogger();
            McpToolCallbackAdapter mcpAdapter = new McpToolCallbackAdapter(
                    externalMcpToolRegistry, springAiAdapter, mcpAuditLogger, mcpProperties);

            List<Tool> runtimeTools = runtimeLocalTools();
            List<ToolCallback> callbacks = new ArrayList<>(localToolCallbacks(runtimeTools));
            callbacks.addAll(mcpAdapter.toolCallbacks());
            List<String> runtimeToolNames = runtimeToolNames(runtimeTools, mcpAdapter);

            assertTrue(runtimeToolNames.contains("searchProjectCode"), "searchProjectCode not available");
            assertTrue(runtimeToolNames.contains("databaseQuery"), "databaseQuery not available");
            assertTrue(runtimeToolNames.contains("knowledgeQuery"), "knowledgeQuery not available");
            assertTrue(runtimeToolNames.contains(browserExposedToolName()), "browser MCP tool not available");

            RecordingAgentTaskLogService logService = new RecordingAgentTaskLogService();
            ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
            when(chatMessageFacadeService.createChatMessage(any(ChatMessageDTO.class)))
                    .thenReturn(CreateChatMessageResponse.builder().chatMessageId("message-real").build());
            when(chatMessageFacadeService.getChatMessageDTOsBySessionId(anyString())).thenReturn(List.of());
            ConversationContextCompressor compressor = mock(ConversationContextCompressor.class);
            when(compressor.check(anyString(), anyString(), any()))
                    .thenReturn(new ConversationContextCompressor.CompressionCheck(false, "not_needed", 0, 0, 0, 0, "TEST", 0, 0));

            ToolExecutionService executionService = new ToolExecutionServiceImpl(
                    toolRegistry,
                    logService,
                    mock(AgentEventPublisher.class),
                    new ToolFailureClassifier(),
                    provider(externalMcpToolRegistry),
                    provider(mcpAuditLogger));

            try (ToolCallBatchExecutorFixture toolRuntime =
                         new ToolCallBatchExecutorFixture(executionService, toolRegistry)) {
                JChatMind agent = new JChatMind(
                    "multi-tool-code-agent",
                    env("DEEPSEEK_OFFICIAL_MODEL", "deepseek-chat"),
                    "multi-tool-code-agent",
                    "manual real multi-tool code question",
                    systemPrompt(repoId, kbId),
                    chatClient,
                    30,
                    List.of(new UserMessage(userQuestion(repoId, kbId))),
                    callbacks,
                    List.of(),
                    "multi-tool-real-session",
                    mock(com.kama.jchatmind.service.SseService.class),
                    executionService,
                    chatMessageFacadeService,
                    mock(ChatMessageConverter.class),
                    logService,
                    compressor,
                    "multi-tool-real-user-message",
                    runtimeToolNames,
                    new ToolCorrectionProperties(),
                    new ToolFailureClassifier(),
                    toolRuntime.batchExecutor()
                );
                agent.setMaxLoopSteps(7);

            agent.run();

            ArgumentCaptor<ChatMessageDTO> messages = ArgumentCaptor.forClass(ChatMessageDTO.class);
            verify(chatMessageFacadeService, atLeastOnce()).createChatMessage(messages.capture());
            List<ChatMessageDTO> capturedMessages = messages.getAllValues();
            int lastToolMessageIndex = lastMessageIndex(capturedMessages, ChatMessageDTO.RoleType.TOOL);
            int finalAssistantIndex = lastMessageIndex(capturedMessages, ChatMessageDTO.RoleType.ASSISTANT);

            assertTrue(lastToolMessageIndex >= 0, "Agent did not persist any tool result message");
            assertTrue(finalAssistantIndex > lastToolMessageIndex,
                    "Agent did not produce a final assistant answer after tool results; messages="
                            + messageSummary(capturedMessages)
                            + ", successfulTools=" + logService.successfulToolNames());
            assertTrue(logService.successfulToolNames().contains("searchProjectCode"),
                    "real code question did not use searchProjectCode");
            assertTrue(logService.successfulToolNames().contains("databaseQuery"),
                    "real code question did not use databaseQuery");
            assertFalse(logService.successfulToolNames().isEmpty(), "no successful tool calls were recorded");

                System.out.println("real multi-tool code question ok: repoId=" + repoId
                    + ", kbId=" + kbId
                    + ", runtimeTools=" + runtimeToolNames
                    + ", startedTools=" + logService.startedToolNames()
                    + ", successfulTools=" + logService.successfulToolNames()
                    + ", mcpAuditEvents=" + mcpAuditLogger.events()
                        + ", finalAnswer=" + sanitize(capturedMessages.get(finalAssistantIndex).getContent()));
            }
        }
    }

    private List<Tool> runtimeLocalTools() {
        List<String> wanted = List.of("knowledgeQuery", "searchProjectCode", "databaseQuery");
        return toolFacadeService.getAllTools().stream()
                .filter(tool -> wanted.contains(tool.getName()))
                .filter(tool -> toolRegistry.canExposeToAgent(tool.getName()))
                .toList();
    }

    private List<ToolCallback> localToolCallbacks(List<Tool> runtimeTools) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (Tool tool : runtimeTools) {
            callbacks.addAll(List.of(MethodToolCallbackProvider.builder()
                    .toolObjects(tool)
                    .build()
                    .getToolCallbacks()));
        }
        return callbacks;
    }

    private List<String> runtimeToolNames(List<Tool> runtimeTools, McpToolCallbackAdapter mcpAdapter) {
        List<String> names = new ArrayList<>(runtimeTools.stream()
                .map(Tool::getName)
                .map(toolRegistry::canonicalName)
                .toList());
        names.addAll(mcpAdapter.exposedToolNames());
        return names.stream().distinct().toList();
    }

    private String systemPrompt(String repoId, String kbId) {
        return """
                You are a strict real-run verification agent.
                Answer the user in Chinese, but you must first gather evidence with tools.
                Use as many relevant available tools as possible without doing any write operation.
                Required evidence gathering order:
                1. Call searchProjectCode with repoId=%s to inspect the real code flow.
                2. Call databaseQuery with this safe PostgreSQL SELECT to confirm repository facts:
                   SELECT id, name, status FROM code_repository WHERE id = '%s'
                3. Call databaseQuery with this safe PostgreSQL SELECT to confirm chunk count:
                   SELECT COUNT(*) AS chunk_count FROM code_chunk WHERE repo_id = '%s'
                4. If knowledgeQuery is available, call it with kbsId=%s for any related note.
                5. If the browser MCP tool is available, call it to open https://example.com only as an external MCP sanity check.
                Do not query a description column from code_repository; that table has id, name, root_path, language, status, created_at, updated_at.
                Do not call terminate in this test; finish by producing a normal assistant answer after tool results.
                Do not call unsafe browser code, do not click, download, submit forms, or write anything.
                After tools return, summarize which tools were used and answer the code question from the gathered evidence.
                """.formatted(repoId, repoId, repoId, kbId);
    }

    private String userQuestion(String repoId, String kbId) {
        return """
                Please run a real multi-tool code Q&A flow for repoId=%s and kbsId=%s.
                Question: In the hmdp codebase, explain the seckill voucher/order flow at a high level.
                Use searchProjectCode for code evidence, databaseQuery for read-only repository/chunk verification,
                knowledgeQuery if available, and browser_navigate to open https://example.com as the external MCP check.
                Then answer in Chinese with the concrete tool evidence you used.
                """.formatted(repoId, kbId);
    }

    private McpSyncClient newBrowserMcpClient() {
        ServerParameters parameters = ServerParameters.builder(env("JCHATMIND_MULTI_TOOL_BROWSER_COMMAND", "cmd"))
                .args(List.of("/c", "npx", "-y", "@playwright/mcp@0.0.76", "--headless", "--isolated",
                        "--browser", "chromium"))
                .build();
        McpSyncClient client = McpClient.sync(new StdioClientTransport(
                        parameters, new JacksonMcpJsonMapper(OBJECT_MAPPER)))
                .clientInfo(new McpSchema.Implementation("jchatmind-real-multi-tool-code-test", "1.0.0"))
                .requestTimeout(Duration.ofSeconds(60))
                .initializationTimeout(Duration.ofSeconds(60))
                .build();
        client.initialize();
        return client;
    }

    private McpClientProperties browserMcpProperties() {
        McpClientProperties properties = new McpClientProperties();
        properties.setEnabled(true);
        properties.setAuditEnabled(true);
        properties.setMaxResultLength(6000);

        ExternalMcpToolProperties tool = new ExternalMcpToolProperties();
        tool.setName(BROWSER_TOOL_NAME);
        tool.setRiskLevel(McpToolRiskLevel.NETWORK_READ);
        tool.setAutoInvokeAllowed(true);

        ExternalMcpServerProperties server = new ExternalMcpServerProperties();
        server.setName(BROWSER_SERVER_NAME);
        server.setType(ExternalMcpServerType.BROWSER);
        server.setTransport("stdio");
        server.setCommand(env("JCHATMIND_MULTI_TOOL_BROWSER_COMMAND", "cmd"));
        server.setEnabled(true);
        server.setAllowedTools(List.of(tool));
        properties.setServers(List.of(server));
        return properties;
    }

    private static String browserExposedToolName() {
        return "mcp_playwright_browser_browser_navigate";
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

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 1000 ? value : value.substring(0, 968) + "\n...[truncated]";
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

    private static class RecordingMcpAuditLogger implements McpToolAuditLogger {
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
        private final Map<String, String> toolNameByLogId = new LinkedHashMap<>();
        private final List<String> startedToolNames = new ArrayList<>();
        private final List<String> successfulToolNames = new ArrayList<>();

        @Override
        public AgentTask startTask(String sessionId, String agentId, String userMessageId, String goal) {
            return AgentTask.builder().id("task-real-multi-tool").build();
        }

        @Override
        public AgentTask startTask(String sessionId, String agentId, String userMessageId, String goal,
                                   String modelName, Integer maxSteps, String traceId) {
            return AgentTask.builder().id("task-real-multi-tool").build();
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
                    .id("step-real-multi-tool-" + stepIds.getAndIncrement())
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
            String logId = "tool-log-real-multi-" + toolLogIds.getAndIncrement();
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

        private List<String> startedToolNames() {
            return List.copyOf(startedToolNames);
        }

        private List<String> successfulToolNames() {
            return successfulToolNames.stream()
                    .filter(Objects::nonNull)
                    .toList();
        }
    }
}
