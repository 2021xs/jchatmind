package com.kama.jchatmind.benchmark.context;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
class ContextLifecycleBenchmarkCase {
    public String caseId;
    public String category;
    public String sessionGroupId;
    public int taskOrder = 1;
    public String activation = "ACTIVE";
    public String question;
    public List<FactExpectation> expectedCriticalFacts = List.of();
    public List<FactExpectation> expectedSupportingFacts = List.of();
    public List<ExactValueExpectation> exactValues = List.of();
    public List<String> expectedRefs = List.of();
    public List<String> forbiddenClaims = List.of();
    public List<String> requiredTools = List.of();
    public List<String> allowedTools = List.of();
    public String notes;

    void validate() {
        requireText(caseId, "caseId");
        requireText(category, "category");
        requireText(question, "question");
        if (taskOrder <= 0) {
            throw new IllegalArgumentException("taskOrder must be positive: " + caseId);
        }
        if (expectedCriticalFacts == null || expectedCriticalFacts.isEmpty()) {
            throw new IllegalArgumentException("expectedCriticalFacts is required: " + caseId);
        }
        expectedCriticalFacts.forEach(value -> value.validate(caseId));
        safe(expectedSupportingFacts).forEach(value -> value.validate(caseId));
        safe(exactValues).forEach(value -> value.validate(caseId));
    }

    boolean active() {
        return "ACTIVE".equalsIgnoreCase(activation);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class FactExpectation {
        public String id;
        public List<String> allOf = List.of();
        public List<String> anyOf = List.of();

        void validate(String caseId) {
            requireText(id, "fact id for " + caseId);
            if (safe(allOf).isEmpty() && safe(anyOf).isEmpty()) {
                throw new IllegalArgumentException("Fact requires allOf or anyOf: " + caseId + "/" + id);
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ExactValueExpectation {
        public String id;
        public String value;

        void validate(String caseId) {
            requireText(id, "exact value id for " + caseId);
            requireText(value, "exact value for " + caseId + "/" + id);
        }
    }

    static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
