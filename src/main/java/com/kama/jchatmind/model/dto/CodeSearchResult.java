package com.kama.jchatmind.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeSearchResult {
    private String chunkId;
    private String repoId;
    private String filePath;
    private String fileType;
    private String chunkType;
    private String symbolName;
    private String apiPath;
    private String httpMethod;
    private Integer startLine;
    private Integer endLine;
    private Double score;
    private Double originalScore;
    private Double boostScore;
    private Double rerankerScore;
    private Double finalScore;
    private String rerankSource;
    private String rerankReasons;
    private String contentPreview;
    private String metadata;
}
