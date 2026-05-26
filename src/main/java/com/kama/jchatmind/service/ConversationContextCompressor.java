package com.kama.jchatmind.service;

import com.kama.jchatmind.model.dto.ChatMessageDTO;

import java.util.List;

public interface ConversationContextCompressor {
    CompressionCheck check(String sessionId, List<ChatMessageDTO> allMessages);

    CompressedContext compressIfNeeded(String sessionId, String model, List<ChatMessageDTO> allMessages);

    record CompressionCheck(boolean needed,
                            String reason,
                            int messageCount,
                            int contextChars,
                            int maxSingleToolResultChars,
                            int newCompressibleMessages) {
    }

    record CompressedContext(String summary, List<ChatMessageDTO> recentMessages, boolean compressed) {
    }
}
