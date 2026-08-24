package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.model.dto.CodeEvidenceCandidateCard;
import com.kama.jchatmind.model.dto.CodeEvidenceSelectionResult;
import com.kama.jchatmind.model.dto.CodeSearchExecutionResult;
import com.kama.jchatmind.model.dto.CodeSearchResult;
import com.kama.jchatmind.service.CodeSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodeRagAnswerEvidenceServiceImplTest {
    @Mock
    private CodeSearchService codeSearchService;
    @Mock
    private CodeLlmEvidenceSelector evidenceSelector;

    private CodeRagProperties properties;
    private CodeRagAnswerEvidenceServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new CodeRagProperties();
        properties.getAnswerEvidence().setRawTopK(50);
        properties.getAnswerEvidence().setFinalTopK(2);
        properties.getLlmSelector().setMaxCandidateChars(120);
        service = new CodeRagAnswerEvidenceServiceImpl(
                codeSearchService,
                evidenceSelector,
                properties
        );
    }

    @Test
    void retrieveReturnsSelectorChosenEvidence() {
        CodeSearchResult raw1 = result("raw-1", "Controller.java", "CONTROLLER_API");
        CodeSearchResult raw2 = result("raw-2", "Service.java", "SERVICE_METHOD");
        mockSearch(raw1, raw2);
        when(evidenceSelector.select(eq("query"), any()))
                .thenReturn(selection(List.of("raw-2"), false));

        var result = service.retrieve("repo", "query");

        assertFalse(result.isFallback());
        assertEquals(2, result.getRawCount());
        assertEquals(2, result.getCandidateCount());
        assertEquals(List.of(raw2), result.getSelectedEvidence());
    }

    @Test
    void retrieveFallsBackWhenSelectorReportsDisabledFallback() {
        CodeSearchResult raw1 = result("raw-1", "Controller.java", "CONTROLLER_API");
        CodeSearchResult raw2 = result("raw-2", "Service.java", "SERVICE_METHOD");
        mockSearch(raw1, raw2);
        when(evidenceSelector.select(eq("query"), any()))
                .thenReturn(selection(List.of("raw-1", "raw-2"), true));

        var result = service.retrieve("repo", "query");

        assertTrue(result.isFallback());
        assertEquals(List.of(raw1, raw2), result.getSelectedEvidence());
    }

    @Test
    void retrieveFallsBackWhenSelectorThrows() {
        CodeSearchResult raw1 = result("raw-1", "Controller.java", "CONTROLLER_API");
        CodeSearchResult raw2 = result("raw-2", "Service.java", "SERVICE_METHOD");
        mockSearch(raw1, raw2);
        when(evidenceSelector.select(eq("query"), any(List.class))).thenThrow(new RuntimeException("selector down"));

        var result = service.retrieve("repo", "query");

        assertTrue(result.isFallback());
        assertFalse(result.isJsonParseOk());
        assertEquals(List.of(raw1, raw2), result.getSelectedEvidence());
    }

    @Test
    void selectorExceptionKeepsFallbackEvidenceAndUsesMeasuredLatency() throws Exception {
        CodeSearchResult raw = result("raw", "Service.java", "SERVICE_METHOD");
        mockSearch(raw);
        when(evidenceSelector.select(eq("query"), any(List.class)))
                .thenAnswer(invocation -> {
                    Thread.sleep(5);
                    throw new RuntimeException("selector down");
                });

        var execution = service.execute("repo", "query");

        assertTrue(execution.getAnswerEvidence().isFallback());
        assertFalse(execution.getAnswerEvidence().isJsonParseOk());
        assertTrue(execution.getAnswerEvidence().getSelectorLatencyMs() > 0);
        assertTrue(execution.getSelectorLatencyMs() > 0);
        assertTrue(execution.getSelectorFallbackReason().startsWith("MODEL_ERROR:"));
    }

    @Test
    void retrieveBuildsCandidateCardsFromRawVectorOnly() {
        CodeSearchResult raw = result("raw", "Service.java", "SERVICE_METHOD");
        mockSearch(raw);
        when(evidenceSelector.select(eq("query"), any()))
                .thenAnswer(invocation -> {
                    List<CodeEvidenceCandidateCard> cards = invocation.getArgument(1);
                    assertEquals(1, cards.size());
                    assertEquals("RAW_VECTOR", cards.get(0).getSource());
                    assertEquals("SERVICE_LOGIC", cards.get(0).getEvidenceRole());
                    assertEquals(10, cards.get(0).getStartLine());
                    assertEquals(20, cards.get(0).getEndLine());
                    return selection(List.of(cards.get(0).getChunkId()), false);
                });

        var result = service.retrieve("repo", "query");

        assertEquals(1, result.getCandidateCount());
        assertEquals(1, result.getRawCount());
        assertEquals(List.of(raw), result.getSelectedEvidence());
        assertEquals("RAW_VECTOR", result.getSelectedEvidence().get(0).getRerankSource());
    }

    @Test
    void retrieveIgnoresIllegalSelectedChunkIds() {
        CodeSearchResult raw1 = result("raw-1", "Controller.java", "CONTROLLER_API");
        CodeSearchResult raw2 = result("raw-2", "Service.java", "SERVICE_METHOD");
        mockSearch(raw1, raw2);
        when(evidenceSelector.select(eq("query"), any()))
                .thenReturn(selection(List.of("missing-id", "raw-2"), false));

        var result = service.retrieve("repo", "query");

        assertFalse(result.isFallback());
        assertEquals(List.of(raw2), result.getSelectedEvidence());
    }

    @Test
    void executeReturnsRawCandidatesAndSearchTraceWithoutChangingAnswerShape() {
        CodeSearchResult raw = result("raw", "Service.java", "SERVICE_METHOD");
        when(codeSearchService.searchWithTrace("repo", "query", 50)).thenReturn(CodeSearchExecutionResult.builder()
                .candidates(List.of(raw))
                .embeddingLatencyMs(7)
                .retrievalLatencyMs(3)
                .cacheHit(true)
                .build());
        when(evidenceSelector.select(eq("query"), any()))
                .thenReturn(selection(List.of("raw"), false));

        var execution = service.execute("repo", "query");

        assertEquals(List.of(raw), execution.getRawCandidates());
        assertEquals(List.of(raw), execution.getAnswerEvidence().getSelectedEvidence());
        assertFalse(execution.getAnswerEvidence().isFallback());
        assertTrue(execution.getAnswerEvidence().isJsonParseOk());
        assertEquals(7, execution.getEmbeddingLatencyMs());
        assertEquals(3, execution.getRetrievalLatencyMs());
        assertTrue(execution.isCacheHit());
        assertTrue(execution.getSelectorLatencyMs() >= 0);
        assertTrue(execution.getTotalLatencyMs() >= execution.getSelectorLatencyMs());
        assertTrue(execution.isSelectorUsageAvailable());
        assertEquals(101, execution.getSelectorPromptTokens());
        assertEquals(9, execution.getSelectorCompletionTokens());
        assertEquals(110, execution.getSelectorTotalTokens());
        assertEquals(1234, execution.getSelectorPromptChars());
        assertEquals(900, execution.getSelectorCandidateSectionChars());
        assertEquals(24, execution.getSelectorResponseChars());
        assertEquals("{\"selectedCandidateIds\":[]}", execution.getSelectorVisibleContent());
        assertEquals(300, execution.getSelectorReasoningContentChars());
        assertTrue(execution.getSelectorReasoningContentPresent());
        assertEquals("STOP", execution.getSelectorFinishReason());
    }

    private void mockSearch(CodeSearchResult... candidates) {
        when(codeSearchService.searchWithTrace("repo", "query", 50)).thenReturn(CodeSearchExecutionResult.builder()
                .candidates(List.of(candidates))
                .embeddingLatencyMs(1)
                .retrievalLatencyMs(1)
                .cacheHit(false)
                .build());
    }

    private CodeEvidenceSelectionResult selection(List<String> ids, boolean fallback) {
        return CodeEvidenceSelectionResult.builder()
                .selectedChunkIds(ids)
                .reason("reason")
                .answerType("SERVICE")
                .fallback(fallback)
                .jsonParseOk(true)
                .latencyMs(12)
                .promptTokens(101)
                .completionTokens(9)
                .totalTokens(110)
                .usageAvailable(true)
                .promptChars(1234)
                .candidateSectionChars(900)
                .responseChars(24)
                .rawResponse("{\"selectedCandidateIds\":[]}")
                .reasoningContentChars(300)
                .reasoningContentPresent(true)
                .finishReason("STOP")
                .build();
    }

    private CodeSearchResult result(String chunkId, String filePath, String chunkType) {
        return CodeSearchResult.builder()
                .chunkId(chunkId)
                .repoId("repo")
                .filePath(filePath)
                .chunkType(chunkType)
                .symbolName(filePath + "#method")
                .score(0.9)
                .finalScore(0.9)
                .startLine(10)
                .endLine(20)
                .contentPreview("preview")
                .metadata("{}")
                .build();
    }
}
