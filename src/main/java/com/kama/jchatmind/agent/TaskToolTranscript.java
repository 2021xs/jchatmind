package com.kama.jchatmind.agent;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Task-local, append-only copy of complete tool protocol batches.
 *
 * <p>Managed Final no longer reads this transcript. Writes remain temporarily
 * enabled for rollback and attribution validation until transcript removal.</p>
 */
final class TaskToolTranscript {

    private final List<Message> protocolMessages = new ArrayList<>();
    private final Set<String> toolCallIds = new HashSet<>();
    private int batchCount;
    private int readCount;

    void append(AssistantMessage assistant, ToolResponseMessage responseMessage) {
        if (assistant == null || responseMessage == null) {
            throw new IllegalStateException("Task tool transcript requires an assistant call and response");
        }
        if (assistant.getToolCalls() == null || assistant.getToolCalls().isEmpty()) {
            throw new IllegalStateException("Task tool transcript assistant message has no tool calls");
        }

        Set<String> expectedIds = new LinkedHashSet<>();
        for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
            if (call == null || !StringUtils.hasText(call.id())) {
                throw new IllegalStateException("Task tool transcript contains a tool call without id");
            }
            if (!expectedIds.add(call.id()) || toolCallIds.contains(call.id())) {
                throw new IllegalStateException(
                        "Task tool transcript contains duplicate toolCallId: " + call.id());
            }
        }

        Set<String> actualIds = new LinkedHashSet<>();
        for (ToolResponseMessage.ToolResponse response : responseMessage.getResponses()) {
            if (response == null || !StringUtils.hasText(response.id())) {
                throw new IllegalStateException("Task tool transcript contains a response without toolCallId");
            }
            if (response.responseData() == null) {
                throw new IllegalStateException(
                        "Task tool transcript contains a response without data: toolCallId=" + response.id());
            }
            if (!actualIds.add(response.id())) {
                throw new IllegalStateException(
                        "Task tool transcript contains duplicate response: toolCallId=" + response.id());
            }
        }
        if (!actualIds.equals(expectedIds)) {
            throw new IllegalStateException("Task tool transcript contains an incomplete tool response batch");
        }

        protocolMessages.add(assistant);
        protocolMessages.add(responseMessage);
        toolCallIds.addAll(expectedIds);
        batchCount++;
    }

    List<Message> snapshot() {
        readCount++;
        return List.copyOf(protocolMessages);
    }

    int readCount() {
        return readCount;
    }

    int batchCount() {
        return batchCount;
    }

    int toolCallCount() {
        return toolCallIds.size();
    }

    void clear() {
        protocolMessages.clear();
        toolCallIds.clear();
        batchCount = 0;
        readCount = 0;
    }
}
