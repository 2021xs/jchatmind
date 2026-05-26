package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.mapper.CodeChunkMapper;
import com.kama.jchatmind.model.dto.CodeSearchResult;
import com.kama.jchatmind.service.RagService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class CodeSearchServiceImplTest {
    @Mock
    private RagService ragService;
    @Mock
    private CodeChunkMapper codeChunkMapper;
    @Mock
    private CodeQueryEmbeddingCache embeddingCache;

    @Test
    void searchEmbedsQueryAndCallsPgvectorMapper() {
        CodeRagProperties properties = new CodeRagProperties();
        CodeSearchServiceImpl service = new CodeSearchServiceImpl(ragService, codeChunkMapper, embeddingCache, properties);
        CodeSearchResult hit = CodeSearchResult.builder()
                .chunkId("chunk-1")
                .score(0.8)
                .build();
        when(embeddingCache.get("query")).thenReturn(null);
        when(ragService.embed("query")).thenReturn(new float[]{0.1f, 0.2f});
        when(codeChunkMapper.similaritySearch("repo", "[0.1,0.2]", 5)).thenReturn(List.of(hit));

        List<CodeSearchResult> results = service.search("repo", "query", 5);

        assertEquals(List.of(hit), results);
        assertEquals("RAW_VECTOR", hit.getRerankSource());
        assertEquals(0.8, hit.getOriginalScore());
        verify(embeddingCache).put(org.mockito.ArgumentMatchers.eq("query"), any(float[].class));
    }

    @Test
    void searchClampsTopKToAnswerEvidenceRawTopK() {
        CodeRagProperties properties = new CodeRagProperties();
        properties.getAnswerEvidence().setRawTopK(50);
        CodeSearchServiceImpl service = new CodeSearchServiceImpl(ragService, codeChunkMapper, embeddingCache, properties);
        when(embeddingCache.get("query")).thenReturn(new float[]{0.1f});
        when(codeChunkMapper.similaritySearch("repo", "[0.1]", 50)).thenReturn(List.of());

        service.search("repo", "query", 200);

        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(codeChunkMapper).similaritySearch(org.mockito.ArgumentMatchers.eq("repo"),
                org.mockito.ArgumentMatchers.eq("[0.1]"), limit.capture());
        assertTrue(limit.getValue() <= 50);
    }
}
