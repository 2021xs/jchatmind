package com.kama.jchatmind.eval;

import com.kama.jchatmind.model.entity.AgentStep;
import com.kama.jchatmind.model.entity.AgentTask;
import com.kama.jchatmind.model.entity.ToolCallLog;
import com.kama.jchatmind.service.AgentTaskLogService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskTrajectoryEvaluatorTest {
    private final AgentTaskTrajectoryEvaluator evaluator = new AgentTaskTrajectoryEvaluator();

    @Test
    void successfulCodeTrajectoryRequiresToolEvidenceAndFinalAnswer() {
        AgentTaskEvalCase evalCase = codeCase();
        AgentTaskTrajectory trajectory = trajectory(
                List.of(step("THINK", null, null), step("TOOL_CALL", null, null), step("FINISH", null, null)),
                List.of(tool("searchProjectCode", AgentTaskLogService.STATUS_SUCCESS,
                        "{\"query\":\"seckill order\"}",
                        "filePath: VoucherOrderController.java\nsymbolName: seckillVoucher", null, false)),
                "The endpoint is implemented by VoucherOrderController.");

        AgentTaskEvalResult result = evaluator.evaluate(evalCase, trajectory);

        assertTrue(result.taskSuccess());
        assertEquals(AgentTaskFailureType.SUCCESS, result.failureType());
        assertEquals(1, result.executedToolCalls());
        assertEquals(Boolean.TRUE, result.evidenceHit());
        assertFalse(result.tokenUsageAvailable());
        assertNull(result.totalTokens());
    }

    @Test
    void missingRequiredToolIsToolSelectionFailure() {
        AgentTaskEvalResult result = evaluator.evaluate(codeCase(), trajectory(
                List.of(step("THINK", null, null), step("FINISH", null, null)),
                List.of(), "An unsupported answer"));

        assertFalse(result.taskSuccess());
        assertEquals(AgentTaskFailureType.TOOL_SELECTION_ERROR, result.failureType());
    }

    @Test
    void calledCodeToolWithoutExpectedEvidenceIsUnresolvedEvidenceFailure() {
        AgentTaskEvalResult result = evaluator.evaluate(codeCase(), trajectory(
                List.of(step("THINK", null, null), step("TOOL_CALL", null, null)),
                List.of(tool("searchProjectCode", AgentTaskLogService.STATUS_SUCCESS,
                        "{\"query\":\"seckill order\"}",
                        "filePath: WrongController.java", null, false)), "answer"));

        assertEquals(AgentTaskFailureType.UNRESOLVED_CODE_EVIDENCE_FAILURE, result.failureType());
        assertEquals(Boolean.TRUE, result.argumentHit());
        assertEquals(Boolean.FALSE, result.evidenceHit());
    }

    @Test
    void duplicateRejectionIsRequestedButNotExecutedAndHardStopIsVisible() {
        AgentTaskEvalCase evalCase = codeCase();
        List<ToolCallLog> calls = List.of(
                tool("searchProjectCode", AgentTaskLogService.STATUS_SUCCESS, "{\"query\":\"seckill\"}",
                        "VoucherOrderController seckillVoucher", null, false),
                tool("searchProjectCode", AgentTaskLogService.STATUS_FAILED, "{\"query\":\"seckill\"}",
                        null, AgentTaskLogService.ERROR_TYPE_DUPLICATE_TOOL_CALL, false,
                        "Duplicate tool call rejected before execution: hardStop=true"));

        AgentTaskEvalResult result = evaluator.evaluate(evalCase, trajectory(
                List.of(step("THINK", 10, 2), step("TOOL_CALL", null, null)), calls, "answer"));

        assertEquals(2, result.requestedToolCalls());
        assertEquals(1, result.executedToolCalls());
        assertEquals(1, result.rejectedToolCalls());
        assertEquals(1, result.duplicateRejectCount());
        assertEquals(1, result.hardStopCount());
        assertTrue(result.tokenUsageAvailable());
        assertEquals(12, result.totalTokens());
    }

    private AgentTaskEvalCase codeCase() {
        AgentTaskEvalCase evalCase = new AgentTaskEvalCase();
        evalCase.id = "case-1";
        evalCase.difficulty = "BASIC";
        evalCase.category = "CODE_LOCATION";
        evalCase.query = "Where is seckill API?";
        evalCase.requiredTools = List.of("searchProjectCode");
        evalCase.allowedTools = List.of("searchProjectCode");
        evalCase.forbiddenTools = List.of("databaseQuery");
        evalCase.expectedArgumentKeywords = List.of("seckill");
        evalCase.expectedEvidenceFileKeywords = List.of("VoucherOrderController");
        evalCase.expectedEvidenceSymbolKeywords = List.of("seckillVoucher");
        return evalCase;
    }

    private AgentTaskTrajectory trajectory(List<AgentStep> steps, List<ToolCallLog> calls, String answer) {
        return new AgentTaskTrajectory(AgentTask.builder()
                .id("task-1")
                .status(AgentTaskLogService.STATUS_SUCCESS)
                .finishReason(AgentTaskLogService.FINISH_REASON_NO_TOOL_CALLS)
                .latencyMs(100L)
                .build(), steps, calls, answer);
    }

    private AgentStep step(String type, Integer inputTokens, Integer outputTokens) {
        return AgentStep.builder().stepType(type).status(AgentTaskLogService.STATUS_SUCCESS)
                .inputTokens(inputTokens).outputTokens(outputTokens).build();
    }

    private ToolCallLog tool(String name, String status, String args, String result,
                             String errorType, boolean truncated) {
        return tool(name, status, args, result, errorType, truncated, null);
    }

    private ToolCallLog tool(String name, String status, String args, String result,
                             String errorType, boolean truncated, String errorMessage) {
        return ToolCallLog.builder().toolName(name).status(status).argumentsJson(args)
                .resultSummary(result).errorType(errorType).errorMessage(errorMessage)
                .resultTruncated(truncated).build();
    }
}
