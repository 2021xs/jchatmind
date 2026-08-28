package com.kama.jchatmind.benchmark.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextLifecycleBenchmarkResultTest {

    @Test
    void keepsActualAndEstimatedTokenMeasurementsIndependent() throws Exception {
        ContextLifecycleBenchmarkResult.TokenMeasurement measurement =
                new ContextLifecycleBenchmarkResult.TokenMeasurement(
                        123, 140, "PROVIDER_USAGE", "ESTIMATED_CHARS");

        String json = new ObjectMapper().writeValueAsString(measurement);

        assertTrue(json.contains("\"actualTokens\":123"));
        assertTrue(json.contains("\"estimatedTokens\":140"));
        assertTrue(json.contains("\"actualSource\":\"PROVIDER_USAGE\""));
        assertTrue(json.contains("\"estimatedSource\":\"ESTIMATED_CHARS\""));
    }

    @Test
    void rejectsTokenValuesWithoutMatchingProvenance() {
        IllegalArgumentException actual = assertThrows(IllegalArgumentException.class,
                () -> new ContextLifecycleBenchmarkResult.TokenMeasurement(1, null, null, "UNAVAILABLE"));
        IllegalArgumentException estimated = assertThrows(IllegalArgumentException.class,
                () -> new ContextLifecycleBenchmarkResult.TokenMeasurement(null, 1, "UNAVAILABLE", null));

        assertEquals("actualSource is required when actualTokens is present", actual.getMessage());
        assertEquals("estimatedSource is required when estimatedTokens is present", estimated.getMessage());
    }

    @Test
    void representsUnavailableUsageWithoutInventingZero() {
        ContextLifecycleBenchmarkResult.TokenMeasurement measurement =
                ContextLifecycleBenchmarkResult.TokenMeasurement.unavailable();

        assertEquals(null, measurement.actualTokens());
        assertEquals(null, measurement.estimatedTokens());
        assertEquals("UNAVAILABLE", measurement.actualSource());
        assertEquals("UNAVAILABLE", measurement.estimatedSource());
    }
}
