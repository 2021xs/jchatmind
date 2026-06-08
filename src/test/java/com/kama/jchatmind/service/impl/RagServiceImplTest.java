package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.service.EmbeddingService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
}
