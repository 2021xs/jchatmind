package com.kama.jchatmind.benchmark.context;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
class ContextLifecycleBenchmarkSuite {
    public String benchmarkSuiteVersion;
    public String architectureLabel;
    public String legacyBehaviorCommit;
    public RepositorySnapshot repositorySnapshot;
    public List<ContextLifecycleBenchmarkCase> cases = List.of();

    void validate() {
        requireText(benchmarkSuiteVersion, "benchmarkSuiteVersion");
        if (!"LEGACY".equals(architectureLabel)) {
            throw new IllegalArgumentException("Baseline suite architectureLabel must be LEGACY");
        }
        if (repositorySnapshot == null) {
            throw new IllegalArgumentException("repositorySnapshot is required");
        }
        repositorySnapshot.validate();
        if (cases == null || cases.size() < 20 || cases.size() > 30) {
            throw new IllegalArgumentException("Context lifecycle suite must contain 20-30 cases");
        }
        cases.forEach(ContextLifecycleBenchmarkCase::validate);
        long distinctIds = cases.stream().map(value -> value.caseId).distinct().count();
        if (distinctIds != cases.size()) {
            throw new IllegalArgumentException("Benchmark case ids must be unique");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RepositorySnapshot {
        public String repositoryName;
        public String repositoryId;
        public String fileManifestMd5;
        public String chunkManifestMd5;
        public int fileCount;
        public int chunkCount;
        public int embeddingCount;

        void validate() {
            requireText(repositoryName, "repositoryName");
            requireText(repositoryId, "repositoryId");
            requireText(fileManifestMd5, "fileManifestMd5");
            requireText(chunkManifestMd5, "chunkManifestMd5");
            if (fileCount <= 0 || chunkCount <= 0 || embeddingCount != chunkCount) {
                throw new IllegalArgumentException("Invalid fixed repository snapshot counts");
            }
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
