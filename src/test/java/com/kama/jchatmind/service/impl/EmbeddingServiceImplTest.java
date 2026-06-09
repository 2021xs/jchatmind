package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.config.CodeRagProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class EmbeddingServiceImplTest {

    @Test
    void embedUsesSingleItemBatchEndpoint() {
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"embeddings\":[[0.1,0.2]]}")
                        .build()));

        float[] embedding = new EmbeddingServiceImpl(builder, new CodeRagProperties()).embed("query");

        assertArrayEquals(new float[]{0.1f, 0.2f}, embedding);
    }

    @Test
    void embedBatchReturnsEmptyListForEmptyInput() {
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> {
                    throw new AssertionError("empty batch should not call embedding endpoint");
                });

        List<float[]> embeddings = new EmbeddingServiceImpl(builder, new CodeRagProperties()).embedBatch(List.of());

        assertEquals(0, embeddings.size());
    }

    @Test
    void embedBatchPreservesResponseOrder() {
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"embeddings\":[[1.0],[2.0],[3.0]]}")
                        .build()));

        List<float[]> embeddings = new EmbeddingServiceImpl(builder, new CodeRagProperties())
                .embedBatch(List.of("a", "b", "c"));

        assertArrayEquals(new float[]{1.0f}, embeddings.get(0));
        assertArrayEquals(new float[]{2.0f}, embeddings.get(1));
        assertArrayEquals(new float[]{3.0f}, embeddings.get(2));
    }

    @Test
    void embedBatchRejectsBlankInput() {
        WebClient.Builder builder = WebClient.builder();

        assertThrows(IllegalArgumentException.class,
                () -> new EmbeddingServiceImpl(builder, new CodeRagProperties()).embedBatch(List.of("ok", " ")));
    }

    @Test
    void embedBatchThrowsWhenResponseSizeDoesNotMatchInputSize() {
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"embeddings\":[[1.0]]}")
                        .build()));

        assertThrows(IllegalStateException.class,
                () -> new EmbeddingServiceImpl(builder, new CodeRagProperties()).embedBatch(List.of("a", "b")));
    }

    @Test
    void embedBatchLogsInputSummariesWhenEndpointReturnsBadRequest(CapturedOutput output) {
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.BAD_REQUEST)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"error\":\"bad input\"}")
                        .build()));

        assertThrows(IllegalStateException.class,
                () -> new EmbeddingServiceImpl(builder, new CodeRagProperties()).embedBatch(List.of("first\nline", "second")));

        assertThat(output.getOut())
                .contains("Embedding batch request failed")
                .contains("inputCount=2")
                .contains("inputIndex=0")
                .contains("inputLength=10")
                .contains("inputHash=")
                .contains("inputPreview=first line")
                .contains("inputIndex=1")
                .contains("inputPreview=second");
    }
}
