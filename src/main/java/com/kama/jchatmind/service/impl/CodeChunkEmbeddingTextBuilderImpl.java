package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.model.dto.ParsedCodeFile;
import com.kama.jchatmind.model.entity.CodeChunk;
import com.kama.jchatmind.service.CodeChunkEmbeddingTextBuilder;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@AllArgsConstructor
public class CodeChunkEmbeddingTextBuilderImpl implements CodeChunkEmbeddingTextBuilder {
    private static final int MAX_EMBED_TEXT_LENGTH = 8000;

    private final ObjectMapper objectMapper;
    private final CodeRagProperties properties;
    private final CodeChunkContextBuilder codeChunkContextBuilder;
    private final CodeChunkEmbeddingMetadataSanitizer metadataSanitizer;

    @Override
    public String build(ParsedCodeFile parsed, CodeChunk chunk) {
        if (!properties.getEmbeddingMetadata().isEnabled()) {
            return buildLegacyText(parsed, chunk);
        }
        Map<String, Object> metadata = readMetadata(chunk.getMetadata());
        return truncate(buildEmbeddingText(parsed, chunk, metadata));
    }

    private String buildEmbeddingText(ParsedCodeFile parsed, CodeChunk chunk, Map<String, Object> metadata) {
        StringBuilder sb = new StringBuilder();
        appendContext(sb, parsed, chunk, metadata);
        append(sb, "chunk_type", chunk.getChunkType());
        append(sb, "file_path", parsed.getRelativePath());
        append(sb, "symbol_name", chunk.getSymbolName());
        appendMetadata(sb, parsed, chunk, metadata);
        sb.append("content:\n").append(nullToEmpty(chunk.getContent()));
        return sb.toString();
    }

    private String buildLegacyText(ParsedCodeFile parsed, CodeChunk chunk) {
        String text = "file: " + nullToEmpty(parsed.getRelativePath()) + "\n"
                + "fileType: " + nullToEmpty(parsed.getFileType()) + "\n"
                + "chunkType: " + nullToEmpty(chunk.getChunkType()) + "\n"
                + "symbol: " + nullToEmpty(chunk.getSymbolName()) + "\n"
                + "api: " + nullToEmpty(chunk.getHttpMethod()) + " " + nullToEmpty(chunk.getApiPath()) + "\n"
                + "content:\n" + nullToEmpty(chunk.getContent());
        return truncate(text);
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

    private void appendMetadata(StringBuilder sb, ParsedCodeFile parsed, CodeChunk chunk, Map<String, Object> metadata) {
        Map<String, Object> enrichedMetadata = new LinkedHashMap<>(metadata);
        putIfPresent(enrichedMetadata, "fileType", parsed.getFileType());
        putIfPresent(enrichedMetadata, "packageName", parsed.getPackageName());
        putIfPresent(enrichedMetadata, "className", parsed.getClassName());
        putIfPresent(enrichedMetadata, "apiPath", chunk.getApiPath());
        putIfPresent(enrichedMetadata, "httpMethod", chunk.getHttpMethod());
        var entries = metadataSanitizer.sanitize(chunk.getChunkType(), enrichedMetadata);
        if (entries.isEmpty()) {
            return;
        }
        sb.append("metadata:\n");
        for (EmbeddingMetadataEntry entry : entries) {
            append(sb, entry.key(), entry.value());
        }
    }

    private void putIfPresent(Map<String, Object> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.putIfAbsent(key, value);
        }
    }

    private void appendContext(StringBuilder sb, ParsedCodeFile parsed, CodeChunk chunk, Map<String, Object> metadata) {
        if (!properties.getContextualPrefix().isEnabled()) {
            return;
        }
        String context = codeChunkContextBuilder.build(parsed, chunk, metadata);
        if (context == null || context.isBlank()) {
            return;
        }
        sb.append("context:\n").append(context).append('\n');
    }

    private void append(StringBuilder sb, String key, Object value) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (!text.isEmpty()) {
            sb.append(key).append(": ").append(text).append('\n');
        }
    }

    private String truncate(String text) {
        return text.length() > MAX_EMBED_TEXT_LENGTH ? text.substring(0, MAX_EMBED_TEXT_LENGTH) : text;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
