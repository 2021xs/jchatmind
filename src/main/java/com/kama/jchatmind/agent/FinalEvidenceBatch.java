package com.kama.jchatmind.agent;

import org.springframework.util.Assert;

import java.util.List;

/** Atomic projection of one assistant(tool_calls) -> tool* execution batch. */
public record FinalEvidenceBatch(int batchIndex, List<FinalEvidence> evidence) {

    public FinalEvidenceBatch {
        Assert.isTrue(batchIndex > 0, "Final evidence batch index must be positive");
        Assert.notEmpty(evidence, "Final evidence batch cannot be empty");
        evidence = List.copyOf(evidence);
    }
}
