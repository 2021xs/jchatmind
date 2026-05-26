package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.model.dto.ParsedCodeFile;
import com.kama.jchatmind.model.entity.CodeChunk;
import com.kama.jchatmind.service.CodeChunkEmbeddingTextBuilder;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Component
@AllArgsConstructor
public class CodeChunkEmbeddingTextBuilderImpl implements CodeChunkEmbeddingTextBuilder {
    private static final int MAX_EMBED_TEXT_LENGTH = 8000;
    private static final List<String> METADATA_KEYS = List.of(
            "sqlId", "id", "tableName", "tables", "className", "methodName",
            "method", "controller", "namespace", "sqlType", "configKey", "configKeys",
            "javaType", "methods", "fileType", "packageName", "qualifiedClassName",
            "signature", "returnType", "parameters", "annotations", "mapperClass",
            "mapperMethod", "relatedSymbol", "apiPath", "httpMethod"
    );

    private final ObjectMapper objectMapper;
    private final CodeRagProperties properties;
    private final CodeChunkContextBuilder codeChunkContextBuilder;

    @Override
    public String build(ParsedCodeFile parsed, CodeChunk chunk) {
        if (!properties.getEmbeddingMetadata().isEnabled()) {
            return buildLegacyText(parsed, chunk);
        }
        Map<String, Object> metadata = readMetadata(chunk.getMetadata());
        String chunkType = nullToEmpty(chunk.getChunkType());
        String text;
        if ("CONTROLLER_API".equals(chunkType)) {
            text = buildControllerApiText(parsed, chunk, metadata);
        } else if ("SERVICE_METHOD".equals(chunkType) || "JAVA_METHOD".equals(chunkType)) {
            text = buildMethodText(parsed, chunk, metadata);
        } else if ("MAPPER_METHOD".equals(chunkType)) {
            text = buildMapperMethodText(parsed, chunk, metadata);
        } else if ("MYBATIS_SQL".equals(chunkType)) {
            text = buildMyBatisSqlText(parsed, chunk, metadata);
        } else {
            text = buildDefaultText(parsed, chunk, metadata);
        }
        return truncate(text);
    }

    private String buildDefaultText(ParsedCodeFile parsed, CodeChunk chunk, Map<String, Object> metadata) {
        StringBuilder sb = new StringBuilder();
        appendContext(sb, parsed, chunk, metadata);
        append(sb, "file_path", parsed.getRelativePath());
        append(sb, "file_type", parsed.getFileType());
        append(sb, "package_name", parsed.getPackageName());
        append(sb, "class_name", parsed.getClassName());
        append(sb, "chunk_type", chunk.getChunkType());
        append(sb, "symbol_name", chunk.getSymbolName());
        append(sb, "api_path", chunk.getApiPath());
        append(sb, "http_method", chunk.getHttpMethod());
        append(sb, "start_line", chunk.getStartLine());
        append(sb, "end_line", chunk.getEndLine());
        appendMetadata(sb, metadata);
        sb.append("content:\n").append(nullToEmpty(chunk.getContent()));
        return sb.toString();
    }

    private String buildControllerApiText(ParsedCodeFile parsed, CodeChunk chunk, Map<String, Object> metadata) {
        StringBuilder sb = new StringBuilder();
        appendContext(sb, parsed, chunk, metadata);
        append(sb, "chunk_type", chunk.getChunkType());
        append(sb, "file_path", parsed.getRelativePath());
        append(sb, "package_name", first(metadata.get("packageName"), parsed.getPackageName()));
        append(sb, "class_name", first(metadata.get("className"), parsed.getClassName()));
        append(sb, "method_name", metadata.get("methodName"));
        append(sb, "signature", metadata.get("signature"));
        append(sb, "http_method", first(metadata.get("httpMethod"), chunk.getHttpMethod()));
        append(sb, "api_path", first(metadata.get("apiPath"), chunk.getApiPath()));
        append(sb, "annotations", formatValue(metadata.get("annotations")));
        sb.append("content:\n").append(nullToEmpty(chunk.getContent()));
        return sb.toString();
    }

    private String buildMethodText(ParsedCodeFile parsed, CodeChunk chunk, Map<String, Object> metadata) {
        StringBuilder sb = new StringBuilder();
        appendContext(sb, parsed, chunk, metadata);
        append(sb, "chunk_type", chunk.getChunkType());
        append(sb, "file_path", parsed.getRelativePath());
        append(sb, "class_name", first(metadata.get("className"), parsed.getClassName()));
        append(sb, "method_name", metadata.get("methodName"));
        append(sb, "signature", metadata.get("signature"));
        append(sb, "annotations", formatValue(metadata.get("annotations")));
        sb.append("content:\n").append(nullToEmpty(chunk.getContent()));
        return sb.toString();
    }

    private String buildMapperMethodText(ParsedCodeFile parsed, CodeChunk chunk, Map<String, Object> metadata) {
        StringBuilder sb = new StringBuilder();
        appendContext(sb, parsed, chunk, metadata);
        append(sb, "chunk_type", chunk.getChunkType());
        append(sb, "file_path", parsed.getRelativePath());
        append(sb, "class_name", first(metadata.get("className"), parsed.getClassName()));
        append(sb, "method_name", metadata.get("methodName"));
        append(sb, "signature", metadata.get("signature"));
        append(sb, "related_sql_id", metadata.get("relatedSqlId"));
        sb.append("content:\n").append(nullToEmpty(chunk.getContent()));
        return sb.toString();
    }

    private String buildMyBatisSqlText(ParsedCodeFile parsed, CodeChunk chunk, Map<String, Object> metadata) {
        StringBuilder sb = new StringBuilder();
        appendContext(sb, parsed, chunk, metadata);
        append(sb, "chunk_type", chunk.getChunkType());
        append(sb, "file_path", parsed.getRelativePath());
        append(sb, "namespace", metadata.get("namespace"));
        append(sb, "mapper_class", metadata.get("mapperClass"));
        append(sb, "mapper_method", metadata.get("mapperMethod"));
        append(sb, "sql_id", metadata.get("sqlId"));
        append(sb, "sql_type", metadata.get("sqlType"));
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

    private void appendMetadata(StringBuilder sb, Map<String, Object> metadata) {
        for (String key : METADATA_KEYS) {
            Object value = metadata.get(key);
            if (value != null) {
                append(sb, "metadata_" + key, formatValue(value));
            }
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

    private String formatValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Collection<?>) {
            Collection<?> collection = (Collection<?>) value;
            return String.join(", ", collection.stream().map(String::valueOf).toList());
        }
        return String.valueOf(value);
    }

    private Object first(Object first, Object fallback) {
        String value = first == null ? "" : String.valueOf(first).trim();
        return value.isEmpty() ? fallback : first;
    }

    private String truncate(String text) {
        return text.length() > MAX_EMBED_TEXT_LENGTH ? text.substring(0, MAX_EMBED_TEXT_LENGTH) : text;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
