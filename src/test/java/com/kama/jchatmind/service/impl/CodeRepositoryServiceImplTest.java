package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.mapper.CodeChunkMapper;
import com.kama.jchatmind.mapper.CodeFileMapper;
import com.kama.jchatmind.mapper.CodeRepositoryMapper;
import com.kama.jchatmind.model.dto.CodeSearchResult;
import com.kama.jchatmind.model.dto.ParsedCodeFile;
import com.kama.jchatmind.model.dto.RagSearchResult;
import com.kama.jchatmind.model.entity.CodeChunk;
import com.kama.jchatmind.model.entity.CodeFile;
import com.kama.jchatmind.model.entity.CodeRepository;
import com.kama.jchatmind.model.request.ImportCodeRepositoryRequest;
import com.kama.jchatmind.service.CodeChunkEmbeddingTextBuilder;
import com.kama.jchatmind.service.CodeChunkParser;
import com.kama.jchatmind.service.CodeFileScanner;
import com.kama.jchatmind.service.RagService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodeRepositoryServiceImplTest {

    @TempDir
    Path tempDir;

    @Test
    void embeddingFailureKeepsExistingChunksUntilReplacementTransaction() throws Exception {
        Path sourceFile = tempDir.resolve("DemoService.java");
        Files.writeString(sourceFile, "class DemoService {}");

        FakeCodeRepositoryMapper repositoryMapper = new FakeCodeRepositoryMapper();
        FakeCodeFileMapper fileMapper = new FakeCodeFileMapper();
        FakeCodeChunkMapper chunkMapper = new FakeCodeChunkMapper();

        CodeFileScanner scanner = rootPath -> new CodeFileScanner.ScanResult(rootPath, List.of(sourceFile), false, "ok");
        CodeChunkParser parser = (rootPath, filePath) -> ParsedCodeFile.builder()
                .relativePath("DemoService.java")
                .fileType("JAVA")
                .className("DemoService")
                .chunks(List.of(CodeChunk.builder()
                        .chunkType("CLASS_SUMMARY")
                        .symbolName("DemoService")
                        .content("class DemoService {}")
                        .build()))
                .build();
        CodeChunkEmbeddingTextBuilder textBuilder = (parsed, chunk) -> chunk.getContent();
        RagService failingRagService = new RagService() {
            @Override
            public float[] embed(String text) {
                throw new IllegalStateException("embedding down");
            }

            @Override
            public List<String> similaritySearch(String kbId, String title) {
                return List.of();
            }

            @Override
            public List<RagSearchResult> similaritySearchWithMetadata(String kbId, String query) {
                return List.of();
            }
        };

        CodeRepositoryServiceImpl service = new CodeRepositoryServiceImpl(
                repositoryMapper,
                fileMapper,
                chunkMapper,
                scanner,
                parser,
                textBuilder,
                failingRagService,
                new NoopTransactionManager()
        );

        ImportCodeRepositoryRequest request = new ImportCodeRepositoryRequest();
        request.setName("demo");
        request.setRootPath(tempDir.toString());

        assertThrows(IllegalStateException.class, () -> service.importRepository(request));

        assertEquals(0, chunkMapper.deleteByRepoIdCount);
        assertEquals(0, fileMapper.deleteByRepoIdCount);
        assertEquals(List.of("IMPORTING", "FAILED"), repositoryMapper.updatedStatuses);
    }

    @Test
    void deleteRepositoryRemovesChunksFilesAndRepositoryInOrder() {
        FakeCodeRepositoryMapper repositoryMapper = new FakeCodeRepositoryMapper();
        FakeCodeFileMapper fileMapper = new FakeCodeFileMapper();
        FakeCodeChunkMapper chunkMapper = new FakeCodeChunkMapper();
        List<String> calls = new ArrayList<>();
        repositoryMapper.calls = calls;
        fileMapper.calls = calls;
        chunkMapper.calls = calls;

        CodeRepositoryServiceImpl service = new CodeRepositoryServiceImpl(
                repositoryMapper,
                fileMapper,
                chunkMapper,
                rootPath -> new CodeFileScanner.ScanResult(rootPath, List.of(), false, "ok"),
                (rootPath, filePath) -> ParsedCodeFile.builder().chunks(List.of()).build(),
                (parsed, chunk) -> "",
                new SuccessfulRagService(),
                new NoopTransactionManager()
        );

        service.deleteRepository("repo-1");

        assertEquals(List.of("deleteChunks", "deleteFiles", "deleteRepository"), calls);
    }

    @Test
    void existingRepositoryRequiresSameNameAndRootPathAtSqlLayer() throws Exception {
        String xml = Files.readString(Path.of("src/main/resources/mapper/CodeRepositoryMapper.xml"));
        org.assertj.core.api.Assertions.assertThat(xml)
                .contains("WHERE name = #{name} AND root_path = #{rootPath}")
                .doesNotContain("WHERE name = #{name} OR root_path = #{rootPath}");
    }

    private static class FakeCodeRepositoryMapper implements CodeRepositoryMapper {
        private final List<String> updatedStatuses = new ArrayList<>();
        private List<String> calls = new ArrayList<>();

        @Override
        public int insert(CodeRepository codeRepository) {
            codeRepository.setId("repo-1");
            return 1;
        }

        @Override
        public CodeRepository selectById(String id) {
            return null;
        }

        @Override
        public CodeRepository selectExisting(String name, String rootPath) {
            return CodeRepository.builder()
                    .id("repo-1")
                    .name(name)
                    .rootPath(rootPath)
                    .status("READY")
                    .build();
        }

        @Override
        public List<CodeRepository> selectAll() {
            return List.of();
        }

        @Override
        public int updateById(CodeRepository codeRepository) {
            updatedStatuses.add(codeRepository.getStatus());
            return 1;
        }

        @Override
        public int deleteById(String id) {
            calls.add("deleteRepository");
            return 1;
        }
    }

    private static class FakeCodeFileMapper implements CodeFileMapper {
        private int deleteByRepoIdCount;
        private List<String> calls = new ArrayList<>();

        @Override
        public int insert(CodeFile codeFile) {
            return 1;
        }

        @Override
        public int deleteByRepoId(String repoId) {
            deleteByRepoIdCount++;
            calls.add("deleteFiles");
            return 1;
        }
    }

    private static class FakeCodeChunkMapper implements CodeChunkMapper {
        private int deleteByRepoIdCount;
        private List<String> calls = new ArrayList<>();

        @Override
        public int insert(CodeChunk codeChunk) {
            return 1;
        }

        @Override
        public int deleteByRepoId(String repoId) {
            deleteByRepoIdCount++;
            calls.add("deleteChunks");
            return 1;
        }

        @Override
        public List<CodeSearchResult> similaritySearch(String repoId, String vectorLiteral, int limit) {
            return List.of();
        }
    }

    private static class NoopTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }

    private static class SuccessfulRagService implements RagService {
        @Override
        public float[] embed(String text) {
            return new float[]{1.0f};
        }

        @Override
        public List<String> similaritySearch(String kbId, String title) {
            return List.of();
        }

        @Override
        public List<RagSearchResult> similaritySearchWithMetadata(String kbId, String query) {
            return List.of();
        }
    }
}
