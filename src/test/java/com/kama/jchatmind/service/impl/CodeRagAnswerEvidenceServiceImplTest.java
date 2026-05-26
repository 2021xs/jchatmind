package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.model.dto.CodeEvidenceCandidateCard;
import com.kama.jchatmind.model.dto.CodeEvidenceSelectionResult;
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
        when(codeSearchService.search("repo", "query", 50)).thenReturn(List.of(raw1, raw2));
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
        when(codeSearchService.search("repo", "query", 50)).thenReturn(List.of(raw1, raw2));
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
        when(codeSearchService.search("repo", "query", 50)).thenReturn(List.of(raw1, raw2));
        when(evidenceSelector.select(eq("query"), any(List.class))).thenThrow(new RuntimeException("selector down"));

        var result = service.retrieve("repo", "query");

        assertTrue(result.isFallback());
        assertFalse(result.isJsonParseOk());
        assertEquals(List.of(raw1, raw2), result.getSelectedEvidence());
    }

    @Test
    void retrieveBuildsCandidateCardsFromRawVectorOnly() {
        CodeSearchResult raw = result("raw", "Service.java", "SERVICE_METHOD");
        when(codeSearchService.search("repo", "query", 50)).thenReturn(List.of(raw));
        when(evidenceSelector.select(eq("query"), any()))
                .thenAnswer(invocation -> {
                    List<CodeEvidenceCandidateCard> cards = invocation.getArgument(1);
                    assertEquals(1, cards.size());
                    assertEquals("RAW_VECTOR", cards.get(0).getSource());
                    assertEquals("SERVICE_LOGIC", cards.get(0).getEvidenceRole());
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
        when(codeSearchService.search("repo", "query", 50)).thenReturn(List.of(raw1, raw2));
        when(evidenceSelector.select(eq("query"), any()))
                .thenReturn(selection(List.of("missing-id", "raw-2"), false));

        var result = service.retrieve("repo", "query");

        assertFalse(result.isFallback());
        assertEquals(List.of(raw2), result.getSelectedEvidence());
    }

    private CodeEvidenceSelectionResult selection(List<String> ids, boolean fallback) {
        return CodeEvidenceSelectionResult.builder()
                .selectedChunkIds(ids)
                .reason("reason")
                .answerType("SERVICE")
                .fallback(fallback)
                .jsonParseOk(true)
                .latencyMs(12)
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
                .contentPreview("preview")
                .metadata("{}")
                .build();
    }
}
