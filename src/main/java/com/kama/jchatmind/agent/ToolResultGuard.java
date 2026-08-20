package com.kama.jchatmind.agent;

import com.kama.jchatmind.config.ToolResultProperties;
import org.springframework.stereotype.Component;

@Component
public class ToolResultGuard {

    private final ToolResultProperties properties;

    public ToolResultGuard(ToolResultProperties properties) {
        this.properties = properties;
    }

    public GuardedToolResult guard(String actualToolName, String canonicalToolName, String rawResult) {
        int maxResultChars = properties.maxResultCharsFor(actualToolName, canonicalToolName);
        if (rawResult == null) {
            return new GuardedToolResult(null, 0, 0, maxResultChars, false);
        }

        int originalChars = rawResult.codePointCount(0, rawResult.length());
        if (originalChars <= maxResultChars) {
            return new GuardedToolResult(rawResult, originalChars, originalChars, maxResultChars, false);
        }

        String marker = "\n...[TRUNCATED: originalChars=" + originalChars + ", maxChars=" + maxResultChars + "]";
        int markerChars = marker.codePointCount(0, marker.length());
        int prefixChars = maxResultChars - markerChars;
        if (prefixChars < 0) {
            throw new IllegalStateException("Tool maxResultChars is too small for the truncation marker");
        }
        int prefixEnd = rawResult.offsetByCodePoints(0, prefixChars);
        String guardedResult = rawResult.substring(0, prefixEnd) + marker;
        return new GuardedToolResult(guardedResult, originalChars, maxResultChars, maxResultChars, true);
    }

    public record GuardedToolResult(
            String value,
            int originalChars,
            int storedChars,
            int maxResultChars,
            boolean truncated) {
    }
}
