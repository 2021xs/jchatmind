package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.config.ContextCompressionProperties;
import com.kama.jchatmind.config.TokenCounterProperties;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.service.TokenCounter.TokenCount;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpTokenCounterTest {
    @Test
    void shouldFallbackToEstimatedCounterWhenTokenizerServiceDisabled() {
        ContextCompressionProperties contextProperties = new ContextCompressionProperties();
        contextProperties.setCharsPerToken(3);
        TokenCounterProperties tokenCounterProperties = new TokenCounterProperties();
        tokenCounterProperties.setEnabled(false);
        HttpTokenCounter counter = new HttpTokenCounter(
                tokenCounterProperties,
                new EstimatedTokenCounter(contextProperties),
                new RestTemplateBuilder());

        TokenCount count = counter.countMessages("gpt-5.5", List.of(message("abcdef"), message("abcdefg")));

        assertEquals(5, count.tokens());
        assertEquals(EstimatedTokenCounter.SOURCE, count.source());
    }

    private ChatMessageDTO message(String content) {
        return ChatMessageDTO.builder()
                .id(content)
                .role(ChatMessageDTO.RoleType.USER)
                .content(content)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
