package com.kama.jchatmind.benchmark.context;

import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.service.ConversationContextCompressor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ContextOriginAttributor {
    static final String CURRENT_USER = "CURRENT_USER";
    static final String CURRENT_TASK_PLANNING = "CURRENT_TASK_PLANNING";
    static final String CURRENT_TASK_TOOL = "CURRENT_TASK_TOOL";
    static final String COMPLETED_TASK_USER_FINAL = "COMPLETED_TASK_USER_FINAL";
    static final String COMPLETED_TASK_TOOL = "COMPLETED_TASK_TOOL";
    static final String SESSION_SUMMARY = "SESSION_SUMMARY";
    static final String UNKNOWN = "UNKNOWN";

    private final EstimatedMessageTokenMeasurer measurer;

    ContextOriginAttributor(EstimatedMessageTokenMeasurer measurer) {
        this.measurer = measurer;
    }

    Map<String, Integer> attribute(List<Message> requestMessages,
                                   String additionalSystemPrompt,
                                   String currentTaskId,
                                   List<ChatMessageDTO> persistedMessages) {
        Map<String, Integer> totals = emptyTotals();
        Map<String, String> taskByProtocolId = taskByProtocolId(persistedMessages);
        int lastUserIndex = lastUserIndex(requestMessages);
        for (int index = 0; index < requestMessages.size(); index++) {
            Message message = requestMessages.get(index);
            String origin = origin(message, index, lastUserIndex, currentTaskId, taskByProtocolId);
            int tokens = measurer.measure(List.of(message), null).tokens();
            totals.compute(origin, (ignored, value) -> value + tokens);
        }
        if (additionalSystemPrompt != null && !additionalSystemPrompt.isEmpty()) {
            totals.compute(CURRENT_TASK_PLANNING,
                    (ignored, value) -> value + measurer.measureText(additionalSystemPrompt));
        }
        return Map.copyOf(totals);
    }

    private String origin(Message message,
                          int index,
                          int lastUserIndex,
                          String currentTaskId,
                          Map<String, String> taskByProtocolId) {
        Set<String> protocolIds = protocolIds(message);
        if (!protocolIds.isEmpty()) {
            Set<String> taskIds = new LinkedHashSet<>();
            protocolIds.stream().map(taskByProtocolId::get).forEach(taskIds::add);
            if (taskIds.size() == 1 && taskIds.iterator().next() != null) {
                return currentTaskId != null && currentTaskId.equals(taskIds.iterator().next())
                        ? CURRENT_TASK_TOOL : COMPLETED_TASK_TOOL;
            }
            return UNKNOWN;
        }
        if (message instanceof SystemMessage
                && message.getText() != null
                && message.getText().startsWith(ConversationContextCompressor.SUMMARY_PREFIX)) {
            return SESSION_SUMMARY;
        }
        if (message instanceof UserMessage) {
            return index == lastUserIndex ? CURRENT_USER : COMPLETED_TASK_USER_FINAL;
        }
        if (message instanceof AssistantMessage) {
            return index > lastUserIndex ? CURRENT_TASK_PLANNING : COMPLETED_TASK_USER_FINAL;
        }
        return UNKNOWN;
    }

    private Map<String, String> taskByProtocolId(List<ChatMessageDTO> messages) {
        Map<String, String> result = new LinkedHashMap<>();
        if (messages == null) {
            return result;
        }
        for (ChatMessageDTO message : messages) {
            if (message == null || message.getMetadata() == null) {
                continue;
            }
            String taskId = message.getMetadata().getTaskId();
            if (message.getMetadata().getToolCalls() != null) {
                message.getMetadata().getToolCalls().stream()
                        .filter(value -> value != null && value.id() != null)
                        .forEach(value -> result.put(value.id(), taskId));
            }
            if (message.getMetadata().getToolResponse() != null
                    && message.getMetadata().getToolResponse().id() != null) {
                result.put(message.getMetadata().getToolResponse().id(), taskId);
            }
        }
        return result;
    }

    private Set<String> protocolIds(Message message) {
        Set<String> ids = new LinkedHashSet<>();
        if (message instanceof AssistantMessage assistant && assistant.getToolCalls() != null) {
            assistant.getToolCalls().stream().filter(value -> value != null && value.id() != null)
                    .forEach(value -> ids.add(value.id()));
        }
        if (message instanceof ToolResponseMessage responseMessage) {
            responseMessage.getResponses().stream().filter(value -> value != null && value.id() != null)
                    .forEach(value -> ids.add(value.id()));
        }
        return ids;
    }

    private int lastUserIndex(List<Message> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof UserMessage) {
                return index;
            }
        }
        return -1;
    }

    private Map<String, Integer> emptyTotals() {
        Map<String, Integer> values = new LinkedHashMap<>();
        values.put(CURRENT_USER, 0);
        values.put(CURRENT_TASK_PLANNING, 0);
        values.put(CURRENT_TASK_TOOL, 0);
        values.put(COMPLETED_TASK_USER_FINAL, 0);
        values.put(COMPLETED_TASK_TOOL, 0);
        values.put(SESSION_SUMMARY, 0);
        values.put(UNKNOWN, 0);
        return values;
    }
}
