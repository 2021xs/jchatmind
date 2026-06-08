package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.service.EmbeddingService;
import lombok.Data;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class EmbeddingServiceImpl implements EmbeddingService {

    private final WebClient webClient;
    private final CodeRagProperties codeRagProperties;

    public EmbeddingServiceImpl(WebClient.Builder builder, CodeRagProperties codeRagProperties) {
        this.webClient = builder.baseUrl(codeRagProperties.getEmbeddingBaseUrl()).build();
        this.codeRagProperties = codeRagProperties;
    }

    @Override
    public float[] embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null) {
            throw new IllegalArgumentException("Embedding texts cannot be null");
        }
        if (texts.isEmpty()) {
            return Collections.emptyList();
        }
        for (String text : texts) {
            if (!StringUtils.hasText(text)) {
                throw new IllegalArgumentException("Embedding text cannot be null or blank");
            }
        }

        BatchEmbeddingResponse response = webClient.post()
                .uri("/api/embed")
                .bodyValue(Map.of(
                        "model", codeRagProperties.getEmbeddingModel(),
                        "input", texts
                ))
                .retrieve()
                .bodyToMono(BatchEmbeddingResponse.class)
                .block();
        Assert.notNull(response, "Embedding response cannot be null");
        List<float[]> embeddings = response.getEmbeddings();
        Assert.notNull(embeddings, "Embedding response embeddings cannot be null");
        if (embeddings.size() != texts.size()) {
            throw new IllegalStateException("Embedding response size mismatch: expected "
                    + texts.size() + ", actual " + embeddings.size());
        }
        return embeddings;
    }

    @Data
    private static class BatchEmbeddingResponse {
        private List<float[]> embeddings;
    }
}
