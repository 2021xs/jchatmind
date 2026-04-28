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
    private String contentPreview;
    private String metadata;
}
