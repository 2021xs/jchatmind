package com.kama.jchatmind.eval;

import com.kama.jchatmind.service.AgentTaskLogService;

class AgentTaskFailureClassifier {

    AgentTaskFailureType classify(AgentTaskEvalCase evalCase,
                                  String taskStatus,
                                  String finishReason,
                                  String taskError,
                                  boolean requiredToolHit,
                                  boolean forbiddenToolUsed,
                                  Boolean argumentHit,
                                  Boolean evidenceHit,
                                  boolean finalAnswerPresent,
                                  int duplicateRejectCount,
                                  int timeoutCount,
                                  int hardStopCount,
                                  int resultTruncatedCount,
                                  boolean evaluationSupported) {
        if (!evaluationSupported) {
            return AgentTaskFailureType.EVAL_UNSUPPORTED;
        }
        if (timeoutCount > 0) {
            return AgentTaskFailureType.TOOL_TIMEOUT;
        }
        if (AgentTaskLogService.FINISH_REASON_MAX_STEPS_REACHED.equals(finishReason)) {
            return AgentTaskFailureType.MAX_STEPS;
        }
        if (!AgentTaskLogService.STATUS_SUCCESS.equals(taskStatus)) {
            return looksLikeModelFailure(taskError)
                    ? AgentTaskFailureType.MODEL_ERROR
                    : AgentTaskFailureType.TOOL_ERROR;
        }
        if (forbiddenToolUsed || !requiredToolHit) {
            return AgentTaskFailureType.TOOL_SELECTION_ERROR;
        }
        if (Boolean.FALSE.equals(evidenceHit)) {
            if (Boolean.FALSE.equals(argumentHit)) {
                return AgentTaskFailureType.TOOL_ARGUMENT_ERROR;
            }
            if (AgentTaskEvalCase.safe(evalCase.requiredTools).stream()
                    .anyMatch("searchProjectCode"::equalsIgnoreCase)) {
                return AgentTaskFailureType.UNRESOLVED_CODE_EVIDENCE_FAILURE;
            }
            return AgentTaskFailureType.EVIDENCE_MISS;
        }
        if (!finalAnswerPresent) {
            if (hardStopCount > 0 || duplicateRejectCount > 0) {
                return AgentTaskFailureType.DUPLICATE_LOOP;
            }
            return AgentTaskFailureType.FINAL_ANSWER_MISSING;
        }
        if (resultTruncatedCount > 0 && !evalCase.evidenceApplicable()) {
            return AgentTaskFailureType.RESULT_TRUNCATION_RISK;
        }
        return AgentTaskFailureType.SUCCESS;
    }

    private boolean looksLikeModelFailure(String error) {
        if (error == null) {
            return false;
        }
        String normalized = error.toLowerCase();
        return normalized.contains("chatclient")
                || normalized.contains("model")
                || normalized.contains("provider")
                || normalized.contains("llm");
    }
}
