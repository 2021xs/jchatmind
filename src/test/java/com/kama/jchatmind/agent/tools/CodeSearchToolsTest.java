package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.model.dto.CodeAnswerEvidenceResult;
import com.kama.jchatmind.model.dto.CodeRagExecutionResult;
import com.kama.jchatmind.model.dto.CodeSearchResult;
import com.kama.jchatmind.service.CodeRagAnswerEvidenceService;
import com.kama.jchatmind.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeSearchToolsTest {

    @Test
    void separatesAgentPresentationFromDiagnosticsAndStillUsesToolLimit() {
        CodeSearchResult selected = CodeSearchResult.builder()
                .chunkId("real-chunk-uuid")
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
        ToolRegistry registry = mock(ToolRegistry.class);
        when(service.retrieve("repo-1", "query")).thenReturn(diagnostics);
        when(registry.truncateResult(eq("searchProjectCode"), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        String result = new CodeSearchTools(service, registry, new CodeRagProperties())
                .searchProjectCode("repo-1", "query");

        assertTrue(result.contains("file: Service.java"));
        assertFalse(result.contains("selectorLatencyMs"));
        assertFalse(result.contains("selectorJsonParseOk"));
        assertFalse(result.contains("rawCandidateCount"));
        assertFalse(result.contains("score:"));
        assertFalse(result.contains("metadata:"));
        assertFalse(result.contains("real-chunk-uuid"));
        verify(registry).truncateResult(eq("searchProjectCode"), eq(result));

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
}
