package com.kama.jchatmind.service;

import com.kama.jchatmind.model.dto.ChatMessageDTO;

import java.util.List;

public interface TokenCounter {
    TokenCount countMessages(String model, List<ChatMessageDTO> messages);

    TokenCount countText(String model, String text);

    record TokenCount(int tokens, String source) {
    }
}
