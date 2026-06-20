package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.config.ContextCompressionProperties;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.service.TokenCounter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

@Component
public class EstimatedTokenCounter implements TokenCounter {
    public static final String SOURCE = "ESTIMATED_CHARS";

    private final ContextCompressionProperties properties;

    public EstimatedTokenCounter(ContextCompressionProperties properties) {
        this.properties = properties;
    }

    @Override
    public TokenCount countMessages(String model, List<ChatMessageDTO> messages) {
        int tokens = messages == null ? 0 : messages.stream()
                .map(ChatMessageDTO::getContent)
                .filter(Objects::nonNull)
                .mapToInt(this::estimateTokens)
                .sum();
        return new TokenCount(tokens, SOURCE);
    }

    @Override
    public TokenCount countText(String model, String text) {
        return new TokenCount(estimateTokens(text), SOURCE);
    }

    private int estimateTokens(String content) {
        if (!StringUtils.hasLength(content)) {
            return 0;
        }
        int charsPerToken = Math.max(1, properties.getCharsPerToken());
        return (content.length() + charsPerToken - 1) / charsPerToken;
    }
}
