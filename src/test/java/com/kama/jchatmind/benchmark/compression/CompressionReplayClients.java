package com.kama.jchatmind.benchmark.compression;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.service.ConversationSummaryClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

final class CompressionReplayClients {
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private CompressionReplayClients() {
    }

    enum Variant {
        GPT_BASELINE,
        DS_THINKING,
        DS_NON_THINKING
    }

    enum ThinkingMode {
        ENABLED("enabled"),
        DISABLED("disabled"),
        UNAVAILABLE(null);

        private final String wireValue;

        ThinkingMode(String wireValue) {
            this.wireValue = wireValue;
        }
    }

    record Invocation(
            int callIndex,
            String promptSha256,
            int inputChars,
            Integer actualInputTokens,
            long latencyMs,
            int outputChars,
            int estimatedOutputTokens,
            Integer actualOutputTokens,
            boolean reasoningContentPresent,
            int reasoningChars,
            int estimatedReasoningTokens,
            String finishReason) {
    }

    interface Client extends ConversationSummaryClient {
        Variant variant();

        String provider();

        String providerModel();

        ThinkingMode thinkingMode();

        boolean thinkingWireVerified();

        List<Invocation> invocations();
    }

    static Client gpt(ChatClient chatClient, String model, int charsPerToken) {
        return new GptClient(chatClient, model, charsPerToken);
    }

    static Client deepSeek(RestClient.Builder builder,
                           ObjectMapper objectMapper,
                           String baseUrl,
                           String apiKey,
                           String model,
                           ThinkingMode thinkingMode,
                           Integer maxTokens,
                           int charsPerToken) {
        return new DeepSeekClient(builder, objectMapper, baseUrl, apiKey, model,
                thinkingMode, maxTokens, charsPerToken);
    }

    static String deepSeekRequestJson(ObjectMapper objectMapper,
                                      String model,
                                      ThinkingMode thinkingMode,
                                      Integer maxTokens,
                                      String prompt) {
        return writeJson(objectMapper, request(model, thinkingMode, maxTokens, prompt));
    }

    static boolean verifyDeepSeekWireContract(ObjectMapper objectMapper,
                                               String model,
                                               ThinkingMode thinkingMode,
                                               Integer maxTokens) {
        if (thinkingMode == ThinkingMode.UNAVAILABLE) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(deepSeekRequestJson(
                    objectMapper, model, thinkingMode, maxTokens, "wire verification"));
            return model.equals(root.path("model").asText())
                    && thinkingMode.wireValue.equals(root.path("thinking").path("type").asText())
                    && root.path("messages").size() == 1
                    && "user".equals(root.path("messages").get(0).path("role").asText())
                    && !root.has("temperature")
                    && !root.has("top_p")
                    && (maxTokens == null ? !root.has("max_tokens")
                    : maxTokens == root.path("max_tokens").asInt());
        } catch (JsonProcessingException error) {
            return false;
        }
    }

    private static ChatCompletionRequest request(String model,
                                                  ThinkingMode thinkingMode,
                                                  Integer maxTokens,
                                                  String prompt) {
        if (thinkingMode == ThinkingMode.UNAVAILABLE) {
            throw new IllegalArgumentException("DeepSeek thinking mode must be explicit");
        }
        return new ChatCompletionRequest(
                model,
                List.of(new RequestMessage("user", prompt)),
                false,
                new Thinking(thinkingMode.wireValue),
                maxTokens);
    }

    private static String writeJson(ObjectMapper objectMapper, Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Failed to serialize compression replay request", error);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static int estimateTokens(String value, int charsPerToken) {
        if (!StringUtils.hasLength(value)) {
            return 0;
        }
        return (int) Math.ceil((double) value.length() / Math.max(1, charsPerToken));
    }

    private static final class GptClient implements Client {
        private final ChatClient chatClient;
        private final String model;
        private final int charsPerToken;
        private final List<Invocation> invocations = new ArrayList<>();

        private GptClient(ChatClient chatClient, String model, int charsPerToken) {
            this.chatClient = Objects.requireNonNull(chatClient);
            this.model = model;
            this.charsPerToken = charsPerToken;
        }

        @Override
        public String summarize(String ignoredMeasurementModel, String prompt) {
            long started = System.nanoTime();
            try {
                String content = chatClient.prompt().user(prompt).call().content();
                long latencyMs = (System.nanoTime() - started) / 1_000_000;
                String safeContent = content == null ? "" : content;
                invocations.add(new Invocation(
                        invocations.size() + 1, sha256(prompt), prompt.length(), null,
                        latencyMs, safeContent.length(), estimateTokens(safeContent, charsPerToken),
                        null, false, 0, 0, null));
                return content;
            } catch (RuntimeException error) {
                invocations.add(failedInvocation(prompt, started, error));
                throw error;
            }
        }

        @Override
        public Variant variant() {
            return Variant.GPT_BASELINE;
        }

        @Override
        public String provider() {
            return "GPT_COMPATIBLE_SPRING_AI_DEEPSEEK_CLIENT";
        }

        @Override
        public String providerModel() {
            return model;
        }

        @Override
        public ThinkingMode thinkingMode() {
            return ThinkingMode.UNAVAILABLE;
        }

        @Override
        public boolean thinkingWireVerified() {
            return false;
        }

        @Override
        public List<Invocation> invocations() {
            return List.copyOf(invocations);
        }
    }

    private static final class DeepSeekClient implements Client {
        private final RestClient restClient;
        private final ObjectMapper objectMapper;
        private final String model;
        private final ThinkingMode thinkingMode;
        private final Integer maxTokens;
        private final int charsPerToken;
        private final boolean wireVerified;
        private final List<Invocation> invocations = new ArrayList<>();

        private DeepSeekClient(RestClient.Builder builder,
                               ObjectMapper objectMapper,
                               String baseUrl,
                               String apiKey,
                               String model,
                               ThinkingMode thinkingMode,
                               Integer maxTokens,
                               int charsPerToken) {
            if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(apiKey)
                    || !StringUtils.hasText(model)) {
                throw new IllegalArgumentException("DeepSeek replay configuration is incomplete");
            }
            this.objectMapper = Objects.requireNonNull(objectMapper);
            this.model = model;
            this.thinkingMode = Objects.requireNonNull(thinkingMode);
            this.maxTokens = maxTokens;
            this.charsPerToken = charsPerToken;
            this.wireVerified = verifyDeepSeekWireContract(objectMapper, model, thinkingMode, maxTokens);
            if (!wireVerified) {
                throw new IllegalStateException("DeepSeek thinking wire contract verification failed");
            }
            this.restClient = Objects.requireNonNull(builder)
                    .baseUrl(removeTrailingSlash(baseUrl))
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .build();
        }

        @Override
        public String summarize(String ignoredMeasurementModel, String prompt) {
            ChatCompletionRequest request = request(model, thinkingMode, maxTokens, prompt);
            long started = System.nanoTime();
            try {
                String responseBody = restClient.post()
                        .uri(CHAT_COMPLETIONS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(writeJson(objectMapper, request))
                        .retrieve()
                        .body(String.class);
                long latencyMs = (System.nanoTime() - started) / 1_000_000;
                ChatCompletionResponse response = readResponse(responseBody);
                Choice choice = requireChoice(response);
                String content = choice.message().content();
                String reasoning = choice.message().reasoningContent();
                Usage usage = response.usage();
                invocations.add(new Invocation(
                        invocations.size() + 1, sha256(prompt), prompt.length(),
                        usage == null ? null : usage.promptTokens(), latencyMs,
                        content.length(), estimateTokens(content, charsPerToken),
                        usage == null ? null : usage.completionTokens(),
                        StringUtils.hasLength(reasoning), reasoning == null ? 0 : reasoning.length(),
                        estimateTokens(reasoning, charsPerToken), choice.finishReason()));
                return content;
            } catch (RuntimeException error) {
                invocations.add(failedInvocation(prompt, started, error));
                throw error;
            }
        }

        private ChatCompletionResponse readResponse(String body) {
            if (!StringUtils.hasText(body)) {
                throw new IllegalStateException("DeepSeek compression replay returned an empty response");
            }
            try {
                return objectMapper.readValue(body, ChatCompletionResponse.class);
            } catch (JsonProcessingException error) {
                throw new IllegalStateException("Failed to deserialize DeepSeek compression replay response", error);
            }
        }

        private Choice requireChoice(ChatCompletionResponse response) {
            if (response == null || response.choices() == null || response.choices().isEmpty()
                    || response.choices().get(0) == null
                    || response.choices().get(0).message() == null
                    || response.choices().get(0).message().content() == null) {
                throw new IllegalStateException("DeepSeek compression replay response has no assistant content");
            }
            return response.choices().get(0);
        }

        @Override
        public Variant variant() {
            return thinkingMode == ThinkingMode.ENABLED
                    ? Variant.DS_THINKING : Variant.DS_NON_THINKING;
        }

        @Override
        public String provider() {
            return "DEEPSEEK_HTTP";
        }

        @Override
        public String providerModel() {
            return model;
        }

        @Override
        public ThinkingMode thinkingMode() {
            return thinkingMode;
        }

        @Override
        public boolean thinkingWireVerified() {
            return wireVerified;
        }

        @Override
        public List<Invocation> invocations() {
            return List.copyOf(invocations);
        }
    }

    private static String removeTrailingSlash(String value) {
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static Invocation failedInvocation(String prompt, long startedNanos, RuntimeException error) {
        return new Invocation(
                1, sha256(prompt), prompt.length(), null,
                (System.nanoTime() - startedNanos) / 1_000_000,
                0, 0, null, false, 0, 0,
                "ERROR:" + error.getClass().getSimpleName());
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ChatCompletionRequest(
            String model,
            List<RequestMessage> messages,
            boolean stream,
            Thinking thinking,
            @JsonProperty("max_tokens") Integer maxTokens) {
    }

    private record RequestMessage(String role, String content) {
    }

    private record Thinking(String type) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ChatCompletionResponse(List<Choice> choices, Usage usage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Choice(ResponseMessage message,
                          @JsonProperty("finish_reason") String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ResponseMessage(String content,
                                   @JsonProperty("reasoning_content") String reasoningContent) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Usage(@JsonProperty("prompt_tokens") Integer promptTokens,
                         @JsonProperty("completion_tokens") Integer completionTokens,
                         @JsonProperty("total_tokens") Integer totalTokens) {
    }
}
