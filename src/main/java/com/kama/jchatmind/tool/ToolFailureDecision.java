package com.kama.jchatmind.tool;

public record ToolFailureDecision(
        String errorType,
        boolean correctable,
        String sanitizedMessage,
        String correctionHint
) {
}
