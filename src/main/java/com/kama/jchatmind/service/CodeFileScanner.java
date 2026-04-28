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

        public ScanResult(Path normalizedRoot, List<Path> files, boolean truncated, String message) {
            this.normalizedRoot = normalizedRoot;
            this.files = files;
            this.truncated = truncated;
            this.message = message;
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
    }
}
