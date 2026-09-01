package com.kama.jchatmind.agent;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only projection from the protocol-bearing execution transcript into a
 * structured final request. Invalid protocol fails closed; the transcript is
 * never edited or repaired here.
 */
public final class FinalSynthesisRequestFactory {

    static final String DEFAULT_FINAL_ANSWER_POLICY = "Answer the user's request directly and completely. "
            + "Use only supported evidence, distinguish facts from uncertainty, and do not expose internal formats.";

    public FinalSynthesisRequest create(List<Message> executionTranscript) {
        return create(executionTranscript, null);
    }

    public FinalSynthesisRequest create(List<Message> executionTranscript, String originalUserQuestion) {
        return create(executionTranscript, List.of(), originalUserQuestion);
    }

    /** Builds the Final request exclusively from the managed Working Context. */
    public FinalSynthesisRequest createFromManagedContext(List<Message> managedWorkingContext,
                                                          String originalUserQuestion) {
        return create(managedWorkingContext, List.of(), originalUserQuestion);
    }

    public FinalSynthesisRequest create(List<Message> executionTranscript,
                                        List<Message> currentTaskToolTranscript,
                                        String originalUserQuestion) {
        Assert.notNull(executionTranscript, "Execution transcript cannot be null");
        Assert.notNull(currentTaskToolTranscript, "Current task tool transcript cannot be null");
        List<Message> mergedTranscript = mergeCurrentTaskToolTranscript(
                executionTranscript, currentTaskToolTranscript);
        List<Message> safeMessages = AgentMemoryHistorySanitizer.toSafeModelMessages(mergedTranscript);
        assertProtocolWasNotDropped(mergedTranscript, safeMessages);

        Map<String, ToolResponseMessage.ToolResponse> responsesById = responsesById(safeMessages);
        int lastUserIndex = lastUserIndex(safeMessages);
        boolean hasExplicitQuestion = StringUtils.hasText(originalUserQuestion);
        int currentUserIndex = hasExplicitQuestion && lastUserIndex >= 0
                && originalUserQuestion.equals(safeMessages.get(lastUserIndex).getText())
                ? lastUserIndex
                : hasExplicitQuestion ? -1 : lastUserIndex;
        String effectiveOriginalQuestion = hasExplicitQuestion
                ? originalUserQuestion
                : currentUserIndex < 0 ? null : safeMessages.get(currentUserIndex).getText();
        if (!StringUtils.hasText(effectiveOriginalQuestion)) {
            throw new IllegalStateException("Final synthesis requires an original user question");
        }

        List<FinalConversationMessage> conversation = new ArrayList<>();
        List<FinalEvidenceBatch> batches = new ArrayList<>();
        Set<String> consumedResponseIds = new HashSet<>();
        int batchIndex = 0;

        for (int index = 0; index < safeMessages.size(); index++) {
            Message message = safeMessages.get(index);
            if (message instanceof ToolResponseMessage) {
                continue;
            }
            if (message instanceof AssistantMessage assistant && hasToolCalls(assistant)) {
                List<FinalEvidence> evidence = new ArrayList<>();
                for (int evidenceIndex = 0; evidenceIndex < assistant.getToolCalls().size(); evidenceIndex++) {
                    AssistantMessage.ToolCall call = assistant.getToolCalls().get(evidenceIndex);
                    if (call == null || !StringUtils.hasText(call.id())) {
                        throw new IllegalStateException("Final synthesis tool call is missing toolCallId");
                    }
                    ToolResponseMessage.ToolResponse response = responsesById.get(call.id());
                    if (response == null) {
                        throw new IllegalStateException(
                                "Final synthesis tool call has no matching response: toolCallId=" + call.id());
                    }
                    if (!consumedResponseIds.add(call.id())) {
                        throw new IllegalStateException(
                                "Final synthesis context contains duplicate tool call: toolCallId=" + call.id());
                    }
                    evidence.add(new FinalEvidence(
                            "evidence-" + (batchIndex + 1) + "-" + (evidenceIndex + 1),
                            call.id(), response.name(), response.responseData(), Map.of()));
                }
                batches.add(new FinalEvidenceBatch(++batchIndex, evidence));
                continue;
            }
            if (message instanceof SystemMessage && StringUtils.hasText(message.getText())) {
                conversation.add(new FinalConversationMessage(
                        FinalConversationMessage.Role.SYSTEM, message.getText()));
            } else if (message instanceof UserMessage && index != currentUserIndex
                    && StringUtils.hasText(message.getText())) {
                conversation.add(new FinalConversationMessage(
                        FinalConversationMessage.Role.USER, message.getText()));
            } else if (message instanceof AssistantMessage && StringUtils.hasText(message.getText())) {
                conversation.add(new FinalConversationMessage(
                        FinalConversationMessage.Role.ASSISTANT, message.getText()));
            }
        }
        if (consumedResponseIds.size() != responsesById.size()) {
            throw new IllegalStateException("Final synthesis context contains an unmatched tool response");
        }
        return new FinalSynthesisRequest(
                effectiveOriginalQuestion, conversation, batches, DEFAULT_FINAL_ANSWER_POLICY);
    }

    private List<Message> mergeCurrentTaskToolTranscript(List<Message> executionTranscript,
                                                         List<Message> currentTaskToolTranscript) {
        if (currentTaskToolTranscript.isEmpty()) {
            return executionTranscript;
        }

        List<Message> safeExecution = AgentMemoryHistorySanitizer.toSafeModelMessages(executionTranscript);
        assertProtocolWasNotDropped(executionTranscript, safeExecution);
        assertCompleteProtocol(safeExecution, "Execution transcript");

        List<Message> safeTask = AgentMemoryHistorySanitizer.toSafeModelMessages(currentTaskToolTranscript);
        assertProtocolWasNotDropped(currentTaskToolTranscript, safeTask);
        Set<String> currentTaskIds = assertCompleteCurrentTaskBatches(safeTask);
        Map<String, AssistantMessage.ToolCall> currentTaskCalls = callsById(safeTask);
        Map<String, ToolResponseMessage.ToolResponse> currentTaskResponses = responsesById(safeTask);

        List<Message> merged = new ArrayList<>(safeExecution.size() + safeTask.size());
        for (Message message : safeExecution) {
            Set<String> messageProtocolIds = protocolIds(message);
            if (messageProtocolIds.isEmpty()
                    || Collections.disjoint(messageProtocolIds, currentTaskIds)) {
                merged.add(message);
                continue;
            }
            if (!currentTaskIds.containsAll(messageProtocolIds)) {
                throw new IllegalStateException(
                        "Execution transcript partially overlaps the current task tool transcript");
            }
            assertMatchingCurrentTaskCopy(message, currentTaskCalls, currentTaskResponses);
            // The current task copy is authoritative and is appended below in true task order.
        }
        merged.addAll(safeTask);
        return List.copyOf(merged);
    }

    private Map<String, AssistantMessage.ToolCall> callsById(List<Message> messages) {
        Map<String, AssistantMessage.ToolCall> calls = new HashMap<>();
        for (Message message : messages) {
            if (!(message instanceof AssistantMessage assistant) || !hasToolCalls(assistant)) {
                continue;
            }
            for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                if (calls.putIfAbsent(call.id(), call) != null) {
                    throw new IllegalStateException(
                            "Current task tool transcript contains duplicate toolCallId: " + call.id());
                }
            }
        }
        return calls;
    }

    private void assertMatchingCurrentTaskCopy(
            Message message,
            Map<String, AssistantMessage.ToolCall> currentTaskCalls,
            Map<String, ToolResponseMessage.ToolResponse> currentTaskResponses) {
        if (message instanceof AssistantMessage assistant && hasToolCalls(assistant)) {
            for (AssistantMessage.ToolCall existing : assistant.getToolCalls()) {
                AssistantMessage.ToolCall expected = currentTaskCalls.get(existing.id());
                if (expected == null
                        || !Objects.equals(existing.type(), expected.type())
                        || !Objects.equals(existing.name(), expected.name())
                        || !Objects.equals(existing.arguments(), expected.arguments())) {
                    throw new IllegalStateException(
                            "Execution transcript conflicts with current task tool call: toolCallId="
                                    + existing.id());
                }
            }
            return;
        }
        if (message instanceof ToolResponseMessage responseMessage) {
            for (ToolResponseMessage.ToolResponse existing : responseMessage.getResponses()) {
                ToolResponseMessage.ToolResponse expected = currentTaskResponses.get(existing.id());
                if (expected == null
                        || !Objects.equals(existing.name(), expected.name())
                        || !Objects.equals(existing.responseData(), expected.responseData())) {
                    throw new IllegalStateException(
                            "Execution transcript conflicts with current task tool response: toolCallId="
                                    + existing.id());
                }
            }
        }
    }

    private void assertCompleteProtocol(List<Message> messages, String label) {
        Map<String, ToolResponseMessage.ToolResponse> responses = responsesById(messages);
        Set<String> callIds = new HashSet<>();
        for (Message message : messages) {
            if (!(message instanceof AssistantMessage assistant) || !hasToolCalls(assistant)) {
                continue;
            }
            for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                if (call == null || !StringUtils.hasText(call.id())) {
                    throw new IllegalStateException(label + " contains a tool call without id");
                }
                if (!callIds.add(call.id())) {
                    throw new IllegalStateException(label + " contains duplicate toolCallId: " + call.id());
                }
                if (!responses.containsKey(call.id())) {
                    throw new IllegalStateException(label + " contains an incomplete tool response batch");
                }
            }
        }
        if (callIds.size() != responses.size()) {
            throw new IllegalStateException(label + " contains an unmatched tool response");
        }
    }

    private Set<String> assertCompleteCurrentTaskBatches(List<Message> messages) {
        Set<String> allIds = new LinkedHashSet<>();
        int index = 0;
        while (index < messages.size()) {
            if (!(messages.get(index) instanceof AssistantMessage assistant) || !hasToolCalls(assistant)) {
                throw new IllegalStateException(
                        "Current task tool transcript must contain only complete tool protocol batches");
            }
            if (index + 1 >= messages.size()
                    || !(messages.get(index + 1) instanceof ToolResponseMessage responseMessage)) {
                throw new IllegalStateException("Current task tool transcript contains an incomplete batch");
            }

            Set<String> expectedIds = new LinkedHashSet<>();
            for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                if (call == null || !StringUtils.hasText(call.id())
                        || !expectedIds.add(call.id()) || !allIds.add(call.id())) {
                    throw new IllegalStateException(
                            "Current task tool transcript contains a missing or duplicate toolCallId");
                }
            }
            Set<String> actualIds = new LinkedHashSet<>();
            for (ToolResponseMessage.ToolResponse response : responseMessage.getResponses()) {
                if (response == null || !StringUtils.hasText(response.id())
                        || response.responseData() == null || !actualIds.add(response.id())) {
                    throw new IllegalStateException(
                            "Current task tool transcript contains an invalid tool response");
                }
            }
            if (!actualIds.equals(expectedIds)) {
                throw new IllegalStateException(
                        "Current task tool transcript contains an incomplete tool response batch");
            }
            index += 2;
        }
        return Set.copyOf(allIds);
    }

    private Set<String> protocolIds(Message message) {
        Set<String> ids = new LinkedHashSet<>();
        if (message instanceof AssistantMessage assistant && hasToolCalls(assistant)) {
            for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                if (call != null && StringUtils.hasText(call.id())) {
                    ids.add(call.id());
                }
            }
        } else if (message instanceof ToolResponseMessage responseMessage) {
            for (ToolResponseMessage.ToolResponse response : responseMessage.getResponses()) {
                if (response != null && StringUtils.hasText(response.id())) {
                    ids.add(response.id());
                }
            }
        }
        return ids;
    }

    private void assertProtocolWasNotDropped(List<Message> original, List<Message> safe) {
        long originalProtocolMessages = original.stream().filter(this::isToolProtocolMessage).count();
        long safeProtocolMessages = safe.stream().filter(this::isToolProtocolMessage).count();
        if (originalProtocolMessages != safeProtocolMessages) {
            throw new IllegalStateException(
                    "Final synthesis context contains incomplete or orphaned tool protocol messages");
        }
    }

    private Map<String, ToolResponseMessage.ToolResponse> responsesById(List<Message> messages) {
        Map<String, ToolResponseMessage.ToolResponse> responses = new HashMap<>();
        for (Message message : messages) {
            if (!(message instanceof ToolResponseMessage toolResponseMessage)) {
                continue;
            }
            for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                if (response == null || !StringUtils.hasText(response.id())) {
                    throw new IllegalStateException("Final synthesis tool response is missing toolCallId");
                }
                if (response.responseData() == null) {
                    throw new IllegalStateException(
                            "Final synthesis tool response is missing evidence content: toolCallId=" + response.id());
                }
                if (responses.putIfAbsent(response.id(), response) != null) {
                    throw new IllegalStateException(
                            "Final synthesis context contains duplicate tool response: toolCallId=" + response.id());
                }
            }
        }
        return responses;
    }

    private int lastUserIndex(List<Message> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof UserMessage && StringUtils.hasText(messages.get(index).getText())) {
                return index;
            }
        }
        return -1;
    }

    private boolean isToolProtocolMessage(Message message) {
        return message instanceof ToolResponseMessage
                || message instanceof AssistantMessage assistant && hasToolCalls(assistant);
    }

    private boolean hasToolCalls(AssistantMessage assistant) {
        return assistant.getToolCalls() != null && !assistant.getToolCalls().isEmpty();
    }
}
