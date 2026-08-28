package com.kama.jchatmind.benchmark.context;

import com.kama.jchatmind.config.FinalSynthesisProperties;
import org.junit.jupiter.api.Test;

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

    private ContextLifecycleBenchmarkResult.ModelCallMetric call(
            int index, ContextLifecycleBenchmarkResult.TokenMeasurement input) {
        return new ContextLifecycleBenchmarkResult.ModelCallMetric(
                index, index, index == 1 ? "THINK" : "FINAL", "model", 1L, "STOP", input,
                ContextLifecycleBenchmarkResult.TokenMeasurement.unavailable(),
                1, input.estimatedTokens(), EstimatedMessageTokenMeasurer.SOURCE, Map.of(), null);
    }
}
