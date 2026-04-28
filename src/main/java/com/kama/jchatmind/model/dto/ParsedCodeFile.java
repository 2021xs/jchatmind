package com.kama.jchatmind.model.dto;

import com.kama.jchatmind.model.entity.CodeChunk;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParsedCodeFile {
    private String relativePath;
    private String fileType;
    private String packageName;
    private String className;
    private String checksum;
    private List<CodeChunk> chunks;
}
