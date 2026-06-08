package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.model.dto.ParsedCodeFile;
import com.kama.jchatmind.model.entity.CodeChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeChunkEmbeddingTextFormatterTest {
    private final CodeChunkEmbeddingTextFormatter formatter = new CodeChunkEmbeddingTextFormatter();

    @Test
    void formatsStableSections() {
        String text = formatter.format(parsed(), chunk("select * from orders"), List.of(
                new EmbeddingMetadataEntry("sqlId", "selectOrders")
        ));

        assertTrue(text.startsWith("identity:\nchunk_type: MYBATIS_SQL"));
        assertTrue(text.contains("metadata:\nsqlId: selectOrders"));
        assertTrue(text.endsWith("content:\nselect * from orders"));
    }

    @Test
    void boundsMetadataAndPreservesContentBudget() {
        List<EmbeddingMetadataEntry> metadata = List.of(
                new EmbeddingMetadataEntry("largeOne", "a".repeat(1800)),
                new EmbeddingMetadataEntry("largeTwo", "b".repeat(1800))
        );

        String text = formatter.format(parsed(), chunk("c".repeat(10000)), metadata);
        int contentStart = text.indexOf("content:\n") + "content:\n".length();

        assertEquals(8000, text.length());
        assertTrue(contentStart <= 2800);
        assertTrue(text.substring(contentStart).length() >= 5200);
    }

    private ParsedCodeFile parsed() {
        return ParsedCodeFile.builder()
                .relativePath("src/main/resources/mapper/OrderMapper.xml")
                .fileType("MYBATIS_XML")
                .build();
    }

    private CodeChunk chunk(String content) {
        return CodeChunk.builder()
                .chunkType("MYBATIS_SQL")
                .symbolName("com.demo.OrderMapper.selectOrders")
                .content(content)
                .build();
    }
}
