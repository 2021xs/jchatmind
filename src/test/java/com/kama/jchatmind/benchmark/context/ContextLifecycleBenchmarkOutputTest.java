package com.kama.jchatmind.benchmark.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextLifecycleBenchmarkOutputTest {
    @TempDir Path temporaryDirectory;

    @Test
    void writesAuditableJsonCsvMarkdownAndAnomalyArtifacts() throws Exception {
        ContextLifecycleBenchmarkResult result = new ContextLifecycleBenchmarkResult(metadata(), List.of(caseResult()));

        ContextLifecycleBenchmarkOutput.Artifacts artifacts = new ContextLifecycleBenchmarkOutput(
                new ObjectMapper().findAndRegisterModules()).write(temporaryDirectory, result);

        assertTrue(Files.readString(artifacts.rawJson()).contains("\"actualTokens\" : 11"));
        assertTrue(Files.readString(artifacts.caseCsv()).contains("total_input_tokens_actual"));
        assertTrue(Files.readString(artifacts.markdownReport()).contains(
                "JChatMind Context Lifecycle Legacy Baseline Report"));
        assertTrue(Files.readString(artifacts.anomaliesCsv()).contains("CRITICAL_FACT_MISS"));
    }

    private ContextLifecycleBenchmarkResult.RunMetadata metadata() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-29T08:00:00+08:00");
        return new ContextLifecycleBenchmarkResult.RunMetadata(
                "run-1", "v1", "LEGACY", "abc", "CLEAN", "gpt-5.5", 0.7, null,
                Map.of("seed", "unavailable"), "repo-1", "FlashDeal", "def", "CLEAN",
                "files", "chunks", 1, 2, 2, 20, 20,
                Map.of("enabled", true), Map.of("enabled", true), now, now.plusSeconds(1),
                3, 1, "actual and estimated separate", "deterministic");
    }

    private ContextLifecycleBenchmarkResult.CaseResult caseResult() {
        ContextLifecycleBenchmarkResult.TokenMeasurement actual =
                new ContextLifecycleBenchmarkResult.TokenMeasurement(11, 12, "PROVIDER_USAGE", "ESTIMATED");
        ContextLifecycleBenchmarkResult.TokenMeasurement estimated =
                new ContextLifecycleBenchmarkResult.TokenMeasurement(null, 3, "UNAVAILABLE", "ESTIMATED");
        ContextLifecycleBenchmarkResult.TokenTotals totals = new ContextLifecycleBenchmarkResult.TokenTotals(
                actual, actual, actual, actual, estimated, estimated, actual, actual, estimated, estimated);
        ContextLifecycleBenchmarkResult.ContextMetrics context = new ContextLifecycleBenchmarkResult.ContextMetrics(
                100, List.of(100), 80, 10, 20, 30, 5, 15, 2, 40, 50, 80, 30);
        ContextLifecycleBenchmarkResult.ToolMetrics tools = new ContextLifecycleBenchmarkResult.ToolMetrics(
                1, Map.of("searchProjectCode", 1), 20, 15, 20, 30);
        ContextLifecycleBenchmarkResult.CompressionTotals compression =
                new ContextLifecycleBenchmarkResult.CompressionTotals(1, 100, 20, 80, 1);
        ContextLifecycleBenchmarkResult.StabilityMetrics stability =
                new ContextLifecycleBenchmarkResult.StabilityMetrics(0, 0, 0, 0, 0);
        ContextLifecycleBenchmarkResult.CorrectnessMetrics correctness =
                new ContextLifecycleBenchmarkResult.CorrectnessMetrics(
                        0.5, 1.0, 0, List.of(), List.of(), List.of(), List.of(),
                        "NOT_USED_DETERMINISTIC_ONLY");
        return new ContextLifecycleBenchmarkResult.CaseResult(
                "case-1", "A", 1, "task-1", "session-1", "SUCCESS", "COMPLETED",
                1000, 500, 200, 100, 200, totals, context, tools, compression, stability,
                correctness, List.of(), List.of(), List.of(), "answer", List.of());
    }
}
