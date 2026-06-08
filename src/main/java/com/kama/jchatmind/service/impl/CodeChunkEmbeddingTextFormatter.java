package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.model.dto.ParsedCodeFile;
import com.kama.jchatmind.model.entity.CodeChunk;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CodeChunkEmbeddingTextFormatter {
    private static final int MAX_EMBED_TEXT_LENGTH = 8000;
    private static final int MAX_IDENTITY_LENGTH = 800;
    private static final int MAX_METADATA_LENGTH = 2000;

    public String format(ParsedCodeFile parsed, CodeChunk chunk, List<EmbeddingMetadataEntry> metadata) {
        String identitySection = boundedSection("identity", buildIdentity(parsed, chunk), MAX_IDENTITY_LENGTH);
        String metadataSection = boundedSection("metadata", buildMetadata(metadata), MAX_METADATA_LENGTH);
        String contentPrefix = "content:\n";
        int contentBudget = Math.max(0, MAX_EMBED_TEXT_LENGTH
                - identitySection.length() - metadataSection.length() - contentPrefix.length());
        String content = truncate(nullToEmpty(chunk.getContent()), contentBudget);
        return identitySection + metadataSection + contentPrefix + content;
    }

    private String buildIdentity(ParsedCodeFile parsed, CodeChunk chunk) {
        StringBuilder sb = new StringBuilder();
        append(sb, "chunk_type", chunk.getChunkType());
        append(sb, "symbol_name", chunk.getSymbolName());
        append(sb, "api_path", chunk.getApiPath());
        append(sb, "http_method", chunk.getHttpMethod());
        append(sb, "file_path", parsed.getRelativePath());
        append(sb, "file_type", parsed.getFileType());
        append(sb, "package_name", parsed.getPackageName());
        append(sb, "class_name", parsed.getClassName());
        return sb.toString();
    }

    private String buildMetadata(List<EmbeddingMetadataEntry> metadata) {
        StringBuilder sb = new StringBuilder();
        for (EmbeddingMetadataEntry entry : metadata) {
            append(sb, entry.key(), entry.value());
        }
        return sb.toString();
    }

    private String boundedSection(String name, String body, int maxLength) {
        if (body.isBlank()) {
            return "";
        }
        String prefix = name + ":\n";
        return prefix + truncateLines(body, Math.max(0, maxLength - prefix.length()));
    }

    private void append(StringBuilder sb, String key, Object value) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (!text.isEmpty()) {
            sb.append(key).append(": ").append(text).append('\n');
        }
    }

    private String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        if (maxLength <= 0) {
            return "";
        }
        return value.substring(0, maxLength);
    }

    private String truncateLines(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        if (maxLength <= 0) {
            return "";
        }
        String truncated = value.substring(0, maxLength);
        int lastNewline = truncated.lastIndexOf('\n');
        return lastNewline < 0 ? "" : truncated.substring(0, lastNewline + 1);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
