package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.ContextCompressionProperties;
import com.kama.jchatmind.mapper.ChatSessionMapper;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.dto.ChatSessionDTO;
import com.kama.jchatmind.model.entity.ChatSession;
import com.kama.jchatmind.service.ConversationContextCompressor;
import com.kama.jchatmind.service.ConversationSummaryClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ConversationContextCompressorImpl implements ConversationContextCompressor {
    private static final int MAX_MESSAGE_CHARS_IN_SUMMARY_PROMPT = 2000;

    private final ContextCompressionProperties properties;
    private final ConversationSummaryClient conversationSummaryClient;
    private final ChatSessionMapper chatSessionMapper;
    private final ObjectMapper objectMapper;

    public ConversationContextCompressorImpl(ContextCompressionProperties properties,
                                             ConversationSummaryClient conversationSummaryClient,
                                             ChatSessionMapper chatSessionMapper,
                                             ObjectMapper objectMapper) {
        this.properties = properties;
        this.conversationSummaryClient = conversationSummaryClient;
        this.chatSessionMapper = chatSessionMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public CompressionCheck check(String sessionId, List<ChatMessageDTO> allMessages) {
        List<ChatMessageDTO> sortedMessages = sortedMessages(allMessages);
        if (!properties.isEnabled()) {
            return new CompressionCheck(false, "disabled", sortedMessages.size(),
                    totalContentTokens(sortedMessages), maxSingleToolResultTokens(sortedMessages), 0);
        }

        ChatSessionDTO.MetaData metadata = loadMetadata(sessionId);
        List<ChatMessageDTO> recentMessages = keepRecentMessages(sortedMessages);
        List<ChatMessageDTO> candidates = messagesBeforeRecentWindow(sortedMessages, recentMessages.size());
        List<ChatMessageDTO> messagesToCompress = filterAlreadySummarized(candidates,
                metadata == null ? null : metadata.getContextSummaryLastMessageId());
        int contextTokens = totalContentTokens(sortedMessages);
        int maxToolResultTokens = maxSingleToolResultTokens(sortedMessages);
        boolean overMessageCount = sortedMessages.size() >= properties.getTriggerMessageCount();
        boolean overContextTokens = contextTokens >= properties.getMaxContextTokens();
        boolean overToolResultTokens = maxToolResultTokens >= properties.getMaxSingleToolResultTokens();
        boolean needed = !messagesToCompress.isEmpty()
                && (overMessageCount || overContextTokens || overToolResultTokens);
        String reason = reason(overMessageCount, overContextTokens, overToolResultTokens, messagesToCompress.isEmpty());
        return new CompressionCheck(needed, reason, sortedMessages.size(),
                contextTokens, maxToolResultTokens, messagesToCompress.size());
    }

    @Override
    public CompressedContext compressIfNeeded(String sessionId, String model, List<ChatMessageDTO> allMessages) {
        List<ChatMessageDTO> sortedMessages = sortedMessages(allMessages);
        if (!properties.isEnabled()) {
            return new CompressedContext(null, keepRecentMessages(sortedMessages), false);
        }

        ChatSessionDTO.MetaData metadata = loadMetadata(sessionId);
        String existingSummary = metadata == null ? null : metadata.getContextSummary();
        CompressionCheck check = checkWithMetadata(sortedMessages, metadata);
        if (!check.needed()) {
            log.info("Context compression skipped: sessionId={}, reason={}, historyMessages={}, contextTokens={}, maxToolResultTokens={}, triggerMessages={}, triggerTokens={}, triggerToolResultTokens={}",
                    sessionId, check.reason(), sortedMessages.size(), check.contextTokens(),
                    check.maxSingleToolResultTokens(), properties.getTriggerMessageCount(),
                    properties.getMaxContextTokens(), properties.getMaxSingleToolResultTokens());
            return new CompressedContext(existingSummary, keepRecentMessages(sortedMessages), false);
        }

        List<ChatMessageDTO> recentMessages = keepRecentMessages(sortedMessages);
        List<ChatMessageDTO> candidates = messagesBeforeRecentWindow(sortedMessages, recentMessages.size());
        List<ChatMessageDTO> messagesToCompress = filterAlreadySummarized(candidates,
                metadata == null ? null : metadata.getContextSummaryLastMessageId());

        if (messagesToCompress.isEmpty()) {
            log.info("Context compression skipped: sessionId={}, reason=no_new_messages, summaryChars={}, recentMessages={}",
                    sessionId, length(existingSummary), recentMessages.size());
            return new CompressedContext(existingSummary, recentMessages, false);
        }

        long start = System.currentTimeMillis();
        try {
            log.info("Context compression started: sessionId={}, historyMessages={}, toCompress={}, recentMessages={}",
                    sessionId, sortedMessages.size(), messagesToCompress.size(), recentMessages.size());
            String summary = summarize(model, existingSummary, messagesToCompress);
            String boundedSummary = limit(summary, properties.getMaxSummaryChars());
            String lastCompressedMessageId = messagesToCompress.get(messagesToCompress.size() - 1).getId();
            saveMetadata(sessionId, metadata, boundedSummary, lastCompressedMessageId);
            long latencyMs = System.currentTimeMillis() - start;
            log.info("Context compression done: sessionId={}, historyMessages={}, recentMessages={}, summaryChars={}, latencyMs={}",
                    sessionId, sortedMessages.size(), recentMessages.size(), length(boundedSummary), latencyMs);
            return new CompressedContext(boundedSummary, recentMessages, true);
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - start;
            log.warn("Context compression failed, fallback to recent messages: sessionId={}, historyMessages={}, recentMessages={}, latencyMs={}, error={}",
                    sessionId, sortedMessages.size(), recentMessages.size(), latencyMs, e.getMessage(), e);
            return new CompressedContext(existingSummary, recentMessages, false);
        }
    }

    private List<ChatMessageDTO> sortedMessages(List<ChatMessageDTO> allMessages) {
        return allMessages == null ? List.of() : allMessages.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(ChatMessageDTO::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private CompressionCheck checkWithMetadata(List<ChatMessageDTO> sortedMessages, ChatSessionDTO.MetaData metadata) {
        List<ChatMessageDTO> recentMessages = keepRecentMessages(sortedMessages);
        List<ChatMessageDTO> candidates = messagesBeforeRecentWindow(sortedMessages, recentMessages.size());
        List<ChatMessageDTO> messagesToCompress = filterAlreadySummarized(candidates,
                metadata == null ? null : metadata.getContextSummaryLastMessageId());
        int contextTokens = totalContentTokens(sortedMessages);
        int maxToolResultTokens = maxSingleToolResultTokens(sortedMessages);
        boolean overMessageCount = sortedMessages.size() >= properties.getTriggerMessageCount();
        boolean overContextTokens = contextTokens >= properties.getMaxContextTokens();
        boolean overToolResultTokens = maxToolResultTokens >= properties.getMaxSingleToolResultTokens();
        boolean needed = !messagesToCompress.isEmpty()
                && (overMessageCount || overContextTokens || overToolResultTokens);
        String reason = reason(overMessageCount, overContextTokens, overToolResultTokens, messagesToCompress.isEmpty());
        return new CompressionCheck(needed, reason, sortedMessages.size(),
                contextTokens, maxToolResultTokens, messagesToCompress.size());
    }

    private List<ChatMessageDTO> keepRecentMessages(List<ChatMessageDTO> messages) {
        int keepMessages = Math.max(1, properties.getKeepRecentRounds() * 2);
        int maxMessages = Math.max(keepMessages, properties.getMaxHistoryMessages());
        int limit = Math.min(messages.size(), maxMessages);
        return new ArrayList<>(messages.subList(messages.size() - limit, messages.size()));
    }

    private List<ChatMessageDTO> messagesBeforeRecentWindow(List<ChatMessageDTO> messages, int recentMessageCount) {
        int endExclusive = Math.max(0, messages.size() - recentMessageCount);
        if (endExclusive == 0) {
            return List.of();
        }
        return new ArrayList<>(messages.subList(0, endExclusive));
    }

    private List<ChatMessageDTO> filterAlreadySummarized(List<ChatMessageDTO> candidates, String lastMessageId) {
        if (!StringUtils.hasLength(lastMessageId)) {
            return candidates;
        }
        int lastIndex = -1;
        for (int i = 0; i < candidates.size(); i++) {
            if (lastMessageId.equals(candidates.get(i).getId())) {
                lastIndex = i;
            }
        }
        if (lastIndex < 0 || lastIndex + 1 >= candidates.size()) {
            return List.of();
        }
        return new ArrayList<>(candidates.subList(lastIndex + 1, candidates.size()));
    }

    private int totalContentTokens(List<ChatMessageDTO> messages) {
        return messages.stream()
                .map(ChatMessageDTO::getContent)
                .filter(Objects::nonNull)
                .mapToInt(this::estimateTokens)
                .sum();
    }

    private int maxSingleToolResultTokens(List<ChatMessageDTO> messages) {
        return messages.stream()
                .filter(message -> message.getRole() == ChatMessageDTO.RoleType.TOOL)
                .map(ChatMessageDTO::getContent)
                .filter(Objects::nonNull)
                .mapToInt(this::estimateTokens)
                .max()
                .orElse(0);
    }

    private int estimateTokens(String content) {
        if (!StringUtils.hasLength(content)) {
            return 0;
        }
        int charsPerToken = Math.max(1, properties.getCharsPerToken());
        return (content.length() + charsPerToken - 1) / charsPerToken;
    }

    private String reason(boolean overMessageCount,
                          boolean overContextTokens,
                          boolean overToolResultTokens,
                          boolean noNewMessages) {
        if (noNewMessages) {
            return "no_new_messages";
        }
        List<String> reasons = new ArrayList<>();
        if (overMessageCount) {
            reasons.add("message_count");
        }
        if (overContextTokens) {
            reasons.add("context_tokens");
        }
        if (overToolResultTokens) {
            reasons.add("tool_result_tokens");
        }
        return reasons.isEmpty() ? "below_threshold" : String.join("+", reasons);
    }

    private String summarize(String model, String oldSummary, List<ChatMessageDTO> messagesToCompress) {
        String prompt = buildSummaryPrompt(oldSummary, messagesToCompress);
        return conversationSummaryClient.summarize(model, prompt);
    }

    private String buildSummaryPrompt(String oldSummary, List<ChatMessageDTO> messagesToCompress) {
        return "你是一个 Java 后端开发 Agent 的上下文压缩器。\n"
                + "请将以下历史对话压缩成一段结构化摘要，用于后续多轮对话继续任务。\n\n"
                + "要求：\n"
                + "1. 保留用户目标、项目背景、已确认结论、关键代码文件、关键技术方案、待办事项。\n"
                + "2. 删除寒暄、重复内容和无关细节。\n"
                + "3. 不要编造历史中没有的信息。\n"
                + "4. 不要输出 Markdown 过度标题，保持简洁。\n"
                + "5. 控制在 " + properties.getMaxSummaryChars() + " 字符以内。\n"
                + "6. 不要长期保存工具返回的超长全文，保留结论和关键路径即可。\n\n"
                + "已有摘要：\n"
                + nullToEmpty(oldSummary) + "\n\n"
                + "新增历史消息：\n"
                + formatMessages(messagesToCompress) + "\n\n"
                + "请输出新的合并摘要。";
    }

    private String formatMessages(List<ChatMessageDTO> messages) {
        return messages.stream()
                .map(message -> message.getRole().getRole() + ": " + limit(nullToEmpty(message.getContent()), MAX_MESSAGE_CHARS_IN_SUMMARY_PROMPT))
                .collect(Collectors.joining("\n\n"));
    }

    private ChatSessionDTO.MetaData loadMetadata(String sessionId) {
        ChatSession chatSession = chatSessionMapper.selectById(sessionId);
        if (chatSession == null || !StringUtils.hasLength(chatSession.getMetadata())) {
            return new ChatSessionDTO.MetaData();
        }
        try {
            return objectMapper.readValue(chatSession.getMetadata(), ChatSessionDTO.MetaData.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse chat session metadata, context summary will start empty: sessionId={}, error={}",
                    sessionId, e.getMessage());
            return new ChatSessionDTO.MetaData();
        }
    }

    private void saveMetadata(String sessionId,
                              ChatSessionDTO.MetaData metadata,
                              String summary,
                              String lastCompressedMessageId) throws JsonProcessingException {
        ChatSessionDTO.MetaData updated = metadata == null ? new ChatSessionDTO.MetaData() : metadata;
        updated.setContextSummary(summary);
        updated.setContextSummaryLastMessageId(lastCompressedMessageId);
        updated.setContextSummaryUpdatedAt(LocalDateTime.now());

        ChatSession chatSession = ChatSession.builder()
                .id(sessionId)
                .metadata(objectMapper.writeValueAsString(updated))
                .build();
        chatSessionMapper.updateById(chatSession);
    }

    private String limit(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        int effectiveMax = Math.max(1, maxChars);
        if (value.length() <= effectiveMax) {
            return value;
        }
        String suffix = "\n...[truncated]";
        if (effectiveMax <= suffix.length()) {
            return value.substring(0, effectiveMax);
        }
        return value.substring(0, effectiveMax - suffix.length()) + suffix;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
    }
}
