package com.kama.jchatmind.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.MultiChatClientConfig;
import com.kama.jchatmind.config.ToolCorrectionProperties;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.AgentStep;
import com.kama.jchatmind.model.entity.AgentTask;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.service.AgentTaskLogService;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.ConversationContextCompressor;
import com.kama.jchatmind.service.FinalCompletionService;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.ToolExecutionService;
import com.kama.jchatmind.tool.ToolFailureClassifier;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JChatMindFinalToolIsolationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<String> CURRENT_TOOL_FIXTURE = List.of(
            "knowledgeQuery",
            "terminate",
            "searchProjectCode",
            "databaseQuery",
            "mcp_context7_resolve_library_id",
            "mcp_context7_query_docs",
            "mcp_playwright_browser_navigate",
            "mcp_playwright_browser_snapshot",
            "mcp_github_mcp_server_search_repositories",
            "mcp_github_mcp_server_get_file_contents",
            "mcp_github_mcp_server_list_commits",
            "mcp_github_mcp_server_list_issues",
            "mcp_github_mcp_server_search_code",
            "mcp_github_mcp_server_search_issues",
            "mcp_github_mcp_server_search_users",
            "mcp_github_mcp_server_get_issue",
            "mcp_github_mcp_server_get_pull_request",
            "mcp_github_mcp_server_list_pull_requests");

    private final List<byte[]> capturedBodies = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private ExecutorService serverExecutor;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", this::handleChatCompletions);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void planningCallbacksCannotLeakIntoFinalWireInSameAgentLifecycle() throws Exception {
        List<ToolCallback> callbacks = CURRENT_TOOL_FIXTURE.stream().map(this::callback).toList();
        JChatMind agent = createAgent(callbacks);
        agent.setPlanningGenerationOptions(0.2, 0.8);
        agent.setFinalStreamingEnabled(true);

        ToolCallingChatOptions planningOptions = planningOptions(agent);
        assertThat(planningOptions.getToolCallbacks()).isEmpty();
        ChatOptions isolatedBeforePlanning = ReflectionTestUtils.invokeMethod(
                agent, "createFinalSynthesisChatOptions");
        assertFinalOptionsAreIsolated(planningOptions, isolatedBeforePlanning);

        agent.run();

        assertThat(capturedBodies).hasSize(2);
        JsonNode planningWire = OBJECT_MAPPER.readTree(capturedBodies.get(0));
        JsonNode finalWire = OBJECT_MAPPER.readTree(capturedBodies.get(1));

        assertThat(planningWire.path("stream").asBoolean()).isFalse();
        assertThat(planningWire.path("model").asText()).isEqualTo("deepseek-chat");
        assertThat(planningWire.path("temperature").asDouble()).isEqualTo(0.2);
        assertThat(planningWire.path("top_p").asDouble()).isEqualTo(0.8);
        assertThat(planningWire.path("tools")).hasSize(18);
        assertThat(toolNames(planningWire)).contains("searchProjectCode");

        assertThat(finalWire.path("stream").asBoolean()).isTrue();
        assertThat(finalWire.has("tools")).isFalse();
        assertThat(finalWire.has("tool_choice")).isFalse();
        assertThat(finalWire.has("thinking")).isFalse();
        assertThat(finalWire.has("temperature")).isFalse();
        assertThat(finalWire.has("top_p")).isFalse();
        assertThat(roles(finalWire)).containsExactly("system", "system", "user");
        assertThat(finalWire.path("messages")).allSatisfy(message -> {
            assertThat(message.path("role").asText()).isIn("system", "user", "assistant");
            assertThat(message.has("tool_calls")).isFalse();
            assertThat(message.has("tool_call_id")).isFalse();
        });
        assertThat(finalWire.path("messages"))
                .noneSatisfy(message -> assertThat(message.path("role").asText()).isEqualTo("tool"));
        List<String> evidence = finalEvidence(finalWire);
        assertThat(evidence).containsExactly("[redacted evidence]");
        assertThat(evidence.stream().mapToInt(String::length).sum()).isEqualTo(19);
        assertThat(evidenceFingerprint(evidence))
                .isEqualTo("1cfc105cb86b81b2b8913ceffbe023063aa38b25ac88168ecf9e1657c880947d");

        assertThat(planningOptions.getToolCallbacks()).hasSize(18);
        ChatOptions isolatedAfterPlanning = ReflectionTestUtils.invokeMethod(
                agent, "createFinalSynthesisChatOptions");
        assertFinalOptionsAreIsolated(planningOptions, isolatedAfterPlanning);
        planningOptions.setToolCallbacks(List.of(callback("latePlanningTool")));
        assertThat(((ToolCallingChatOptions) isolatedAfterPlanning).getToolCallbacks()).isEmpty();
    }

    @Test
    void unsetPlanningGenerationOptionsLeaveProviderDefaultsOffTheWire() throws Exception {
        JChatMind agent = createAgent(List.of());
        agent.setPlanningGenerationOptions(null, null);

        agent.run();

        assertThat(capturedBodies).hasSize(2);
        JsonNode planningWire = OBJECT_MAPPER.readTree(capturedBodies.get(0));
        JsonNode finalWire = OBJECT_MAPPER.readTree(capturedBodies.get(1));
        assertThat(planningWire.path("model").asText()).isEqualTo("deepseek-chat");
        assertThat(planningWire.has("temperature")).isFalse();
        assertThat(planningWire.has("top_p")).isFalse();
        assertThat(finalWire.has("tools")).isFalse();
        assertThat(finalWire.has("temperature")).isFalse();
        assertThat(finalWire.has("top_p")).isFalse();
    }

    private void assertFinalOptionsAreIsolated(ToolCallingChatOptions planningOptions, ChatOptions finalOptions) {
        assertThat(finalOptions).isInstanceOf(ToolCallingChatOptions.class).isNotSameAs(planningOptions);
        ToolCallingChatOptions toolOptions = (ToolCallingChatOptions) finalOptions;
        assertThat(toolOptions.getToolCallbacks()).isEmpty();
        assertThat(toolOptions.getToolNames()).isEmpty();
        assertThat(toolOptions.getToolContext()).isEmpty();
        assertThat(toolOptions.getInternalToolExecutionEnabled()).isFalse();
    }

    private ToolCallingChatOptions planningOptions(JChatMind agent) {
        return (ToolCallingChatOptions) ReflectionTestUtils.getField(agent, "chatOptions");
    }

    private List<String> toolNames(JsonNode request) {
        List<String> names = new ArrayList<>();
        request.path("tools").forEach(tool -> names.add(tool.path("function").path("name").asText()));
        return names;
    }

    private List<String> roles(JsonNode request) {
        List<String> roles = new ArrayList<>();
        request.path("messages").forEach(message -> roles.add(message.path("role").asText()));
        return roles;
    }

    private List<String> finalEvidence(JsonNode request) {
        List<String> evidence = new ArrayList<>();
        request.path("messages").forEach(message -> {
            String content = message.path("content").asText();
            int cursor = 0;
            while (true) {
                int marker = content.indexOf("<evidence id=\"", cursor);
                if (marker < 0) {
                    break;
                }
                int start = content.indexOf('>', marker) + 2;
                int end = content.indexOf("\n</evidence>", start);
                if (start < 1 || end < start) {
                    break;
                }
                evidence.add(content.substring(start, end));
                cursor = end + "\n</evidence>".length();
            }
            if (!content.contains("<final_evidence_data>")) {
                return;
            }
            assertThat(message.path("role").asText()).isEqualTo("user");
            assertThat(content).contains("Now answer the original user question directly");
        });
        return evidence;
    }

    private String evidenceFingerprint(List<String> evidence) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        for (String content : evidence) {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private JChatMind createAgent(List<ToolCallback> callbacks) {
        ChatClient chatClient = new MultiChatClientConfig().deepSeekOfficialChatClient(
                "diagnostic-key", baseUrl, "deepseek-chat");
        AgentTaskLogService logService = mock(AgentTaskLogService.class);
        when(logService.startTask(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyString())).thenReturn(AgentTask.builder().id("task-1").build());
        AtomicInteger stepSequence = new AtomicInteger();
        when(logService.startStep(anyString(), anyInt(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> AgentStep.builder()
                        .id("step-" + stepSequence.incrementAndGet())
                        .stepNo(invocation.getArgument(1))
                        .stepType(invocation.getArgument(2))
                        .build());

        ChatMessageFacadeService messageService = mock(ChatMessageFacadeService.class);
        when(messageService.createChatMessage(any(ChatMessageDTO.class)))
                .thenReturn(CreateChatMessageResponse.builder().chatMessageId("final-message-1").build());
        when(messageService.getChatMessageDTOsBySessionId(anyString())).thenReturn(List.of());

        ConversationContextCompressor compressor = mock(ConversationContextCompressor.class);
        when(compressor.check(anyString(), anyString(), any()))
                .thenReturn(new ConversationContextCompressor.CompressionCheck(
                        false, "not_needed", 0, 0, 0, 0, "TEST", 0, 0));

        List<Message> memory = List.of(
                new UserMessage("Describe the project"),
                AssistantMessage.builder().content("").toolCalls(List.of(
                        new AssistantMessage.ToolCall(
                                "call-1", "function", "searchProjectCode", "{\"query\":\"project\"}")))
                        .build(),
                ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse(
                                "call-1", "searchProjectCode", "[redacted evidence]")))
                        .build());

        JChatMind agent = new JChatMind(
                "agent-1", "deepseek-chat", "test-agent", "test", "system", chatClient, 20,
                memory, callbacks, List.of(), "session-1", mock(SseService.class),
                mock(AgentEventPublisher.class), mock(ToolExecutionService.class), messageService,
                mock(ChatMessageConverter.class), logService, compressor, "user-message-1",
                callbacks.stream().map(tool -> tool.getToolDefinition().name()).toList(),
                new ToolCorrectionProperties(), new ToolFailureClassifier(), null,
                mock(ToolCallBatchExecutor.class));
        FinalCompletionService completionService = mock(FinalCompletionService.class);
        when(completionService.complete(any())).thenAnswer(invocation -> {
            FinalCompletionService.FinalCompletionCommand command = invocation.getArgument(0);
            return new FinalCompletionService.FinalCompletionResult(
                    "final-message-1", command.finalStepId(), command.finalStepNo(),
                    "finish-step-1", command.finishStepNo());
        });
        agent.setFinalCompletionService(completionService);
        return agent;
    }

    private ToolCallback callback(String name) {
        return new ToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder()
                    .name(name)
                    .description(name)
                    .inputSchema("{\"type\":\"object\"}")
                    .build();

            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return "unused";
            }
        };
    }

    private void handleChatCompletions(HttpExchange exchange) throws IOException {
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        capturedBodies.add(requestBody);
        JsonNode request = OBJECT_MAPPER.readTree(requestBody);
        if (request.path("stream").asBoolean()) {
            writeFinalStream(exchange);
        } else {
            writePlanningResponse(exchange);
        }
    }

    private void writePlanningResponse(HttpExchange exchange) throws IOException {
        byte[] response = ("{\"id\":\"mock-planning\",\"object\":\"chat.completion\","
                + "\"created\":1,\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"ready\"},"
                + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,"
                + "\"completion_tokens\":1,\"total_tokens\":2}}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private void writeFinalStream(HttpExchange exchange) throws IOException {
        byte[] response = ("data: {\"id\":\"mock-final\",\"object\":\"chat.completion.chunk\","
                + "\"created\":1,\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"role\":\"assistant\",\"content\":\"A\"},"
                + "\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"mock-final\",\"object\":\"chat.completion.chunk\","
                + "\"created\":1,\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"content\":\"BC\"},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
