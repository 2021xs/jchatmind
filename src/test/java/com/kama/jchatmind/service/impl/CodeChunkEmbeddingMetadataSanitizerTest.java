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
        metadata.put("className", "OrderService");
        metadata.put("emptyText", " ");
        metadata.put("emptyList", List.of());
        metadata.put("startLine", 10);
        metadata.put("includeExpanded", true);
        metadata.put("apiToken", "do-not-embed");

        List<EmbeddingMetadataEntry> entries = sanitizer.sanitize("SERVICE_METHOD", metadata);

        assertEquals(List.of(new EmbeddingMetadataEntry("className", "OrderService")), entries);
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

    @Test
    void metadataBudgetKeepsRetrievalFieldsAndBoundsOutput() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("zLargeField1", "a".repeat(600));
        metadata.put("zLargeField2", "b".repeat(600));
        metadata.put("zLargeField3", "c".repeat(600));
        metadata.put("zLargeField4", "d".repeat(600));
        metadata.put("zLargeField5", "e".repeat(600));
        metadata.put("zLargeField6", "f".repeat(600));
        metadata.put("normalizedSymbols", List.of("important retrieval symbol"));

        List<EmbeddingMetadataEntry> entries = sanitizer.sanitize("SERVICE_METHOD", metadata);
        int renderedLength = entries.stream()
                .mapToInt(entry -> entry.key().length() + entry.value().length() + 2)
                .sum();

        assertTrue(entries.contains(new EmbeddingMetadataEntry("normalizedSymbols", "important retrieval symbol")));
        assertTrue(renderedLength <= 3000);
    }
}
