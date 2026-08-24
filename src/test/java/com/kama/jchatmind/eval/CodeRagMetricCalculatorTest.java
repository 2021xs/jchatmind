package com.kama.jchatmind.eval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeRagMetricCalculatorTest {
    private final CodeRagMetricCalculator calculator = new CodeRagMetricCalculator();

    @Test
    void calculatesRecallBoundaries() {
        assertTrue(calculator.hitAt(1, 1));
        assertTrue(calculator.hitAt(5, 5));
        assertFalse(calculator.hitAt(6, 5));
        assertFalse(calculator.hitAt(0, 5));
    }

    @Test
    void calculatesReciprocalRankAndMissing() {
        assertEquals(1.0, calculator.reciprocalRank(1));
        assertEquals(0.2, calculator.reciprocalRank(5));
        assertEquals(0.0, calculator.reciprocalRank(0));
    }
}
