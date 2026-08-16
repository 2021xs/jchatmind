package com.kama.jchatmind.model.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CodeEvidenceSelectionResult {
    private List<String> selectedChunkIds;
    private List<String> proposedChunkIds;
    private List<String> validChunkIds;
    private List<String> invalidChunkIds;
    private List<String> proposedCandidateIds;
    private List<String> validCandidateIds;
    private List<String> invalidCandidateIds;
    private String reason;
    private String answerType;
    private String rawResponse;
    private boolean jsonParseOk;
    private boolean fallback;
    private boolean emptySelectorResult;
    private boolean executionError;
    private long latencyMs;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private boolean usageAvailable;
    private int promptChars;
    private int candidateSectionChars;
}
