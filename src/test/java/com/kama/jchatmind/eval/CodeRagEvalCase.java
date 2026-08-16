package com.kama.jchatmind.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
class CodeRagEvalCase {
    public String id;
    public String query;
    public List<String> expectedFileKeywords = List.of();
    public List<String> expectedSymbolKeywords = List.of();
    public List<String> expectedChunkTypes = List.of();
    public String category;
    public String difficulty;

    boolean hasValidGroundTruth() {
        return hasText(id)
                && hasText(query)
                && hasText(category)
                && (!expectedFileKeywords.isEmpty() || !expectedSymbolKeywords.isEmpty());
    }

    String groundTruthDescription() {
        return "files=" + expectedFileKeywords
                + "; symbols=" + expectedSymbolKeywords
                + "; chunkTypes=" + expectedChunkTypes;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
