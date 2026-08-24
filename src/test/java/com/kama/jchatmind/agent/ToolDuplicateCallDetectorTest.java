package com.kama.jchatmind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.ToolDuplicateDetectionProperties;
import com.kama.jchatmind.tool.ToolDuplicateCallState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolDuplicateCallDetectorTest {
    private ToolDuplicateCallDetector detector;
    private ToolDuplicateCallState state;

    @BeforeEach
    void setUp() {
        ToolDuplicateDetectionProperties properties = new ToolDuplicateDetectionProperties();
        properties.setMaxConsecutiveSameCalls(2);
        detector = new ToolDuplicateCallDetector(new ObjectMapper(), properties);
        state = new ToolDuplicateCallState();
    }

    @Test
    void firstTwoCallsAreAllowedAndThirdIsRejected() {
        assertEquals(ToolDuplicateCallState.Action.ALLOWED, check("tool", "{\"query\":\"A\"}").action());
        assertEquals(ToolDuplicateCallState.Action.ALLOWED, check("tool", "{\"query\":\"A\"}").action());

        ToolDuplicateCallDetector.DuplicateCheck third = check("tool", "{\"query\":\"A\"}");

        assertTrue(third.rejected());
        assertFalse(third.hardStop());
        assertEquals(3, third.consecutiveCount());
    }

    @Test
    void nextSameCallAfterRejectionRequestsHardStop() {
        check("tool", "{}");
        check("tool", "{}");
        check("tool", "{}");

        ToolDuplicateCallDetector.DuplicateCheck fourth = check("tool", "{}");

        assertTrue(fourth.hardStop());
        assertTrue(state.isHardStopRequested());
    }

    @Test
    void objectAndNestedFieldOrderAreCanonicalized() {
        ToolDuplicateCallDetector.DuplicateCheck first = check(
                "tool", "{\"query\":\"A\",\"filter\":{\"b\":2,\"a\":1}}");
        ToolDuplicateCallDetector.DuplicateCheck second = check(
                "tool", "{\"filter\":{\"a\":1,\"b\":2},\"query\":\"A\"}");

        assertEquals(first.duplicateKey(), second.duplicateKey());
        assertEquals(2, second.consecutiveCount());
    }

    @Test
    void arrayOrderAndValueTypesRemainDistinct() {
        check("tool", "{\"ids\":[1,2]}");
        assertEquals(1, check("tool", "{\"ids\":[2,1]}").consecutiveCount());

        check("tool", "{\"topK\":5}");
        assertEquals(1, check("tool", "{\"topK\":\"5\"}").consecutiveCount());
    }

    @Test
    void differentToolDifferentArgsAndNonConsecutiveCallsResetSequence() {
        check("toolA", "{\"query\":\"A\"}");
        assertEquals(1, check("toolA", "{\"query\":\"B\"}").consecutiveCount());
        assertEquals(1, check("toolB", "{\"query\":\"B\"}").consecutiveCount());
        assertEquals(1, check("toolA", "{\"query\":\"B\"}").consecutiveCount());
    }

    @Test
    void invalidJsonSkipsDetectionAndResetsConsecutiveSequence() {
        check("tool", "{}");
        check("tool", "{}");

        ToolDuplicateCallDetector.DuplicateCheck invalid = check("tool", "{} {}");
        ToolDuplicateCallDetector.DuplicateCheck validAgain = check("tool", "{}");

        assertNull(invalid.action());
        assertFalse(invalid.rejected());
        assertEquals(1, validAgain.consecutiveCount());
    }

    private ToolDuplicateCallDetector.DuplicateCheck check(String toolName, String arguments) {
        return detector.check(state, toolName, arguments);
    }
}
