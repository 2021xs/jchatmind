package com.kama.jchatmind.eval;

import com.kama.jchatmind.model.dto.CodeSearchResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeRagEvaluationReportWriterTest {
    private static final Path TEST_OUTPUT_DIRECTORY = Path.of("target", "test-eval", "code-rag-report-writer");

    private final CodeRagEvaluationReportWriter writer = new CodeRagEvaluationReportWriter();

    @BeforeEach
    void prepareOutputDirectory() throws Exception {
        Files.createDirectories(TEST_OUTPUT_DIRECTORY);
        deleteKnownOutputs();
    }

    @AfterEach
    void cleanOutputDirectory() throws Exception {
        deleteKnownOutputs();
    }

    @Test
    void writesStableCsvHeadersAndEscapesSpecialCharacters() throws Exception {
        CodeRagEvalCaseResult result = result();

        writer.write(TEST_OUTPUT_DIRECTORY, List.of(result), new CodeRagEvaluationReportWriter.Environment(
                OffsetDateTime.parse("2026-08-12T12:00:00+08:00"), "21", "jdbc:postgresql://localhost/db",
                "bge-m3", "gpt-test", 20, 5));

        String detail = Files.readString(TEST_OUTPUT_DIRECTORY.resolve("code-rag-eval-detail.csv"));
        String summary = Files.readString(TEST_OUTPUT_DIRECTORY.resolve("code-rag-eval-summary.csv"));
        String report = Files.readString(TEST_OUTPUT_DIRECTORY.resolve("code-rag-evaluation-report.md"));
        assertEquals(String.join(",", CodeRagEvaluationReportWriter.DETAIL_HEADERS), detail.lines().findFirst().orElseThrow());
        assertEquals(String.join(",", CodeRagEvaluationReportWriter.SUMMARY_HEADERS), summary.lines().findFirst().orElseThrow());
        assertTrue(detail.contains("\"question, with \"\"quote\"\""));
        assertTrue(detail.contains("line two\""));
        assertTrue(report.contains("keyword-level acceptable evidence"));
        assertTrue(report.contains("Selector"));
    }

    @Test
    void summaryCalculatesRetrievalSelectorAndLatencyMetrics() {
        CodeRagEvaluationReportWriter.Summary summary = writer.summarize(List.of(result())).get(0);

        assertEquals(1.0, summary.recall1());
        assertEquals(1.0, summary.selected1());
        assertEquals(1, summary.count(CodeRagFailureType.SUCCESS));
        assertEquals(0, summary.invalidSelectorIdCount());
        assertEquals(0, summary.emptySelectorResultCount());
        assertEquals(11, summary.embedding().p50());
        assertEquals(12, summary.retrieval().p95());
        assertEquals(13, summary.selector().p99());
        assertEquals(40, summary.total().p50());
        assertEquals("AVAILABLE", summary.usageStatus());
        assertEquals(101, summary.promptTokens().p50());
        assertEquals(9, summary.completionTokens().p95());
        assertEquals(110, summary.totalTokens().sum());
        assertEquals(1234, summary.promptChars().p50());
        assertEquals(900, summary.candidateSectionChars().sum());
    }

    private void deleteKnownOutputs() throws Exception {
        Files.deleteIfExists(TEST_OUTPUT_DIRECTORY.resolve("code-rag-eval-detail.csv"));
        Files.deleteIfExists(TEST_OUTPUT_DIRECTORY.resolve("code-rag-eval-summary.csv"));
        Files.deleteIfExists(TEST_OUTPUT_DIRECTORY.resolve("code-rag-evaluation-report.md"));
    }

    private CodeRagEvalCaseResult result() {
        CodeRagEvalCase evalCase = new CodeRagEvalCase();
        evalCase.id = "case-1";
        evalCase.query = "question, with \"quote\"\nline two";
        evalCase.category = "API";
        evalCase.difficulty = "BASIC";
        evalCase.expectedFileKeywords = List.of("Controller");
        evalCase.expectedSymbolKeywords = List.of("method");
        evalCase.expectedChunkTypes = List.of("CONTROLLER_API");
        CodeSearchResult evidence = CodeSearchResult.builder()
                .chunkId("chunk-1")
                .filePath("src/Controller.java")
                .chunkType("CONTROLLER_API")
                .symbolName("Controller#method")
                .startLine(10)
                .endLine(20)
                .score(0.9)
                .build();
        return new CodeRagEvalCaseResult(evalCase, List.of(evidence), List.of(evidence),
                1, 1, CodeRagFailureType.SUCCESS, false, true, false,
                List.of("chunk-1"), List.of("chunk-1"), List.of(),
                List.of("C01"), List.of("C01"), List.of(), "", false, "",
                11, 12, 13, 40, 101, 9, 110, true, 1234, 900);
    }
}
