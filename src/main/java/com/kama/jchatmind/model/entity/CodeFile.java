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
public class CodeFile {
    private String id;
    private String repoId;
    private String filePath;
    private String fileType;
    private String packageName;
    private String className;
    private String checksum;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
