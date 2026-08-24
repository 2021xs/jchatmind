package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.config.TokenCounterProperties;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.service.TokenCounter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Slf4j
@Primary
@Component
public class HttpTokenCounter implements TokenCounter {
    private static final String SOURCE = "TOKENIZER_SERVICE";

    private final TokenCounterProperties properties;
    private final EstimatedTokenCounter fallback;
    private final RestTemplate restTemplate;

    public HttpTokenCounter(TokenCounterProperties properties,
                            EstimatedTokenCounter fallback,
                            RestTemplateBuilder restTemplateBuilder) {
        this.properties = properties;
        this.fallback = fallback;
        this.restTemplate = restTemplateBuilder
                .connectTimeout(properties.getTimeout())
                .readTimeout(properties.getTimeout())
                .build();
    }

    @Override
    public TokenCount countMessages(String model, List<ChatMessageDTO> messages) {
        if (!isAvailable()) {
            return fallback.countMessages(model, messages);
        }
        try {
            List<TokenMessage> tokenMessages = messages == null ? List.of() : messages.stream()
                    .map(message -> new TokenMessage(
                            message.getRole() == null ? "unknown" : message.getRole().getRole(),
                            message.getContent() == null ? "" : message.getContent()))
                    .toList();
            TokenizeResponse response = restTemplate.postForObject(
                    endpoint("/v1/tokenize/chat"),
                    new ChatTokenizeRequest(model, tokenMessages),
                    TokenizeResponse.class);
            return toTokenCount(response, () -> fallbackOrUnavailable(model, messages));
        } catch (Exception e) {
            log.warn("Tokenizer service message count failed, fallback to estimated tokens: model={}, error={}",
                    model, e.getMessage());
            return fallbackOrUnavailable(model, messages);
        }
    }

    @Override
    public TokenCount countText(String model, String text) {
        if (!isAvailable()) {
            return fallback.countText(model, text);
        }
        try {
            TokenizeResponse response = restTemplate.postForObject(
                    endpoint("/v1/tokenize/text"),
                    new TextTokenizeRequest(model, text == null ? "" : text),
                    TokenizeResponse.class);
            return toTokenCount(response, () -> fallbackOrUnavailable(model, text));
        } catch (Exception e) {
            log.warn("Tokenizer service text count failed, fallback to estimated tokens: model={}, error={}",
                    model, e.getMessage());
            return fallbackOrUnavailable(model, text);
        }
    }

    private boolean isAvailable() {
        return properties.isEnabled() && StringUtils.hasText(properties.getBaseUrl());
    }

    private TokenCount fallbackOrUnavailable(String model, List<ChatMessageDTO> messages) {
        return properties.isFallbackToEstimated()
                ? fallback.countMessages(model, messages)
                : new TokenCount(0, "UNAVAILABLE");
    }

    private TokenCount fallbackOrUnavailable(String model, String text) {
        return properties.isFallbackToEstimated()
                ? fallback.countText(model, text)
                : new TokenCount(0, "UNAVAILABLE");
    }

    private TokenCount toTokenCount(TokenizeResponse response, TokenFallback fallbackSupplier) {
        if (response == null || response.totalTokens() == null) {
            return fallbackSupplier.get();
        }
        String source = StringUtils.hasText(response.source()) ? response.source() : SOURCE;
        return new TokenCount(Math.max(0, response.totalTokens()), source);
    }

    private String endpoint(String path) {
        String baseUrl = properties.getBaseUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + path;
    }

    private interface TokenFallback {
        TokenCount get();
    }

    private record ChatTokenizeRequest(String model, List<TokenMessage> messages) {
    }

    private record TextTokenizeRequest(String model, String text) {
    }

    private record TokenMessage(String role, String content) {
    }

    private record TokenizeResponse(String model, Integer totalTokens, String source, String encoding) {
    }
}
