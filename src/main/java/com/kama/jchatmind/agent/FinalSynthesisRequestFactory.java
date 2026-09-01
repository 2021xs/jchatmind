package com.kama.jchatmind.agent;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only projection from the protocol-bearing managed Working Context into
 * a structured final request. Invalid protocol fails closed; the context is
 * never edited or repaired here.
 */
public final class FinalSynthesisRequestFactory {

    static final String DEFAULT_FINAL_ANSWER_POLICY = "Answer the user's request directly and completely. "
            + "Use only supported evidence, distinguish facts from uncertainty, and do not expose internal formats.";

    public FinalSynthesisRequest create(List<Message> executionTranscript) {
        return createFromManagedContext(executionTranscript, null);
    }

    public FinalSynthesisRequest create(List<Message> executionTranscript, String originalUserQuestion) {
        return createFromManagedContext(executionTranscript, originalUserQuestion);
    }

    /** Builds the Final request exclusively from the managed Working Context. */
    public FinalSynthesisRequest createFromManagedContext(List<Message> managedWorkingContext,
                                                          String originalUserQuestion) {
        Assert.notNull(managedWorkingContext, "Managed Working Context cannot be null");
        List<Message> safeMessages = AgentMemoryHistorySanitizer.toSafeModelMessages(managedWorkingContext);
        assertProtocolWasNotDropped(managedWorkingContext, safeMessages);

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
