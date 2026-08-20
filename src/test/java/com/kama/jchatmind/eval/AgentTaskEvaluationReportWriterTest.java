package com.kama.jchatmind.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskEvaluationReportWriterTest {
    @TempDir
    Path tempDir;

    @Test
    void writesEscapedDetailSummaryAndMarkdown() throws Exception {
        AgentTaskEvaluationReportWriter writer = new AgentTaskEvaluationReportWriter();
        AgentTaskEvalCase evalCase = new AgentTaskEvalCase();
        evalCase.id = "case-1";
        evalCase.category = "CODE,LOCATION";
        evalCase.difficulty = "BASIC";
        evalCase.query = "query";
        AgentTaskEvalResult result = new AgentTaskEvalResult(
                evalCase, "task-1", "SUCCESS", "NO_TOOL_CALLS", "answer", 1, 1, 1, 1, 0,
                true, List.of("searchProjectCode"), List.of("{\"query\":\"a,b\"}"),
                0, 0, 0, 0, true, false, true, true, null,
                true, true, true, true, false, AgentTaskFailureType.SUCCESS,
                120, null, null, null, false, "THINK -> TOOL_CALL -> FINISH");

        writer.write(tempDir, List.of(result), new AgentTaskEvaluationReportWriter.Environment(
                OffsetDateTime.now(), "21", "repo-1", "FlashDeal", 10, 20, 20, "agent-1", "gpt-5.5"));

        String detail = Files.readString(tempDir.resolve("agent-task-eval-detail.csv"));
        String summary = Files.readString(tempDir.resolve("agent-task-eval-summary.csv"));
        String report = Files.readString(tempDir.resolve("agent-task-evaluation-report.md"));
        assertTrue(detail.contains("\"CODE,LOCATION\""));
        assertTrue(detail.contains("\"{\"\"query\"\":\"\"a,b\"\"}\""));
        assertEquals(AgentTaskEvaluationReportWriter.SUMMARY_HEADERS.size(),
                summary.lines().findFirst().orElseThrow().split(",", -1).length);
        assertTrue(report.contains("Task Success"));
        assertTrue(report.contains("Token Usage：UNAVAILABLE"));
    }
}
