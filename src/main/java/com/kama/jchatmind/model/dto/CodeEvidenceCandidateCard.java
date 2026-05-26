package com.kama.jchatmind.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CodeEvidenceCandidateCard {
    // Compact selector input. Keep this as metadata/snippet, not a full Top50 code dump.
    private String chunkId;
    private String chunkType;
    private String filePath;
    private String symbolName;
    private String apiPath;
    private String httpMethod;
    private String metadataSummary;
    private String snippet;
    private String evidenceRole;
    private String evidenceHint;
    private String source;
    private int rawRank;
    private int candidateRank;
    private Double candidateScore;
}
