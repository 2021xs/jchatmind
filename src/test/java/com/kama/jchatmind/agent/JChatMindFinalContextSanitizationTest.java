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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.FileSystemResource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class JChatMindFinalContextSanitizationTest {

    private static final String SESSION_ID = "c1b56f0b-eb1e-4208-8e08-f37ce8441bab";
    private static final String TASK_ID = "994d64db-0c9a-478c-b9a3-819f8a343950";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Path OUTPUT_DIR = Path.of("target", "final-context-sanitization");
    private static final Path STRUCTURE_PATH = OUTPUT_DIR.resolve("context-structure.json");
    private static final Path CSV_PATH = OUTPUT_DIR.resolve("provider-observations.csv");
    private static final Path REPORT_PATH = OUTPUT_DIR.resolve("context-sanitization-report.md");
    private static final List<RunSpec> SCHEDULE = List.of(
            new RunSpec("A", 1), new RunSpec("B", 1), new RunSpec("B", 2),
            new RunSpec("A", 2), new RunSpec("A", 3), new RunSpec("B", 3),
            new RunSpec("B", 4), new RunSpec("A", 4), new RunSpec("A", 5),
            new RunSpec("B", 5));
    private static final List<Topic> TOPICS = List.of(
            new Topic("PROJECT", List.of("flashdeal")),
            new Topic("SECKILL_FLOW", List.of("seckill", "\u79d2\u6740")),
            new Topic("REDIS_LUA", List.of("redis", "lua")),
            new Topic("MESSAGING", List.of("rabbitmq", "rocketmq", "message queue", "\u6d88\u606f\u961f\u5217")),
            new Topic("CACHE", List.of("cache", "\u7f13\u5b58")),
            new Topic("ORDER", List.of("order", "\u8ba2\u5355")),
            new Topic("CONTROLLER", List.of("controller")),
            new Topic("SERVICE", List.of("service")),
            new Topic("MAPPER", List.of("mapper")));
    private static final List<Topic> UNSUPPORTED_CLAIM_PROBES = List.of(
            new Topic("KAFKA", List.of("kafka")),
            new Topic("ELASTICSEARCH", List.of("elasticsearch")),
            new Topic("KUBERNETES", List.of("kubernetes")),
            new Topic("GRPC", List.of("grpc")),
            new Topic("SPRING_CLOUD", List.of("spring cloud")));

    private final List<byte[]> capturedBodies = new CopyOnWriteArrayList<>();
    private final AtomicReference<byte[]> mockResponse = new AtomicReference<>();
    private HttpServer server;
    private ExecutorService serverExecutor;
    private String mockBaseUrl;

    @BeforeEach
    void startServer() throws IOException {
        mockResponse.set(normalMockResponse());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", this::handleChatCompletions);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.start();
        mockBaseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
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
    void noToolContextKeepsOrdinaryConversationAndEndsWithAnswerInstruction() {
        SystemMessage system = new SystemMessage("system");
        UserMessage firstUser = new UserMessage("first question");
        AssistantMessage ordinaryAssistant = AssistantMessage.builder()
                .content("ordinary answer")
                .toolCalls(List.of())
                .build();
        UserMessage currentUser = new UserMessage("current question");
        List<Message> runtime = new ArrayList<>(List.of(system, firstUser, ordinaryAssistant, currentUser));

        List<Message> sanitized = JChatMind.buildFinalSynthesisMessages(runtime);

        assertThat(sanitized).hasSize(5);
        assertThat(sanitized.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(sanitized.subList(1, 4)).containsExactly(system, firstUser, ordinaryAssistant);
        assertThat(sanitized.get(4)).isInstanceOf(UserMessage.class);
        assertThat(sanitized.get(4).getText())
                .contains("Original user question:\ncurrent question")
                .contains("Now answer the original user question directly")
                .doesNotContain("[FINAL_EVIDENCE_BATCH]");
        assertThat(runtime).containsExactly(system, firstUser, ordinaryAssistant, currentUser);
    }

    @Test
    void singleToolCallBecomesStructuredEvidenceWithoutMutatingRuntimeMemory() {
        AssistantMessage toolCall = toolCallBatch(toolCall("call-a", "searchProjectCode", "ARG_A"));
        ToolResponseMessage response = toolResponses(toolResponse("call-a", "searchProjectCode", "evidence-A"));
        List<Message> runtime = new ArrayList<>(List.of(
                new SystemMessage("system"), new UserMessage("question"), toolCall, response));
        List<Message> originalReferences = List.copyOf(runtime);

        FinalSynthesisRequest request = new FinalSynthesisRequestFactory().create(runtime);
        List<Message> sanitized = new FinalContextCompiler().compile(request);

        assertThat(runtime).hasSameSizeAs(originalReferences);
        for (int i = 0; i < runtime.size(); i++) {
            assertThat(runtime.get(i)).isSameAs(originalReferences.get(i));
        }
        assertThat(toolCall.getToolCalls()).hasSize(1);
        assertThat(response.getResponses()).hasSize(1);
        assertToolProtocolPurity(sanitized);
        assertThat(request.evidenceBatches()).hasSize(1);
        assertThat(evidenceContents(request)).containsExactly("evidence-A");
        assertThat(request.evidenceBatches().get(0).evidence().get(0).toolName())
                .isEqualTo("searchProjectCode");
        assertThat(sanitized).noneMatch(message -> message instanceof AssistantMessage assistant
                && assistant.getText().contains("evidence-A"));
        assertThat(sanitized.get(sanitized.size() - 1).getText())
                .contains("evidence-A")
                .doesNotContain("ARG_A", "[FINAL_EVIDENCE_BATCH]");
    }

    @Test
    void multiToolBatchUsesToolCallOrderAndPreservesEvidenceFingerprint() throws Exception {
        List<String> expectedEvidence = List.of(
                "evidence-A\nwith lines", "evidence-B", "evidence-C");
        List<Message> runtime = List.of(
                new UserMessage("question"),
                toolCallBatch(
                        toolCall("call-a", "searchProjectCode", "ARG_A"),
                        toolCall("call-b", "databaseQuery", "ARG_B"),
                        toolCall("call-c", "searchProjectCode", "ARG_C")),
                toolResponses(
                        toolResponse("call-c", "searchProjectCode", expectedEvidence.get(2)),
                        toolResponse("call-a", "searchProjectCode", expectedEvidence.get(0))),
                toolResponses(toolResponse("call-b", "databaseQuery", expectedEvidence.get(1))));

        FinalSynthesisRequest request = new FinalSynthesisRequestFactory().create(runtime);
        List<Message> sanitized = new FinalContextCompiler().compile(request);
        List<String> actualEvidence = evidenceContents(request);

        assertThat(request.evidenceBatches()).hasSize(1);
        assertThat(actualEvidence).containsExactlyElementsOf(expectedEvidence);
        assertThat(actualEvidence.stream().mapToInt(String::length).sum())
                .isEqualTo(expectedEvidence.stream().mapToInt(String::length).sum());
        assertThat(evidenceFingerprint(actualEvidence)).isEqualTo(evidenceFingerprint(expectedEvidence));
        assertToolProtocolPurity(sanitized);
    }

    @Test
    void multiplePlanningBatchesRemainSeparateAndOrdered() {
        List<Message> runtime = List.of(
                new UserMessage("question"),
                toolCallBatch(toolCall("call-a", "toolA", "args-a"),
                        toolCall("call-b", "toolB", "args-b"),
                        toolCall("call-c", "toolC", "args-c")),
                toolResponses(toolResponse("call-b", "toolB", "B"),
                        toolResponse("call-c", "toolC", "C"),
                        toolResponse("call-a", "toolA", "A")),
                toolCallBatch(toolCall("call-d", "toolD", "args-d"),
                        toolCall("call-e", "toolE", "args-e")),
                toolResponses(toolResponse("call-e", "toolE", "E"),
                        toolResponse("call-d", "toolD", "D")));

        FinalSynthesisRequest request = new FinalSynthesisRequestFactory().create(runtime);
        List<Message> sanitized = new FinalContextCompiler().compile(request);
        List<FinalEvidenceBatch> batches = request.evidenceBatches();

        assertThat(batches).hasSize(2);
        assertThat(batches.get(0).evidence().stream().map(FinalEvidence::content))
                .containsExactly("A", "B", "C");
        assertThat(batches.get(1).evidence().stream().map(FinalEvidence::content))
                .containsExactly("D", "E");
        assertToolProtocolPurity(sanitized);
    }

    @Test
    void incompleteToolProtocolFailsInsteadOfDroppingEvidenceSilently() {
        List<Message> runtime = List.of(
                new UserMessage("question"),
                toolCallBatch(toolCall("call-a", "searchProjectCode", "ARG_A")));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> JChatMind.buildFinalSynthesisMessages(runtime))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incomplete or orphaned tool protocol");
    }

    @Test
    void sanitizedContextPreservesEvidenceAndPassesMockStreamingContract() throws Exception {
        FrozenContext frozen = deterministicFrozenContext();
        ContextVariants variants = buildVariants(frozen);
        assertEvidenceCompleteness(variants);

        ChatClient mockClient = productionClient("diagnostic-key", mockBaseUrl, "deepseek-chat");
        List<ChatResponse> aResponses = finalStream(mockClient, variants.aPrompt());
        List<ChatResponse> bResponses = finalStream(mockClient, variants.bPrompt());

        assertThat(visibleText(aResponses)).isEqualTo("ABC");
        assertThat(visibleText(bResponses)).isEqualTo("ABC");
        assertThat(toolCallCount(aResponses)).isZero();
        assertThat(toolCallCount(bResponses)).isZero();
        assertThat(capturedBodies).hasSize(2);

        ObjectNode requestA = (ObjectNode) OBJECT_MAPPER.readTree(capturedBodies.get(0));
        ObjectNode requestB = (ObjectNode) OBJECT_MAPPER.readTree(capturedBodies.get(1));
        RequestStructure aStructure = structure(requestA);
        RequestStructure bStructure = structure(requestB);
        assertSameOptionsExceptMessages(requestA, requestB);
        assertThat(aStructure.assistantToolCallMessageCount()).isEqualTo(4);
        assertThat(aStructure.toolMessageCount()).isEqualTo(10);
        assertThat(bStructure.assistantToolCallMessageCount()).isZero();
        assertThat(bStructure.toolMessageCount()).isZero();

        writeStructureArtifact(variants, requestA, requestB, aStructure, bStructure);
    }

    @Test
    @EnabledIfSystemProperty(named = "final.context.sanitization.provider.enabled", matches = "true")
    void comparesProductionAndSanitizedFrozenContextsAgainstProvider() throws Exception {
        PropertySourcesPropertyResolver settings = localSettings();
        FrozenContext frozen = loadHistoricalContext(settings);
        ContextVariants variants = buildVariants(frozen);
        assertEvidenceCompleteness(variants);

        String model = settings.getRequiredProperty("jchatmind.ai.deepseek.official.model");
        ChatClient mockClient = productionClient("diagnostic-key", mockBaseUrl, model);
        assertThat(visibleText(finalStream(mockClient, variants.aPrompt()))).isEqualTo("ABC");
        assertThat(visibleText(finalStream(mockClient, variants.bPrompt()))).isEqualTo("ABC");
        assertThat(capturedBodies).hasSize(2);
        ObjectNode requestA = (ObjectNode) OBJECT_MAPPER.readTree(capturedBodies.get(0));
        ObjectNode requestB = (ObjectNode) OBJECT_MAPPER.readTree(capturedBodies.get(1));
        assertSameOptionsExceptMessages(requestA, requestB);

        byte[] bodyA = OBJECT_MAPPER.writeValueAsBytes(requestA);
        byte[] bodyB = OBJECT_MAPPER.writeValueAsBytes(requestB);
        String providerBaseUrl = settings.getRequiredProperty("jchatmind.ai.deepseek.official.base-url");
        String apiKey = settings.getRequiredProperty("jchatmind.ai.deepseek.official.api-key");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        Set<String> supportedTopics = topicCoverage(variants.evidence(), TOPICS);
        List<Observation> observations = new ArrayList<>();

        int sequenceIndex = 0;
        for (RunSpec run : SCHEDULE) {
            sequenceIndex++;
            byte[] body = "A".equals(run.variant()) ? bodyA : bodyB;
            RawProviderResult raw = sendAndInspect(client, providerBaseUrl, apiKey, body);
            Quality quality = assessQuality(raw.visibleText(), variants.evidence(), supportedTopics);
            observations.add(new Observation(run.variant(), run.run(), sequenceIndex,
                    raw.httpStatus(), raw.ttftMs(), raw.latencyMs(), raw.visibleDeltaCount(),
                    raw.visibleText().length(), raw.reasoningChars() > 0, raw.reasoningChars(),
                    raw.toolCallPresent(), raw.toolCallFrameCount(), raw.finishReason(),
                    raw.toolName(), raw.argumentsChars(), raw.argumentsSha256(), quality));
            if (raw.httpStatus() != 200) {
                throw new IllegalStateException("Provider experiment failed with HTTP " + raw.httpStatus());
            }
        }

        assertThat(observations).hasSize(10);
        writeProviderArtifacts(observations, model, variants, requestA, requestB, supportedTopics);
    }

    private FrozenContext deterministicFrozenContext() {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("Answer only from the supplied repository evidence."));
        messages.add(new UserMessage("Explain the FlashDeal seckill request flow."));
        int callIndex = 1;
        for (int batchSize : List.of(3, 3, 2, 2)) {
            List<AssistantMessage.ToolCall> calls = new ArrayList<>();
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            for (int item = 0; item < batchSize; item++) {
                String id = "fixture-call-" + callIndex;
                calls.add(toolCall(id, "searchProjectCode", "fixture-query-" + callIndex));
                responses.add(toolResponse(id, "searchProjectCode", switch (callIndex) {
                    case 1 -> "FlashDeal controller accepts the seckill request.";
                    case 2 -> "The service validates stock and order constraints.";
                    case 3 -> "Redis executes the Lua script atomically.";
                    case 4 -> "A message queue carries the accepted order.";
                    case 5 -> "The order service persists the order.";
                    case 6 -> "The mapper updates inventory state.";
                    case 7 -> "Cache keys identify the seckill activity.";
                    case 8 -> "The consumer handles the queued order.";
                    case 9 -> "Failure paths restore reserved stock.";
                    default -> "Repository evidence completes the seckill flow.";
                }));
                callIndex++;
            }
            messages.add(toolCallBatch(calls.toArray(AssistantMessage.ToolCall[]::new)));
            messages.add(toolResponses(responses.toArray(ToolResponseMessage.ToolResponse[]::new)));
        }
        List<Message> safeMessages = AgentMemoryHistorySanitizer.toSafeModelMessages(messages);
        return new FrozenContext(safeMessages, extractEvidence(safeMessages));
    }

    private FrozenContext loadHistoricalContext(PropertySourcesPropertyResolver settings) throws Exception {
        String url = settings.getRequiredProperty("spring.datasource.url");
        String username = settings.getRequiredProperty("spring.datasource.username");
        String password = settings.getRequiredProperty("spring.datasource.password");
        List<Message> messages = new ArrayList<>();
        Set<String> successfulToolCallIds = new HashSet<>();
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "select a.system_prompt from chat_session cs join agent a on a.id = cs.agent_id where cs.id = ?")) {
                statement.setObject(1, java.util.UUID.fromString(SESSION_ID));
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    String systemPrompt = resultSet.getString(1);
                    if (systemPrompt != null && !systemPrompt.isBlank()) {
                        messages.add(new SystemMessage(systemPrompt));
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "select tool_call_id from tool_call_log where task_id = ? and status = 'SUCCESS' order by created_at")) {
                statement.setObject(1, java.util.UUID.fromString(TASK_ID));
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        successfulToolCallIds.add(resultSet.getString(1));
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "select role, content, metadata::text from chat_message where session_id = ? order by created_at")) {
                statement.setObject(1, java.util.UUID.fromString(SESSION_ID));
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        messages.add(toMessage(resultSet.getString(1), resultSet.getString(2),
                                resultSet.getString(3)));
                    }
                }
            }
        }
        List<Message> safeMessages = AgentMemoryHistorySanitizer.toSafeModelMessages(messages);
        List<Evidence> evidence = extractEvidence(safeMessages);
        assertThat(successfulToolCallIds).hasSize(10);
        assertThat(evidence).hasSize(successfulToolCallIds.size());
        assertThat(evidence).allMatch(item -> successfulToolCallIds.contains(item.toolCallId()));
        return new FrozenContext(safeMessages, evidence);
    }

    private Message toMessage(String role, String content, String metadataJson) throws Exception {
        JsonNode metadata = metadataJson == null
                ? OBJECT_MAPPER.createObjectNode() : OBJECT_MAPPER.readTree(metadataJson);
        return switch (role) {
            case "system" -> new SystemMessage(content);
            case "user" -> new UserMessage(content);
            case "assistant" -> AssistantMessage.builder()
                    .content(content == null ? "" : content)
                    .toolCalls(readToolCalls(metadata.path("toolCalls")))
                    .build();
            case "tool" -> ToolResponseMessage.builder()
                    .responses(List.of(readToolResponse(metadata.path("toolResponse"))))
                    .build();
            default -> throw new IllegalStateException("Unsupported frozen message role: " + role);
        };
    }

    private List<AssistantMessage.ToolCall> readToolCalls(JsonNode toolCalls) {
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        toolCalls.forEach(call -> calls.add(new AssistantMessage.ToolCall(
                call.path("id").asText(), call.path("type").asText(),
                call.path("name").asText(), call.path("arguments").asText())));
        return calls;
    }

    private ToolResponseMessage.ToolResponse readToolResponse(JsonNode response) {
        return new ToolResponseMessage.ToolResponse(
                response.path("id").asText(), response.path("name").asText(),
                response.path("responseData").asText());
    }

    private List<Evidence> extractEvidence(List<Message> messages) {
        List<Evidence> evidence = new ArrayList<>();
        for (Message message : messages) {
            if (message instanceof ToolResponseMessage toolResponseMessage) {
                for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                    evidence.add(new Evidence(evidence.size() + 1, response.id(), response.name(),
                            response.responseData()));
                }
            }
        }
        return evidence;
    }

    private ContextVariants buildVariants(FrozenContext frozen) throws Exception {
        FinalSynthesisRequest request = new FinalSynthesisRequestFactory().create(frozen.messages());
        List<Message> compiled = new FinalContextCompiler().compile(request);
        List<String> extractedSources = evidenceContents(request);
        String aFingerprint = evidenceFingerprint(frozen.evidence().stream().map(Evidence::content).toList());
        String bFingerprint = evidenceFingerprint(extractedSources);
        return new ContextVariants(finalPrompt(frozen.messages()), finalPrompt(compiled),
                frozen.evidence(), extractedSources, aFingerprint, bFingerprint,
                frozen.evidence().stream().mapToInt(item -> item.content().length()).sum(),
                extractedSources.stream().mapToInt(String::length).sum());
    }

    private void assertEvidenceCompleteness(ContextVariants variants) {
        assertThat(variants.extractedSources()).hasSize(variants.evidence().size());
        assertThat(variants.bEvidenceChars()).isEqualTo(variants.aEvidenceChars());
        assertThat(variants.bEvidenceFingerprint()).isEqualTo(variants.aEvidenceFingerprint());
        assertThat(variants.extractedSources())
                .containsExactlyElementsOf(variants.evidence().stream().map(Evidence::content).toList());
    }

    private Prompt finalPrompt(List<Message> messages) {
        return Prompt.builder()
                .chatOptions(DefaultToolCallingChatOptions.builder()
                        .internalToolExecutionEnabled(false)
                        .build())
                .messages(messages)
                .build();
    }

    private ChatClient productionClient(String apiKey, String baseUrl, String model) {
        return new MultiChatClientConfig().deepSeekOfficialChatClient(apiKey, baseUrl, model);
    }

    private List<ChatResponse> finalStream(ChatClient client, Prompt prompt) {
        return client.prompt(prompt)
                .toolCallbacks(new ToolCallback[0])
                .stream()
                .chatResponse()
                .collectList()
                .block(Duration.ofSeconds(120));
    }

    private String visibleText(List<ChatResponse> responses) {
        return responses.stream()
                .map(ChatResponse::getResult)
                .filter(result -> result != null && result.getOutput() != null)
                .map(result -> result.getOutput().getText())
                .filter(text -> text != null && !text.isEmpty())
                .reduce("", String::concat);
    }

    private long toolCallCount(List<ChatResponse> responses) {
        return responses.stream()
                .map(ChatResponse::getResult)
                .filter(result -> result != null && result.getOutput() != null)
                .map(result -> result.getOutput().getToolCalls())
                .filter(calls -> calls != null)
                .mapToLong(List::size)
                .sum();
    }

    private RequestStructure structure(ObjectNode request) {
        ArrayNode messages = (ArrayNode) request.path("messages");
        List<String> roles = new ArrayList<>();
        int assistantToolCalls = 0;
        int tools = 0;
        for (JsonNode message : messages) {
            String role = message.path("role").asText();
            boolean hasToolCalls = message.path("tool_calls").isArray()
                    && !message.path("tool_calls").isEmpty();
            roles.add(hasToolCalls ? role + "(tool_calls)" : role);
            if ("assistant".equals(role) && hasToolCalls) {
                assistantToolCalls++;
            }
            if ("tool".equals(role)) {
                tools++;
            }
        }
        return new RequestStructure(messages.size(), roles, assistantToolCalls, tools);
    }

    private void assertSameOptionsExceptMessages(ObjectNode requestA, ObjectNode requestB) throws Exception {
        assertThat(requestA.has("tools")).isFalse();
        assertThat(requestB.has("tools")).isFalse();
        assertThat(requestA.has("tool_choice")).isFalse();
        assertThat(requestB.has("tool_choice")).isFalse();
        ObjectNode withoutMessagesA = requestA.deepCopy();
        ObjectNode withoutMessagesB = requestB.deepCopy();
        withoutMessagesA.remove("messages");
        withoutMessagesB.remove("messages");
        assertThat(withoutMessagesB).isEqualTo(withoutMessagesA);
    }

    private RawProviderResult sendAndInspect(HttpClient client, String baseUrl,
                                             String apiKey, byte[] body) throws Exception {
        URI endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions");
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(180))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        long startedAt = System.nanoTime();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        RawAccumulator accumulator = new RawAccumulator(startedAt, response.statusCode());
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                accumulator.accept(line);
            }
        }
        return accumulator.finish();
    }

    private Quality assessQuality(String answer, List<Evidence> evidence, Set<String> supportedTopics) {
        Set<String> covered = topicCoverage(answer, TOPICS);
        covered.retainAll(supportedTopics);
        Set<String> evidenceCoverage = topicCoverage(evidence, TOPICS);
        Set<String> unsupportedClaims = new HashSet<>();
        String normalizedAnswer = answer.toLowerCase(Locale.ROOT);
        String evidenceText = evidence.stream().map(Evidence::content)
                .reduce("", (left, right) -> left + "\n" + right).toLowerCase(Locale.ROOT);
        for (Topic probe : UNSUPPORTED_CLAIM_PROBES) {
            if (containsAny(normalizedAnswer, probe.keywords())
                    && !containsAny(evidenceText, probe.keywords())) {
                unsupportedClaims.add(probe.name());
            }
        }
        boolean tooShort = answer.length() < 200;
        boolean answersQuestion = !tooShort && !covered.isEmpty();
        return new Quality(answer.length(), covered, evidenceCoverage, unsupportedClaims,
                tooShort, answersQuestion);
    }

    private Set<String> topicCoverage(List<Evidence> evidence, List<Topic> topics) {
        String text = evidence.stream().map(Evidence::content)
                .reduce("", (left, right) -> left + "\n" + right);
        return topicCoverage(text, topics);
    }

    private Set<String> topicCoverage(String text, List<Topic> topics) {
        String normalized = text.toLowerCase(Locale.ROOT);
        Set<String> result = new HashSet<>();
        for (Topic topic : topics) {
            if (containsAny(normalized, topic.keywords())) {
                result.add(topic.name());
            }
        }
        return result;
    }

    private boolean containsAny(String text, List<String> keywords) {
        return keywords.stream().anyMatch(keyword -> text.contains(keyword.toLowerCase(Locale.ROOT)));
    }

    private void writeStructureArtifact(ContextVariants variants, ObjectNode requestA, ObjectNode requestB,
                                        RequestStructure a, RequestStructure b) throws Exception {
        Files.createDirectories(OUTPUT_DIR);
        ObjectNode summary = OBJECT_MAPPER.createObjectNode();
        summary.put("sessionId", SESSION_ID);
        summary.put("taskId", TASK_ID);
        summary.put("aRequestFingerprint", canonicalFingerprint(requestA));
        summary.put("bRequestFingerprint", canonicalFingerprint(requestB));
        summary.put("evidenceCountA", variants.evidence().size());
        summary.put("evidenceCountB", variants.extractedSources().size());
        summary.put("evidenceCharsA", variants.aEvidenceChars());
        summary.put("evidenceCharsB", variants.bEvidenceChars());
        summary.put("evidenceFingerprintA", variants.aEvidenceFingerprint());
        summary.put("evidenceFingerprintB", variants.bEvidenceFingerprint());
        summary.put("evidenceCompletenessPass",
                variants.aEvidenceFingerprint().equals(variants.bEvidenceFingerprint()));
        summary.set("aStructure", OBJECT_MAPPER.valueToTree(a));
        summary.set("bStructure", OBJECT_MAPPER.valueToTree(b));
        summary.put("mockVisibleA", "ABC");
        summary.put("mockVisibleB", "ABC");
        summary.put("mockToolCallsA", 0);
        summary.put("mockToolCallsB", 0);
        Files.writeString(STRUCTURE_PATH,
                OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(summary),
                StandardCharsets.UTF_8);
    }

    private void writeProviderArtifacts(List<Observation> observations, String model,
                                        ContextVariants variants, ObjectNode requestA,
                                        ObjectNode requestB, Set<String> supportedTopics) throws Exception {
        Files.createDirectories(OUTPUT_DIR);
        List<String> csv = new ArrayList<>();
        csv.add("variant,run,sequenceIndex,httpStatus,ttftMs,latencyMs,visibleDeltaCount,visibleChars,"
                + "reasoningPresent,reasoningChars,rawToolCallPresent,rawToolCallFrameCount,finishReason,"
                + "toolName,argumentsChars,argumentsSHA256,coverageCount,coverageTopics,unsupportedClaims,"
                + "tooShort,answersQuestion");
        observations.forEach(item -> csv.add(item.toCsv()));
        Files.write(CSV_PATH, csv, StandardCharsets.UTF_8);

        VariantSummary a = summarize("A", observations);
        VariantSummary b = summarize("B", observations);
        String recommendation;
        if (b.rawToolCalls() > 0) {
            recommendation = "Do not implement production sanitization; sanitized context still produced a raw tool call.";
        } else if (b.meanCoverage() + 0.001 < a.meanCoverage() - 1
                || b.answersQuestion() < a.answersQuestion()
                || b.tooShort() > a.tooShort()) {
            recommendation = "Do not implement production sanitization; answer quality regressed.";
        } else {
            recommendation = "Implement production Final Context Sanitization as structural hardening, not as a statistically proven bug fix.";
        }
        String markdown = "# Frozen Final Context Sanitization Experiment\n\n"
                + "- Session: `" + SESSION_ID + "`\n"
                + "- Task: `" + TASK_ID + "`\n"
                + "- Model: `" + model + "`\n"
                + "- Calls: 10; retry: 0; concurrency: 1\n"
                + "- Schedule: A1, B1, B2, A2, A3, B3, B4, A4, A5, B5\n"
                + "- A request fingerprint: `" + canonicalFingerprint(requestA) + "`\n"
                + "- B request fingerprint: `" + canonicalFingerprint(requestB) + "`\n"
                + "- Evidence fingerprint: `" + variants.aEvidenceFingerprint() + "`\n"
                + "- Supported topics: `" + String.join("|", supportedTopics.stream().sorted().toList()) + "`\n\n"
                + "| Variant | Calls | Success | Raw Tool Calls | Mean TTFT ms | Mean latency ms | Visible chars | Reasoning chars | Mean coverage | Unsupported claims | Too short | Answers question |\n"
                + "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n"
                + a.toMarkdownRow() + "\n" + b.toMarkdownRow() + "\n\n"
                + "## Recommendation\n\n" + recommendation + "\n";
        Files.writeString(REPORT_PATH, markdown, StandardCharsets.UTF_8);
    }

    private VariantSummary summarize(String variant, List<Observation> observations) {
        List<Observation> selected = observations.stream()
                .filter(item -> variant.equals(item.variant())).toList();
        return new VariantSummary(variant, selected.size(),
                selected.stream().filter(Observation::success).count(),
                selected.stream().filter(Observation::rawToolCallPresent).count(),
                selected.stream().filter(item -> item.ttftMs() != null)
                        .mapToLong(Observation::ttftMs).average().orElse(0),
                selected.stream().mapToLong(Observation::latencyMs).average().orElse(0),
                selected.stream().mapToInt(Observation::visibleChars).sum(),
                selected.stream().mapToInt(Observation::reasoningChars).sum(),
                selected.stream().mapToInt(item -> item.quality().coveredTopics().size()).average().orElse(0),
                selected.stream().mapToInt(item -> item.quality().unsupportedClaims().size()).sum(),
                selected.stream().filter(item -> item.quality().tooShort()).count(),
                selected.stream().filter(item -> item.quality().answersQuestion()).count());
    }

    private PropertySourcesPropertyResolver localSettings() throws IOException {
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader().load(
                "application-local", new FileSystemResource("application-local.yaml"));
        MutablePropertySources sources = new MutablePropertySources();
        loaded.forEach(sources::addLast);
        return new PropertySourcesPropertyResolver(sources);
    }

    private String evidenceFingerprint(List<String> contents) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String content : contents) {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private AssistantMessage.ToolCall toolCall(String id, String name, String arguments) {
        return new AssistantMessage.ToolCall(id, "function", name, arguments);
    }

    private AssistantMessage toolCallBatch(AssistantMessage.ToolCall... toolCalls) {
        return AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(toolCalls))
                .build();
    }

    private ToolResponseMessage.ToolResponse toolResponse(String id, String name, String content) {
        return new ToolResponseMessage.ToolResponse(id, name, content);
    }

    private ToolResponseMessage toolResponses(ToolResponseMessage.ToolResponse... responses) {
        return ToolResponseMessage.builder()
                .responses(List.of(responses))
                .build();
    }

    private void assertToolProtocolPurity(List<Message> messages) {
        assertThat(messages).noneMatch(ToolResponseMessage.class::isInstance);
        assertThat(messages.stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .flatMap(message -> message.getToolCalls().stream()))
                .isEmpty();
    }

    private List<String> evidenceContents(FinalSynthesisRequest request) {
        return request.evidenceBatches().stream()
                .flatMap(batch -> batch.evidence().stream())
                .map(FinalEvidence::content)
                .toList();
    }

    private List<AssistantMessage> evidenceBatchMessages(List<Message> messages) {
        return messages.stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .filter(message -> message.getText().startsWith("[FINAL_EVIDENCE_BATCH]"))
                .toList();
    }

    private List<String> extractEvidenceFromBatches(List<Message> messages) {
        return evidenceBatchMessages(messages).stream()
                .flatMap(message -> extractEvidenceFromBatch(message.getText()).stream())
                .toList();
    }

    private List<String> extractEvidenceFromBatch(String content) {
        List<String> evidence = new ArrayList<>();
        int cursor = 0;
        while (true) {
            int lengthStart = content.indexOf("Content-Characters: ", cursor);
            if (lengthStart < 0) {
                return evidence;
            }
            int lengthEnd = content.indexOf('\n', lengthStart);
            int sourceStart = content.indexOf("Content:\n", lengthEnd) + "Content:\n".length();
            int chars = Integer.parseInt(content.substring(
                    lengthStart + "Content-Characters: ".length(), lengthEnd));
            evidence.add(content.substring(sourceStart, sourceStart + chars));
            cursor = sourceStart + chars;
        }
    }

    private String canonicalFingerprint(JsonNode node) throws Exception {
        return sha256(OBJECT_MAPPER.writeValueAsBytes(sortRecursively(node)));
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

    private byte[] normalMockResponse() {
        return ("data: {\"id\":\"mock-1\",\"object\":\"chat.completion.chunk\","
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
    }

    private void handleChatCompletions(HttpExchange exchange) throws IOException {
        capturedBodies.add(exchange.getRequestBody().readAllBytes());
        byte[] response = mockResponse.get();
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private final class RawAccumulator {
        private final long startedAt;
        private final int httpStatus;
        private final StringBuilder visible = new StringBuilder();
        private final Map<Integer, RawToolCall> toolCalls = new HashMap<>();
        private Long ttftMs;
        private int visibleDeltaCount;
        private int reasoningChars;
        private int toolCallFrameCount;
        private String finishReason;

        private RawAccumulator(long startedAt, int httpStatus) {
            this.startedAt = startedAt;
            this.httpStatus = httpStatus;
        }

        private void accept(String line) throws Exception {
            if (!line.startsWith("data:")) {
                return;
            }
            String data = line.substring(5).trim();
            if (data.isEmpty() || "[DONE]".equals(data)) {
                return;
            }
            JsonNode frame = OBJECT_MAPPER.readTree(data);
            for (JsonNode choice : frame.path("choices")) {
                JsonNode delta = choice.path("delta");
                String content = delta.path("content").asText("");
                if (!content.isEmpty()) {
                    if (ttftMs == null) {
                        ttftMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
                    }
                    visibleDeltaCount++;
                    visible.append(content);
                }
                reasoningChars += delta.path("reasoning_content").asText("").length();
                JsonNode calls = delta.path("tool_calls");
                if (calls.isArray() && !calls.isEmpty()) {
                    toolCallFrameCount++;
                }
                for (JsonNode call : calls) {
                    RawToolCall aggregate = toolCalls.computeIfAbsent(call.path("index").asInt(0),
                            ignored -> new RawToolCall());
                    aggregate.name.append(call.path("function").path("name").asText(""));
                    aggregate.arguments.append(call.path("function").path("arguments").asText(""));
                }
                if (!choice.path("finish_reason").isNull()
                        && !choice.path("finish_reason").isMissingNode()) {
                    finishReason = choice.path("finish_reason").asText();
                }
            }
        }

        private RawProviderResult finish() throws Exception {
            long latencyMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            RawToolCall first = toolCalls.entrySet().stream().min(Map.Entry.comparingByKey())
                    .map(Map.Entry::getValue).orElse(null);
            return new RawProviderResult(httpStatus, ttftMs, latencyMs, visibleDeltaCount,
                    visible.toString(), reasoningChars, !toolCalls.isEmpty(), toolCallFrameCount,
                    finishReason, first == null ? null : first.name.toString(),
                    first == null ? 0 : first.arguments.length(),
                    first == null ? null : sha256(first.arguments.toString().getBytes(StandardCharsets.UTF_8)));
        }
    }

    private static final class RawToolCall {
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }

    private record FrozenContext(List<Message> messages, List<Evidence> evidence) {
    }

    private record Evidence(int index, String toolCallId, String toolName, String content) {
    }

    private record ContextVariants(Prompt aPrompt, Prompt bPrompt, List<Evidence> evidence,
                                   List<String> extractedSources, String aEvidenceFingerprint,
                                   String bEvidenceFingerprint, int aEvidenceChars, int bEvidenceChars) {
    }

    private record RequestStructure(int messageCount, List<String> roleSequence,
                                    int assistantToolCallMessageCount, int toolMessageCount) {
    }

    private record RunSpec(String variant, int run) {
    }

    private record Topic(String name, List<String> keywords) {
    }

    private record Quality(int answerChars, Set<String> coveredTopics, Set<String> evidenceTopics,
                           Set<String> unsupportedClaims, boolean tooShort, boolean answersQuestion) {
    }

    private record RawProviderResult(int httpStatus, Long ttftMs, long latencyMs,
                                     int visibleDeltaCount, String visibleText, int reasoningChars,
                                     boolean toolCallPresent, int toolCallFrameCount,
                                     String finishReason, String toolName, int argumentsChars,
                                     String argumentsSha256) {
    }

    private record Observation(String variant, int run, int sequenceIndex, int httpStatus,
                               Long ttftMs, long latencyMs, int visibleDeltaCount, int visibleChars,
                               boolean reasoningPresent, int reasoningChars, boolean rawToolCallPresent,
                               int rawToolCallFrameCount, String finishReason, String toolName,
                               int argumentsChars, String argumentsSha256, Quality quality) {

        private boolean success() {
            return httpStatus == 200 && !rawToolCallPresent && visibleChars > 0
                    && "stop".equalsIgnoreCase(finishReason);
        }

        private String toCsv() {
            return String.join(",", variant, Integer.toString(run), Integer.toString(sequenceIndex),
                    Integer.toString(httpStatus), ttftMs == null ? "" : ttftMs.toString(),
                    Long.toString(latencyMs), Integer.toString(visibleDeltaCount),
                    Integer.toString(visibleChars), Boolean.toString(reasoningPresent),
                    Integer.toString(reasoningChars), Boolean.toString(rawToolCallPresent),
                    Integer.toString(rawToolCallFrameCount), safe(finishReason), safe(toolName),
                    Integer.toString(argumentsChars), safe(argumentsSha256),
                    Integer.toString(quality.coveredTopics().size()),
                    safe(String.join("|", quality.coveredTopics().stream().sorted().toList())),
                    safe(String.join("|", quality.unsupportedClaims().stream().sorted().toList())),
                    Boolean.toString(quality.tooShort()), Boolean.toString(quality.answersQuestion()));
        }

        private static String safe(String value) {
            return value == null ? "" : value.replace(",", "_").replace("\n", " ").replace("\r", " ");
        }
    }

    private record VariantSummary(String variant, int calls, long success, long rawToolCalls,
                                  double meanTtftMs, double meanLatencyMs, int visibleChars,
                                  int reasoningChars, double meanCoverage, int unsupportedClaims,
                                  long tooShort, long answersQuestion) {

        private String toMarkdownRow() {
            return String.format(Locale.ROOT,
                    "| %s | %d | %d | %d | %.1f | %.1f | %d | %d | %.1f | %d | %d | %d |",
                    variant, calls, success, rawToolCalls, meanTtftMs, meanLatencyMs,
                    visibleChars, reasoningChars, meanCoverage, unsupportedClaims,
                    tooShort, answersQuestion);
        }
    }
}
