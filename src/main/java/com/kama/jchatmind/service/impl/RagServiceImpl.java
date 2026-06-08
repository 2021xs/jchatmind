package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.dto.RagSearchResult;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.service.EmbeddingService;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.util.PgVectorUtils;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RagServiceImpl implements RagService {

    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final EmbeddingService embeddingService;

    public RagServiceImpl(ChunkBgeM3Mapper chunkBgeM3Mapper, EmbeddingService embeddingService) {
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
        this.embeddingService = embeddingService;
    }

    @Override
    public float[] embed(String text) {
        return embeddingService.embed(text);
    }

    @Override
    public List<String> similaritySearch(String kbId, String title) {
        return similaritySearchWithMetadata(kbId, title)
                .stream()
                .map(RagSearchResult::getContent)
                .toList();
    }

    @Override
    public List<RagSearchResult> similaritySearchWithMetadata(String kbId, String query) {
        String queryEmbedding = PgVectorUtils.toLiteral(embeddingService.embed(query));
        List<ChunkBgeM3> chunks = chunkBgeM3Mapper.similaritySearch(kbId, queryEmbedding, 3);
        return chunks.stream().map(this::toSearchResult).toList();
    }

    private RagSearchResult toSearchResult(ChunkBgeM3 chunk) {
        return RagSearchResult.builder()
                .chunkId(chunk.getId())
                .title(extractTitle(chunk.getMetadata()))
                .content(chunk.getContent())
                .score(chunk.getScore())
                .metadata(chunk.getMetadata())
                .sourceType("document_chunk")
                .sourceId(chunk.getDocId())
                .build();
    }

    private String extractTitle(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return "";
        }
        String marker = "\"title\"";
        int titleIndex = metadata.indexOf(marker);
        if (titleIndex < 0) {
            return "";
        }
        int colonIndex = metadata.indexOf(':', titleIndex + marker.length());
        int firstQuote = metadata.indexOf('"', colonIndex + 1);
        int secondQuote = metadata.indexOf('"', firstQuote + 1);
        if (colonIndex < 0 || firstQuote < 0 || secondQuote < 0) {
            return "";
        }
        return metadata.substring(firstQuote + 1, secondQuote);
    }

}
