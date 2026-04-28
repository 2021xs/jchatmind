package com.kama.jchatmind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "jchatmind.code-rag")
public class CodeRagProperties {
    private List<String> allowedRoots = new ArrayList<>();
    private long maxFileSizeBytes = 1024 * 1024;
    private int maxFilesPerImport = 2000;
}
