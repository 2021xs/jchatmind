package com.kama.jchatmind.model.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ImportCodeRepositoryResponse {
    private String repoId;
    private Integer fileCount;
    private Integer chunkCount;
    private Boolean truncated;
    private String message;
}
