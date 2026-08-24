package com.kama.jchatmind.agent;

import org.springframework.util.Assert;

import java.util.List;

/**
 * Structured final-synthesis input. This is deliberately not a Provider DTO and
 * not an alias for Spring AI's List<Message>.
 */
public record FinalSynthesisRequest(
        String originalUserQuestion,
        List<FinalConversationMessage> conversationContext,
        List<FinalEvidenceBatch> evidenceBatches,
        String finalAnswerPolicy) {

    public FinalSynthesisRequest {
        Assert.hasText(originalUserQuestion, "Original user question cannot be empty");
        Assert.notNull(conversationContext, "Final conversation context cannot be null");
        Assert.notNull(evidenceBatches, "Final evidence batches cannot be null");
        Assert.hasText(finalAnswerPolicy, "Final answer policy cannot be empty");
        conversationContext = List.copyOf(conversationContext);
        evidenceBatches = List.copyOf(evidenceBatches);
    }
}
