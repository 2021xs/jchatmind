package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.model.dto.CodeSearchResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeSearchEvidenceFormatterTest {

    private final CodeSearchEvidenceFormatter formatter = new CodeSearchEvidenceFormatter();

    @Test
    void presentsCoreEvidenceAndCompactApiWithoutInternalDiagnostics() {
        CodeSearchResult evidence = CodeSearchResult.builder()
                .chunkId("chunk-1")
                .repoId("repo-1")
                .filePath("src/main/java/example/VoucherController.java")
                .fileType("JAVA")
                .symbolName("VoucherController#seckill")
                .chunkType("CONTROLLER_API")
                .startLine(31)
                .endLine(48)
                .httpMethod("POST")
                .apiPath("/voucher/{id}")
                .score(0.91)
                .metadata("{\"rawCandidateCount\":20,\"sqlId\":\"unused\"}")
                .contentPreview("public Result seckill(Long id) {\n    return service.seckill(id);\n}")
                .build();

        String result = formatter.format(List.of(evidence));

        assertTrue(result.contains("repoId: repo-1"));
        assertTrue(result.contains("chunkId: chunk-1"));
        assertTrue(result.contains("file: src/main/java/example/VoucherController.java"));
        assertTrue(result.contains("symbol: VoucherController#seckill"));
        assertTrue(result.contains("type: CONTROLLER_API"));
        assertTrue(result.contains("lines: 31-48"));
        assertTrue(result.contains("api: POST /voucher/{id}"));
        assertTrue(result.contains("snippet:\n" + evidence.getContentPreview()));
        assertFalse(result.contains("score:"));
        assertFalse(result.contains("metadata:"));
        assertFalse(result.contains("rawCandidateCount"));
        assertFalse(result.contains("fileType:"));
    }

    @Test
    void preservesSelectorOrderAndDoesNotMutateSelectedEvidence() {
        List<CodeSearchResult> selected = new ArrayList<>(List.of(
                evidence("chunk-a", "A.java", "A#method"),
                evidence("chunk-b", "B.java", "B#method"),
                evidence("chunk-c", "C.java", "C#method")));
        List<String> idsBefore = selected.stream().map(CodeSearchResult::getChunkId).toList();

        String result = formatter.format(selected);

        assertTrue(result.indexOf("file: A.java") < result.indexOf("file: B.java"));
        assertTrue(result.indexOf("file: B.java") < result.indexOf("file: C.java"));
        assertEquals(idsBefore, selected.stream().map(CodeSearchResult::getChunkId).toList());
    }

    @Test
    void omitsEmptyOptionalFields() {
        CodeSearchResult evidence = CodeSearchResult.builder()
                .repoId("repo-1")
                .chunkId("chunk-1")
                .filePath("OnlyFile.java")
                .chunkType("CLASS_SUMMARY")
                .contentPreview("class OnlyFile {}")
                .build();

        String result = formatter.format(List.of(evidence));

        assertFalse(result.contains("symbol:"));
        assertFalse(result.contains("lines:"));
        assertFalse(result.contains("api:"));
        assertFalse(result.contains("null"));
        assertFalse(result.contains("UNKNOWN"));
        assertFalse(result.contains("N/A"));
    }

    @Test
    void keepsLocatorsPairedForDifferentChunksFromTheSameFile() {
        CodeSearchResult first = evidence("chunk-a", "Same.java", "Same#first");
        CodeSearchResult second = evidence("chunk-b", "Same.java", "Same#second");

        String result = formatter.format(List.of(first, second));

        String firstBlock = result.substring(result.indexOf("[1]"), result.indexOf("[2]"));
        String secondBlock = result.substring(result.indexOf("[2]"));
        assertTrue(firstBlock.contains("repoId: repo-1"));
        assertTrue(firstBlock.contains("chunkId: chunk-a"));
        assertFalse(firstBlock.contains("chunkId: chunk-b"));
        assertTrue(secondBlock.contains("repoId: repo-1"));
        assertTrue(secondBlock.contains("chunkId: chunk-b"));
        assertFalse(secondBlock.contains("chunkId: chunk-a"));
    }

    @Test
    void rejectsSelectedEvidenceWithoutStableLocator() {
        CodeSearchResult missingRepoId = CodeSearchResult.builder()
                .chunkId("chunk-1")
                .filePath("File.java")
                .build();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> formatter.format(List.of(missingRepoId)));

        assertEquals("Selected code evidence is missing repoId or chunkId", failure.getMessage());
    }

    @Test
    void presentsFiveEvidenceBlocksWithStableBoundaries() {
        List<CodeSearchResult> evidence = List.of(
                evidence("1", "One.java", "one"), evidence("2", "Two.java", "two"),
                evidence("3", "Three.java", "three"), evidence("4", "Four.java", "four"),
                evidence("5", "Five.java", "five"));

        String result = formatter.format(evidence);

        for (int i = 1; i <= 5; i++) {
            assertEquals(1, occurrences(result, "[" + i + "]"));
        }
        assertEquals(5, occurrences(result, "snippet:"));
    }

    private CodeSearchResult evidence(String id, String file, String symbol) {
        return CodeSearchResult.builder()
                .chunkId(id).repoId("repo-1").filePath(file).symbolName(symbol).chunkType("METHOD")
                .startLine(10).endLine(20).contentPreview("void " + symbol + "() {}")
                .build();
    }

    private int occurrences(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }
}
