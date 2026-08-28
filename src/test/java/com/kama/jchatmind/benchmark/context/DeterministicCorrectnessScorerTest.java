package com.kama.jchatmind.benchmark.context;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeterministicCorrectnessScorerTest {

    @Test
    void scoresCriticalFactsExactValuesAndForbiddenClaimsIndependently() {
        ContextLifecycleBenchmarkCase benchmarkCase = new ContextLifecycleBenchmarkCase();
        benchmarkCase.expectedCriticalFacts = List.of(
                fact("location", List.of("VoucherOrderController", "seckillVoucher"), List.of()),
                fact("queue", List.of(), List.of("SECKILL_ORDER_QUEUE", "queue constant")));
        ContextLifecycleBenchmarkCase.ExactValueExpectation exact =
                new ContextLifecycleBenchmarkCase.ExactValueExpectation();
        exact.id = "return-code";
        exact.value = "2";
        benchmarkCase.exactValues = List.of(exact);
        benchmarkCase.expectedRefs = List.of("VoucherOrderController");
        benchmarkCase.forbiddenClaims = List.of("database write succeeded");

        ContextLifecycleBenchmarkResult.CorrectnessMetrics result =
                new DeterministicCorrectnessScorer().score(benchmarkCase,
                        "VoucherOrderController calls seckillVoucher. SECKILL_ORDER_QUEUE uses return code 2.");

        assertEquals(1.0, result.criticalFactRecall());
        assertEquals(1.0, result.exactValueAccuracy());
        assertEquals(0, result.forbiddenClaimCount());
        assertEquals(true, result.supportingFacts().get(0).matched());
    }

    @Test
    void doesNotTreatPartialTokenAsAnExactNumericValue() {
        ContextLifecycleBenchmarkCase benchmarkCase = new ContextLifecycleBenchmarkCase();
        benchmarkCase.expectedCriticalFacts = List.of(fact("fact", List.of("code"), List.of()));
        ContextLifecycleBenchmarkCase.ExactValueExpectation exact =
                new ContextLifecycleBenchmarkCase.ExactValueExpectation();
        exact.id = "value";
        exact.value = "2";
        benchmarkCase.exactValues = List.of(exact);

        ContextLifecycleBenchmarkResult.CorrectnessMetrics result =
                new DeterministicCorrectnessScorer().score(benchmarkCase, "code 20");

        assertEquals(0.0, result.exactValueAccuracy());
    }

    private ContextLifecycleBenchmarkCase.FactExpectation fact(
            String id, List<String> allOf, List<String> anyOf) {
        ContextLifecycleBenchmarkCase.FactExpectation value =
                new ContextLifecycleBenchmarkCase.FactExpectation();
        value.id = id;
        value.allOf = allOf;
        value.anyOf = anyOf;
        return value;
    }
}
