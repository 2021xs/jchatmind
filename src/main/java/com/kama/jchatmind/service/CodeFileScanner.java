package com.kama.jchatmind.service;

import java.nio.file.Path;
import java.util.List;

public interface CodeFileScanner {
    ScanResult scan(Path rootPath);

    class ScanResult {
        private final Path normalizedRoot;
        private final List<Path> files;
        private final boolean truncated;
        private final String message;
        private final int skippedSqlFileCount;
        private final List<String> skippedSqlFilePaths;
        private final long eligibleSourceBytes;
        private final int oversizedFileCount;

        public ScanResult(Path normalizedRoot, List<Path> files, boolean truncated, String message) {
            this(normalizedRoot, files, truncated, message, 0, List.of());
        }

        public ScanResult(Path normalizedRoot, List<Path> files, boolean truncated, String message,
                          int skippedSqlFileCount, List<String> skippedSqlFilePaths) {
            this(normalizedRoot, files, truncated, message, skippedSqlFileCount, skippedSqlFilePaths, 0, 0);
        }

        public ScanResult(Path normalizedRoot, List<Path> files, boolean truncated, String message,
                          int skippedSqlFileCount, List<String> skippedSqlFilePaths,
                          long eligibleSourceBytes, int oversizedFileCount) {
            this.normalizedRoot = normalizedRoot;
            this.files = files;
            this.truncated = truncated;
            this.message = message;
            this.skippedSqlFileCount = skippedSqlFileCount;
            this.skippedSqlFilePaths = skippedSqlFilePaths == null ? List.of() : List.copyOf(skippedSqlFilePaths);
            this.eligibleSourceBytes = eligibleSourceBytes;
            this.oversizedFileCount = oversizedFileCount;
        }

        public Path getNormalizedRoot() {
            return normalizedRoot;
        }

        public List<Path> getFiles() {
            return files;
        }

        public boolean isTruncated() {
            return truncated;
        }

        public String getMessage() {
            return message;
        }

        public int getSkippedSqlFileCount() {
            return skippedSqlFileCount;
        }

        public List<String> getSkippedSqlFilePaths() {
            return skippedSqlFilePaths;
        }

        public long getEligibleSourceBytes() {
            return eligibleSourceBytes;
        }

        public int getOversizedFileCount() {
            return oversizedFileCount;
        }
    }
}
