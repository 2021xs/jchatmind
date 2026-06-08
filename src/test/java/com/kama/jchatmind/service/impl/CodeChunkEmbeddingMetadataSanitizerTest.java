package com.kama.jchatmind.service.impl;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeChunkEmbeddingMetadataSanitizerTest {
    private final CodeChunkEmbeddingMetadataSanitizer sanitizer = new CodeChunkEmbeddingMetadataSanitizer();

    @Test
    void removesEmptySensitiveAndLowValueFields() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("emptyText", " ");
        metadata.put("emptyList", List.of());
        metadata.put("startLine", 10);
        metadata.put("includeExpanded", true);
        metadata.put("apiToken", "do-not-embed");
        metadata.put("className", "DuplicateIdentity");
        metadata.put("apiPath", "/duplicate");

        List<EmbeddingMetadataEntry> entries = sanitizer.sanitize("SERVICE_METHOD", metadata);

        assertTrue(entries.isEmpty());
    }

    @Test
    void myBatisAliasesAreMergedAndRetrievalFieldsAreKept() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mapperMethod", "selectPaid");
        metadata.put("id", "selectPaid");
        metadata.put("statementType", "SELECT");
        metadata.put("dynamicTags", List.of("where", "if", "if"));
        metadata.put("normalizedSymbols", List.of("select paid", "order mapper"));
        metadata.put("symbols", List.of("selectPaid"));
        metadata.put("literalValues", List.of("selectPaid"));
        metadata.put("futureRetrievalHint", "paid orders");

        List<EmbeddingMetadataEntry> entries = sanitizer.sanitize("MYBATIS_SQL", metadata);

        assertTrue(entries.contains(new EmbeddingMetadataEntry("sqlId", "selectPaid")));
        assertTrue(entries.contains(new EmbeddingMetadataEntry("sqlType", "SELECT")));
        assertTrue(entries.contains(new EmbeddingMetadataEntry("dynamicTags", "where, if")));
        assertTrue(entries.contains(new EmbeddingMetadataEntry("normalizedSymbols", "select paid, order mapper")));
        assertTrue(entries.contains(new EmbeddingMetadataEntry("futureRetrievalHint", "paid orders")));
        assertFalse(entries.stream().anyMatch(entry -> "id".equals(entry.key())));
        assertFalse(entries.stream().anyMatch(entry -> "mapperMethod".equals(entry.key())));
        assertFalse(entries.stream().anyMatch(entry -> "statementType".equals(entry.key())));
        assertFalse(entries.stream().anyMatch(entry -> "symbols".equals(entry.key())));
        assertFalse(entries.stream().anyMatch(entry -> "literalValues".equals(entry.key())));
    }

    @Test
    void outputOrderIsStable() {
        List<EmbeddingMetadataEntry> entries = sanitizer.sanitize("CONFIG", Map.of(
                "zKey", "last",
                "aKey", "first"
        ));

        assertEquals(List.of(
                new EmbeddingMetadataEntry("aKey", "first"),
                new EmbeddingMetadataEntry("zKey", "last")
        ), entries);
    }
}
