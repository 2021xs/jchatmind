package com.kama.jchatmind.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagSearchResult {
    private String chunkId;
    private String title;
    private String content;
    private Double score;
    private String metadata;
    private String sourceType;
    private String sourceId;
}
