package com.kama.jchatmind.agent;

import org.springframework.util.Assert;

import java.util.Map;

/**
 * Immutable, task-local evidence projected from one completed tool response.
 * Evidence content is untrusted data and is never treated as a model instruction.
 */
public record FinalEvidence(
        String evidenceId,
        String toolCallId,
        String toolName,
        String content,
        Map<String, Object> sourceMetadata) {

    public FinalEvidence {
        Assert.hasText(evidenceId, "Final evidence id cannot be empty");
        Assert.hasText(toolCallId, "Final evidence toolCallId cannot be empty");
        Assert.hasText(toolName, "Final evidence tool name cannot be empty");
        Assert.notNull(content, "Final evidence content cannot be null");
        sourceMetadata = sourceMetadata == null ? Map.of() : Map.copyOf(sourceMetadata);
    }
}
