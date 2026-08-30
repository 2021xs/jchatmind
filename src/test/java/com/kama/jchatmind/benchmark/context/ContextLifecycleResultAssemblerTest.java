package com.kama.jchatmind.benchmark.context;

import com.kama.jchatmind.config.FinalSynthesisProperties;
import com.kama.jchatmind.model.entity.AgentTask;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ContextLifecycleResultAssemblerTest {

    @Test
    void doesNotExposePartialProviderUsageAsAnActualTaskTotal() {
        ContextLifecycleResultAssembler assembler =
                new ContextLifecycleResultAssembler(3, new FinalSynthesisProperties());
        List<ContextLifecycleBenchmarkResult.ModelCallMetric> calls = List.of(
                call(1, new ContextLifecycleBenchmarkResult.TokenMeasurement(
                        100, 90, "PROVIDER_USAGE", EstimatedMessageTokenMeasurer.SOURCE)),
                call(2, new ContextLifecycleBenchmarkResult.TokenMeasurement(
                        null, 80, "UNAVAILABLE", EstimatedMessageTokenMeasurer.SOURCE)));

        ContextLifecycleBenchmarkResult.TokenMeasurement total =
                assembler.aggregate(calls, value -> true, true);

        assertNull(total.actualTokens());
        assertEquals(170, total.estimatedTokens());
        assertEquals("UNAVAILABLE_INCOMPLETE_PROVIDER_USAGE_1_OF_2", total.actualSource());
    }

    @Test
    void preservesLegacyTranscriptZeroAsPresent() {
        ContextLifecycleBenchmarkResult.ContextMetrics context = new ContextLifecycleResultAssembler(
                3, new FinalSynthesisProperties(),
                ContextLifecycleBenchmarkResult.ExecutionArchitecture.LEGACY)
                .assemble(execution()).context();

        assertEquals(0, context.taskToolTranscriptEstimatedTokens());
        assertEquals(ContextLifecycleBenchmarkResult.TranscriptMetricStatus.PRESENT,
                context.taskToolTranscriptStatus());
        assertEquals(0, context.finalTranscriptContributionTokens());
        assertEquals(ContextLifecycleBenchmarkResult.TranscriptMetricStatus.PRESENT,
                context.finalTranscriptContributionStatus());
    }

    @Test
    void marksTaskAwareTranscriptMetricsRemovedWithoutNumericZero() {
        ContextLifecycleBenchmarkResult.ContextMetrics context = new ContextLifecycleResultAssembler(
                3, new FinalSynthesisProperties(),
                ContextLifecycleBenchmarkResult.ExecutionArchitecture.TASK_AWARE)
                .assemble(execution()).context();

        assertNull(context.taskToolTranscriptEstimatedTokens());
        assertEquals(ContextLifecycleBenchmarkResult.TranscriptMetricStatus.REMOVED_NOT_APPLICABLE,
                context.taskToolTranscriptStatus());
        assertNull(context.finalTranscriptContributionTokens());
        assertEquals(ContextLifecycleBenchmarkResult.TranscriptMetricStatus.REMOVED_NOT_APPLICABLE,
                context.finalTranscriptContributionStatus());
    }

    private ContextLifecycleCaseExecution execution() {
        ContextLifecycleBenchmarkCase benchmarkCase = new ContextLifecycleBenchmarkCase();
        benchmarkCase.caseId = "case-1";
        benchmarkCase.category = "A";
        ContextLifecycleObservationCollector.CaseCapture capture =
                new ContextLifecycleObservationCollector.CaseCapture(
                        benchmarkCase.caseId, 1, "session-1", OffsetDateTime.now());
        capture.taskId = "task-1";
        return new ContextLifecycleCaseExecution(
                benchmarkCase,
                1,
                "session-1",
                AgentTask.builder().id("task-1").status("SUCCESS").build(),
                List.of(), List.of(), List.of(), capture, null);
    }

    private ContextLifecycleBenchmarkResult.ModelCallMetric call(
            int index, ContextLifecycleBenchmarkResult.TokenMeasurement input) {
        return new ContextLifecycleBenchmarkResult.ModelCallMetric(
                index, index, index == 1 ? "THINK" : "FINAL", "model", 1L, "STOP", input,
                ContextLifecycleBenchmarkResult.TokenMeasurement.unavailable(),
                1, input.estimatedTokens(), EstimatedMessageTokenMeasurer.SOURCE, Map.of(), null);
    }
}
