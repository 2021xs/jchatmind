package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.dto.RagSearchResult;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.service.RagService;
import lombok.Data;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Service
public class RagServiceImpl implements RagService {

    // 封装本地的模型调用
    private final WebClient webClient;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;

    public RagServiceImpl(WebClient.Builder builder, ChunkBgeM3Mapper chunkBgeM3Mapper) {
        this.webClient = builder.baseUrl("http://localhost:11434").build();
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
    }

    @Data
    private static class EmbeddingResponse {
        private float[] embedding;
    }

    private float[] doEmbed(String text) {
        EmbeddingResponse resp = webClient.post()
                .uri("/api/embeddings")
                .bodyValue(Map.of(
                        "model", "bge-m3",
                        "prompt", text
                ))
                .retrieve()
                .bodyToMono(EmbeddingResponse.class)
                .block();
        Assert.notNull(resp, "Embedding response cannot be null");
        return resp.getEmbedding();
    }

    @Override
    public float[] embed(String text) {
        return doEmbed(text);
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
        String queryEmbedding = toPgVector(doEmbed(query));
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

    private String toPgVector(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            sb.append(v[i]);
            if (i < v.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }
}
