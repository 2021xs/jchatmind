package com.kama.jchatmind.benchmark.context;

import com.kama.jchatmind.model.entity.CodeRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

final class ContextLifecycleBenchmarkPreflight {
    private static final String FILE_MANIFEST_SQL = """
            SELECT md5(string_agg(md5(coalesce(file_path,'') || E'\\n' || coalesce(checksum,'')),
                       '' ORDER BY file_path))
            FROM code_file WHERE repo_id = CAST(? AS uuid)
            """;
    private static final String CHUNK_MANIFEST_SQL = """
            WITH d AS (
              SELECT f.file_path,
                     coalesce(c.start_line,-1) AS start_line,
                     coalesce(c.end_line,-1) AS end_line,
                     md5(coalesce(c.chunk_type,'') || E'\\n' || coalesce(c.symbol_name,'') || E'\\n'
                         || coalesce(c.api_path,'') || E'\\n' || coalesce(c.http_method,'') || E'\\n'
                         || coalesce(c.start_line::text,'') || E'\\n' || coalesce(c.end_line::text,'') || E'\\n'
                         || c.content || E'\\n' || coalesce(c.metadata::text,'')) AS digest
              FROM code_chunk c JOIN code_file f ON f.id=c.file_id
              WHERE c.repo_id = CAST(? AS uuid)
            )
            SELECT md5(string_agg(digest,'' ORDER BY file_path,start_line,end_line,digest)) FROM d
            """;

    private final JdbcTemplate jdbcTemplate;

    ContextLifecycleBenchmarkPreflight(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    Snapshot verify(CodeRepository repository,
                    ContextLifecycleBenchmarkSuite.RepositorySnapshot expected,
                    boolean requireCleanMainTree) {
        if (repository == null || !expected.repositoryId.equals(repository.getId())) {
            throw new IllegalStateException("Benchmark repository id does not match the fixed suite snapshot");
        }
        int files = count("code_file", repository.getId(), false);
        int chunks = count("code_chunk", repository.getId(), false);
        int embeddings = count("code_chunk", repository.getId(), true);
        String fileDigest = jdbcTemplate.queryForObject(FILE_MANIFEST_SQL, String.class, repository.getId());
        String chunkDigest = jdbcTemplate.queryForObject(CHUNK_MANIFEST_SQL, String.class, repository.getId());
        Snapshot snapshot = new Snapshot(repository.getId(), repository.getName(), files, chunks, embeddings,
                fileDigest, chunkDigest, gitState(Path.of(".")), gitState(Path.of(repository.getRootPath())));
        verifyExpected(snapshot, expected);
        if (requireCleanMainTree && !snapshot.mainGit().clean()) {
            throw new IllegalStateException("Benchmark main working tree is not clean: "
                    + snapshot.mainGit().status().replace('\n', ' '));
        }
        return snapshot;
    }

    static void verifyExpected(Snapshot actual,
                               ContextLifecycleBenchmarkSuite.RepositorySnapshot expected) {
        if (actual.fileCount() != expected.fileCount
                || actual.chunkCount() != expected.chunkCount
                || actual.embeddingCount() != expected.embeddingCount
                || !expected.fileManifestMd5.equals(actual.fileManifestMd5())
                || !expected.chunkManifestMd5.equals(actual.chunkManifestMd5())) {
            throw new IllegalStateException("Benchmark repository snapshot mismatch: expected="
                    + expected.fileCount + "/" + expected.chunkCount + "/" + expected.embeddingCount
                    + "/" + expected.fileManifestMd5 + "/" + expected.chunkManifestMd5
                    + ", actual=" + actual.fileCount() + "/" + actual.chunkCount() + "/"
                    + actual.embeddingCount() + "/" + actual.fileManifestMd5() + "/"
                    + actual.chunkManifestMd5());
        }
    }

    private int count(String table, String repoId, boolean onlyEmbeddings) {
        if (!List.of("code_file", "code_chunk").contains(table)) {
            throw new IllegalArgumentException("Unsupported benchmark count table");
        }
        String sql = "SELECT COUNT(*) FROM " + table + " WHERE repo_id = CAST(? AS uuid)"
                + (onlyEmbeddings ? " AND embedding IS NOT NULL" : "");
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, repoId);
        return value == null ? 0 : value;
    }

    private GitState gitState(Path directory) {
        String head = command(directory, "git", "rev-parse", "HEAD").trim();
        String status = command(directory, "git", "status", "--porcelain=v1").strip();
        return new GitState(head, status.isEmpty() ? "CLEAN" : status, status.isEmpty());
    }

    private String command(Path directory, String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(directory.toAbsolutePath().normalize().toFile())
                    .redirectErrorStream(true)
                    .start();
            byte[] output = process.getInputStream().readAllBytes();
            int exit = process.waitFor();
            String text = new String(output, StandardCharsets.UTF_8);
            if (exit != 0) {
                throw new IllegalStateException("Preflight command failed: " + String.join(" ", command)
                        + ", output=" + text.strip());
            }
            return text;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot run preflight command: " + String.join(" ", command), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Preflight command interrupted: " + String.join(" ", command), e);
        }
    }

    record Snapshot(String repositoryId,
                    String repositoryName,
                    int fileCount,
                    int chunkCount,
                    int embeddingCount,
                    String fileManifestMd5,
                    String chunkManifestMd5,
                    GitState mainGit,
                    GitState repositoryGit) {
    }

    record GitState(String commit, String status, boolean clean) {
    }
}
