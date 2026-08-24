package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.model.dto.CodeEvidenceCandidateCard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeEvidenceCandidateFormatterTest {
    private final CodeEvidenceCandidateFormatter formatter =
            new CodeEvidenceCandidateFormatter(new ObjectMapper());

    @Test
    void formatsCoreFieldsLinesApiAndCompactSignals() {
        CodeEvidenceCandidateCard candidate = baseCandidate()
                .startLine(118).endLine(158)
                .apiPath("/voucher-order/seckill/{id}").httpMethod("POST")
                .metadataSummary("""
                        {"sqlId":"decrementStock","namespace":"com.demo.Mapper","tables":["tb_stock"],
                         "symbols":["SECKILL_STOCK_KEY"],"unknown":"ignored"}
                        """)
                .build();

        String text = formatter.format("C07", candidate);

        assertTrue(text.contains("[C07]\n"));
        assertTrue(text.contains("file: src/Service.java"));
        assertTrue(text.contains("symbol: Service#method"));
        assertTrue(text.contains("type: SERVICE_METHOD"));
        assertTrue(text.contains("lines: 118-158"));
        assertTrue(text.contains("api: POST /voucher-order/seckill/{id}"));
        assertTrue(text.contains("signals: sqlId=decrementStock; namespace=com.demo.Mapper; tables=tb_stock; symbols=SECKILL_STOCK_KEY"));
        assertFalse(text.contains("unknown"));
    }

    @Test
    void omitsMissingOptionalFields() {
        CodeEvidenceCandidateCard candidate = CodeEvidenceCandidateCard.builder()
                .chunkId("uuid-1")
                .chunkType("LUA_SCRIPT")
                .filePath("src/main/resources/seckill.lua")
                .snippet("return 1")
                .build();

        String text = formatter.format("C01", candidate);

        assertFalse(text.contains("symbol:"));
        assertFalse(text.contains("lines:"));
        assertFalse(text.contains("api:"));
        assertFalse(text.contains("signals:"));
    }

    @Test
    void omitsIncompleteLineRange() {
        String text = formatter.format("C01", baseCandidate().startLine(10).endLine(null).build());

        assertFalse(text.contains("lines:"));
    }

    @Test
    void signalsKeepAllowedValuesAndRemoveDisplayedDuplicates() {
        CodeEvidenceCandidateCard candidate = baseCandidate()
                .metadataSummary("""
                        {"symbols":["Service#method","CACHE_KEY"],"literalValues":["cache:key"],
                         "annotations":["Transactional"],"fileName":"Service.java","random":"ignored"}
                        """)
                .build();

        String signals = formatter.compactSignals(candidate);

        assertEquals("symbols=CACHE_KEY; literals=cache:key; annotations=Transactional", signals);
    }

    @Test
    void emptyAndUnknownJsonMetadataProduceNoSignals() {
        assertEquals("", formatter.compactSignals(baseCandidate().metadataSummary(null).build()));
        assertEquals("", formatter.compactSignals(baseCandidate().metadataSummary("{}").build()));
        assertEquals("", formatter.compactSignals(baseCandidate().metadataSummary("{\"unknown\":\"value\"}").build()));
    }

    @Test
    void invalidMetadataUsesBoundedFallbackFormatter() {
        String metadata = "plain metadata\n" + "x".repeat(300);

        String signals = formatter.compactSignals(baseCandidate().metadataSummary(metadata).build());

        assertTrue(signals.length() <= 160);
        assertTrue(signals.endsWith("...[truncated]"));
        assertFalse(signals.contains("\n"));
    }

    @Test
    void snippetUsesUniformBoundAndPreservesShortTextAndLineBreaks() {
        String shortText = "line one\nline two";
        String shortFormatted = formatter.format("C01", baseCandidate().snippet(shortText).build());
        String longFormatted = formatter.format("C01", baseCandidate().snippet("x".repeat(500)).build());

        assertTrue(shortFormatted.contains("snippet:\n" + shortText));
        assertFalse(shortFormatted.contains("...[truncated]"));
        assertTrue(longFormatted.contains("...[truncated]"));
        assertFalse(longFormatted.contains("x".repeat(CodeEvidenceCandidateFormatter.MAX_SNIPPET_CHARS + 1)));
    }

    private CodeEvidenceCandidateCard.CodeEvidenceCandidateCardBuilder baseCandidate() {
        return CodeEvidenceCandidateCard.builder()
                .chunkId("uuid-1")
                .chunkType("SERVICE_METHOD")
                .filePath("src/Service.java")
                .symbolName("Service#method")
                .snippet("snippet");
    }
}
