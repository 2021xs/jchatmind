package com.kama.jchatmind.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeChunk {
    private String id;
    private String repoId;
    private String fileId;
    private String chunkType;
    private String symbolName;
    private String apiPath;
    private String httpMethod;
    private Integer startLine;
    private Integer endLine;
    private String content;
    private String metadata;
    private float[] embedding;
    private LocalDateTime createdAt;
}
