package com.kama.jchatmind.model.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CodeAnswerEvidenceResult {
    private List<CodeSearchResult> selectedEvidence;
    private String selectorReason;
    private String answerType;
    private boolean fallback;
    private boolean jsonParseOk;
    private long selectorLatencyMs;
    private int candidateCount;
    private int rawCount;
}
