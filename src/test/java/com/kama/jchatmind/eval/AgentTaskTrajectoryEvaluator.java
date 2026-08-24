package com.kama.jchatmind.eval;

import com.kama.jchatmind.model.entity.AgentStep;
import com.kama.jchatmind.model.entity.AgentTask;
import com.kama.jchatmind.model.entity.ToolCallLog;
import com.kama.jchatmind.service.AgentTaskLogService;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

class AgentTaskTrajectoryEvaluator {
    private final AgentTaskFailureClassifier failureClassifier = new AgentTaskFailureClassifier();

    AgentTaskEvalResult evaluate(AgentTaskEvalCase evalCase, AgentTaskTrajectory trajectory) {
        AgentTask task = Objects.requireNonNull(trajectory.task(), "task");
        List<AgentStep> steps = trajectory.steps();
        List<ToolCallLog> toolCalls = trajectory.toolCalls();
        List<String> toolNames = toolCalls.stream().map(ToolCallLog::getToolName)
                .filter(Objects::nonNull).toList();
        List<String> toolArguments = toolCalls.stream().map(ToolCallLog::getArgumentsJson)
                .filter(Objects::nonNull).toList();

        int duplicateRejectCount = countError(toolCalls, AgentTaskLogService.ERROR_TYPE_DUPLICATE_TOOL_CALL);
        int timeoutCount = countError(toolCalls, AgentTaskLogService.ERROR_TYPE_TOOL_TIMEOUT);
        int resultTruncatedCount = (int) toolCalls.stream()
                .filter(call -> Boolean.TRUE.equals(call.getResultTruncated())).count();
        int hardStopCount = (int) toolCalls.stream()
                .filter(call -> AgentTaskLogService.ERROR_TYPE_DUPLICATE_TOOL_CALL.equals(call.getErrorType()))
                .filter(call -> contains(call.getErrorMessage(), "hardStop=true"))
                .count();
        int rejectedToolCalls = (int) toolCalls.stream().filter(this::rejectedBeforeExecution).count();
        boolean executionCountReliable = toolCalls.stream()
                .noneMatch(call -> AgentTaskLogService.ERROR_TYPE_POLICY_REJECTED.equals(call.getErrorType()));
        int executedToolCalls = Math.max(0, toolCalls.size() - rejectedToolCalls);

        Boolean requiredToolHit = AgentTaskEvalCase.safe(evalCase.requiredTools).isEmpty()
                ? null
                : containsAllIgnoreCase(toolNames, evalCase.requiredTools);
        boolean effectiveRequiredToolHit = requiredToolHit == null || requiredToolHit;
        boolean forbiddenToolUsed = containsAnyIgnoreCase(toolNames, evalCase.forbiddenTools);
        Boolean argumentHit = AgentTaskEvalCase.safe(evalCase.expectedArgumentKeywords).isEmpty()
                ? null
                : containsAll(String.join("\n", toolArguments), evalCase.expectedArgumentKeywords);

        String evidenceText = toolCalls.stream().map(ToolCallLog::getResultSummary)
                .filter(Objects::nonNull).collect(Collectors.joining("\n"));
        Boolean evidenceHit = evalCase.evidenceApplicable()
                ? evidenceMatches(evalCase, evidenceText)
                : null;
        Boolean answerKeywordHit = AgentTaskEvalCase.safe(evalCase.expectedAnswerKeywords).isEmpty()
                ? null
                : containsAll(trajectory.finalAnswer(), evalCase.expectedAnswerKeywords);
        boolean finalAnswerPresent = trajectory.finalAnswer() != null && !trajectory.finalAnswer().isBlank();
        boolean runtimeSuccess = AgentTaskLogService.STATUS_SUCCESS.equals(task.getStatus());
        boolean toolSelectionSuccess = effectiveRequiredToolHit && !forbiddenToolUsed;
        boolean evidenceSuccess = evidenceHit == null || evidenceHit;
        boolean answerKeywordSuccess = answerKeywordHit == null || answerKeywordHit;
        boolean expectedStatus = Objects.equals(evalCase.expectedTaskStatus, task.getStatus());
        boolean taskSuccess = expectedStatus && toolSelectionSuccess && evidenceSuccess
                && answerKeywordSuccess && finalAnswerPresent;
        boolean reasonableStepsExceeded = evalCase.maxReasonableSteps != null
                && countSteps(steps, "THINK") > evalCase.maxReasonableSteps;
        boolean evaluationSupported = executionCountReliable || toolCalls.stream()
                .noneMatch(call -> AgentTaskLogService.ERROR_TYPE_POLICY_REJECTED.equals(call.getErrorType()));

        AgentTaskFailureType failureType = taskSuccess
                ? AgentTaskFailureType.SUCCESS
                : failureClassifier.classify(evalCase, task.getStatus(), task.getFinishReason(), task.getErrorMessage(),
                effectiveRequiredToolHit, forbiddenToolUsed, argumentHit, evidenceHit, finalAnswerPresent,
                duplicateRejectCount, timeoutCount, hardStopCount, resultTruncatedCount, evaluationSupported);

        TokenUsage usage = tokenUsage(steps);
        return new AgentTaskEvalResult(
                evalCase, task.getId(), task.getStatus(), task.getFinishReason(), trajectory.finalAnswer(),
                countSteps(steps, "THINK"), countSteps(steps, "TOOL_CALL"),
                toolCalls.size(), executedToolCalls, rejectedToolCalls, executionCountReliable,
                toolNames, toolArguments, duplicateRejectCount, timeoutCount, resultTruncatedCount,
                hardStopCount, requiredToolHit, forbiddenToolUsed, argumentHit, evidenceHit,
                answerKeywordHit, finalAnswerPresent, runtimeSuccess, toolSelectionSuccess, taskSuccess,
                reasonableStepsExceeded, failureType, task.getLatencyMs() == null ? 0 : task.getLatencyMs(),
                usage.promptTokens(), usage.completionTokens(), usage.totalTokens(), usage.available(),
                trajectorySummary(steps, toolCalls));
    }

    private int countSteps(List<AgentStep> steps, String type) {
        return (int) steps.stream().filter(step -> type.equals(step.getStepType())).count();
    }

    private int countError(List<ToolCallLog> calls, String errorType) {
        return (int) calls.stream().filter(call -> errorType.equals(call.getErrorType())).count();
    }

    private boolean rejectedBeforeExecution(ToolCallLog call) {
        String errorType = call.getErrorType();
        return AgentTaskLogService.ERROR_TYPE_DUPLICATE_TOOL_CALL.equals(errorType)
                || AgentTaskLogService.ERROR_TYPE_UNKNOWN_TOOL.equals(errorType)
                || AgentTaskLogService.ERROR_TYPE_POLICY_REJECTED.equals(errorType);
    }

    private boolean evidenceMatches(AgentTaskEvalCase evalCase, String evidenceText) {
        return groupMatches(evidenceText, evalCase.expectedEvidenceFileKeywords)
                && groupMatches(evidenceText, evalCase.expectedEvidenceSymbolKeywords)
                && groupMatches(evidenceText, evalCase.expectedEvidenceKeywords);
    }

    private boolean groupMatches(String text, List<String> keywords) {
        List<String> expected = AgentTaskEvalCase.safe(keywords);
        return expected.isEmpty() || containsAny(text, expected);
    }

    private boolean containsAll(String text, List<String> keywords) {
        String normalized = normalize(text);
        return AgentTaskEvalCase.safe(keywords).stream().allMatch(keyword -> normalized.contains(normalize(keyword)));
    }

    private boolean containsAny(String text, List<String> keywords) {
        String normalized = normalize(text);
        return AgentTaskEvalCase.safe(keywords).stream().anyMatch(keyword -> normalized.contains(normalize(keyword)));
    }

    private boolean contains(String text, String keyword) {
        return normalize(text).contains(normalize(keyword));
    }

    private boolean containsAllIgnoreCase(List<String> actual, List<String> expected) {
        return AgentTaskEvalCase.safe(expected).stream()
                .allMatch(value -> actual.stream().anyMatch(item -> item.equalsIgnoreCase(value)));
    }

    private boolean containsAnyIgnoreCase(List<String> actual, List<String> expected) {
        return AgentTaskEvalCase.safe(expected).stream()
                .anyMatch(value -> actual.stream().anyMatch(item -> item.equalsIgnoreCase(value)));
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private TokenUsage tokenUsage(List<AgentStep> steps) {
        List<AgentStep> thinkSteps = steps.stream().filter(step -> "THINK".equals(step.getStepType())).toList();
        boolean available = !thinkSteps.isEmpty() && thinkSteps.stream()
                .allMatch(step -> step.getInputTokens() != null && step.getOutputTokens() != null);
        if (!available) {
            return new TokenUsage(null, null, null, false);
        }
        int prompt = thinkSteps.stream().mapToInt(AgentStep::getInputTokens).sum();
        int completion = thinkSteps.stream().mapToInt(AgentStep::getOutputTokens).sum();
        return new TokenUsage(prompt, completion, prompt + completion, true);
    }

    private String trajectorySummary(List<AgentStep> steps, List<ToolCallLog> toolCalls) {
        String stepPath = steps.stream().map(AgentStep::getStepType).collect(Collectors.joining(" -> "));
        String calls = toolCalls.stream()
                .map(call -> call.getToolName() + "[" + call.getStatus()
                        + (call.getErrorType() == null ? "" : "/" + call.getErrorType()) + "]")
                .collect(Collectors.joining(" -> "));
        return stepPath + (calls.isBlank() ? "" : " | tools: " + calls);
    }

    private record TokenUsage(Integer promptTokens, Integer completionTokens,
                              Integer totalTokens, boolean available) {
    }
}
