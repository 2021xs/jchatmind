package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.observability.AgentLifecycleObservationPublisher;
import com.kama.jchatmind.config.ContextCompressionProperties;
import com.kama.jchatmind.mapper.ChatSessionMapper;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.dto.ChatSessionDTO;
import com.kama.jchatmind.model.entity.ChatSession;
import com.kama.jchatmind.service.ConversationContextCompressor;
import com.kama.jchatmind.service.ConversationSummaryClient;
import com.kama.jchatmind.service.TokenCounter;
import com.kama.jchatmind.service.TokenCounter.TokenCount;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ConversationContextCompressorImpl implements ConversationContextCompressor {
    private static final int MAX_MESSAGE_CHARS_IN_SUMMARY_PROMPT = 2000;

    private final ContextCompressionProperties properties;
    private final ConversationSummaryClient conversationSummaryClient;
    private final ChatSessionMapper chatSessionMapper;
    private final ObjectMapper objectMapper;
    private final TokenCounter tokenCounter;

    public ConversationContextCompressorImpl(ContextCompressionProperties properties,
                                             ConversationSummaryClient conversationSummaryClient,
                                             ChatSessionMapper chatSessionMapper,
                                             ObjectMapper objectMapper,
                                             TokenCounter tokenCounter) {
        this.properties = properties;
        this.conversationSummaryClient = conversationSummaryClient;
        this.chatSessionMapper = chatSessionMapper;
        this.objectMapper = objectMapper;
        this.tokenCounter = tokenCounter;
    }

    @Override
    public CompressionCheck check(String sessionId, List<ChatMessageDTO> allMessages) {
        return check(sessionId, null, allMessages);
    }

    @Override
    public CompressionCheck check(String sessionId, String model, List<ChatMessageDTO> allMessages) {
        List<ChatMessageDTO> sortedMessages = sortedMessages(allMessages);
        ContextCompressionProperties.TokenThreshold threshold = properties.thresholdFor(model);
        if (!properties.isEnabled()) {
            TokenCount rawHistoryTokenCount = totalContentTokens(model, sortedMessages);
            MaxToolResultTokenCount maxToolResultTokenCount = maxSingleToolResultTokens(model, sortedMessages);
            return new CompressionCheck(false, "disabled", sortedMessages.size(),
                    rawHistoryTokenCount.tokens(), rawHistoryTokenCount.tokens(),
                    maxToolResultTokenCount.tokens(), 0,
                    combineSources(rawHistoryTokenCount.source(), maxToolResultTokenCount.source()),
                    threshold.getMaxContextTokens(), threshold.getMaxSingleToolResultTokens());
        }

        ChatSessionDTO.MetaData metadata = loadMetadata(sessionId);
        ContextState state = buildContextState(sessionId, model, sortedMessages, metadata);
        return compressionCheck(sortedMessages.size(), threshold, state);
    }

    @Override
    public CompressedContext compressIfNeeded(String sessionId, String model, List<ChatMessageDTO> allMessages) {
        List<ChatMessageDTO> sortedMessages = sortedMessages(allMessages);
        if (!properties.isEnabled()) {
            return new CompressedContext(null,
                    keepRecentMessages(sortedMessages, logicalMessageGroups(sortedMessages)), false);
        }

        ChatSessionDTO.MetaData metadata = loadMetadata(sessionId);
        ContextCompressionProperties.TokenThreshold threshold = properties.thresholdFor(model);
        ContextState state = buildContextState(sessionId, model, sortedMessages, metadata);
        CompressionCheck check = compressionCheck(sortedMessages.size(), threshold, state);
        if (!check.needed()) {
            log.info("Context compression skipped: sessionId={}, reason={}, historyMessages={}, rawHistoryTokens={}, effectiveContextTokens={}, maxToolResultTokens={}, tokenSource={}, maxContextTokens={}, maxSingleToolResultTokens={}",
                    sessionId, check.reason(), sortedMessages.size(), check.rawHistoryTokens(),
                    check.effectiveContextTokens(),
                    check.maxSingleToolResultTokens(), check.tokenSource(),
                    check.maxContextTokens(), check.maxSingleToolResultTokensThreshold());
            List<ChatMessageDTO> currentMessages = state.summaryUsable()
                    ? state.effectiveTail()
                    : state.recentMessages();
            return new CompressedContext(state.effectiveSummary(), currentMessages, false);
        }

        List<ChatMessageDTO> recentMessages = state.recentMessages();
        List<ChatMessageDTO> messagesToCompress = state.messagesToCompress();

        if (messagesToCompress.isEmpty()) {
            log.info("Context compression skipped: sessionId={}, reason=no_new_messages, summaryChars={}, recentMessages={}",
                    sessionId, length(state.effectiveSummary()), recentMessages.size());
            return new CompressedContext(state.effectiveSummary(), recentMessages, false);
        }

        String compressionPrompt = buildSummaryPrompt(state.effectiveSummary(), messagesToCompress);
        long start = System.currentTimeMillis();
        try {
            log.info("Context compression started: sessionId={}, historyMessages={}, toCompress={}, recentMessages={}",
                    sessionId, sortedMessages.size(), messagesToCompress.size(), recentMessages.size());
            String summary = conversationSummaryClient.summarize(model, compressionPrompt);
            String boundedSummary = limit(summary, properties.getMaxSummaryChars());
            String lastCompressedMessageId = messagesToCompress.get(messagesToCompress.size() - 1).getId();
            saveMetadata(sessionId, metadata, boundedSummary, lastCompressedMessageId);
            long latencyMs = System.currentTimeMillis() - start;
            if (AgentLifecycleObservationPublisher.isCompressionObservationEnabled()) {
                List<ChatMessageDTO> compressedMessages = new ArrayList<>();
                compressedMessages.add(summaryMessage(boundedSummary));
                compressedMessages.addAll(recentMessages);
                TokenCount afterTokenCount = totalContentTokens(model, compressedMessages);
                AgentLifecycleObservationPublisher.publishCompression(
                        new AgentLifecycleObservationPublisher.CompressionObservation(
                                sessionId, model, check.reason(), check.effectiveContextTokens(),
                                afterTokenCount.tokens(), check.rawHistoryTokens(),
                                combineSources(check.tokenSource(), afterTokenCount.source()),
                                compressionPrompt, state.effectiveSummary(), boundedSummary,
                                latencyMs, true, null));
            }
            log.info("Context compression done: sessionId={}, historyMessages={}, recentMessages={}, summaryChars={}, latencyMs={}",
                    sessionId, sortedMessages.size(), recentMessages.size(), length(boundedSummary), latencyMs);
            return new CompressedContext(boundedSummary, recentMessages, true);
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - start;
            AgentLifecycleObservationPublisher.publishCompression(
                    new AgentLifecycleObservationPublisher.CompressionObservation(
                            sessionId, model, check.reason(), check.effectiveContextTokens(),
                            check.effectiveContextTokens(), check.rawHistoryTokens(), check.tokenSource(),
                            compressionPrompt, state.effectiveSummary(), null,
                            latencyMs, false, e.getClass().getSimpleName() + ": " + e.getMessage()));
            log.warn("Context compression failed, fallback to recent messages: sessionId={}, historyMessages={}, recentMessages={}, latencyMs={}, error={}",
                    sessionId, sortedMessages.size(), recentMessages.size(), latencyMs, e.getMessage(), e);
            List<ChatMessageDTO> fallbackMessages = state.summaryUsable()
                    ? state.effectiveTail()
                    : recentMessages;
            return new CompressedContext(state.effectiveSummary(), fallbackMessages, false);
        }
    }

    private List<ChatMessageDTO> sortedMessages(List<ChatMessageDTO> allMessages) {
        return allMessages == null ? List.of() : allMessages.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(ChatMessageDTO::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private ContextState buildContextState(String sessionId,
                                           String model,
                                           List<ChatMessageDTO> sortedMessages,
                                           ChatSessionDTO.MetaData metadata) {
        List<LogicalMessageGroup> groups = logicalMessageGroups(sortedMessages);
        List<ChatMessageDTO> recentMessages = keepRecentMessages(sortedMessages, groups);
        int recentStartIndex = sortedMessages.size() - recentMessages.size();

        String existingSummary = metadata == null ? null : metadata.getContextSummary();
        String summaryBoundaryMessageId = metadata == null
                ? null
                : metadata.getContextSummaryLastMessageId();
        int summaryBoundaryIndex = summaryBoundaryIndex(
                sortedMessages, groups, summaryBoundaryMessageId);
        boolean summaryUsable = StringUtils.hasText(existingSummary)
                && StringUtils.hasText(summaryBoundaryMessageId)
                && summaryBoundaryIndex >= 0;
        if (StringUtils.hasText(existingSummary) && !summaryUsable) {
            log.warn("Ignoring context summary with missing or protocol-unsafe boundary: sessionId={}, boundaryMessageId={}",
                    sessionId, summaryBoundaryMessageId);
        }

        int compressStartIndex = summaryUsable ? summaryBoundaryIndex + 1 : 0;
        int compressEndIndex = recentStartIndex;
        List<ChatMessageDTO> messagesToCompress = compressStartIndex < compressEndIndex
                ? new ArrayList<>(sortedMessages.subList(compressStartIndex, compressEndIndex))
                : List.of();
        List<ChatMessageDTO> effectiveTail = summaryUsable
                ? new ArrayList<>(sortedMessages.subList(summaryBoundaryIndex + 1, sortedMessages.size()))
                : new ArrayList<>(sortedMessages);
        List<ChatMessageDTO> effectiveMessages = new ArrayList<>();
        if (summaryUsable) {
            effectiveMessages.add(summaryMessage(existingSummary));
        }
        effectiveMessages.addAll(effectiveTail);

        TokenCount rawHistoryTokenCount = totalContentTokens(model, sortedMessages);
        TokenCount effectiveContextTokenCount = totalContentTokens(model, effectiveMessages);
        MaxToolResultTokenCount maxToolResultTokenCount = maxSingleToolResultTokens(model, effectiveMessages);
        return new ContextState(
                recentMessages,
                effectiveTail,
                messagesToCompress,
                summaryUsable ? existingSummary : null,
                summaryUsable,
                rawHistoryTokenCount.tokens(),
                effectiveContextTokenCount.tokens(),
                maxToolResultTokenCount.tokens(),
                combineSources(effectiveContextTokenCount.source(), maxToolResultTokenCount.source()));
    }

    private CompressionCheck compressionCheck(int messageCount,
                                              ContextCompressionProperties.TokenThreshold threshold,
                                              ContextState state) {
        boolean overContextTokens = state.effectiveContextTokens() >= threshold.getMaxContextTokens();
        boolean overToolResultTokens = state.maxSingleToolResultTokens()
                >= threshold.getMaxSingleToolResultTokens();
        boolean needed = !state.messagesToCompress().isEmpty()
                && (overContextTokens || overToolResultTokens);
        String reason = reason(overContextTokens, overToolResultTokens,
                state.messagesToCompress().isEmpty());
        return new CompressionCheck(
                needed,
                reason,
                messageCount,
                state.rawHistoryTokens(),
                state.effectiveContextTokens(),
                state.maxSingleToolResultTokens(),
                state.messagesToCompress().size(),
                state.tokenSource(),
                threshold.getMaxContextTokens(),
                threshold.getMaxSingleToolResultTokens());
    }

    private List<ChatMessageDTO> keepRecentMessages(List<ChatMessageDTO> messages,
                                                    List<LogicalMessageGroup> groups) {
        if (messages.isEmpty()) {
            return List.of();
        }
        int keepMessages = Math.max(1, properties.getKeepRecentRounds() * 2);
        int maxMessages = Math.max(keepMessages, properties.getMaxHistoryMessages());
        int retainedMessages = 0;
        int firstGroupIndex = groups.size();
        while (firstGroupIndex > 0 && retainedMessages < maxMessages) {
            LogicalMessageGroup group = groups.get(--firstGroupIndex);
            retainedMessages += group.endExclusive() - group.startInclusive();
        }
        int startIndex = groups.get(firstGroupIndex).startInclusive();
        return new ArrayList<>(messages.subList(startIndex, messages.size()));
    }

    private List<LogicalMessageGroup> logicalMessageGroups(List<ChatMessageDTO> messages) {
        List<LogicalMessageGroup> groups = new ArrayList<>();
        Set<String> historyToolCallIds = new HashSet<>();
        int index = 0;
        while (index < messages.size()) {
            ChatMessageDTO message = messages.get(index);
            if (message.getRole() == null) {
                throw new IllegalStateException("Context history contains message without role");
            }
            if (message.getRole() == ChatMessageDTO.RoleType.TOOL) {
                throw new IllegalStateException("Context history contains orphan tool response: messageId="
                        + message.getId());
            }
            if (!hasToolCalls(message)) {
                groups.add(new LogicalMessageGroup(index, index + 1));
                index++;
                continue;
            }

            Set<String> expectedResponseIds = new HashSet<>();
            for (org.springframework.ai.chat.messages.AssistantMessage.ToolCall toolCall
                    : message.getMetadata().getToolCalls()) {
                if (toolCall == null || !StringUtils.hasText(toolCall.id())) {
                    throw new IllegalStateException("Context history contains assistant tool call without id: messageId="
                            + message.getId());
                }
                if (!expectedResponseIds.add(toolCall.id()) || !historyToolCallIds.add(toolCall.id())) {
                    throw new IllegalStateException("Context history contains duplicate toolCallId: " + toolCall.id());
                }
            }

            Set<String> actualResponseIds = new HashSet<>();
            int groupEnd = index + 1;
            while (groupEnd < messages.size()
                    && messages.get(groupEnd).getRole() == ChatMessageDTO.RoleType.TOOL) {
                ChatMessageDTO toolMessage = messages.get(groupEnd);
                String responseId = toolResponseId(toolMessage);
                if (!StringUtils.hasText(responseId)) {
                    throw new IllegalStateException("Context history contains tool response without toolCallId: messageId="
                            + toolMessage.getId());
                }
                if (!expectedResponseIds.contains(responseId) || !actualResponseIds.add(responseId)) {
                    throw new IllegalStateException("Context history contains unexpected or duplicate tool response: toolCallId="
                            + responseId);
                }
                groupEnd++;
            }
            if (!actualResponseIds.equals(expectedResponseIds)) {
                throw new IllegalStateException("Context history contains incomplete tool response batch: messageId="
                        + message.getId());
            }
            groups.add(new LogicalMessageGroup(index, groupEnd));
            index = groupEnd;
        }
        return groups;
    }

    private int summaryBoundaryIndex(List<ChatMessageDTO> messages,
                                     List<LogicalMessageGroup> groups,
                                     String boundaryMessageId) {
        if (!StringUtils.hasText(boundaryMessageId)) {
            return -1;
        }
        int boundaryIndex = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (boundaryMessageId.equals(messages.get(i).getId())) {
                boundaryIndex = i;
                break;
            }
        }
        if (boundaryIndex < 0) {
            return -1;
        }
        for (LogicalMessageGroup group : groups) {
            if (group.endExclusive() - 1 == boundaryIndex) {
                return boundaryIndex;
            }
        }
        return -1;
    }

    private boolean hasToolCalls(ChatMessageDTO message) {
        return message.getRole() == ChatMessageDTO.RoleType.ASSISTANT
                && message.getMetadata() != null
                && message.getMetadata().getToolCalls() != null
                && !message.getMetadata().getToolCalls().isEmpty();
    }

    private String toolResponseId(ChatMessageDTO message) {
        if (message.getMetadata() == null || message.getMetadata().getToolResponse() == null) {
            return null;
        }
        return message.getMetadata().getToolResponse().id();
    }

    private ChatMessageDTO summaryMessage(String summary) {
        return ChatMessageDTO.builder()
                .id("context-summary")
                .role(ChatMessageDTO.RoleType.SYSTEM)
                .content(ConversationContextCompressor.summaryMessageContent(summary))
                .build();
    }

    private TokenCount totalContentTokens(String model, List<ChatMessageDTO> messages) {
        return tokenCounter.countMessages(model, messages);
    }

    private MaxToolResultTokenCount maxSingleToolResultTokens(String model, List<ChatMessageDTO> messages) {
        List<TokenCount> tokenCounts = messages.stream()
                .filter(message -> message.getRole() == ChatMessageDTO.RoleType.TOOL)
                .map(ChatMessageDTO::getContent)
                .filter(Objects::nonNull)
                .map(content -> tokenCounter.countText(model, content))
                .toList();
        int tokens = tokenCounts.stream()
                .mapToInt(TokenCount::tokens)
                .max()
                .orElse(0);
        String source = tokenCounts.stream()
                .map(TokenCount::source)
                .filter(StringUtils::hasLength)
                .distinct()
                .collect(Collectors.joining("+"));
        return new MaxToolResultTokenCount(tokens, source);
    }

    private String combineSources(String contextSource, String toolSource) {
        List<String> sources = new ArrayList<>();
        if (StringUtils.hasLength(contextSource)) {
            sources.add(contextSource);
        }
        if (StringUtils.hasLength(toolSource) && !sources.contains(toolSource)) {
            sources.add(toolSource);
        }
        return sources.isEmpty() ? "UNAVAILABLE" : String.join("+", sources);
    }

    private String reason(boolean overContextTokens,
                          boolean overToolResultTokens,
                          boolean noNewMessages) {
        if (noNewMessages) {
            return "no_new_messages";
        }
        List<String> reasons = new ArrayList<>();
        if (overContextTokens) {
            reasons.add("context_tokens");
        }
        if (overToolResultTokens) {
            reasons.add("tool_result_tokens");
        }
        return reasons.isEmpty() ? "below_threshold" : String.join("+", reasons);
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

    private record ContextState(List<ChatMessageDTO> recentMessages,
                                List<ChatMessageDTO> effectiveTail,
                                List<ChatMessageDTO> messagesToCompress,
                                String effectiveSummary,
                                boolean summaryUsable,
                                int rawHistoryTokens,
                                int effectiveContextTokens,
                                int maxSingleToolResultTokens,
                                String tokenSource) {
    }

    private record LogicalMessageGroup(int startInclusive, int endExclusive) {
    }

    private record MaxToolResultTokenCount(int tokens, String source) {
    }
}
