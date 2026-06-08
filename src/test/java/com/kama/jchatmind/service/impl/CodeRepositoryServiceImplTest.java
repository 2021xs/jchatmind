package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.exception.CodeRepositoryImportException;
import com.kama.jchatmind.mapper.CodeChunkMapper;
import com.kama.jchatmind.mapper.CodeFileMapper;
import com.kama.jchatmind.mapper.CodeRepositoryMapper;
import com.kama.jchatmind.model.dto.CodeSearchResult;
import com.kama.jchatmind.model.dto.ImportQualitySummary;
import com.kama.jchatmind.model.dto.ParsedCodeFile;
import com.kama.jchatmind.model.entity.CodeChunk;
import com.kama.jchatmind.model.entity.CodeFile;
import com.kama.jchatmind.model.entity.CodeRepository;
import com.kama.jchatmind.model.request.ImportCodeRepositoryRequest;
import com.kama.jchatmind.model.response.ImportCodeRepositoryResponse;
import com.kama.jchatmind.service.CodeChunkEmbeddingTextBuilder;
import com.kama.jchatmind.service.CodeChunkParser;
import com.kama.jchatmind.service.CodeFileScanner;
import com.kama.jchatmind.service.EmbeddingService;
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
        EmbeddingService failingEmbeddingService = new FailingEmbeddingService();

        CodeRepositoryServiceImpl service = new CodeRepositoryServiceImpl(
                repositoryMapper,
                fileMapper,
                chunkMapper,
                scanner,
                parser,
                textBuilder,
                failingEmbeddingService,
                new NoopTransactionManager(),
                new ObjectMapper(),
                codeRagProperties(2)
        );

        ImportCodeRepositoryRequest request = new ImportCodeRepositoryRequest();
        request.setName("demo");
        request.setRootPath(tempDir.toString());

        CodeRepositoryImportException exception = assertThrows(CodeRepositoryImportException.class,
                () -> service.importRepository(request));

        assertEquals(0, chunkMapper.deleteByRepoIdCount);
        assertEquals(0, fileMapper.deleteByRepoIdCount);
        assertEquals(List.of("IMPORTING", "FAILED"), repositoryMapper.updatedStatuses);
        assertEquals("repo-1", exception.getResponse().getRepoId());
        assertEquals("FAILED", exception.getResponse().getImportQualitySummary().getStatus());
        assertEquals(1, exception.getResponse().getImportQualitySummary().getParsedFiles());
        assertEquals(1, exception.getResponse().getImportQualitySummary().getFailedFiles());
        assertEquals(0, exception.getResponse().getImportQualitySummary().getEmbeddedChunkCount());
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
                new SuccessfulEmbeddingService(),
                new NoopTransactionManager(),
                new ObjectMapper(),
                codeRagProperties(2)
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

    @Test
    void importRepositoryReturnsQualitySummaryForSuccessfulImport() throws Exception {
        Path javaFile = tempDir.resolve("BrokenService.java");
        Path xmlFile = tempDir.resolve("BrokenMapper.xml");
        Files.writeString(javaFile, "class BrokenService {");
        Files.writeString(xmlFile, "<mapper namespace=\"demo.BrokenMapper\"><select id=\"broken\"></mapper>");

        FakeCodeRepositoryMapper repositoryMapper = new FakeCodeRepositoryMapper();
        FakeCodeFileMapper fileMapper = new FakeCodeFileMapper();
        FakeCodeChunkMapper chunkMapper = new FakeCodeChunkMapper();
        CodeFileScanner scanner = rootPath -> new CodeFileScanner.ScanResult(rootPath, List.of(javaFile, xmlFile), false, "ok");
        CodeChunkParser parser = new CodeChunkParserImpl(new ObjectMapper());

        CodeRepositoryServiceImpl service = new CodeRepositoryServiceImpl(
                repositoryMapper,
                fileMapper,
                chunkMapper,
                scanner,
                parser,
                (parsed, chunk) -> chunk.getContent(),
                new SuccessfulEmbeddingService(),
                new NoopTransactionManager(),
                new ObjectMapper(),
                codeRagProperties(2)
        );

        ImportCodeRepositoryRequest request = new ImportCodeRepositoryRequest();
        request.setName("demo");
        request.setRootPath(tempDir.toString());

        ImportCodeRepositoryResponse response = service.importRepository(request);

        ImportQualitySummary summary = response.getImportQualitySummary();
        assertEquals(2, summary.getTotalFiles());
        assertEquals(2, summary.getScannedFiles());
        assertEquals(2, summary.getParsedFiles());
        assertEquals(2, summary.getFallbackFiles());
        assertEquals(1, summary.getJavaFallbackCount());
        assertEquals(1, summary.getXmlFallbackCount());
        assertEquals(0, summary.getIncludeWarningCount());
        assertEquals(0, summary.getFailedFiles());
        assertEquals(2, summary.getChunkCount());
        assertEquals(2, summary.getEmbeddedChunkCount());
        assertEquals("READY", summary.getStatus());
        assertEquals(List.of("IMPORTING", "READY"), repositoryMapper.updatedStatuses);
    }

    @Test
    void importRepositoryCountsIncludeWarningsInQualitySummary() throws Exception {
        Path xmlFile = tempDir.resolve("OrderMapper.xml");
        Files.writeString(xmlFile, """
                <mapper namespace="demo.OrderMapper">
                    <select id="selectOrder">
                        select <include refid="Missing_Columns"/> from tb_order
                    </select>
                </mapper>
                """);

        CodeRepositoryServiceImpl service = new CodeRepositoryServiceImpl(
                new FakeCodeRepositoryMapper(),
                new FakeCodeFileMapper(),
                new FakeCodeChunkMapper(),
                rootPath -> new CodeFileScanner.ScanResult(rootPath, List.of(xmlFile), false, "ok"),
                new CodeChunkParserImpl(new ObjectMapper()),
                (parsed, chunk) -> chunk.getContent(),
                new SuccessfulEmbeddingService(),
                new NoopTransactionManager(),
                new ObjectMapper(),
                codeRagProperties(2)
        );

        ImportCodeRepositoryRequest request = new ImportCodeRepositoryRequest();
        request.setName("demo");
        request.setRootPath(tempDir.toString());

        ImportCodeRepositoryResponse response = service.importRepository(request);

        ImportQualitySummary summary = response.getImportQualitySummary();
        assertEquals(1, summary.getTotalFiles());
        assertEquals(1, summary.getParsedFiles());
        assertEquals(0, summary.getFallbackFiles());
        assertEquals(1, summary.getIncludeWarningCount());
        assertEquals(1, summary.getChunkCount());
        assertEquals(1, summary.getEmbeddedChunkCount());
        assertEquals("READY", summary.getStatus());
    }

    @Test
    void embeddingFailureMarksSummaryFailureWithoutPartialSuccessSemantics() throws Exception {
        Path sourceFile = tempDir.resolve("DemoService.java");
        Files.writeString(sourceFile, "class DemoService {}");

        FakeCodeRepositoryMapper repositoryMapper = new FakeCodeRepositoryMapper();

        CodeRepositoryServiceImpl service = new CodeRepositoryServiceImpl(
                repositoryMapper,
                new FakeCodeFileMapper(),
                new FakeCodeChunkMapper(),
                rootPath -> new CodeFileScanner.ScanResult(rootPath, List.of(sourceFile), false, "ok"),
                (rootPath, filePath) -> ParsedCodeFile.builder()
                        .relativePath("DemoService.java")
                        .fileType("JAVA")
                        .className("DemoService")
                        .chunks(List.of(CodeChunk.builder()
                                .chunkType("CLASS_SUMMARY")
                                .symbolName("DemoService")
                                .content("class DemoService {}")
                                .metadata("{}")
                                .build()))
                        .build(),
                (parsed, chunk) -> chunk.getContent(),
                new FailingEmbeddingService(),
                new NoopTransactionManager(),
                new ObjectMapper(),
                codeRagProperties(2)
        );

        ImportCodeRepositoryRequest request = new ImportCodeRepositoryRequest();
        request.setName("demo");
        request.setRootPath(tempDir.toString());

        CodeRepositoryImportException exception = assertThrows(CodeRepositoryImportException.class,
                () -> service.importRepository(request));

        assertEquals(List.of("IMPORTING", "FAILED"), repositoryMapper.updatedStatuses);
        assertEquals("FAILED", exception.getResponse().getImportQualitySummary().getStatus());
        assertEquals(1, exception.getResponse().getImportQualitySummary().getFailedFiles());
    }

    @Test
    void importRepositoryEmbedsChunksInBatchesAndKeepsChunkOrder() throws Exception {
        Path firstFile = tempDir.resolve("First.java");
        Path secondFile = tempDir.resolve("Second.java");
        Files.writeString(firstFile, "class First {}");
        Files.writeString(secondFile, "class Second {}");

        FakeCodeChunkMapper chunkMapper = new FakeCodeChunkMapper();
        RecordingEmbeddingService embeddingService = new RecordingEmbeddingService();

        CodeRepositoryServiceImpl service = new CodeRepositoryServiceImpl(
                new FakeCodeRepositoryMapper(),
                new FakeCodeFileMapper(),
                chunkMapper,
                rootPath -> new CodeFileScanner.ScanResult(rootPath, List.of(firstFile, secondFile), false, "ok"),
                (rootPath, filePath) -> ParsedCodeFile.builder()
                        .relativePath(filePath.getFileName().toString())
                        .fileType("JAVA")
                        .className(filePath.getFileName().toString())
                        .chunks(List.of(
                                CodeChunk.builder().chunkType("CLASS_SUMMARY").symbolName(filePath.getFileName() + "#1").content("chunk-1").metadata("{}").build(),
                                CodeChunk.builder().chunkType("CLASS_SUMMARY").symbolName(filePath.getFileName() + "#2").content("chunk-2").metadata("{}").build()
                        ))
                        .build(),
                (parsed, chunk) -> parsed.getRelativePath() + ":" + chunk.getContent(),
                embeddingService,
                new NoopTransactionManager(),
                new ObjectMapper(),
                codeRagProperties(3)
        );

        ImportCodeRepositoryRequest request = new ImportCodeRepositoryRequest();
        request.setName("demo");
        request.setRootPath(tempDir.toString());

        ImportCodeRepositoryResponse response = service.importRepository(request);

        assertEquals(List.of(3, 1), embeddingService.batchSizes);
        assertEquals(4, response.getImportQualitySummary().getEmbeddedChunkCount());
        assertEquals(4, chunkMapper.insertedChunks.size());
        assertEquals(1.0f, chunkMapper.insertedChunks.get(0).getEmbedding()[0]);
        assertEquals(2.0f, chunkMapper.insertedChunks.get(1).getEmbedding()[0]);
        assertEquals(3.0f, chunkMapper.insertedChunks.get(2).getEmbedding()[0]);
        assertEquals(4.0f, chunkMapper.insertedChunks.get(3).getEmbedding()[0]);
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
        private final List<CodeChunk> insertedChunks = new ArrayList<>();

        @Override
        public int insert(CodeChunk codeChunk) {
            insertedChunks.add(codeChunk);
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

    private static class SuccessfulEmbeddingService implements EmbeddingService {
        @Override
        public float[] embed(String text) {
            return new float[]{1.0f};
        }

        @Override
        public List<float[]> embedBatch(List<String> texts) {
            return texts.stream().map(text -> new float[]{1.0f}).toList();
        }
    }

    private static class FailingEmbeddingService implements EmbeddingService {
        @Override
        public float[] embed(String text) {
            throw new IllegalStateException("embedding down");
        }

        @Override
        public List<float[]> embedBatch(List<String> texts) {
            throw new IllegalStateException("embedding down");
        }
    }

    private static class RecordingEmbeddingService implements EmbeddingService {
        private final List<Integer> batchSizes = new ArrayList<>();
        private int next = 1;

        @Override
        public float[] embed(String text) {
            throw new AssertionError("Code RAG import should use embedBatch");
        }

        @Override
        public List<float[]> embedBatch(List<String> texts) {
            batchSizes.add(texts.size());
            List<float[]> result = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                result.add(new float[]{next++});
            }
            return result;
        }
    }

    private static CodeRagProperties codeRagProperties(int batchSize) {
        CodeRagProperties properties = new CodeRagProperties();
        properties.setEmbeddingBatchSize(batchSize);
        return properties;
    }
}
