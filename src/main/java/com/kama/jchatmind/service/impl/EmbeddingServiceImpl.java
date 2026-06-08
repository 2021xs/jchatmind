package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.service.EmbeddingService;
import lombok.Data;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.reactive.function.client.WebClient;

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
        EmbeddingResponse response = webClient.post()
                .uri("/api/embeddings")
                .bodyValue(Map.of(
                        "model", codeRagProperties.getEmbeddingModel(),
                        "prompt", text
                ))
                .retrieve()
                .bodyToMono(EmbeddingResponse.class)
                .block();
        Assert.notNull(response, "Embedding response cannot be null");
        return response.getEmbedding();
    }

    @Data
    private static class EmbeddingResponse {
        private float[] embedding;
    }
}
