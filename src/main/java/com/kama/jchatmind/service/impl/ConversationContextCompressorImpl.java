package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.observability.AgentLifecycleObservationPublisher;
import com.kama.jchatmind.config.ContextCompressionProperties;
import com.kama.jchatmind.mapper.AgentTaskMapper;
import com.kama.jchatmind.mapper.ChatSessionMapper;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.dto.ChatSessionDTO;
import com.kama.jchatmind.model.entity.AgentTask;
import com.kama.jchatmind.model.entity.ChatSession;
import com.kama.jchatmind.service.AgentTaskLogService;
import com.kama.jchatmind.service.ConversationContextCompressor;
import com.kama.jchatmind.service.ConversationSummaryClient;
import com.kama.jchatmind.service.TokenCounter;
import com.kama.jchatmind.service.TokenCounter.TokenCount;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ConversationContextCompressorImpl implements ConversationContextCompressor {
    private static final int MAX_MESSAGE_CHARS_IN_SUMMARY_PROMPT = 2000;
    private static final String TASK_AWARE_SUMMARY_HEADER = "Conversation Context";
    private static final List<String> TASK_AWARE_SUMMARY_SECTIONS = List.of(
            "- User Goals / Requests",
            "- Confirmed Final Conclusions",
            "- Important Constraints / Exact Values",
            "- Decisions / Preferences Relevant to Future Turns",
            "- Open Conversation-level Follow-ups");
    private static final String CURRENT_TASK_SUMMARY_HEADER = "Current Task Continuation State";
    private static final List<String> CURRENT_TASK_SUMMARY_SECTIONS = List.of(
            "- Goal",
            "- Known",
            "- Constraints",
            "- Refs",
            "- Open",
            "- Next");
    private static final Pattern REPO_ID_ASSIGNMENT = Pattern.compile("repoId\\s*[:=]\\s*\\S+");
    private static final Pattern CHUNK_ID_ASSIGNMENT = Pattern.compile("chunkId\\s*[:=]\\s*\\S+");

    private final ContextCompressionProperties properties;
    private final ConversationSummaryClient conversationSummaryClient;
    private final ChatSessionMapper chatSessionMapper;
    private final AgentTaskMapper agentTaskMapper;
    private final ObjectMapper objectMapper;
    private final TokenCounter tokenCounter;

    public ConversationContextCompressorImpl(ContextCompressionProperties properties,
                                             ConversationSummaryClient conversationSummaryClient,
                                             ChatSessionMapper chatSessionMapper,
                                             AgentTaskMapper agentTaskMapper,
                                             ObjectMapper objectMapper,
                                             TokenCounter tokenCounter) {
        this.properties = properties;
        this.conversationSummaryClient = conversationSummaryClient;
        this.chatSessionMapper = chatSessionMapper;
        this.agentTaskMapper = agentTaskMapper;
        this.objectMapper = objectMapper;
        this.tokenCounter = tokenCounter;
    }

    @Override
    public CompletedConversationProjection projectCompletedConversation(String sessionId,
                                                                        String model,
                                                                        String currentUserMessageId,
                                                                        List<ChatMessageDTO> allMessages) {
        List<ChatMessageDTO> sortedMessages = sortedMessages(allMessages);
        Map<String, ChatMessageDTO> messagesById = sortedMessages.stream()
                .filter(message -> StringUtils.hasText(message.getId()))
                .collect(Collectors.toMap(ChatMessageDTO::getId, message -> message,
                        (first, ignored) -> first, LinkedHashMap::new));
        Map<String, Integer> messageOrder = new LinkedHashMap<>();
        for (int index = 0; index < sortedMessages.size(); index++) {
            if (StringUtils.hasText(sortedMessages.get(index).getId())) {
                messageOrder.putIfAbsent(sortedMessages.get(index).getId(), index);
            }
        }

        ChatMessageDTO currentUser = currentUserMessage(messagesById, currentUserMessageId);
        List<AgentTask> tasks = agentTaskMapper.selectBySessionId(sessionId);
        List<CompletedConversationPair> eligiblePairs = eligibleCompletedPairs(
                tasks == null ? List.of() : tasks, sortedMessages, messagesById, messageOrder);
        int unlinkedLegacyFinalCount = unlinkedLegacyFinalCount(sortedMessages);
        ChatSessionDTO.MetaData metadata = loadMetadata(sessionId);
        String existingSummary = metadata == null ? null : metadata.getContextSummary();
        String existingBoundary = metadata == null ? null : metadata.getContextSummaryLastMessageId();
        CompletedConversationPair boundaryPair = eligiblePairs.stream()
                .filter(pair -> pair.finalMessage().getId().equals(existingBoundary))
                .findFirst()
                .orElse(null);
        boolean cacheUsable = isTaskAwareSummary(existingSummary) && boundaryPair != null;

        if (unlinkedLegacyFinalCount > 0) {
            log.info("Task-aware completed conversation ignored unlinked legacy finals: sessionId={}, count={}",
                    sessionId, unlinkedLegacyFinalCount);
        }

        if (!cacheUsable && (StringUtils.hasText(existingSummary) || StringUtils.hasText(existingBoundary))) {
            log.info("Ignoring untrusted completed-conversation summary cache: sessionId={}, boundaryMessageId={}",
                    sessionId, existingBoundary);
            clearSummaryMetadata(sessionId, metadata);
        }

        if (eligiblePairs.isEmpty()) {
            return new CompletedConversationProjection(null, currentUser == null ? List.of() : List.of(currentUser),
                    null, false, unlinkedLegacyFinalCount);
        }

        if (!cacheUsable) {
            return rebuildCompletedConversation(sessionId, model, metadata, eligiblePairs, currentUser,
                    unlinkedLegacyFinalCount, null);
        }

        List<CompletedConversationPair> uncoveredPairs = eligiblePairs.stream()
                .filter(pair -> pair.finalOrder() > boundaryPair.finalOrder())
                .toList();
        List<ChatMessageDTO> uncoveredMessages = completedMessages(uncoveredPairs, currentUser);
        if (completedProjectionNeedsRefresh(model, existingSummary, uncoveredMessages)) {
            return rebuildCompletedConversation(sessionId, model, metadata, eligiblePairs, currentUser,
                    unlinkedLegacyFinalCount, existingSummary);
        }
        return new CompletedConversationProjection(existingSummary, uncoveredMessages,
                boundaryPair.finalMessage().getId(), false, unlinkedLegacyFinalCount);
    }

    @Override
    public CompressionCheck checkCurrentTask(String model,
                                             ChatMessageDTO originalUser,
                                             String conversationSummary,
                                             List<ChatMessageDTO> completedConversationMessages,
                                             List<ChatMessageDTO> currentTaskProtocolMessages,
                                             List<ChatMessageDTO> fixedPlanningMessages,
                                             CurrentTaskWorkingState state) {
        CurrentTaskWorkingState safeState = state == null ? CurrentTaskWorkingState.empty() : state;
        CurrentTaskInputs inputs = currentTaskInputs(originalUser, conversationSummary,
                completedConversationMessages, currentTaskProtocolMessages, safeState);
        ContextCompressionProperties.TokenThreshold threshold = properties.thresholdFor(model);
        TokenCount effective = planningContentTokens(model, fixedPlanningMessages, inputs.effectiveMessages());
        MaxToolResultTokenCount maxTool = maxSingleToolResultTokens(model, inputs.effectiveMessages());
        boolean overContext = effective.tokens() >= threshold.getMaxContextTokens();
        boolean overSingleResult = maxTool.tokens() >= threshold.getMaxSingleToolResultTokens();
        boolean needed = properties.isEnabled() && !inputs.uncoveredGroups().isEmpty()
                && !safeState.compressionSuppressed()
                && (overContext || overSingleResult);
        String reason = !properties.isEnabled() ? "disabled"
                : inputs.uncoveredGroups().isEmpty() ? "no_new_messages"
                : safeState.compressionSuppressed() ? "previous_failure"
                : overSingleResult ? "single_tool_result_tokens"
                : overContext ? "context_tokens"
                : "not_needed";
        return new CompressionCheck(needed, reason, inputs.effectiveMessages().size(),
                effective.tokens(), effective.tokens(), maxTool.tokens(),
                inputs.uncoveredProtocolMessages().size(),
                combineSources(effective.source(), maxTool.source()),
                threshold.getMaxContextTokens(), threshold.getMaxSingleToolResultTokens());
    }

    @Override
    public CurrentTaskCompression compressCurrentTaskIfNeeded(String sessionId,
                                                               String model,
                                                               ChatMessageDTO originalUser,
                                                               String conversationSummary,
                                                               List<ChatMessageDTO> completedConversationMessages,
                                                               List<ChatMessageDTO> currentTaskProtocolMessages,
                                                               List<ChatMessageDTO> fixedPlanningMessages,
                                                               CurrentTaskWorkingState state) {
        CurrentTaskWorkingState safeState = state == null ? CurrentTaskWorkingState.empty() : state;
        CurrentTaskInputs inputs = currentTaskInputs(originalUser, conversationSummary,
                completedConversationMessages, currentTaskProtocolMessages, safeState);
        CompressionCheck check = checkCurrentTask(model, originalUser, conversationSummary,
                completedConversationMessages, currentTaskProtocolMessages, fixedPlanningMessages, safeState);
        if (!check.needed()) {
            return new CurrentTaskCompression(safeState, inputs.uncoveredProtocolMessages(), check, false, 0);
        }

        AssimilationSelection selection = selectAssimilation(model, originalUser, conversationSummary,
                completedConversationMessages, fixedPlanningMessages, inputs,
                check.effectiveContextTokens() >= check.maxContextTokens());
        String prompt = buildCurrentTaskSummaryPrompt(originalUser, safeState.summary(),
                selection.selectedProtocolMessages());
        long start = System.currentTimeMillis();
        int correctiveRetryCount = 0;
        String attemptId = sessionId + ":" + (safeState.compressionCount() + 1);
        String primaryState = null;
        String correctivePrompt = null;
        String correctiveState = null;
        String acceptedState = null;
        try {
            String generated = conversationSummaryClient.summarize(model, prompt);
            primaryState = generated == null ? null : generated.strip();
            String summary = primaryState;
            boolean budgetCorrectiveInvoked = false;
            CandidateMeasurement primaryMeasurement;
            try {
                validateCurrentTaskStateStructure(summary);
                validateCurrentTaskStateReduction(model, summary, safeState.summary(),
                        selection.selectedProtocolMessages());
            } catch (RepairableCurrentTaskSummaryException repairable) {
                correctiveRetryCount = 1;
                correctivePrompt = buildCurrentTaskStructureCorrectivePrompt(
                        originalUser, summary, repairable.getMessage(), selection.availableStateTokens());
                log.info("Current task state corrective retry: attemptId={}, type=structure, reason={}, candidateChars={}",
                        attemptId, repairable.getMessage(), length(summary));
                String corrected = conversationSummaryClient.summarize(model, correctivePrompt);
                correctiveState = corrected == null ? null : corrected.strip();
                summary = correctiveState;
                validateCurrentTaskStateStructure(summary);
                validateCurrentTaskStateReduction(model, summary, safeState.summary(),
                        selection.selectedProtocolMessages());
            }
            primaryMeasurement = measureCandidate(model, originalUser, conversationSummary,
                    completedConversationMessages, fixedPlanningMessages, summary,
                    selection.retainedProtocolMessages());
            if (primaryMeasurement.fullCandidateTokens() > check.maxContextTokens()) {
                if (correctiveRetryCount > 0) {
                    throw new IllegalStateException("Corrected current task state leaves Planning request over budget: tokens="
                            + primaryMeasurement.fullCandidateTokens() + ", maxContextTokens="
                            + check.maxContextTokens());
                }
                correctiveRetryCount = 1;
                budgetCorrectiveInvoked = true;
                correctivePrompt = buildCurrentTaskBudgetCorrectivePrompt(
                        originalUser, summary, selection.availableStateTokens());
                log.info("Current task state corrective retry: attemptId={}, type=budget, availableStateTokens={}, primaryStateTokens={}, primaryCandidateTokens={}",
                        attemptId, selection.availableStateTokens(), primaryMeasurement.stateTokens(),
                        primaryMeasurement.fullCandidateTokens());
                String corrected = conversationSummaryClient.summarize(model, correctivePrompt);
                String correctedState = corrected == null ? null : corrected.strip();
                correctiveState = correctedState;
                validateCurrentTaskStateStructure(correctedState);
                int correctedTokens = currentTaskStateBodyTokens(model, correctedState);
                if (correctedTokens >= primaryMeasurement.stateTokens()) {
                    throw new IllegalStateException("Budget-corrected current task state did not reduce state tokens");
                }
                validateCurrentTaskStateReduction(model, correctedState, safeState.summary(),
                        selection.selectedProtocolMessages());
                summary = correctedState;
            }
            CandidateMeasurement finalMeasurement = measureCandidate(model, originalUser, conversationSummary,
                    completedConversationMessages, fixedPlanningMessages, summary,
                    selection.retainedProtocolMessages());
            if (finalMeasurement.fullCandidateTokens() > check.maxContextTokens()) {
                throw new IllegalStateException("Current task continuation state leaves Planning request over budget: tokens="
                        + finalMeasurement.fullCandidateTokens() + ", maxContextTokens="
                        + check.maxContextTokens());
            }
            CurrentTaskWorkingState updated = new CurrentTaskWorkingState(
                    summary,
                    safeState.coveredThroughLogicalGroup() + selection.selectedGroupCount(),
                    safeState.summaryDepth() + 1,
                    safeState.compressionCount() + 1,
                    false);
            acceptedState = summary;
            logCurrentTaskBudgetAttempt(attemptId, model, originalUser, conversationSummary,
                    completedConversationMessages, fixedPlanningMessages, safeState.summary(), selection,
                    primaryState, primaryMeasurement, budgetCorrectiveInvoked, summary, finalMeasurement, true);
            publishCurrentTaskCompression(sessionId, model, check, prompt, safeState.summary(), summary,
                    System.currentTimeMillis() - start, true, null, attemptId, primaryState,
                    correctivePrompt, correctiveState, acceptedState, true,
                    updated.coveredThroughLogicalGroup(), selection.selectedProtocolMessages(),
                    selection.retainedProtocolMessages(), updated.summaryDepth(),
                    updated.compressionCount(), correctiveRetryCount);
            return new CurrentTaskCompression(updated, selection.retainedProtocolMessages(), check, true,
                    correctiveRetryCount);
        } catch (Exception error) {
            log.warn("Current task budget attempt rejected: attemptId={}, candidateBeforeTokens={}, selectedGroups={}, selectedRawTokens={}, retainedRawTokens={}, availableStateTokens={}, error={}",
                    attemptId, check.effectiveContextTokens(), selection.selectedGroupCount(),
                    selection.selectedRawTokens(), selection.retainedRawTokens(),
                    selection.availableStateTokens(), error.getMessage());
            publishCurrentTaskCompression(sessionId, model, check, prompt, safeState.summary(), null,
                    System.currentTimeMillis() - start, false,
                    error.getClass().getSimpleName() + ": " + error.getMessage(), attemptId,
                    primaryState, correctivePrompt, correctiveState, null, false,
                    safeState.coveredThroughLogicalGroup(), selection.selectedProtocolMessages(),
                    inputs.uncoveredProtocolMessages(), safeState.summaryDepth(),
                    safeState.compressionCount(), correctiveRetryCount);
            log.warn("Current task continuation state failed closed: sessionId={}, coveredGroups={}, error={}",
                    sessionId, safeState.coveredThroughLogicalGroup(), error.getMessage(), error);
            CurrentTaskWorkingState suppressed = new CurrentTaskWorkingState(
                    safeState.summary(),
                    safeState.coveredThroughLogicalGroup(),
                    safeState.summaryDepth(),
                    safeState.compressionCount(),
                    true);
            return new CurrentTaskCompression(suppressed, inputs.uncoveredProtocolMessages(), check, false,
                    correctiveRetryCount);
        }
    }

    @Override
    public void assertPlanningContextWithinBudget(String model, List<Message> messages) {
        List<Message> safeMessages = messages == null ? List.of() : List.copyOf(messages);
        List<ChatMessageDTO> measurableMessages = safeMessages.stream()
                .map(this::toTokenMeasurementMessage)
                .toList();
        TokenCount measured = tokenCounter.countMessages(model, measurableMessages);
        int hardBudget = properties.thresholdFor(model).getMaxContextTokens();
        if (measured.tokens() > hardBudget) {
            throw new IllegalStateException("Planning working context exceeds hard token budget: tokens="
                    + measured.tokens() + ", maxContextTokens=" + hardBudget
                    + ", tokenSource=" + measured.source());
        }
    }

    private ChatMessageDTO toTokenMeasurementMessage(Message message) {
        ChatMessageDTO.RoleType role;
        String measurableContent = message.getText();
        if (message instanceof UserMessage) {
            role = ChatMessageDTO.RoleType.USER;
        } else if (message instanceof AssistantMessage assistantMessage) {
            role = ChatMessageDTO.RoleType.ASSISTANT;
            measurableContent = assistantMeasurementContent(assistantMessage);
        } else if (message instanceof ToolResponseMessage toolResponseMessage) {
            role = ChatMessageDTO.RoleType.TOOL;
            measurableContent = toolResponseMessage.getResponses().stream()
                    .map(response -> nullToEmpty(response.responseData()))
                    .collect(Collectors.joining("\n"));
        } else {
            role = ChatMessageDTO.RoleType.SYSTEM;
        }
        return ChatMessageDTO.builder()
                .role(role)
                .content(measurableContent)
                .build();
    }

    private String assistantMeasurementContent(AssistantMessage message) {
        StringBuilder content = new StringBuilder(nullToEmpty(message.getText()));
        if (message.getToolCalls() != null) {
            for (AssistantMessage.ToolCall call : message.getToolCalls()) {
                content.append('\n')
                        .append(nullToEmpty(call.id())).append(' ')
                        .append(nullToEmpty(call.name())).append(' ')
                        .append(nullToEmpty(call.arguments()));
            }
        }
        return content.toString();
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
            logicalMessageGroups(sortedMessages);
            return new CompressedContext(null, sortedMessages, false);
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
                    : sortedMessages;
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
                    : sortedMessages;
            return new CompressedContext(state.effectiveSummary(), fallbackMessages, false);
        }
    }

    private ChatMessageDTO currentUserMessage(Map<String, ChatMessageDTO> messagesById,
                                              String currentUserMessageId) {
        if (!StringUtils.hasText(currentUserMessageId)) {
            return null;
        }
        ChatMessageDTO currentUser = messagesById.get(currentUserMessageId);
        if (currentUser == null || currentUser.getRole() != ChatMessageDTO.RoleType.USER) {
            throw new IllegalStateException(
                    "Current Agent task user message is missing or is not a User message: messageId="
                            + currentUserMessageId);
        }
        return currentUser;
    }

    private List<CompletedConversationPair> eligibleCompletedPairs(
            List<AgentTask> tasks,
            List<ChatMessageDTO> sortedMessages,
            Map<String, ChatMessageDTO> messagesById,
            Map<String, Integer> messageOrder) {
        Map<String, List<ChatMessageDTO>> finalMessagesByTaskId = sortedMessages.stream()
                .filter(this::isTaskLinkedFinal)
                .collect(Collectors.groupingBy(
                        message -> message.getMetadata().getTaskId(),
                        LinkedHashMap::new,
                        Collectors.toList()));
        List<CompletedConversationPair> pairs = new ArrayList<>();
        for (AgentTask task : tasks) {
            if (task == null || !AgentTaskLogService.STATUS_SUCCESS.equals(task.getStatus())
                    || !StringUtils.hasText(task.getId()) || !StringUtils.hasText(task.getUserMessageId())) {
                continue;
            }
            ChatMessageDTO user = messagesById.get(task.getUserMessageId());
            List<ChatMessageDTO> finals = finalMessagesByTaskId.getOrDefault(task.getId(), List.of());
            if (user == null || user.getRole() != ChatMessageDTO.RoleType.USER || finals.size() != 1) {
                log.warn("Ignoring completed task without a unique durable User/Final pair: taskId={}, userMessageId={}, finalCount={}",
                        task.getId(), task.getUserMessageId(), finals.size());
                continue;
            }
            ChatMessageDTO finalMessage = finals.get(0);
            Integer userOrder = messageOrder.get(user.getId());
            Integer finalOrder = messageOrder.get(finalMessage.getId());
            if (userOrder == null || finalOrder == null || userOrder >= finalOrder) {
                log.warn("Ignoring completed task with invalid durable User/Final order: taskId={}", task.getId());
                continue;
            }
            pairs.add(new CompletedConversationPair(task.getId(), user, finalMessage, finalOrder));
        }
        pairs.sort(Comparator.comparingInt(CompletedConversationPair::finalOrder));
        return List.copyOf(pairs);
    }

    private boolean isTaskLinkedFinal(ChatMessageDTO message) {
        return message != null
                && message.getRole() == ChatMessageDTO.RoleType.ASSISTANT
                && StringUtils.hasText(message.getContent())
                && message.getMetadata() != null
                && StringUtils.hasText(message.getMetadata().getTaskId())
                && (message.getMetadata().getToolCalls() == null
                || message.getMetadata().getToolCalls().isEmpty());
    }

    private int unlinkedLegacyFinalCount(List<ChatMessageDTO> messages) {
        return (int) messages.stream()
                .filter(Objects::nonNull)
                .filter(message -> message.getRole() == ChatMessageDTO.RoleType.ASSISTANT)
                .filter(message -> StringUtils.hasText(message.getContent()))
                .filter(message -> message.getMetadata() == null
                        || !StringUtils.hasText(message.getMetadata().getTaskId()))
                .filter(message -> message.getMetadata() == null
                        || message.getMetadata().getToolCalls() == null
                        || message.getMetadata().getToolCalls().isEmpty())
                .count();
    }

    private CompletedConversationProjection rebuildCompletedConversation(
            String sessionId,
            String model,
            ChatSessionDTO.MetaData metadata,
            List<CompletedConversationPair> eligiblePairs,
            ChatMessageDTO currentUser,
            int unlinkedLegacyFinalCount,
            String safeFallbackSummary) {
        List<ChatMessageDTO> eligibleMessages = completedMessages(eligiblePairs, null);
        String prompt = buildCompletedConversationSummaryPrompt(eligiblePairs);
        try {
            String summary = conversationSummaryClient.summarize(model, prompt);
            String normalizedSummary = summary == null ? null : summary.strip();
            if (!isTaskAwareSummary(normalizedSummary)) {
                throw new IllegalStateException("Completed conversation summary has invalid structure");
            }
            if (normalizedSummary.length() > properties.getMaxSummaryChars()) {
                throw new IllegalStateException("Completed conversation summary exceeds maxSummaryChars");
            }
            int inputTokens = totalContentTokens(model, eligibleMessages).tokens();
            int outputTokens = tokenCounter.countText(model, normalizedSummary).tokens();
            if (outputTokens >= inputTokens) {
                throw new IllegalStateException("Completed conversation summary did not reduce tokens");
            }
            String boundaryMessageId = eligiblePairs.get(eligiblePairs.size() - 1).finalMessage().getId();
            saveMetadata(sessionId, metadata, normalizedSummary, boundaryMessageId);
            return new CompletedConversationProjection(normalizedSummary,
                    currentUser == null ? List.of() : List.of(currentUser),
                    boundaryMessageId, true, unlinkedLegacyFinalCount);
        } catch (Exception error) {
            log.warn("Task-aware completed conversation summary failed closed: sessionId={}, error={}",
                    sessionId, error.getMessage(), error);
            if (StringUtils.hasText(safeFallbackSummary)) {
                CompletedConversationPair fallbackBoundary = eligiblePairs.stream()
                        .filter(pair -> pair.finalMessage().getId().equals(
                                metadata == null ? null : metadata.getContextSummaryLastMessageId()))
                        .findFirst()
                        .orElse(null);
                if (fallbackBoundary != null && isTaskAwareSummary(safeFallbackSummary)) {
                    List<CompletedConversationPair> uncovered = eligiblePairs.stream()
                            .filter(pair -> pair.finalOrder() > fallbackBoundary.finalOrder())
                            .toList();
                    return new CompletedConversationProjection(safeFallbackSummary,
                            completedMessages(uncovered, currentUser),
                            fallbackBoundary.finalMessage().getId(), false, unlinkedLegacyFinalCount);
                }
            }
            clearSummaryMetadata(sessionId, metadata);
            return new CompletedConversationProjection(null,
                    completedMessages(eligiblePairs, currentUser), null, false, unlinkedLegacyFinalCount);
        }
    }

    private List<ChatMessageDTO> completedMessages(List<CompletedConversationPair> pairs,
                                                   ChatMessageDTO currentUser) {
        List<ChatMessageDTO> messages = new ArrayList<>(pairs.size() * 2 + (currentUser == null ? 0 : 1));
        for (CompletedConversationPair pair : pairs) {
            messages.add(pair.userMessage());
            messages.add(pair.finalMessage());
        }
        if (currentUser != null) {
            messages.add(currentUser);
        }
        return List.copyOf(messages);
    }

    private CurrentTaskInputs currentTaskInputs(ChatMessageDTO originalUser,
                                                String conversationSummary,
                                                List<ChatMessageDTO> completedConversationMessages,
                                                List<ChatMessageDTO> currentTaskProtocolMessages,
                                                CurrentTaskWorkingState state) {
        if (originalUser == null || originalUser.getRole() != ChatMessageDTO.RoleType.USER
                || !StringUtils.hasText(originalUser.getContent())) {
            throw new IllegalStateException("Current task requires its explicitly linked original User message");
        }
        CurrentTaskWorkingState safeState = state == null ? CurrentTaskWorkingState.empty() : state;
        List<ChatMessageDTO> protocol = currentTaskProtocolMessages == null
                ? List.of() : List.copyOf(currentTaskProtocolMessages);
        List<LogicalMessageGroup> groups = logicalMessageGroups(protocol);
        for (LogicalMessageGroup group : groups) {
            if (!hasToolCalls(protocol.get(group.startInclusive()))) {
                throw new IllegalStateException("Current task coverage can contain only complete tool protocol groups");
            }
        }
        if (safeState.coveredThroughLogicalGroup() < 0
                || safeState.coveredThroughLogicalGroup() > groups.size()) {
            throw new IllegalStateException("Current task working summary coverage boundary is invalid");
        }
        int uncoveredStart = safeState.coveredThroughLogicalGroup() == groups.size()
                ? protocol.size()
                : groups.get(safeState.coveredThroughLogicalGroup()).startInclusive();
        List<ChatMessageDTO> uncovered = List.copyOf(protocol.subList(uncoveredStart, protocol.size()));
        List<LogicalMessageGroup> uncoveredGroups = groups.subList(
                safeState.coveredThroughLogicalGroup(), groups.size());

        List<ChatMessageDTO> effective = new ArrayList<>(baseCurrentTaskContext(
                originalUser, conversationSummary, completedConversationMessages));
        if (StringUtils.hasText(safeState.summary())) {
            effective.add(currentTaskSummaryMessage(safeState.summary()));
        }
        effective.addAll(uncovered);
        return new CurrentTaskInputs(protocol, List.copyOf(groups), List.copyOf(uncoveredGroups), uncovered,
                List.copyOf(effective));
    }

    private AssimilationSelection selectAssimilation(String model,
                                                      ChatMessageDTO originalUser,
                                                      String conversationSummary,
                                                      List<ChatMessageDTO> completedConversationMessages,
                                                      List<ChatMessageDTO> fixedPlanningMessages,
                                                      CurrentTaskInputs inputs,
                                                      boolean cumulativeContextPressure) {
        List<LogicalMessageGroup> uncoveredGroups = inputs.uncoveredGroups();
        if (uncoveredGroups.isEmpty()) {
            throw new IllegalStateException("Current task pressure has no complete logical group to assimilate");
        }
        int selectedGroupCount = cumulativeContextPressure || uncoveredGroups.size() == 1
                ? uncoveredGroups.size()
                : uncoveredGroups.size() - 1;
        int minimumStateTokens = currentTaskStateBodyTokens(model, minimumContinuationState());
        AssimilationSelection selection;
        while (true) {
            selection = assimilationSelection(model, originalUser, conversationSummary,
                    completedConversationMessages, fixedPlanningMessages, inputs, selectedGroupCount);
            if (selectedGroupCount == uncoveredGroups.size()
                    || selection.availableStateTokens() >= minimumStateTokens) {
                return selection;
            }
            selectedGroupCount++;
        }
    }

    private AssimilationSelection assimilationSelection(String model,
                                                         ChatMessageDTO originalUser,
                                                         String conversationSummary,
                                                         List<ChatMessageDTO> completedConversationMessages,
                                                         List<ChatMessageDTO> fixedPlanningMessages,
                                                         CurrentTaskInputs inputs,
                                                         int selectedGroupCount) {
        List<LogicalMessageGroup> uncoveredGroups = inputs.uncoveredGroups();
        LogicalMessageGroup first = uncoveredGroups.get(0);
        LogicalMessageGroup lastSelected = uncoveredGroups.get(selectedGroupCount - 1);
        List<ChatMessageDTO> selected = List.copyOf(inputs.protocolMessages().subList(
                first.startInclusive(), lastSelected.endExclusive()));
        int retainedStart = selectedGroupCount == uncoveredGroups.size()
                ? inputs.protocolMessages().size()
                : uncoveredGroups.get(selectedGroupCount).startInclusive();
        List<ChatMessageDTO> retained = List.copyOf(inputs.protocolMessages().subList(
                retainedStart, inputs.protocolMessages().size()));
        CandidateMeasurement withoutState = measureCandidate(model, originalUser, conversationSummary,
                completedConversationMessages, fixedPlanningMessages, null, retained);
        CandidateMeasurement withEmptyState = measureCandidate(model, originalUser, conversationSummary,
                completedConversationMessages, fixedPlanningMessages, "", retained);
        int stateWrapperTokens = Math.max(0,
                withEmptyState.fullCandidateTokens() - withoutState.fullCandidateTokens());
        int hardBudget = properties.thresholdFor(model).getMaxContextTokens();
        int availableStateTokens = Math.max(0,
                hardBudget - withoutState.fullCandidateTokens() - stateWrapperTokens);
        return new AssimilationSelection(selectedGroupCount, selected, retained,
                planningContentTokens(model, List.of(), selected).tokens(),
                planningContentTokens(model, List.of(), retained).tokens(),
                withoutState.fullCandidateTokens(), stateWrapperTokens, availableStateTokens);
    }

    private CandidateMeasurement measureCandidate(String model,
                                                   ChatMessageDTO originalUser,
                                                   String conversationSummary,
                                                   List<ChatMessageDTO> completedConversationMessages,
                                                   List<ChatMessageDTO> fixedPlanningMessages,
                                                   String state,
                                                   List<ChatMessageDTO> retainedProtocolMessages) {
        List<ChatMessageDTO> base = baseCurrentTaskContext(
                originalUser, conversationSummary, completedConversationMessages);
        List<ChatMessageDTO> candidate = new ArrayList<>(base);
        if (state != null) {
            candidate.add(currentTaskSummaryMessage(state));
        }
        if (retainedProtocolMessages != null) {
            candidate.addAll(retainedProtocolMessages);
        }
        int fixedTokens = planningContentTokens(model, fixedPlanningMessages, List.of()).tokens();
        int currentUserTokens = planningContentTokens(model, List.of(), List.of(originalUser)).tokens();
        List<ChatMessageDTO> conversationOnly = base.stream()
                .filter(message -> !samePersistentMessage(message, originalUser))
                .toList();
        int conversationTokens = planningContentTokens(model, List.of(), conversationOnly).tokens();
        int stateTokens = state == null ? 0 : currentTaskStateBodyTokens(model, state);
        int stateMessageTokens = state == null ? 0 : currentTaskStateMessageTokens(model, state);
        int rawTokens = planningContentTokens(model, List.of(),
                retainedProtocolMessages == null ? List.of() : retainedProtocolMessages).tokens();
        TokenCount full = planningContentTokens(model, fixedPlanningMessages, candidate);
        return new CandidateMeasurement(fixedTokens, conversationTokens, currentUserTokens,
                stateTokens, stateMessageTokens, rawTokens, full.tokens(), full.source());
    }

    private List<ChatMessageDTO> baseCurrentTaskContext(ChatMessageDTO originalUser,
                                                        String conversationSummary,
                                                        List<ChatMessageDTO> completedConversationMessages) {
        List<ChatMessageDTO> base = new ArrayList<>();
        if (StringUtils.hasText(conversationSummary)) {
            base.add(summaryMessage(conversationSummary));
        }
        if (completedConversationMessages != null) {
            base.addAll(completedConversationMessages);
        }
        boolean currentUserPresent = base.stream().anyMatch(message -> samePersistentMessage(message, originalUser));
        if (!currentUserPresent) {
            base.add(originalUser);
        }
        return List.copyOf(base);
    }

    private boolean samePersistentMessage(ChatMessageDTO left, ChatMessageDTO right) {
        if (left == null || right == null) {
            return false;
        }
        if (StringUtils.hasText(left.getId()) && StringUtils.hasText(right.getId())) {
            return left.getId().equals(right.getId());
        }
        return left == right;
    }

    private String minimumContinuationState() {
        return CURRENT_TASK_SUMMARY_HEADER + "\n\n"
                + CURRENT_TASK_SUMMARY_SECTIONS.stream()
                .map(section -> section + "\n  - none")
                .collect(Collectors.joining("\n"));
    }

    private String buildCurrentTaskSummaryPrompt(ChatMessageDTO originalUser,
                                                 String existingSummary,
                                                 List<ChatMessageDTO> uncoveredProtocolMessages) {
        String previous = StringUtils.hasText(existingSummary) ? existingSummary : "none";
        return "你是 Java Agent 当前任务的Continuation State更新器。\n"
                + "输出不是Tool transcript摘要或Evidence Archive，而是Agent继续完成原始任务现在真正需要的工作状态。\n"
                + "输入只包含原始用户问题、已有Continuation State和本次要assimiliate的完整Tool protocol groups。\n"
                + "只保留仍影响后续Planning的Known事实、关键Constraints/精确值、必要stable refs、Open问题和Next方向。\n"
                + "删除Tool调用过程、背景解释、重复事实、已完成且不影响后续行动的细节和低价值reference。\n"
                + "存在仍有价值的源码引用时以完整repoId/chunkId pair保留。不要复制完整Tool正文，不要添加未确认事实。\n"
                + "必须严格使用以下结构并保留全部section标题：\n\n"
                + CURRENT_TASK_SUMMARY_HEADER + "\n\n"
                + String.join("\n", CURRENT_TASK_SUMMARY_SECTIONS) + "\n\n"
                + "每个section只写一个简洁缩进条目，可用分号分隔必要事实；没有内容写none。\n"
                + "不要写叙事性回答、推理过程或逐Tool复述。\n\n"
                + "Original User Question:\n" + originalUser.getContent() + "\n\n"
                + "Existing Current Task Continuation State:\n" + previous + "\n\n"
                + "Uncovered complete Tool protocol groups:\n"
                + formatCurrentTaskProtocol(uncoveredProtocolMessages);
    }

    private String buildCurrentTaskStructureCorrectivePrompt(ChatMessageDTO originalUser,
                                                             String candidate,
                                                             String validationFailure,
                                                             int availableStateTokens) {
        return "Repair the proposed Current Task Continuation State.\n"
                + "Return plain text only: no explanation and no Markdown code fence.\n"
                + "Copy every heading in the template below byte-for-byte, in exactly this order. "
                + "Do not translate, rename, omit, or add headings.\n"
                + "Do not introduce new facts. Retain only information needed to continue the original task.\n"
                + "If a source locator is retained, keep its complete repoId/chunkId pair.\n"
                + "Use exactly one concise indented bullet under every section; write none when empty.\n"
                + "Target at most " + availableStateTokens + " estimated state tokens so the full Planning request can fit.\n"
                + "Validation failure: " + validationFailure + "\n\n"
                + CURRENT_TASK_SUMMARY_HEADER + "\n\n"
                + CURRENT_TASK_SUMMARY_SECTIONS.stream()
                .map(section -> section + "\n  - <one concise value or none>")
                .collect(Collectors.joining("\n")) + "\n\n"
                + "Original User Question:\n" + originalUser.getContent() + "\n\n"
                + "Candidate to rewrite:\n" + nullToEmpty(candidate);
    }

    private String buildCurrentTaskBudgetCorrectivePrompt(ChatMessageDTO originalUser,
                                                          String proposedState,
                                                          int availableStateTokens) {
        return "Compact only the proposed Current Task Continuation State below. Do not re-analyze Tool evidence.\n"
                + "If space is limited, prioritize in this order:\n"
                + "1. Goal, Open, Next.\n"
                + "2. Known facts and exact Constraints that directly affect the next planning decision.\n"
                + "3. Only Refs likely needed for an exact reread.\n"
                + "Remove Tool-call narration, background explanation, duplication, resolved low-value details, and low-value refs.\n"
                + "Do not introduce new facts. If a locator is retained, keep its complete repoId/chunkId pair.\n"
                + "Keep every required heading in order and exactly one concise indented bullet per section.\n"
                + "Available estimated state token budget: " + availableStateTokens + ". This is guidance; return a complete valid state.\n\n"
                + "Original User Question:\n" + originalUser.getContent() + "\n\n"
                + "Proposed Continuation State to compact:\n" + nullToEmpty(proposedState);
    }

    private String formatCurrentTaskProtocol(List<ChatMessageDTO> messages) {
        return messages.stream().map(message -> {
            if (hasToolCalls(message)) {
                String calls = message.getMetadata().getToolCalls().stream()
                        .map(call -> "id=" + call.id() + ", name=" + call.name()
                                + ", arguments=" + nullToEmpty(call.arguments()))
                        .collect(Collectors.joining("; "));
                return "Assistant tool_calls: " + calls;
            }
            String responseId = toolResponseId(message);
            String toolName = message.getMetadata() == null || message.getMetadata().getToolResponse() == null
                    ? "unknown" : message.getMetadata().getToolResponse().name();
            return "ToolResponse: id=" + responseId + ", name=" + toolName + "\n"
                    + nullToEmpty(message.getContent());
        }).collect(Collectors.joining("\n\n"));
    }

    private void validateCurrentTaskStateStructure(String summary) {
        if (!StringUtils.hasText(summary) || !summary.startsWith(CURRENT_TASK_SUMMARY_HEADER)) {
            if (!StringUtils.hasText(summary)) {
                throw new IllegalStateException("Current task continuation state is empty");
            }
            throw new RepairableCurrentTaskSummaryException("Current task continuation state has invalid structure");
        }
        int previousIndex = -1;
        for (String section : CURRENT_TASK_SUMMARY_SECTIONS) {
            int index = summary.indexOf(section);
            if (index <= previousIndex) {
                throw new RepairableCurrentTaskSummaryException(
                        "Current task continuation state is missing ordered section: " + section);
            }
            previousIndex = index;
        }
        long repoIds = REPO_ID_ASSIGNMENT.matcher(summary).results().count();
        long chunkIds = CHUNK_ID_ASSIGNMENT.matcher(summary).results().count();
        if (repoIds != chunkIds) {
            throw new RepairableCurrentTaskSummaryException(
                    "Current task continuation state contains an incomplete repoId/chunkId pair");
        }
    }

    private void validateCurrentTaskStateReduction(String model,
                                                   String summary,
                                                   String existingSummary,
                                                   List<ChatMessageDTO> selectedProtocolMessages) {
        List<ChatMessageDTO> before = new ArrayList<>();
        if (StringUtils.hasText(existingSummary)) {
            before.add(currentTaskSummaryMessage(existingSummary));
        }
        before.addAll(selectedProtocolMessages);
        int beforeTokens = planningContentTokens(model, List.of(), before).tokens();
        int afterTokens = currentTaskStateMessageTokens(model, summary);
        if (afterTokens >= beforeTokens) {
            throw new IllegalStateException("Current task continuation state did not reduce tokens");
        }
    }

    private void publishCurrentTaskCompression(String sessionId,
                                               String model,
                                               CompressionCheck check,
                                               String prompt,
                                               String previousSummary,
                                               String outputSummary,
                                               long latencyMs,
                                               boolean succeeded,
                                               String failure,
                                               String compressionAttemptId,
                                               String primaryState,
                                               String correctivePrompt,
                                               String correctiveState,
                                               String acceptedState,
                                               boolean accepted,
                                               int coveredThroughLogicalGroup,
                                               List<ChatMessageDTO> selectedProtocolMessages,
                                               List<ChatMessageDTO> remainingRawProtocolMessages,
                                               int summaryDepth,
                                               int compressionCount,
                                               int correctiveRetryCount) {
        if (!AgentLifecycleObservationPublisher.isCompressionObservationEnabled()) {
            return;
        }
        int afterTokens = succeeded
                ? tokenCounter.countText(model,
                ConversationContextCompressor.currentTaskSummaryMessageContent(outputSummary)).tokens()
                : check.effectiveContextTokens();
        AgentLifecycleObservationPublisher.publishCompression(
                new AgentLifecycleObservationPublisher.CompressionObservation(
                        diagnosticTaskId(selectedProtocolMessages, remainingRawProtocolMessages),
                        sessionId, model, "current_task_" + check.reason(),
                        check.effectiveContextTokens(), afterTokens, check.rawHistoryTokens(),
                        check.tokenSource(), prompt, previousSummary, outputSummary,
                        latencyMs, succeeded, failure, compressionAttemptId, primaryState,
                        correctivePrompt, correctiveState, acceptedState, accepted,
                        coveredThroughLogicalGroup, selectedProtocolMessages,
                        remainingRawProtocolMessages, summaryDepth, compressionCount,
                        correctiveRetryCount));
    }

    private String diagnosticTaskId(List<ChatMessageDTO> selectedProtocolMessages,
                                    List<ChatMessageDTO> remainingRawProtocolMessages) {
        return java.util.stream.Stream.concat(
                        selectedProtocolMessages == null ? java.util.stream.Stream.empty()
                                : selectedProtocolMessages.stream(),
                        remainingRawProtocolMessages == null ? java.util.stream.Stream.empty()
                                : remainingRawProtocolMessages.stream())
                .map(ChatMessageDTO::getMetadata)
                .filter(java.util.Objects::nonNull)
                .map(ChatMessageDTO.MetaData::getTaskId)
                .filter(org.springframework.util.StringUtils::hasText)
                .findFirst()
                .orElse(null);
    }

    private ChatMessageDTO currentTaskSummaryMessage(String summary) {
        return ChatMessageDTO.builder()
                .id("current-task-working-summary")
                .role(ChatMessageDTO.RoleType.SYSTEM)
                .content(ConversationContextCompressor.currentTaskSummaryMessageContent(summary))
                .build();
    }

    private boolean completedProjectionNeedsRefresh(String model,
                                                    String summary,
                                                    List<ChatMessageDTO> uncoveredMessages) {
        if (uncoveredMessages.isEmpty()) {
            return false;
        }
        List<ChatMessageDTO> effective = new ArrayList<>();
        effective.add(summaryMessage(summary));
        effective.addAll(uncoveredMessages);
        int tokenLimit = properties.thresholdFor(model).getMaxContextTokens();
        return totalContentTokens(model, effective).tokens() >= tokenLimit;
    }

    private String buildCompletedConversationSummaryPrompt(List<CompletedConversationPair> pairs) {
        String completedConversation = pairs.stream()
                .map(pair -> "Completed Task " + pair.taskId() + "\n"
                        + "User Question:\n" + nullToEmpty(pair.userMessage().getContent()) + "\n\n"
                        + "Final Answer:\n" + nullToEmpty(pair.finalMessage().getContent()))
                .collect(Collectors.joining("\n\n---\n\n"));
        return "你是一个 Java 后端 Agent 的跨任务会话摘要器。\n"
                + "输入只包含已经成功完成并具有可靠User/Final关联的会话。\n"
                + "不要添加工具调用、工具结果、内部执行步骤或未确认事实。\n"
                + "必须严格使用以下结构并保留所有五个section标题：\n\n"
                + TASK_AWARE_SUMMARY_HEADER + "\n\n"
                + String.join("\n", TASK_AWARE_SUMMARY_SECTIONS) + "\n\n"
                + "在各section标题下使用简洁的缩进条目；没有内容时写 none。\n"
                + "保留对未来轮次仍重要的精确数值、约束、决策和未解决事项。\n"
                + "控制在 " + properties.getMaxSummaryChars() + " 字符以内。\n\n"
                + "Eligible completed conversations:\n"
                + completedConversation;
    }

    private boolean isTaskAwareSummary(String summary) {
        if (!StringUtils.hasText(summary) || !summary.startsWith(TASK_AWARE_SUMMARY_HEADER)) {
            return false;
        }
        int previousIndex = -1;
        for (String section : TASK_AWARE_SUMMARY_SECTIONS) {
            int index = summary.indexOf(section);
            if (index <= previousIndex) {
                return false;
            }
            previousIndex = index;
        }
        return true;
    }

    private void clearSummaryMetadata(String sessionId, ChatSessionDTO.MetaData metadata) {
        ChatSessionDTO.MetaData updated = metadata == null ? new ChatSessionDTO.MetaData() : metadata;
        if (!StringUtils.hasText(updated.getContextSummary())
                && !StringUtils.hasText(updated.getContextSummaryLastMessageId())) {
            return;
        }
        updated.setContextSummary(null);
        updated.setContextSummaryLastMessageId(null);
        updated.setContextSummaryUpdatedAt(null);
        try {
            ChatSession chatSession = ChatSession.builder()
                    .id(sessionId)
                    .metadata(objectMapper.writeValueAsString(updated))
                    .build();
            chatSessionMapper.updateById(chatSession);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to clear untrusted completed-conversation summary", error);
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

    private TokenCount planningContentTokens(String model,
                                             List<ChatMessageDTO> fixedPlanningMessages,
                                             List<ChatMessageDTO> contextMessages) {
        List<ChatMessageDTO> measurable = new ArrayList<>();
        if (fixedPlanningMessages != null) {
            measurable.addAll(fixedPlanningMessages.stream()
                    .map(this::toPlanningMeasurementMessage)
                    .toList());
        }
        if (contextMessages != null) {
            measurable.addAll(contextMessages.stream()
                    .map(this::toPlanningMeasurementMessage)
                    .toList());
        }
        return tokenCounter.countMessages(model, measurable);
    }

    private ChatMessageDTO toPlanningMeasurementMessage(ChatMessageDTO message) {
        if (message == null || !hasToolCalls(message)) {
            return message;
        }
        StringBuilder content = new StringBuilder(nullToEmpty(message.getContent()));
        for (AssistantMessage.ToolCall call : message.getMetadata().getToolCalls()) {
            content.append('\n')
                    .append(nullToEmpty(call.id())).append(' ')
                    .append(nullToEmpty(call.name())).append(' ')
                    .append(nullToEmpty(call.arguments()));
        }
        return ChatMessageDTO.builder()
                .id(message.getId())
                .sessionId(message.getSessionId())
                .role(message.getRole())
                .content(content.toString())
                .metadata(message.getMetadata())
                .createdAt(message.getCreatedAt())
                .build();
    }

    private int currentTaskStateBodyTokens(String model, String state) {
        return tokenCounter.countText(model, nullToEmpty(state)).tokens();
    }

    private int currentTaskStateMessageTokens(String model, String state) {
        return tokenCounter.countText(model,
                ConversationContextCompressor.currentTaskSummaryMessageContent(nullToEmpty(state))).tokens();
    }

    private void logCurrentTaskBudgetAttempt(String attemptId,
                                             String model,
                                             ChatMessageDTO originalUser,
                                             String conversationSummary,
                                             List<ChatMessageDTO> completedConversationMessages,
                                             List<ChatMessageDTO> fixedPlanningMessages,
                                             String existingState,
                                             AssimilationSelection selection,
                                             String primaryState,
                                             CandidateMeasurement primaryMeasurement,
                                             boolean budgetCorrectiveInvoked,
                                             String acceptedState,
                                             CandidateMeasurement finalMeasurement,
                                             boolean accepted) {
        CandidateMeasurement before = measureCandidate(model, originalUser, conversationSummary,
                completedConversationMessages, fixedPlanningMessages, existingState,
                mergeProtocol(selection.selectedProtocolMessages(), selection.retainedProtocolMessages()));
        log.info("Current task budget attempt: attemptId={}, candidateBeforeTokens={}, fixedTokens={}, conversationTokens={}, currentUserTokens={}, existingStateTokens={}, selectedGroups={}, selectedRawTokens={}, retainedRawTokens={}, availableStateTokens={}, primaryStateTokens={}, primaryCandidateTokens={}, budgetCorrectiveInvoked={}, correctedStateTokens={}, correctedCandidateTokens={}, accepted={}",
                attemptId, before.fullCandidateTokens(), finalMeasurement.fixedTokens(),
                finalMeasurement.conversationTokens(), finalMeasurement.currentUserTokens(),
                currentTaskStateBodyTokens(model, existingState), selection.selectedGroupCount(),
                selection.selectedRawTokens(), selection.retainedRawTokens(), selection.availableStateTokens(),
                primaryMeasurement.stateTokens(), primaryMeasurement.fullCandidateTokens(),
                budgetCorrectiveInvoked,
                budgetCorrectiveInvoked ? currentTaskStateBodyTokens(model, acceptedState) : 0,
                finalMeasurement.fullCandidateTokens(), accepted);
    }

    private List<ChatMessageDTO> mergeProtocol(List<ChatMessageDTO> first, List<ChatMessageDTO> second) {
        List<ChatMessageDTO> merged = new ArrayList<>();
        if (first != null) {
            merged.addAll(first);
        }
        if (second != null) {
            merged.addAll(second);
        }
        return List.copyOf(merged);
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

    private record CompletedConversationPair(String taskId,
                                             ChatMessageDTO userMessage,
                                             ChatMessageDTO finalMessage,
                                             int finalOrder) {
    }

    private record CurrentTaskInputs(List<ChatMessageDTO> protocolMessages,
                                     List<LogicalMessageGroup> allGroups,
                                     List<LogicalMessageGroup> uncoveredGroups,
                                     List<ChatMessageDTO> uncoveredProtocolMessages,
                                     List<ChatMessageDTO> effectiveMessages) {
    }

    private record AssimilationSelection(int selectedGroupCount,
                                         List<ChatMessageDTO> selectedProtocolMessages,
                                         List<ChatMessageDTO> retainedProtocolMessages,
                                         int selectedRawTokens,
                                         int retainedRawTokens,
                                         int candidateWithoutStateTokens,
                                         int stateWrapperTokens,
                                         int availableStateTokens) {
    }

    private record CandidateMeasurement(int fixedTokens,
                                        int conversationTokens,
                                        int currentUserTokens,
                                        int stateTokens,
                                        int stateMessageTokens,
                                        int rawTokens,
                                        int fullCandidateTokens,
                                        String tokenSource) {
    }

    private static final class RepairableCurrentTaskSummaryException extends IllegalStateException {
        private RepairableCurrentTaskSummaryException(String message) {
            super(message);
        }
    }
}
