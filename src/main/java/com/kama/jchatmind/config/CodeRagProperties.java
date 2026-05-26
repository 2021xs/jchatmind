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
    private String embeddingBaseUrl = "http://localhost:11434";
    private String embeddingModel = "bge-m3";
    private EmbeddingWarmup embeddingWarmup = new EmbeddingWarmup();
    private EmbeddingCache embeddingCache = new EmbeddingCache();
    private EmbeddingMetadata embeddingMetadata = new EmbeddingMetadata();
    private ContextualPrefix contextualPrefix = new ContextualPrefix();
    private LlmSelector llmSelector = new LlmSelector();
    private AnswerEvidence answerEvidence = new AnswerEvidence();

    @Data
    public static class EmbeddingWarmup {
        private boolean enabled = true;
        private String prompt = "warmup code rag embedding";
    }

    @Data
    public static class EmbeddingCache {
        private boolean enabled = true;
        private int maxSize = 512;
        private long ttlMinutes = 30;
    }

    @Data
    public static class EmbeddingMetadata {
        private boolean enabled = false;
    }

    @Data
    public static class ContextualPrefix {
        private boolean enabled = true;
    }

    @Data
    public static class LlmSelector {
        /**
         * Answer-time evidence selector. Tests may disable it to verify fallback behavior.
         */
        private boolean enabled = true;
        private String model = "deepseek-official-chat";
        private int maxCandidateChars = 600;
        private int maxSelected = 5;
        private long timeoutMs = 30000;
    }

    @Data
    public static class AnswerEvidence {
        /**
         * Agent answer-time Code RAG path: RAW_VECTOR + LLM evidence selector.
         * This does not change import/chunk/embedding/index logic.
         */
        private int rawTopK = 50;
        private int finalTopK = 5;
        private boolean includeSelectorDebug = true;
    }
}
