package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.service.EmbeddingService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
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
                .onStatus(status -> !status.is2xxSuccessful(), clientResponse ->
                        clientResponse.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(body -> {
                                    logEmbeddingRequestFailure(clientResponse.statusCode().value(), body, texts);
                                    return new IllegalStateException("Embedding request failed: status="
                                            + clientResponse.statusCode().value() + ", body=" + summarize(body, 500));
                                }))
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

    private void logEmbeddingRequestFailure(int statusCode, String responseBody, List<String> texts) {
        log.warn("Embedding batch request failed: status={}, model={}, inputCount={}, responseBody={}",
                statusCode, codeRagProperties.getEmbeddingModel(), texts.size(), summarize(responseBody, 500));
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            log.warn("Embedding batch failed input summary: model={}, inputIndex={}, inputLength={}, inputHash={}, hasControlChars={}, hasInvalidSurrogate={}, maxLineLength={}, inputPreview={}",
                    codeRagProperties.getEmbeddingModel(), i, text.length(), sha256Prefix(text),
                    hasControlChars(text), hasInvalidSurrogate(text), maxLineLength(text), summarize(text, 200));
        }
    }

    private static String summarize(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
                .replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxChars)) + "...[truncated]";
    }

    private static String sha256Prefix(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }

    private static boolean hasControlChars(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isISOControl(ch) && ch != '\n' && ch != '\r' && ch != '\t') {
                return true;
            }
        }
        return false;
    }

    private static boolean hasInvalidSurrogate(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(ch)) {
                return true;
            }
        }
        return false;
    }

    private static int maxLineLength(String value) {
        int max = 0;
        int current = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\n' || ch == '\r') {
                max = Math.max(max, current);
                current = 0;
            } else {
                current++;
            }
        }
        return Math.max(max, current);
    }

    @Data
    private static class BatchEmbeddingResponse {
        private List<float[]> embeddings;
    }
}
