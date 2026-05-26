package com.kama.jchatmind.model.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CodeEvidenceSelectionResult {
    private List<String> selectedChunkIds;
    private String reason;
    private String answerType;
    private String rawResponse;
    private boolean jsonParseOk;
    private boolean fallback;
    private long latencyMs;
}
