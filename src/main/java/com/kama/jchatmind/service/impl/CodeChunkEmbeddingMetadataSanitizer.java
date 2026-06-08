package com.kama.jchatmind.service.impl;

import org.springframework.stereotype.Component;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class CodeChunkEmbeddingMetadataSanitizer {
    private static final int MAX_ENTRIES = 40;
    private static final int MAX_COLLECTION_ITEMS = 20;
    private static final int MAX_VALUE_LENGTH = 600;
    private static final int MAX_COLLECTION_ITEM_LENGTH = 160;

    private static final Set<String> EXCLUDED_KEYS = Set.of(
            "startLine", "endLine", "includeExpanded", "includeWarnings",
            "fileName", "fileType", "packageName", "className", "qualifiedClassName",
            "apiPath", "httpMethod"
    );
    private static final List<String> SENSITIVE_KEY_PARTS = List.of(
            "password", "secret", "token", "credential", "authorization", "privatekey", "accesskey"
    );

    public List<EmbeddingMetadataEntry> sanitize(String chunkType, Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }

        Map<String, Object> metadata = new LinkedHashMap<>(source);
        applyChunkTypeAliases(chunkType, metadata);

        return metadata.entrySet().stream()
                .filter(entry -> shouldKeep(entry.getKey()))
                .map(entry -> new EmbeddingMetadataEntry(entry.getKey(), formatValue(entry.getValue())))
                .filter(entry -> !entry.value().isBlank())
                .sorted(Comparator.comparingInt((EmbeddingMetadataEntry entry) -> retrievalPriority(entry.key()))
                        .thenComparing(EmbeddingMetadataEntry::key))
                .limit(MAX_ENTRIES)
                .toList();
    }

    private void applyChunkTypeAliases(String chunkType, Map<String, Object> metadata) {
        if ("MYBATIS_SQL".equals(chunkType)) {
            mergeAliases(metadata, "sqlId", "id", "mapperMethod");
            mergeAliases(metadata, "sqlType", "statementType");
            metadata.remove("symbols");
            metadata.remove("literalValues");
        }
    }

    private void mergeAliases(Map<String, Object> metadata, String canonicalKey, String... aliases) {
        if (isEmpty(metadata.get(canonicalKey))) {
            for (String alias : aliases) {
                if (!isEmpty(metadata.get(alias))) {
                    metadata.put(canonicalKey, metadata.get(alias));
                    break;
                }
            }
        }
        for (String alias : aliases) {
            metadata.remove(alias);
        }
    }

    private boolean shouldKeep(String key) {
        if (key == null || key.isBlank() || EXCLUDED_KEYS.contains(key)) {
            return false;
        }
        String normalized = key.replaceAll("[^A-Za-z0-9]", "").toLowerCase();
        return SENSITIVE_KEY_PARTS.stream().noneMatch(normalized::contains);
    }

    private int retrievalPriority(String key) {
        return switch (key) {
            case "normalizedSymbols" -> 0;
            case "symbols" -> 1;
            case "literalValues" -> 2;
            default -> 10;
        };
    }

    private String formatValue(Object value) {
        if (isEmpty(value)) {
            return "";
        }
        if (value instanceof Map<?, ?> map) {
            List<String> entries = map.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .map(entry -> String.valueOf(entry.getKey()) + "=" + formatScalar(entry.getValue(), MAX_COLLECTION_ITEM_LENGTH))
                    .filter(entry -> !entry.endsWith("="))
                    .limit(MAX_COLLECTION_ITEMS)
                    .toList();
            return truncate(String.join(", ", entries), MAX_VALUE_LENGTH);
        }
        if (value instanceof Collection<?> collection) {
            return formatCollection(collection);
        }
        if (value.getClass().isArray()) {
            List<Object> items = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) {
                items.add(Array.get(value, i));
            }
            return formatCollection(items);
        }
        return formatScalar(value, MAX_VALUE_LENGTH);
    }

    private String formatCollection(Collection<?> collection) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (Object item : collection) {
            String formatted = formatScalar(item, MAX_COLLECTION_ITEM_LENGTH);
            if (!formatted.isBlank()) {
                values.add(formatted);
            }
            if (values.size() >= MAX_COLLECTION_ITEMS) {
                break;
            }
        }
        return truncate(String.join(", ", values), MAX_VALUE_LENGTH);
    }

    private String formatScalar(Object value, int maxLength) {
        return value == null ? "" : truncate(String.valueOf(value).trim(), maxLength);
    }

    private boolean isEmpty(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String text) {
            return text.isBlank();
        }
        if (value instanceof Collection<?> collection) {
            return collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        return value.getClass().isArray() && Array.getLength(value) == 0;
    }

    private String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 16) + "...[truncated]";
    }
}
