package com.kama.jchatmind.model.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CodeSearchExecutionResult {
    private List<CodeSearchResult> candidates;
    private long embeddingLatencyMs;
    private long retrievalLatencyMs;
    private boolean cacheHit;
}
