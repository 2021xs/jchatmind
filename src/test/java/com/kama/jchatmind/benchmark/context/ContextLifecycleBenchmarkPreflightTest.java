package com.kama.jchatmind.benchmark.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContextLifecycleBenchmarkPreflightTest {

    @Test
    void acceptsOnlyTheExactFrozenDatabaseSnapshot() {
        ContextLifecycleBenchmarkSuite.RepositorySnapshot expected = expected();
        ContextLifecycleBenchmarkPreflight.Snapshot actual = new ContextLifecycleBenchmarkPreflight.Snapshot(
                expected.repositoryId, expected.repositoryName,
                expected.fileCount, expected.chunkCount, expected.embeddingCount,
                expected.fileManifestMd5, expected.chunkManifestMd5,
                new ContextLifecycleBenchmarkPreflight.GitState("main", "CLEAN", true),
                new ContextLifecycleBenchmarkPreflight.GitState("repo", "dirty", false));

        assertDoesNotThrow(() -> ContextLifecycleBenchmarkPreflight.verifyExpected(actual, expected));
    }

    @Test
    void rejectsChangedChunkContentEvenWhenCountsMatch() {
        ContextLifecycleBenchmarkSuite.RepositorySnapshot expected = expected();
        ContextLifecycleBenchmarkPreflight.Snapshot actual = new ContextLifecycleBenchmarkPreflight.Snapshot(
                expected.repositoryId, expected.repositoryName,
                expected.fileCount, expected.chunkCount, expected.embeddingCount,
                expected.fileManifestMd5, "changed",
                new ContextLifecycleBenchmarkPreflight.GitState("main", "CLEAN", true),
                new ContextLifecycleBenchmarkPreflight.GitState("repo", "dirty", false));

        assertThrows(IllegalStateException.class,
                () -> ContextLifecycleBenchmarkPreflight.verifyExpected(actual, expected));
    }

    private ContextLifecycleBenchmarkSuite.RepositorySnapshot expected() {
        ContextLifecycleBenchmarkSuite.RepositorySnapshot value =
                new ContextLifecycleBenchmarkSuite.RepositorySnapshot();
        value.repositoryId = "repo";
        value.repositoryName = "FlashDeal";
        value.fileCount = 167;
        value.chunkCount = 642;
        value.embeddingCount = 642;
        value.fileManifestMd5 = "files";
        value.chunkManifestMd5 = "chunks";
        return value;
    }
}
