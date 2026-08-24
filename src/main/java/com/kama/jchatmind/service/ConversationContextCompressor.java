package com.kama.jchatmind.service;

import com.kama.jchatmind.model.dto.ChatMessageDTO;

import java.util.List;

public interface ConversationContextCompressor {
    String SUMMARY_PREFIX = "[Conversation summary]\n";
    String SUMMARY_SUFFIX = "\n\nNote: The summary is only auxiliary context. If it conflicts with recent user input or retrieval results, prefer the recent input and retrieval results.";

    CompressionCheck check(String sessionId, List<ChatMessageDTO> allMessages);

    CompressionCheck check(String sessionId, String model, List<ChatMessageDTO> allMessages);

    CompressedContext compressIfNeeded(String sessionId, String model, List<ChatMessageDTO> allMessages);

    static String summaryMessageContent(String summary) {
        return SUMMARY_PREFIX + summary + SUMMARY_SUFFIX;
    }

    record CompressionCheck(boolean needed,
                            String reason,
                            int messageCount,
                            int rawHistoryTokens,
                            int contextTokens,
                            int maxSingleToolResultTokens,
                            int newCompressibleMessages,
                            String tokenSource,
                            int maxContextTokens,
                            int maxSingleToolResultTokensThreshold) {

        public CompressionCheck(boolean needed,
                                String reason,
                                int messageCount,
                                int contextTokens,
                                int maxSingleToolResultTokens,
                                int newCompressibleMessages,
                                String tokenSource,
                                int maxContextTokens,
                                int maxSingleToolResultTokensThreshold) {
            this(needed, reason, messageCount, contextTokens, contextTokens,
                    maxSingleToolResultTokens, newCompressibleMessages, tokenSource,
                    maxContextTokens, maxSingleToolResultTokensThreshold);
        }

        public int effectiveContextTokens() {
            return contextTokens;
        }
    }

    record CompressedContext(String summary, List<ChatMessageDTO> recentMessages, boolean compressed) {
    }
}
