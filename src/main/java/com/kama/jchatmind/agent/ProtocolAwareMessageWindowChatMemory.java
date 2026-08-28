package com.kama.jchatmind.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A message window that evicts complete tool protocol batches instead of individual
 * assistant/tool messages.
 */
@Slf4j
final class ProtocolAwareMessageWindowChatMemory implements ChatMemory {

    private final ChatMemoryRepository repository;
    private final int maxMessages;

    ProtocolAwareMessageWindowChatMemory(int maxMessages) {
        this(new InMemoryChatMemoryRepository(), maxMessages);
    }

    ProtocolAwareMessageWindowChatMemory(ChatMemoryRepository repository, int maxMessages) {
        Assert.notNull(repository, "repository cannot be null");
        Assert.isTrue(maxMessages > 0, "maxMessages must be greater than 0");
        this.repository = repository;
        this.maxMessages = maxMessages;
    }

    @Override
    public synchronized void add(String conversationId, List<Message> messages) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        Assert.notNull(messages, "messages cannot be null");
        Assert.noNullElements(messages, "messages cannot contain null elements");

        List<Message> existing = repository.findByConversationId(conversationId);
        List<Message> combined = replaceExistingSystemMessages(existing, messages);
        combined.addAll(messages);
        List<Message> retained = trimToProtocolSafeWindow(combined);
        repository.saveAll(conversationId, retained);
        if (retained.size() != combined.size()) {
            log.info("Protocol-aware runtime memory trimmed: maxMessages={}, beforeMessages={}, "
                            + "afterMessages={}, protocolValid=true, orphanToolResponses=0, partialToolBatches=0",
                    maxMessages, combined.size(), retained.size());
        }
    }

    @Override
    public synchronized List<Message> get(String conversationId) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        return repository.findByConversationId(conversationId);
    }

    @Override
    public synchronized void clear(String conversationId) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        repository.deleteByConversationId(conversationId);
    }

    private List<Message> replaceExistingSystemMessages(List<Message> existing,
                                                        List<Message> incoming) {
        Set<Message> existingMessages = new HashSet<>(existing);
        boolean hasNewSystemMessage = incoming.stream()
                .filter(SystemMessage.class::isInstance)
                .anyMatch(message -> !existingMessages.contains(message));
        if (!hasNewSystemMessage) {
            return new ArrayList<>(existing);
        }
        return existing.stream()
                .filter(message -> !(message instanceof SystemMessage))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private List<Message> trimToProtocolSafeWindow(List<Message> messages) {
        List<LogicalMessageGroup> groups = logicalMessageGroups(messages);
        if (messages.size() <= maxMessages) {
            return messages;
        }

        boolean[] retained = new boolean[groups.size()];
        int systemMessageCount = 0;
        for (int index = 0; index < groups.size(); index++) {
            LogicalMessageGroup group = groups.get(index);
            if (group.system()) {
                retained[index] = true;
                systemMessageCount += group.size();
            }
        }

        int remainingBudget = Math.max(0, maxMessages - systemMessageCount);
        boolean retainedNonSystem = false;
        for (int index = groups.size() - 1; index >= 0; index--) {
            LogicalMessageGroup group = groups.get(index);
            if (group.system()) {
                continue;
            }
            if (group.size() <= remainingBudget) {
                retained[index] = true;
                retainedNonSystem = true;
                remainingBudget -= group.size();
                continue;
            }
            if (!retainedNonSystem && group.toolBatch()) {
                // Correctness wins over a hard message cap when the newest atomic batch
                // cannot fit by itself.
                retained[index] = true;
            }
            break;
        }

        List<Message> result = new ArrayList<>();
        for (int index = 0; index < groups.size(); index++) {
            if (!retained[index]) {
                continue;
            }
            LogicalMessageGroup group = groups.get(index);
            result.addAll(messages.subList(group.startInclusive(), group.endExclusive()));
        }
        return result;
    }

    private static List<LogicalMessageGroup> logicalMessageGroups(List<Message> messages) {
        List<LogicalMessageGroup> groups = new ArrayList<>();
        Set<String> historyToolCallIds = new HashSet<>();
        int index = 0;
        while (index < messages.size()) {
            Message message = messages.get(index);
            if (message instanceof ToolResponseMessage) {
                throw new IllegalStateException("Runtime memory contains orphan tool response at index " + index);
            }
            if (!(message instanceof AssistantMessage assistantMessage) || !hasToolCalls(assistantMessage)) {
                groups.add(new LogicalMessageGroup(
                        index, index + 1, message instanceof SystemMessage, false));
                index++;
                continue;
            }

            Set<String> expectedResponseIds = new HashSet<>();
            for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                if (toolCall == null || !StringUtils.hasText(toolCall.id())) {
                    throw new IllegalStateException(
                            "Runtime memory contains assistant tool call without id at index " + index);
                }
                if (!expectedResponseIds.add(toolCall.id()) || !historyToolCallIds.add(toolCall.id())) {
                    throw new IllegalStateException(
                            "Runtime memory contains duplicate toolCallId: " + toolCall.id());
                }
            }

            Set<String> actualResponseIds = new HashSet<>();
            int groupEnd = index + 1;
            while (groupEnd < messages.size()
                    && messages.get(groupEnd) instanceof ToolResponseMessage responseMessage) {
                for (ToolResponseMessage.ToolResponse response : responseMessage.getResponses()) {
                    if (response == null || !StringUtils.hasText(response.id())) {
                        throw new IllegalStateException(
                                "Runtime memory contains tool response without toolCallId at index " + groupEnd);
                    }
                    if (!expectedResponseIds.contains(response.id())
                            || !actualResponseIds.add(response.id())) {
                        throw new IllegalStateException(
                                "Runtime memory contains unexpected or duplicate tool response: toolCallId="
                                        + response.id());
                    }
                }
                groupEnd++;
            }
            if (!actualResponseIds.equals(expectedResponseIds)) {
                throw new IllegalStateException(
                        "Runtime memory contains incomplete tool response batch at index " + index);
            }
            groups.add(new LogicalMessageGroup(index, groupEnd, false, true));
            index = groupEnd;
        }
        return groups;
    }

    private static boolean hasToolCalls(AssistantMessage message) {
        return message.getToolCalls() != null && !message.getToolCalls().isEmpty();
    }

    static ProtocolValidation inspectProtocol(List<Message> messages) {
        try {
            logicalMessageGroups(messages == null ? List.of() : messages);
            return new ProtocolValidation(0, 0, null);
        } catch (IllegalStateException e) {
            String diagnostic = e.getMessage();
            int orphanCount = diagnostic != null
                    && (diagnostic.contains("orphan tool response")
                    || diagnostic.contains("incomplete tool response batch")
                    || diagnostic.contains("unexpected or duplicate tool response")) ? 1 : 0;
            return new ProtocolValidation(orphanCount, 1, diagnostic);
        }
    }

    record ProtocolValidation(int orphanToolProtocolCount,
                              int protocolValidationFailureCount,
                              String diagnostic) {
    }

    private record LogicalMessageGroup(int startInclusive,
                                       int endExclusive,
                                       boolean system,
                                       boolean toolBatch) {
        private int size() {
            return endExclusive - startInclusive;
        }
    }
}
