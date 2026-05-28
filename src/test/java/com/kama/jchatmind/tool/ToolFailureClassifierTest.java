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
    void classifiesStructuredToolFailuresBeforeMessageFallback() {
        ToolFailureDecision argument = classifier.classify(
                new ToolArgumentException("missing required field query", null));
        ToolFailureDecision unknown = classifier.classify(
                new ToolUnknownException("Unknown tool: imaginaryTool"));
        ToolFailureDecision policy = classifier.classify(
                new ToolPolicyRejectedException("SQL rejected by safety policy"));

        assertTrue(argument.correctable());
        assertEquals(AgentTaskLogService.ERROR_TYPE_ARGUMENT_PARSE_ERROR, argument.errorType());
        assertFalse(unknown.correctable());
        assertEquals(AgentTaskLogService.ERROR_TYPE_UNKNOWN_TOOL, unknown.errorType());
        assertFalse(policy.correctable());
        assertEquals(AgentTaskLogService.ERROR_TYPE_POLICY_REJECTED, policy.errorType());
    }

    @Test
    void treatsUnstructuredNonArgumentFailuresAsGenericToolException() {
        ToolFailureDecision policy = classifier.classify(
                new IllegalStateException("Tool is not allowed in current agent runtime: databaseQuery"));
        ToolFailureDecision timeout = classifier.classify(
                new IllegalStateException("Tool execution timed out"));
        ToolFailureDecision database = classifier.classify(
                new IllegalStateException("Database query execution failed: connection refused"));

        assertFalse(policy.correctable());
        assertEquals(AgentTaskLogService.ERROR_TYPE_TOOL_EXCEPTION, policy.errorType());
        assertFalse(timeout.correctable());
        assertEquals(AgentTaskLogService.ERROR_TYPE_TOOL_EXCEPTION, timeout.errorType());
        assertFalse(database.correctable());
        assertEquals(AgentTaskLogService.ERROR_TYPE_TOOL_EXCEPTION, database.errorType());
    }

    @Test
    void doesNotClassifyUnstructuredMessagesByKeywords() {
        ToolFailureDecision decision = classifier.classify(
                new RuntimeException("Failed to parse JSON argument: missing required field query"));

        assertFalse(decision.correctable());
        assertEquals(AgentTaskLogService.ERROR_TYPE_TOOL_EXCEPTION, decision.errorType());
    }
}
