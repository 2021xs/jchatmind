package com.kama.jchatmind.tool;

import com.kama.jchatmind.service.AgentTaskLogService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolFailureClassifierTest {

    private final ToolFailureClassifier classifier = new ToolFailureClassifier();

    @Test
    void classifiesArgumentParseErrorsAsCorrectable() {
        ToolFailureDecision decision = classifier.classify(
                new IllegalArgumentException("Failed to parse JSON argument: missing required field query"));

        assertTrue(decision.correctable());
        assertEquals(AgentTaskLogService.ERROR_TYPE_ARGUMENT_PARSE_ERROR, decision.errorType());
        assertTrue(decision.correctionHint().contains("valid JSON arguments"));
    }

    @Test
    void doesNotCorrectPolicyOrSystemFailures() {
        ToolFailureDecision policy = classifier.classify(
                new IllegalStateException("Tool is not allowed in current agent runtime: databaseQuery"));
        ToolFailureDecision database = classifier.classify(
                new IllegalStateException("Database query execution failed: connection refused"));

        assertFalse(policy.correctable());
        assertEquals(AgentTaskLogService.ERROR_TYPE_POLICY_REJECTED, policy.errorType());
        assertFalse(database.correctable());
        assertEquals(AgentTaskLogService.ERROR_TYPE_TOOL_EXCEPTION, database.errorType());
    }
}
