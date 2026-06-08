package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.config.CodeRagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class EmbeddingServiceImplTest {

    @Test
    void returnsEmbeddingFromConfiguredOllamaCompatibleEndpoint() {
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"embedding\":[0.1,0.2]}")
                        .build()));

        float[] embedding = new EmbeddingServiceImpl(builder, new CodeRagProperties()).embed("query");

        assertArrayEquals(new float[]{0.1f, 0.2f}, embedding);
    }
}
