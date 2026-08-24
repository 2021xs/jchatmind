package com.kama.jchatmind.model.entity;

import com.kama.jchatmind.model.common.RepositorySourceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeRepository {
    private String id;
    private String name;
    private String rootPath;
    private String language;
    private String status;

    private RepositorySourceType sourceType;

    private String remoteUrl;

    private String branch;

    private String commitSha;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
