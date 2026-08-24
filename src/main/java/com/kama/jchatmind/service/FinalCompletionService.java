package com.kama.jchatmind.service;

/** Durable, Final-only completion boundary. Implementations must not publish SSE. */
public interface FinalCompletionService {

    FinalCompletionResult complete(FinalCompletionCommand command);

    record FinalCompletionCommand(
            String sessionId,
            String taskId,
            String finalAnswer,
            String finalStepId,
            Integer finalStepNo,
            String finalStepSummary,
            Long finalLlmLatencyMs,
            Integer finishStepNo,
            String finishReason,
            String modelName,
            Integer actualSteps,
            Integer toolCallCount) {
    }

    record FinalCompletionResult(
            String messageId,
            String finalStepId,
            Integer finalStepNo,
            String finishStepId,
            Integer finishStepNo) {
    }
}
