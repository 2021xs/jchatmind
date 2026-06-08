package com.kama.jchatmind.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ImportQualitySummary {
    private Integer totalFiles;
    private Integer scannedFiles;
    private Integer parsedFiles;
    private Integer fallbackFiles;
    private Integer javaFallbackCount;
    private Integer xmlFallbackCount;
    /**
     * Counts MYBATIS_XML_INCLUDE parser warnings recorded on generated chunks.
     * The current parser records warnings per SQL chunk, so this is a chunk-level warning count.
     */
    private Integer includeWarningCount;
    private Integer failedFiles;
    private Integer chunkCount;
    private Integer embeddedChunkCount;
    private String status;
}
