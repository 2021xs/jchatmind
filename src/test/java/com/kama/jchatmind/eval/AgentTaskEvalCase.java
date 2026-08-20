package com.kama.jchatmind.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
class AgentTaskEvalCase {
    public String id;
    public String difficulty;
    public String category;
    public String query;
    public String expectedTaskStatus = "SUCCESS";
    public List<String> requiredTools = List.of();
    public List<String> allowedTools = List.of();
    public List<String> forbiddenTools = List.of();
    public List<String> expectedArgumentKeywords = List.of();
    public List<String> expectedEvidenceFileKeywords = List.of();
    public List<String> expectedEvidenceSymbolKeywords = List.of();
    public List<String> expectedEvidenceKeywords = List.of();
    public List<String> expectedAnswerKeywords = List.of();
    public Integer maxReasonableSteps;
    public String notes;

    boolean valid() {
        return hasText(id)
                && hasText(difficulty)
                && hasText(category)
                && hasText(query)
                && hasText(expectedTaskStatus);
    }

    boolean evidenceApplicable() {
        return !safe(expectedEvidenceFileKeywords).isEmpty()
                || !safe(expectedEvidenceSymbolKeywords).isEmpty()
                || !safe(expectedEvidenceKeywords).isEmpty();
    }

    static List<String> safe(List<String> values) {
        return values == null ? List.of() : values;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
