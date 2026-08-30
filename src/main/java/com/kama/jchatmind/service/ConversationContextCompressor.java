package com.kama.jchatmind.service;

import com.kama.jchatmind.model.dto.ChatMessageDTO;

import java.util.List;

public interface ConversationContextCompressor {
    String SUMMARY_PREFIX = "[Conversation summary]\n";
    String SUMMARY_SUFFIX = "\n\nNote: The summary is only auxiliary context. If it conflicts with recent user input or retrieval results, prefer the recent input and retrieval results.";

    CompressionCheck check(String sessionId, List<ChatMessageDTO> allMessages);

    CompressionCheck check(String sessionId, String model, List<ChatMessageDTO> allMessages);

    CompressedContext compressIfNeeded(String sessionId, String model, List<ChatMessageDTO> allMessages);

    CompletedConversationProjection projectCompletedConversation(String sessionId,
                                                                  String model,
                                                                  String currentUserMessageId,
                                                                  List<ChatMessageDTO> allMessages);

    CompressionCheck checkCurrentTask(String model,
                                      ChatMessageDTO originalUser,
                                      String conversationSummary,
                                      List<ChatMessageDTO> completedConversationMessages,
                                      List<ChatMessageDTO> currentTaskProtocolMessages,
                                      CurrentTaskWorkingState state);

    CurrentTaskCompression compressCurrentTaskIfNeeded(String sessionId,
                                                        String model,
                                                        ChatMessageDTO originalUser,
                                                        String conversationSummary,
                                                        List<ChatMessageDTO> completedConversationMessages,
                                                        List<ChatMessageDTO> currentTaskProtocolMessages,
                                                        CurrentTaskWorkingState state);

    static String summaryMessageContent(String summary) {
        return SUMMARY_PREFIX + summary + SUMMARY_SUFFIX;
    }

    static String currentTaskSummaryMessageContent(String summary) {
        return "[Current task working summary]\n" + summary
                + "\n\nNote: This is lossy runtime working state. Preserve the raw current user question as authoritative.";
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

    record CompletedConversationProjection(String summary,
                                           List<ChatMessageDTO> messages,
                                           String coverageBoundaryMessageId,
                                           boolean freshRebuild,
                                           int unlinkedLegacyFinalCount) {
    }

    record CurrentTaskWorkingState(String summary,
                                   int coveredThroughLogicalGroup,
                                   int summaryDepth,
                                   int compressionCount,
                                   boolean compressionSuppressed) {

        public CurrentTaskWorkingState(String summary,
                                       int coveredThroughLogicalGroup,
                                       int summaryDepth,
                                       int compressionCount) {
            this(summary, coveredThroughLogicalGroup, summaryDepth, compressionCount, false);
        }

        public static CurrentTaskWorkingState empty() {
            return new CurrentTaskWorkingState(null, 0, 0, 0, false);
        }
    }

    record CurrentTaskCompression(CurrentTaskWorkingState state,
                                  List<ChatMessageDTO> uncoveredProtocolMessages,
                                  CompressionCheck check,
                                  boolean compressed) {
    }
}
