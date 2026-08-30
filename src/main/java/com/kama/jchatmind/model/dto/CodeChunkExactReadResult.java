package com.kama.jchatmind.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeChunkExactReadResult {
    private String repoId;
    private String chunkId;
    private String filePath;
    private String symbolName;
    private String chunkType;
    private Integer startLine;
    private Integer endLine;
    private String content;
}
