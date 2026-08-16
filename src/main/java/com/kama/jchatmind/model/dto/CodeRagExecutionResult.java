package com.kama.jchatmind.model.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CodeRagExecutionResult {
    private CodeAnswerEvidenceResult answerEvidence;
    private List<CodeSearchResult> rawCandidates;
    private long embeddingLatencyMs;
    private long retrievalLatencyMs;
    private long selectorLatencyMs;
    private long totalLatencyMs;
    private boolean cacheHit;
    private List<String> selectorProposedChunkIds;
    private List<String> selectorValidChunkIds;
    private List<String> selectorInvalidChunkIds;
    private List<String> selectorProposedCandidateIds;
    private List<String> selectorValidCandidateIds;
    private List<String> selectorInvalidCandidateIds;
    private String selectorFallbackReason;
    private boolean emptySelectorResult;
    private boolean selectorExecutionError;
    private Integer selectorPromptTokens;
    private Integer selectorCompletionTokens;
    private Integer selectorTotalTokens;
    private boolean selectorUsageAvailable;
    private int selectorPromptChars;
    private int selectorCandidateSectionChars;
}
