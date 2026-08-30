package com.kama.jchatmind.benchmark.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    @Test
    void parsesSupportedExecutionArchitecturesAndRejectsUnknownValues() {
        String property = ContextLifecycleBenchmarkResult.ExecutionArchitecture.PROPERTY;
        String previous = System.getProperty(property);
        try {
            System.clearProperty(property);
            assertEquals(ContextLifecycleBenchmarkResult.ExecutionArchitecture.LEGACY,
                    ContextLifecycleBenchmarkResult.ExecutionArchitecture.configured());
            System.setProperty(property, "legacy");
            assertEquals(ContextLifecycleBenchmarkResult.ExecutionArchitecture.LEGACY,
                    ContextLifecycleBenchmarkResult.ExecutionArchitecture.configured());
            System.setProperty(property, "TASK_AWARE");
            assertEquals(ContextLifecycleBenchmarkResult.ExecutionArchitecture.TASK_AWARE,
                    ContextLifecycleBenchmarkResult.ExecutionArchitecture.configured());
            System.setProperty(property, "XXX");
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    ContextLifecycleBenchmarkResult.ExecutionArchitecture::configured);
            assertTrue(error.getMessage().contains("Unsupported context.benchmark.architecture: XXX"));
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    @Test
    void rejectsNumericZeroWhenTranscriptComponentIsRemoved() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ContextLifecycleBenchmarkResult.ContextMetrics(
                        0, List.of(), 0, 0, 0, 0, 0, 0, 0,
                        0, ContextLifecycleBenchmarkResult.TranscriptMetricStatus.REMOVED_NOT_APPLICABLE,
                        null, null,
                        null, ContextLifecycleBenchmarkResult.TranscriptMetricStatus.REMOVED_NOT_APPLICABLE));

        assertEquals("taskToolTranscriptEstimatedTokens value must be null when component is removed",
                error.getMessage());
    }
}
