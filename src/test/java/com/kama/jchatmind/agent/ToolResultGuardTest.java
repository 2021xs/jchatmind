package com.kama.jchatmind.agent;

import com.kama.jchatmind.config.ToolResultProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultGuardTest {
    private ToolResultProperties properties;
    private ToolResultGuard guard;

    @BeforeEach
    void setUp() {
        properties = new ToolResultProperties();
        properties.setDefaultMaxResultChars(100);
        guard = new ToolResultGuard(properties);
    }

    @Test
    void smallResultIsUnchanged() {
        ToolResultGuard.GuardedToolResult result = guard.guard("tool", "tool", "small result");

        assertEquals("small result", result.value());
        assertEquals(12, result.originalChars());
        assertEquals(12, result.storedChars());
        assertFalse(result.truncated());
    }

    @Test
    void exactLimitIsUnchanged() {
        String raw = "x".repeat(100);

        ToolResultGuard.GuardedToolResult result = guard.guard("tool", "tool", raw);

        assertEquals(raw, result.value());
        assertEquals(100, result.storedChars());
        assertFalse(result.truncated());
    }

    @Test
    void oversizedResultIncludesMarkerWithinLimit() {
        String raw = "prefix-" + "x".repeat(200) + "-TAIL-MUST-NOT-SURVIVE";

        ToolResultGuard.GuardedToolResult result = guard.guard("tool", "tool", raw);

        assertTrue(result.truncated());
        assertEquals(100, result.value().codePointCount(0, result.value().length()));
        assertEquals(raw.codePointCount(0, raw.length()), result.originalChars());
        assertTrue(result.value().contains("[TRUNCATED: originalChars="));
        assertFalse(result.value().contains("TAIL-MUST-NOT-SURVIVE"));
    }

    @Test
    void veryLargeResultIsBoundedDeterministically() {
        String raw = "0123456789".repeat(5_000);

        ToolResultGuard.GuardedToolResult first = guard.guard("tool", "tool", raw);
        ToolResultGuard.GuardedToolResult second = guard.guard("tool", "tool", raw);

        assertEquals(first, second);
        assertEquals(50_000, first.originalChars());
        assertEquals(100, first.storedChars());
    }

    @Test
    void unicodeTruncationDoesNotSplitSurrogatePairs() {
        String raw = "中文\n{\"emoji\":\"😀\"}" + "😀".repeat(100) + "结尾";

        ToolResultGuard.GuardedToolResult result = guard.guard("tool", "tool", raw);

        assertTrue(result.truncated());
        assertEquals(100, result.value().codePointCount(0, result.value().length()));
        assertFalse(hasUnpairedSurrogate(result.value()));
    }

    @Test
    void perToolOverrideUsesActualNameBeforeCanonicalName() {
        properties.setDefaultMaxResultChars(80);
        properties.setOverrides(Map.of("aliasTool", 160, "canonicalTool", 120));
        String raw = "x".repeat(140);

        ToolResultGuard.GuardedToolResult result = guard.guard("aliasTOOL", "canonicalTool", raw);

        assertFalse(result.truncated());
        assertEquals(160, result.maxResultChars());
    }

    private boolean hasUnpairedSurrogate(String value) {
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i))) {
                    return true;
                }
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }
}
