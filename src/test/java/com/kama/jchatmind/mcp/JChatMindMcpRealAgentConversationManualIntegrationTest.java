package com.kama.jchatmind.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.AgentEventPublisher;
import com.kama.jchatmind.agent.JChatMind;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JChatMindMcpRealAgentConversationManualIntegrationTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    @EnabledIfEnvironmentVariable(named = "JCHATMIND_MCP_REAL_AGENT_ENABLED", matches = "true")
    void realAgentConversationUsesExternalMcpTool() {
        requireEnv("DEEPSEEK_OFFICIAL_API_KEY");

        RealAgentScenario scenario = RealAgentScenario.fromEnv();
        try (McpSyncClient mcpClient = newMcpClient(scenario)) {
            SpringAiExternalMcpClientAdapter springAiAdapter =
                    new SpringAiExternalMcpClientAdapter(List.of(mcpClient), OBJECT_MAPPER);
            McpClientProperties properties = mcpProperties(scenario);
            ExternalMcpToolRegistry registry = new ExternalMcpToolRegistry(
                    new ExternalMcpServerRegistry(properties),
                    springAiAdapter,
                    new McpExternalToolPolicy());
            RecordingAuditLogger auditLogger = new RecordingAuditLogger();
            McpToolCallbackAdapter mcpAdapter = new McpToolCallbackAdapter(
                    registry, springAiAdapter, auditLogger, properties);
            List<ToolCallback> toolCallbacks = mcpAdapter.toolCallbacks();
            List<String> runtimeToolNames = mcpAdapter.exposedToolNames();

            assertTrue(runtimeToolNames.contains(scenario.exposedToolName()),
                    "external MCP tool did not enter Agent runtime tool list: " + scenario.exposedToolName());

            RecordingAgentTaskLogService logService = new RecordingAgentTaskLogService();
            ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
            when(chatMessageFacadeService.createChatMessage(any(ChatMessageDTO.class)))
                    .thenReturn(CreateChatMessageResponse.builder().chatMessageId("message-real").build());
            when(chatMessageFacadeService.getChatMessageDTOsBySessionId(anyString())).thenReturn(List.of());
            ConversationContextCompressor compressor = mock(ConversationContextCompressor.class);
            when(compressor.check(anyString(), any()))
                    .thenReturn(new ConversationContextCompressor.CompressionCheck(false, "not_needed", 0, 0, 0, 0));

            ToolExecutionServiceImpl executionService = new ToolExecutionServiceImpl(
                    new NoLocalToolRegistry(),
                    logService,
                    mock(AgentEventPublisher.class),
                    new ToolFailureClassifier(),
                    provider(registry),
                    provider(auditLogger));

            JChatMind agent = new JChatMind(
                    "real-agent",
                    scenario.model(),
                    "real-agent",
                    "manual MCP real agent conversation",
                    scenario.systemPrompt(),
                    realChatClient(scenario.model()),
                    20,
                    List.of(new UserMessage(scenario.userQuestion())),
                    toolCallbacks,
                    List.of(),
                    "real-session",
                    mock(SseService.class),
                    executionService,
                    chatMessageFacadeService,
                    mock(ChatMessageConverter.class),
                    logService,
                    compressor,
                    "real-user-message",
                    runtimeToolNames,
                    new ToolCorrectionProperties(),
                    new ToolFailureClassifier()
            );
            agent.setMaxLoopSteps(4);

            agent.run();

            ArgumentCaptor<ChatMessageDTO> messages = ArgumentCaptor.forClass(ChatMessageDTO.class);
            verify(chatMessageFacadeService, atLeastOnce()).createChatMessage(messages.capture());
            List<ChatMessageDTO> capturedMessages = messages.getAllValues();
            int lastToolMessageIndex = lastMessageIndex(capturedMessages, ChatMessageDTO.RoleType.TOOL);
            int finalAssistantIndex = lastMessageIndex(capturedMessages, ChatMessageDTO.RoleType.ASSISTANT);

            assertTrue(lastToolMessageIndex >= 0, "Agent did not persist the MCP tool result message");
            assertTrue(finalAssistantIndex > lastToolMessageIndex,
                    "Agent did not produce a final assistant answer after the MCP tool result");
            assertTrue(auditLogger.events().stream().anyMatch(event -> event.equals("start:" + scenario.toolName())),
                    "MCP audit did not record tool start");
            assertTrue(auditLogger.events().stream().anyMatch(event -> event.startsWith("success:" + scenario.toolName())),
                    "MCP audit did not record allowed tool success");
            assertTrue(logService.startedToolNames().contains(scenario.exposedToolName()),
                    "ToolExecutionServiceImpl preflight did not start the MCP tool call");
            assertFalse(logService.toolResults().isEmpty(), "MCP tool result was not recorded by Agent runtime");

            System.out.println("real Agent MCP conversation ok: scenario=" + scenario.name()
                    + ", serverName=" + scenario.serverName()
                    + ", exposedTool=" + scenario.exposedToolName()
                    + ", auditEvents=" + auditLogger.events()
                    + ", toolResultLength=" + logService.toolResults().get(0).length()
                    + ", finalAnswer=" + sanitize(capturedMessages.get(finalAssistantIndex).getContent()));
        }
    }

    private static int lastMessageIndex(List<ChatMessageDTO> messages, ChatMessageDTO.RoleType role) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessageDTO message = messages.get(i);
            if (message.getRole() == role && message.getContent() != null && !message.getContent().isBlank()) {
                return i;
            }
        }
        return -1;
    }

    private McpSyncClient newMcpClient(RealAgentScenario scenario) {
        ServerParameters parameters = ServerParameters.builder(scenario.command())
                .args(scenario.args())
                .env(scenario.childEnv())
                .build();
        McpSyncClient client = McpClient.sync(new StdioClientTransport(
                        parameters, new JacksonMcpJsonMapper(OBJECT_MAPPER)))
                .clientInfo(new McpSchema.Implementation("jchatmind-real-agent-mcp-test", "1.0.0"))
                .requestTimeout(Duration.ofSeconds(60))
                .initializationTimeout(Duration.ofSeconds(60))
                .build();
        client.initialize();
        return client;
    }

    private ChatClient realChatClient(String model) {
        String apiKey = requireEnv("DEEPSEEK_OFFICIAL_API_KEY");
        String baseUrl = env("DEEPSEEK_OFFICIAL_BASE_URL", "https://api.deepseek.com");
        DeepSeekApi deepSeekApi = DeepSeekApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();
        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .model(model)
                .build();
        DeepSeekChatModel chatModel = DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .defaultOptions(options)
                .retryTemplate(RetryUtils.DEFAULT_RETRY_TEMPLATE)
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
        return ChatClient.create(chatModel);
    }

    private McpClientProperties mcpProperties(RealAgentScenario scenario) {
        McpClientProperties properties = new McpClientProperties();
        properties.setEnabled(true);
        properties.setAuditEnabled(true);
        properties.setMaxResultLength(scenario.maxResultLength());

        ExternalMcpToolProperties tool = new ExternalMcpToolProperties();
        tool.setName(scenario.toolName());
        tool.setRiskLevel(scenario.riskLevel());
        tool.setAutoInvokeAllowed(true);

        ExternalMcpServerProperties server = new ExternalMcpServerProperties();
        server.setName(scenario.serverName());
        server.setType(scenario.serverType());
        server.setTransport("stdio");
        server.setCommand(scenario.command());
        server.setEnabled(true);
        server.setAllowedTools(List.of(tool));
        properties.setServers(List.of(server));
        return properties;
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank() || "your-api-key".equals(value)) {
            throw new IllegalStateException("Required environment variable is not configured: " + name);
        }
        return value;
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
        return value.length() <= 800 ? value : value.substring(0, 768) + "\n...[truncated]";
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

    private record RealAgentScenario(String name,
                                     ExternalMcpServerType serverType,
                                     String serverName,
                                     String command,
                                     List<String> args,
                                     Map<String, String> childEnv,
                                     String toolName,
                                     McpToolRiskLevel riskLevel,
                                     String userQuestion,
                                     String systemPrompt,
                                     String model,
                                     int maxResultLength) {
        static RealAgentScenario fromEnv() {
            String scenario = env("JCHATMIND_MCP_AGENT_SCENARIO", "BROWSER")
                    .trim()
                    .toUpperCase(Locale.ROOT);
            return switch (scenario) {
                case "DOCS" -> docs();
                case "GITHUB" -> github();
                case "BROWSER" -> browser();
                default -> throw new IllegalArgumentException("Unsupported JCHATMIND_MCP_AGENT_SCENARIO: " + scenario);
            };
        }

        private static RealAgentScenario docs() {
            return new RealAgentScenario(
                    "DOCS",
                    ExternalMcpServerType.DOCS,
                    env("JCHATMIND_MCP_REAL_AGENT_SERVER_NAME", "context7-docs"),
                    env("JCHATMIND_MCP_REAL_AGENT_COMMAND", "cmd"),
                    List.of("/c", "npx", "-y", "@upstash/context7-mcp@3.2.1"),
                    optionalChildEnv("CONTEXT7_API_KEY"),
                    env("JCHATMIND_MCP_REAL_AGENT_TOOL_NAME", "resolve-library-id"),
                    McpToolRiskLevel.NETWORK_READ,
                    env("JCHATMIND_MCP_REAL_AGENT_QUESTION",
                            "You must call the external docs MCP tool to look up Spring AI MCP Client. "
                                    + "Then answer in Chinese with a short summary of what it does."),
                    defaultSystemPrompt(),
                    env("DEEPSEEK_OFFICIAL_MODEL", "deepseek-chat"),
                    6000
            );
        }

        private static RealAgentScenario browser() {
            return new RealAgentScenario(
                    "BROWSER",
                    ExternalMcpServerType.BROWSER,
                    env("JCHATMIND_MCP_REAL_AGENT_SERVER_NAME", "playwright-browser"),
                    env("JCHATMIND_MCP_REAL_AGENT_COMMAND", "cmd"),
                    List.of("/c", "npx", "-y", "@playwright/mcp@0.0.76", "--headless", "--isolated",
                            "--browser", "chromium"),
                    Map.of(),
                    env("JCHATMIND_MCP_REAL_AGENT_TOOL_NAME", "browser_navigate"),
                    McpToolRiskLevel.NETWORK_READ,
                    env("JCHATMIND_MCP_REAL_AGENT_QUESTION",
                            "You must call the browser MCP tool to open https://example.com. "
                                    + "Then answer in Chinese with the page title or main content."),
                    defaultSystemPrompt(),
                    env("DEEPSEEK_OFFICIAL_MODEL", "deepseek-chat"),
                    6000
            );
        }

        private static RealAgentScenario github() {
            return new RealAgentScenario(
                    "GITHUB",
                    ExternalMcpServerType.GITHUB,
                    env("JCHATMIND_MCP_REAL_AGENT_SERVER_NAME", "github-readonly"),
                    env("JCHATMIND_MCP_REAL_AGENT_COMMAND", "cmd"),
                    List.of("/c", "npx", "-y", "@modelcontextprotocol/server-github@2025.4.8"),
                    optionalChildEnv("GITHUB_PERSONAL_ACCESS_TOKEN"),
                    env("JCHATMIND_MCP_REAL_AGENT_TOOL_NAME", "search_repositories"),
                    McpToolRiskLevel.NETWORK_READ,
                    env("JCHATMIND_MCP_REAL_AGENT_QUESTION",
                            "You must call the GitHub MCP tool to search for public repositories about spring-ai. "
                                    + "Then answer in Chinese with one repository name."),
                    defaultSystemPrompt(),
                    env("DEEPSEEK_OFFICIAL_MODEL", "deepseek-chat"),
                    6000
            );
        }

        private static String defaultSystemPrompt() {
            return "You are a test agent. For this run, you must call the provided external MCP tool before answering. "
                    + "Do not answer from memory only. Do not perform write operations.";
        }

        private static Map<String, String> optionalChildEnv(String key) {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? Map.of() : Map.of(key, value);
        }

        public String exposedToolName() {
            return exposedName(serverName, toolName);
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
        private final List<String> startedToolNames = new ArrayList<>();
        private final List<String> toolResults = new ArrayList<>();

        @Override
        public AgentTask startTask(String sessionId, String agentId, String userMessageId, String goal) {
            return AgentTask.builder().id("task-real-agent").build();
        }

        @Override
        public AgentTask startTask(String sessionId, String agentId, String userMessageId, String goal,
                                   String modelName, Integer maxSteps, String traceId) {
            return AgentTask.builder().id("task-real-agent").build();
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
                    .id("step-real-" + stepIds.getAndIncrement())
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
            startedToolNames.add(toolName);
            return ToolCallLog.builder().id("tool-log-real").build();
        }

        @Override
        public ToolCallLog startToolCall(String taskId, String stepId, String toolName, String actualToolName,
                                         String toolCallId, String argumentsJson, boolean argumentTruncated) {
            startedToolNames.add(toolName);
            return ToolCallLog.builder().id("tool-log-real").build();
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
            toolResults.add(resultSummary);
        }

        @Override
        public void finishToolCall(String toolCallLogId, String resultSummary, long latencyMs, boolean resultTruncated) {
            toolResults.add(resultSummary);
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

        private List<String> toolResults() {
            return List.copyOf(toolResults);
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
}
