package com.kama.jchatmind.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PercentileCalculatorTest {
    private final PercentileCalculator calculator = new PercentileCalculator();

    @Test
    void calculatesNearestRankPercentiles() {
        List<Long> values = java.util.stream.LongStream.rangeClosed(1, 100).boxed().toList();
        assertEquals(50, calculator.percentile(values, 0.50));
        assertEquals(95, calculator.percentile(values, 0.95));
        assertEquals(99, calculator.percentile(values, 0.99));
    }

    @Test
    void handlesEmptyAndRejectsInvalidPercentile() {
        assertEquals(0, calculator.percentile(List.of(), 0.95));
        assertThrows(IllegalArgumentException.class, () -> calculator.percentile(List.of(1L), 0));
    }
}
