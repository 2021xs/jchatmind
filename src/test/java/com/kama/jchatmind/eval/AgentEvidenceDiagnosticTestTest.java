package com.kama.jchatmind.eval;

import com.kama.jchatmind.model.dto.CodeAnswerEvidenceResult;
import com.kama.jchatmind.model.dto.CodeSearchExecutionResult;
import com.kama.jchatmind.model.dto.CodeSearchResult;
import com.kama.jchatmind.model.entity.AgentTask;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEvidenceDiagnosticTestTest {
    @Test
    void searchDiagnosticReportsRawAndSelectedGroundTruthRanks() {
        AgentTaskEvalCase evalCase = new AgentTaskEvalCase();
        evalCase.expectedEvidenceFileKeywords = List.of("ShopServiceImpl");
        evalCase.expectedEvidenceSymbolKeywords = List.of("queryShopWithTwoLevelCache");
        CodeSearchResult distractor = CodeSearchResult.builder()
                .chunkId("chunk-a").filePath("Other.java").symbolName("other").build();
        CodeSearchResult gt = CodeSearchResult.builder()
                .chunkId("chunk-gt").filePath("ShopServiceImpl.java")
                .symbolName("queryShopWithTwoLevelCache").build();
        CodeSearchExecutionResult execution = CodeSearchExecutionResult.builder()
                .candidates(List.of(distractor, gt))
                .build();

        AgentEvidenceDiagnosticTest.SearchDiagnostic diagnostic =
                AgentEvidenceDiagnosticTest.SearchDiagnostic.from(
                        1, "shop cache", "repo-1", execution,
                        "selected evidence ShopServiceImpl queryShopWithTwoLevelCache", evalCase, 50);

        assertTrue(diagnostic.rawGtHit());
        assertEquals(2, diagnostic.rawGtRank());
        assertTrue(diagnostic.selectedGtHit());
        assertEquals(1, diagnostic.selectedGtRank());
    }

    @Test
    void reportWriterEscapesCsvAndWritesDiagnosticFiles() throws Exception {
        AgentTask task = AgentTask.builder().id("task-1").traceId("trace-1")
                .status("SUCCESS").latencyMs(12L).build();
        AgentTaskEvalCase evalCase = new AgentTaskEvalCase();
        evalCase.id = "case-1";
        evalCase.category = "CODE_LOCATION";
        evalCase.difficulty = "BASIC";
        AgentEvidenceDiagnosticTest.RunResult run = new AgentEvidenceDiagnosticTest.RunResult(
                evalCase.id, evalCase.category, evalCase.difficulty, 1, task,
                2, 1, 1, true, "SUCCESS", "final answer", List.of());
        Path directory = Path.of("target", "test-eval", "agent-evidence-presentation-v1");
        new AgentEvidenceDiagnosticTest.DiagnosticReportWriter().write(
                directory, List.of(evalCase), List.of(run),
                com.kama.jchatmind.model.entity.CodeRepository.builder()
                        .id("repo-1").name("FlashDeal").build(), "agent-1", 50);

        assertTrue(Files.exists(directory.resolve("agent-evidence-run-detail.csv")));
        assertTrue(Files.exists(directory.resolve("agent-evidence-presentation-call-detail.csv")));
        assertTrue(Files.readString(directory.resolve("agent-evidence-run-detail.csv")).contains("case-1"));
        assertTrue(Files.readString(directory.resolve("agent-evidence-diagnostic-report.md")).contains("Run Detail"));
    }
}
