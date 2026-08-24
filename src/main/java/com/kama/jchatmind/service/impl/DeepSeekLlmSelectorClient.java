package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.model.dto.SelectorModelResponse;
import com.kama.jchatmind.service.LlmSelectorClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Objects;

@Component
@ConditionalOnProperty(prefix = "jchatmind.code-rag.llm-selector", name = "client-type",
        havingValue = "DEEPSEEK_HTTP", matchIfMissing = true)
public class DeepSeekLlmSelectorClient implements LlmSelectorClient {
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String model;

    public DeepSeekLlmSelectorClient(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${jchatmind.ai.deepseek.official.base-url}") String baseUrl,
            @Value("${jchatmind.ai.deepseek.official.api-key}") String apiKey,
            @Value("${jchatmind.ai.deepseek.official.model}") String model) {
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalArgumentException("DeepSeek base URL must not be blank");
        }
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalArgumentException("DeepSeek API key must not be blank");
        }
        if (!StringUtils.hasText(model)) {
            throw new IllegalArgumentException("DeepSeek provider model must not be blank");
        }
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.model = model;
        this.restClient = Objects.requireNonNull(restClientBuilder, "restClientBuilder must not be null")
                .baseUrl(removeTrailingSlash(baseUrl))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public SelectorModelResponse call(String prompt) {
        Objects.requireNonNull(prompt, "prompt must not be null");
        ChatCompletionRequest request = new ChatCompletionRequest(
                model,
                List.of(new RequestMessage("user", prompt)),
                false,
                new Thinking("disabled"));

        String responseBody = restClient.post()
                .uri(CHAT_COMPLETIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(writeRequest(request))
                .retrieve()
                .body(String.class);

        return mapResponse(readResponse(responseBody));
    }

    private String writeRequest(ChatCompletionRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize DeepSeek selector request", e);
        }
    }

    private ChatCompletionResponse readResponse(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            throw new IllegalStateException("DeepSeek selector returned an empty response body");
        }
        try {
            return objectMapper.readValue(responseBody, ChatCompletionResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize DeepSeek selector response", e);
        }
    }

    private SelectorModelResponse mapResponse(ChatCompletionResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new IllegalStateException("DeepSeek selector response contains no choices");
        }
        Choice choice = response.choices().get(0);
        if (choice == null || choice.message() == null) {
            throw new IllegalStateException("DeepSeek selector response is missing the assistant message");
        }
        if (choice.message().content() == null) {
            throw new IllegalStateException("DeepSeek selector response is missing assistant content");
        }

        String reasoningContent = choice.message().reasoningContent();
        Usage usage = response.usage();
        return new SelectorModelResponse(
                choice.message().content(),
                reasoningContent == null ? 0 : reasoningContent.length(),
                StringUtils.hasLength(reasoningContent),
                usage == null ? null : usage.promptTokens(),
                usage == null ? null : usage.completionTokens(),
                usage == null ? null : usage.totalTokens(),
                choice.finishReason());
    }

    private String removeTrailingSlash(String baseUrl) {
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private record ChatCompletionRequest(String model,
                                         List<RequestMessage> messages,
                                         boolean stream,
                                         Thinking thinking) {
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
