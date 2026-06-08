package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.model.dto.ParsedCodeFile;
import com.kama.jchatmind.model.entity.CodeChunk;
import com.kama.jchatmind.service.CodeChunkEmbeddingTextBuilder;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@AllArgsConstructor
public class CodeChunkEmbeddingTextBuilderImpl implements CodeChunkEmbeddingTextBuilder {
    private final ObjectMapper objectMapper;
    private final CodeRagProperties properties;
    private final CodeChunkEmbeddingMetadataSanitizer metadataSanitizer;
    private final CodeChunkEmbeddingTextFormatter textFormatter;

    @Override
    public String build(ParsedCodeFile parsed, CodeChunk chunk) {
        if (!properties.getEmbeddingMetadata().isEnabled()) {
            return buildLegacyText(parsed, chunk);
        }
        Map<String, Object> metadata = readMetadata(chunk.getMetadata());
        var sanitizedMetadata = metadataSanitizer.sanitize(chunk.getChunkType(), metadata);
        return textFormatter.format(parsed, chunk, sanitizedMetadata);
    }

    private String buildLegacyText(ParsedCodeFile parsed, CodeChunk chunk) {
        String text = "file: " + nullToEmpty(parsed.getRelativePath()) + "\n"
                + "fileType: " + nullToEmpty(parsed.getFileType()) + "\n"
                + "chunkType: " + nullToEmpty(chunk.getChunkType()) + "\n"
                + "symbol: " + nullToEmpty(chunk.getSymbolName()) + "\n"
                + "api: " + nullToEmpty(chunk.getHttpMethod()) + " " + nullToEmpty(chunk.getApiPath()) + "\n"
                + "content:\n" + nullToEmpty(chunk.getContent());
        return text.length() > 8000 ? text.substring(0, 8000) : text;
    }

    private Map<String, Object> readMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(metadataJson, new TypeReference<>() {
            });
        } catch (Exception ignored) {
            return Map.of("metadata", metadataJson);
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
