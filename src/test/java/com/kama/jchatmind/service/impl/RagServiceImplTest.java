package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.service.EmbeddingService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagServiceImplTest {

    @Test
    void keepsEmbedCompatibilityByDelegatingToEmbeddingService() {
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        when(embeddingService.embed("query")).thenReturn(new float[]{0.1f});
        RagServiceImpl ragService = new RagServiceImpl(mock(ChunkBgeM3Mapper.class), embeddingService);

        assertArrayEquals(new float[]{0.1f}, ragService.embed("query"));

        verify(embeddingService).embed("query");
    }

    @Test
    void knowledgeRetrievalKeepsOriginalQueryTopThreeAndMapperOrdering() {
        ChunkBgeM3Mapper mapper = mock(ChunkBgeM3Mapper.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        when(embeddingService.embed("original query")).thenReturn(new float[]{0.1f, 0.2f});
        when(mapper.similaritySearch("kb-1", "[0.1,0.2]", 3)).thenReturn(List.of(
                ChunkBgeM3.builder().id("chunk-2").docId("doc-2").content("second").build(),
                ChunkBgeM3.builder().id("chunk-1").docId("doc-1").content("first").build()));
        RagServiceImpl ragService = new RagServiceImpl(mapper, embeddingService);

        var results = ragService.similaritySearchWithMetadata("kb-1", "original query");

        assertEquals(List.of("chunk-2", "chunk-1"),
                results.stream().map(result -> result.getChunkId()).toList());
        verify(embeddingService).embed("original query");
        verify(mapper).similaritySearch("kb-1", "[0.1,0.2]", 3);
    }
}
