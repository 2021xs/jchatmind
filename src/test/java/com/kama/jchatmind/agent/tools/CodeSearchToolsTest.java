package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.agent.TaskEvidenceState;
import com.kama.jchatmind.agent.observability.AgentLifecycleObservationPublisher;
import com.kama.jchatmind.model.dto.CodeAnswerEvidenceResult;
import com.kama.jchatmind.model.dto.CodeRagExecutionResult;
import com.kama.jchatmind.model.dto.CodeSearchResult;
import com.kama.jchatmind.service.CodeRagAnswerEvidenceService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodeSearchToolsTest {

    @Test
    void separatesAgentPresentationFromDiagnosticsAndExposesStableLocator() {
        CodeSearchResult selected = CodeSearchResult.builder()
                .chunkId("real-chunk-uuid")
                .repoId("repo-1")
                .filePath("Service.java")
                .symbolName("Service#run")
                .chunkType("SERVICE_METHOD")
                .startLine(10)
                .endLine(20)
                .score(0.88)
                .metadata("{\"symbols\":[\"Service#run\"]}")
                .contentPreview("void run() {}")
                .build();
        CodeAnswerEvidenceResult diagnostics = CodeAnswerEvidenceResult.builder()
                .selectedEvidence(List.of(selected))
                .rawCount(20)
                .candidateCount(20)
                .fallback(true)
                .jsonParseOk(false)
                .selectorLatencyMs(321)
                .selectorReason("full diagnostic reason")
                .answerType("CODE_LOCATION")
                .build();
        CodeRagAnswerEvidenceService service = mock(CodeRagAnswerEvidenceService.class);
        when(service.retrieve("repo-1", "query")).thenReturn(diagnostics);

        String result = new CodeSearchTools(service)
                .searchProjectCode("repo-1", "query");

        assertTrue(result.contains("repoId: repo-1"));
        assertTrue(result.contains("chunkId: real-chunk-uuid"));
        assertTrue(result.contains("file: Service.java"));
        assertFalse(result.contains("selectorLatencyMs"));
        assertFalse(result.contains("selectorJsonParseOk"));
        assertFalse(result.contains("rawCandidateCount"));
        assertFalse(result.contains("score:"));
        assertFalse(result.contains("metadata:"));
        assertEquals(20, diagnostics.getRawCount());
        assertEquals(20, diagnostics.getCandidateCount());
        assertTrue(diagnostics.isFallback());
        assertFalse(diagnostics.isJsonParseOk());
        assertEquals(321, diagnostics.getSelectorLatencyMs());
        assertEquals("full diagnostic reason", diagnostics.getSelectorReason());
        assertSame(selected, diagnostics.getSelectedEvidence().get(0));
        assertEquals("real-chunk-uuid", selected.getChunkId());
        assertEquals(0.88, selected.getScore());
        assertTrue(selected.getMetadata().contains("symbols"));
    }

    @Test
    void returnsFormattedEvidenceBeyondLegacyCodeLocalLimitWithoutTailLoss() {
        String tailMarker = "CODE-CANONICAL-TAIL";
        CodeSearchResult selected = CodeSearchResult.builder()
                .repoId("repo-1")
                .chunkId("chunk-large")
                .filePath("LargeService.java")
                .symbolName("LargeService#run")
                .chunkType("SERVICE_METHOD")
                .startLine(1)
                .endLine(500)
                .contentPreview("x".repeat(7_100) + tailMarker)
                .build();
        CodeRagAnswerEvidenceService service = mock(CodeRagAnswerEvidenceService.class);
        when(service.retrieve("repo-1", "large query")).thenReturn(CodeAnswerEvidenceResult.builder()
                .selectedEvidence(List.of(selected))
                .build());

        String result = new CodeSearchTools(service).searchProjectCode("repo-1", "large query");

        assertTrue(result.length() > 7_000);
        assertTrue(result.endsWith(tailMarker + "\n"));
        assertTrue(result.contains("repoId: repo-1"));
        assertTrue(result.contains("chunkId: chunk-large"));
        assertFalse(result.contains("...[truncated]"));
    }

    @Test
    void keepsFullExecutionDiagnosticsAvailableOutsideAgentPresentation() {
        CodeAnswerEvidenceResult answer = CodeAnswerEvidenceResult.builder()
                .selectedEvidence(List.of(CodeSearchResult.builder().chunkId("selected-id").build()))
                .rawCount(20).candidateCount(20).fallback(false).jsonParseOk(true)
                .selectorLatencyMs(123).selectorReason("selected by selector").build();
        CodeSearchResult raw = CodeSearchResult.builder()
                .chunkId("raw-id").score(0.42).metadata("{\"symbols\":[\"GT\"]}").build();
        CodeRagExecutionResult execution = CodeRagExecutionResult.builder()
                .answerEvidence(answer).rawCandidates(List.of(raw))
                .selectorLatencyMs(123).selectorProposedCandidateIds(List.of("C01"))
                .selectorValidCandidateIds(List.of("C01")).selectorInvalidCandidateIds(List.of("C99"))
                .selectorFallbackReason("").selectorPromptChars(900)
                .selectorCandidateSectionChars(700).build();

        assertEquals("selected-id", execution.getAnswerEvidence().getSelectedEvidence().get(0).getChunkId());
        assertEquals("raw-id", execution.getRawCandidates().get(0).getChunkId());
        assertEquals(0.42, execution.getRawCandidates().get(0).getScore());
        assertTrue(execution.getRawCandidates().get(0).getMetadata().contains("GT"));
        assertEquals(List.of("C01"), execution.getSelectorValidCandidateIds());
        assertEquals(List.of("C99"), execution.getSelectorInvalidCandidateIds());
        assertEquals(123, execution.getSelectorLatencyMs());
        assertEquals(900, execution.getSelectorPromptChars());
        assertEquals(700, execution.getSelectorCandidateSectionChars());
    }

    @Test
    void benchmarkObservationCapturesSelectorProvenanceWithoutChangingToolResult() {
        CodeSearchResult c1 = result("repo-1", "c1", "A.java", "A#run", 0.9);
        CodeSearchResult c2 = result("repo-1", "c2", "B.java", "B#run", 0.8);
        CodeSearchResult c3 = result("repo-1", "c3", "C.java", "C#run", 0.7);
        CodeAnswerEvidenceResult answer = CodeAnswerEvidenceResult.builder()
                .selectedEvidence(List.of(c1, c3)).build();
        CodeRagExecutionResult execution = CodeRagExecutionResult.builder()
                .answerEvidence(answer).rawCandidates(List.of(c1, c2, c3)).build();
        CodeRagAnswerEvidenceService service = mock(CodeRagAnswerEvidenceService.class);
        when(service.retrieve("repo-1", "trace query")).thenReturn(answer);
        when(service.execute("repo-1", "trace query")).thenReturn(execution);
        CodeSearchTools tools = new CodeSearchTools(service);
        ToolContext toolContext = new ToolContext(Map.of(
                TaskEvidenceState.TASK_ID_TOOL_CONTEXT_KEY, "task-1",
                AgentLifecycleObservationPublisher.DIAGNOSTIC_SESSION_ID_CONTEXT_KEY, "session-1",
                AgentLifecycleObservationPublisher.DIAGNOSTIC_TOOL_CALL_ID_CONTEXT_KEY, "call-1"));

        String diagnosticsOff = tools.searchProjectCode("repo-1", "trace query", toolContext);
        AtomicReference<AgentLifecycleObservationPublisher.SelectorProvenanceObservation> captured =
                new AtomicReference<>();
        String diagnosticsOn;
        try (AgentLifecycleObservationPublisher.Registration ignored =
                     AgentLifecycleObservationPublisher.registerSelectorProvenance(captured::set)) {
            diagnosticsOn = tools.searchProjectCode("repo-1", "trace query", toolContext);
        }

        assertEquals(diagnosticsOff, diagnosticsOn);
        AgentLifecycleObservationPublisher.SelectorProvenanceObservation observation = captured.get();
        assertEquals("task-1", observation.taskId());
        assertEquals("session-1", observation.sessionId());
        assertEquals("call-1", observation.toolCallId());
        assertEquals("trace query", observation.query());
        assertEquals(List.of("c1", "c2", "c3"),
                observation.rawTopK().stream().map(
                        AgentLifecycleObservationPublisher.CodeEvidenceIdentity::chunkId).toList());
        assertEquals(List.of("c1", "c3"), observation.selected().stream().map(
                AgentLifecycleObservationPublisher.CodeEvidenceIdentity::chunkId).toList());
        assertEquals(List.of("c2"), observation.rejected().stream().map(
                AgentLifecycleObservationPublisher.CodeEvidenceIdentity::chunkId).toList());
        assertEquals(List.of(1, 2, 3), observation.rawTopK().stream().map(
                AgentLifecycleObservationPublisher.CodeEvidenceIdentity::rank).toList());
    }

    private CodeSearchResult result(String repoId, String chunkId, String filePath,
                                    String symbol, double score) {
        return CodeSearchResult.builder()
                .repoId(repoId).chunkId(chunkId).filePath(filePath).symbolName(symbol)
                .score(score).contentPreview("content " + chunkId).build();
    }
}
