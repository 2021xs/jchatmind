package com.kama.jchatmind.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentTaskMetricCalculatorTest {

    @Test
    void calculatesRatesPercentilesAndGovernanceCounts() {
        AgentTaskMetricCalculator calculator = new AgentTaskMetricCalculator();
        List<AgentTaskEvalResult> results = List.of(
                result("a", true, 1, 1, 100, 0),
                result("b", true, 2, 2, 200, 1),
                result("c", false, 5, 4, 500, 0));

        AgentTaskMetricCalculator.Summary summary = calculator.summarize("ALL", results);

        assertEquals(3, summary.cases());
        assertEquals(2.0 / 3.0, summary.taskSuccessRate(), 0.0001);
        assertEquals(2, summary.thinkSteps().p50());
        assertEquals(5, summary.thinkSteps().p95());
        assertEquals(4, summary.executedToolCalls().max());
        assertEquals(500, summary.latencyMs().p99());
        assertEquals(1, summary.duplicateRejectCount());
        assertEquals("UNAVAILABLE", summary.tokenStatus());
    }

    private AgentTaskEvalResult result(String id, boolean success, int steps, int calls,
                                       long latency, int duplicates) {
        AgentTaskEvalCase evalCase = new AgentTaskEvalCase();
        evalCase.id = id;
        evalCase.category = "TEST";
        evalCase.difficulty = "BASIC";
        evalCase.query = id;
        return new AgentTaskEvalResult(evalCase, "task-" + id, "SUCCESS", "NO_TOOL_CALLS", "answer",
                steps, calls, calls, calls, 0, true, List.of("tool"), List.of("{}"),
                duplicates, 0, 0, 0, true, false, null, true, null,
                true, true, true, success, false,
                success ? AgentTaskFailureType.SUCCESS : AgentTaskFailureType.EVIDENCE_MISS,
                latency, null, null, null, false, "THINK");
    }
}
