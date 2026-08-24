package com.kama.jchatmind.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kama.jchatmind.config.MultiChatClientConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class JChatMindFinalRequestContractTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Path SUMMARY_PATH = Path.of(
            "target", "final-request-diagnostic", "wire-capture-fixture-summary.json");

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
    void capturesProductionEquivalentFinalWireRequestAndParsesNormalStream() throws Exception {
        ChatClient chatClient = productionClient();
        Prompt prompt = productionEquivalentFinalPrompt();

        List<ChatResponse> responses = chatClient.prompt(prompt)
                .toolCallbacks(new ToolCallback[0])
                .stream()
                .chatResponse()
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(responses).isNotNull();
        String visible = responses.stream()
                .map(ChatResponse::getResult)
                .filter(result -> result != null && result.getOutput() != null)
                .map(result -> result.getOutput().getText())
                .filter(text -> text != null && !text.isEmpty())
                .reduce("", String::concat);
        long springAiToolCallCount = responses.stream()
                .map(ChatResponse::getResult)
                .filter(result -> result != null && result.getOutput() != null)
                .map(result -> result.getOutput().getToolCalls())
                .filter(toolCalls -> toolCalls != null)
                .mapToLong(List::size)
                .sum();

        assertThat(visible).isEqualTo("ABC");
        assertThat(springAiToolCallCount).isZero();
        assertThat(capturedBodies).hasSize(1);

        JsonNode request = OBJECT_MAPPER.readTree(capturedBodies.get(0));
        assertThat(request.path("model").asText()).isEqualTo("deepseek-chat");
        assertThat(request.path("stream").asBoolean()).isTrue();
        assertThat(request.has("tools")).isFalse();
        assertThat(request.has("tool_choice")).isFalse();
        assertThat(request.has("thinking")).isFalse();
        ArrayNode messages = (ArrayNode) request.path("messages");
        assertThat(messages).allSatisfy(message -> {
            assertThat(message.path("role").asText()).isIn("system", "user", "assistant");
            assertThat(message.has("tool_calls")).isFalse();
            assertThat(message.has("tool_call_id")).isFalse();
        });
        assertThat(messages).noneSatisfy(message -> assertThat(message.path("content").asText())
                .contains("[FINAL_EVIDENCE_BATCH]"));
        assertThat(messages.get(messages.size() - 1).path("role").asText()).isEqualTo("user");
        assertThat(messages.get(messages.size() - 1).path("content").asText())
                .contains("Original user question:\n\u8be6\u7ec6\u4ecb\u7ecd\u4e00\u4e0b")
                .contains("<final_evidence_data>")
                .contains("[redacted evidence]")
                .contains("Now answer the original user question directly");

        ObjectNode summary = summarize(request, springAiToolCallCount);
        Files.createDirectories(SUMMARY_PATH.getParent());
        Files.writeString(SUMMARY_PATH,
                OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(summary),
                StandardCharsets.UTF_8);
    }

    @Test
    void explicitEmptyCallbacksDoNotClearChatClientDefaults() throws Exception {
        ToolCallback defaultCallback = callback("diagnosticTool");
        ChatClient clientWithDefault = productionClient().mutate()
                .defaultToolCallbacks(defaultCallback)
                .build();

        collect(clientWithDefault.prompt(productionEquivalentFinalPrompt()));
        collect(clientWithDefault.prompt(productionEquivalentFinalPrompt())
                .toolCallbacks(new ToolCallback[0]));

        assertThat(capturedBodies).hasSize(2);
        JsonNode omitted = OBJECT_MAPPER.readTree(capturedBodies.get(0));
        JsonNode explicitEmpty = OBJECT_MAPPER.readTree(capturedBodies.get(1));
        assertThat(omitted.path("tools")).hasSize(1);
        assertThat(explicitEmpty.path("tools")).hasSize(1);
        assertThat(explicitEmpty.path("tools")).isEqualTo(omitted.path("tools"));
    }

    @Test
    void deepSeekPublicOptionsExpressExplicitNoneWithoutTools() throws Exception {
        Prompt prompt = Prompt.builder()
                .chatOptions(DeepSeekChatOptions.builder()
                        .model("deepseek-chat")
                        .toolChoice(DeepSeekApi.ChatCompletionRequest.ToolChoiceBuilder.NONE)
                        .internalToolExecutionEnabled(false)
                        .build())
                .messages(productionEquivalentFinalPrompt().getInstructions())
                .build();

        collect(productionClient().prompt(prompt).toolCallbacks(new ToolCallback[0]));

        assertThat(capturedBodies).hasSize(1);
        JsonNode request = OBJECT_MAPPER.readTree(capturedBodies.get(0));
        assertThat(request.has("tools")).isFalse();
        assertThat(request.path("tool_choice").asText()).isEqualTo("none");
    }

    private void collect(ChatClient.ChatClientRequestSpec requestSpec) {
        requestSpec.stream().chatResponse().collectList().block(Duration.ofSeconds(10));
    }

    private ChatClient productionClient() {
        return new MultiChatClientConfig().deepSeekOfficialChatClient(
                "diagnostic-key", baseUrl, "deepseek-chat");
    }

    private Prompt productionEquivalentFinalPrompt() {
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage("Answer as the configured JChatMind agent."));
        history.add(new UserMessage("\u8be6\u7ec6\u4ecb\u7ecd\u4e00\u4e0b"));
        history.add(toolCallMessage(List.of(toolCall("call-1"))));
        history.add(toolResponseMessage(List.of(toolResponse("call-1"))));
        history.add(toolCallMessage(List.of(toolCall("call-2"), toolCall("call-3"))));
        history.add(toolResponseMessage(List.of(toolResponse("call-2"), toolResponse("call-3"))));
        history.add(toolCallMessage(List.of(toolCall("call-4"))));
        history.add(toolResponseMessage(List.of(toolResponse("call-4"))));

        return Prompt.builder()
                .chatOptions(DefaultToolCallingChatOptions.builder()
                        .internalToolExecutionEnabled(false)
                        .build())
                .messages(JChatMind.buildFinalSynthesisMessages(history))
                .build();
    }

    private AssistantMessage toolCallMessage(List<AssistantMessage.ToolCall> toolCalls) {
        return AssistantMessage.builder().content("").toolCalls(toolCalls).build();
    }

    private AssistantMessage.ToolCall toolCall(String id) {
        return new AssistantMessage.ToolCall(id, "function", "searchProjectCode", "{\"query\":\"redacted\"}");
    }

    private ToolResponseMessage toolResponseMessage(List<ToolResponseMessage.ToolResponse> responses) {
        return ToolResponseMessage.builder().responses(responses).build();
    }

    private ToolResponseMessage.ToolResponse toolResponse(String id) {
        return new ToolResponseMessage.ToolResponse(id, "searchProjectCode", "[redacted evidence]");
    }

    private ObjectNode summarize(JsonNode request, long springAiToolCallCount) throws Exception {
        ArrayNode messages = (ArrayNode) request.path("messages");
        List<String> roles = new ArrayList<>();
        int assistantToolCallMessageCount = 0;
        int toolResponseMessageCount = 0;
        int plainAssistantMessageCount = 0;
        for (JsonNode message : messages) {
            String role = message.path("role").asText();
            JsonNode toolCalls = message.get("tool_calls");
            boolean hasToolCalls = toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty();
            roles.add(hasToolCalls ? role + "(tool_calls)" : role);
            if ("assistant".equals(role)) {
                if (hasToolCalls) {
                    assistantToolCallMessageCount++;
                } else {
                    plainAssistantMessageCount++;
                }
            } else if ("tool".equals(role)) {
                toolResponseMessageCount++;
            }
        }

        ObjectNode summary = OBJECT_MAPPER.createObjectNode();
        summary.put("model", request.path("model").asText());
        summary.put("stream", request.path("stream").asBoolean());
        summary.put("messageCount", messages.size());
        summary.putPOJO("roleSequence", roles);
        summary.put("assistantToolCallMessageCount", assistantToolCallMessageCount);
        summary.put("toolResponseMessageCount", toolResponseMessageCount);
        summary.put("plainAssistantMessageCount", plainAssistantMessageCount);
        summary.put("toolsPresent", request.has("tools"));
        summary.put("toolsCount", request.path("tools").isArray() ? request.path("tools").size() : 0);
        summary.set("toolChoice", request.has("tool_choice") ? request.get("tool_choice") : null);
        summary.set("thinking", request.has("thinking") ? request.get("thinking") : null);
        summary.set("temperature", request.has("temperature") ? request.get("temperature") : null);
        summary.set("topP", request.has("top_p") ? request.get("top_p") : null);
        summary.put("canonicalRequestSha256", sha256(canonicalize(request)));
        summary.put("providerVisibleFrames", 3);
        summary.put("springAiVisibleDelta", "ABC");
        summary.put("springAiToolCallsCount", springAiToolCallCount);
        return summary;
    }

    private byte[] canonicalize(JsonNode node) throws IOException {
        return OBJECT_MAPPER.writeValueAsBytes(sortRecursively(node));
    }

    private JsonNode sortRecursively(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = OBJECT_MAPPER.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.stream().sorted(Comparator.naturalOrder())
                    .forEach(name -> sorted.set(name, sortRecursively(node.get(name))));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode sorted = OBJECT_MAPPER.createArrayNode();
            node.forEach(child -> sorted.add(sortRecursively(child)));
            return sorted;
        }
        return node;
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private ToolCallback callback(String name) {
        return new ToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder()
                    .name(name)
                    .description("Diagnostic callback")
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
        capturedBodies.add(exchange.getRequestBody().readAllBytes());
        byte[] response = ("data: {\"id\":\"mock-1\",\"object\":\"chat.completion.chunk\","
                + "\"created\":1,\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"role\":\"assistant\",\"content\":\"A\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"mock-1\",\"object\":\"chat.completion.chunk\","
                + "\"created\":1,\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"content\":\"B\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"mock-1\",\"object\":\"chat.completion.chunk\","
                + "\"created\":1,\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"content\":\"C\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"mock-1\",\"object\":\"chat.completion.chunk\","
                + "\"created\":1,\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"content\":\"\"},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
