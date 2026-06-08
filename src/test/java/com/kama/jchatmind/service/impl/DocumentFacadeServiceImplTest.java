package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.converter.DocumentConverter;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.model.entity.Document;
import com.kama.jchatmind.model.response.CreateDocumentResponse;
import com.kama.jchatmind.service.DocumentStorageService;
import com.kama.jchatmind.service.EmbeddingService;
import com.kama.jchatmind.service.MarkdownParserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DocumentFacadeServiceImplTest {

    @TempDir
    Path tempDir;

    @Test
    void uploadMarkdownDocumentEmbedsSectionsInBatches() throws Exception {
        FakeDocumentMapper documentMapper = new FakeDocumentMapper();
        FakeChunkMapper chunkMapper = new FakeChunkMapper();
        RecordingEmbeddingService embeddingService = new RecordingEmbeddingService();

        DocumentFacadeServiceImpl service = new DocumentFacadeServiceImpl(
                documentMapper,
                new DocumentConverter(new ObjectMapper()),
                new FakeStorageService(tempDir),
                inputStream -> List.of(
                        new MarkdownParserService.MarkdownSection("first", "content-1"),
                        new MarkdownParserService.MarkdownSection("second", "content-2"),
                        new MarkdownParserService.MarkdownSection("third", "content-3")
                ),
                embeddingService,
                chunkMapper,
                codeRagProperties(2)
        );

        CreateDocumentResponse response = service.uploadDocument("kb-1",
                new MockMultipartFile("file", "demo.md", "text/markdown", "# demo".getBytes()));

        assertEquals("doc-1", response.getDocumentId());
        assertEquals(List.of(2, 1), embeddingService.batchSizes);
        assertEquals(3, chunkMapper.inserted.size());
        assertEquals(1.0f, chunkMapper.inserted.get(0).getEmbedding()[0]);
        assertEquals(2.0f, chunkMapper.inserted.get(1).getEmbedding()[0]);
        assertEquals(3.0f, chunkMapper.inserted.get(2).getEmbedding()[0]);
    }

    @Test
    void markdownEmbeddingFailureKeepsUploadFailureSemanticsUnchanged() throws Exception {
        FakeDocumentMapper documentMapper = new FakeDocumentMapper();
        FakeChunkMapper chunkMapper = new FakeChunkMapper();

        DocumentFacadeServiceImpl service = new DocumentFacadeServiceImpl(
                documentMapper,
                new DocumentConverter(new ObjectMapper()),
                new FakeStorageService(tempDir),
                inputStream -> List.of(new MarkdownParserService.MarkdownSection("first", "content-1")),
                new FailingEmbeddingService(),
                chunkMapper,
                codeRagProperties(2)
        );

        CreateDocumentResponse response = service.uploadDocument("kb-1",
                new MockMultipartFile("file", "demo.md", "text/markdown", "# demo".getBytes()));

        assertEquals("doc-1", response.getDocumentId());
        assertEquals(0, chunkMapper.inserted.size());
    }

    private static CodeRagProperties codeRagProperties(int batchSize) {
        CodeRagProperties properties = new CodeRagProperties();
        properties.setEmbeddingBatchSize(batchSize);
        return properties;
    }

    private static class FakeDocumentMapper implements DocumentMapper {
        @Override
        public int insert(Document document) {
            document.setId("doc-1");
            return 1;
        }

        @Override
        public Document selectById(String id) {
            return null;
        }

        @Override
        public List<Document> selectAll() {
            return List.of();
        }

        @Override
        public List<Document> selectByKbId(String kbId) {
            return List.of();
        }

        @Override
        public int deleteById(String id) {
            return 1;
        }

        @Override
        public int updateById(Document document) {
            return 1;
        }
    }

    private static class FakeChunkMapper implements ChunkBgeM3Mapper {
        private final List<ChunkBgeM3> inserted = new ArrayList<>();

        @Override
        public int insert(ChunkBgeM3 chunkBgeM3) {
            inserted.add(chunkBgeM3);
            return 1;
        }

        @Override
        public ChunkBgeM3 selectById(String id) {
            return null;
        }

        @Override
        public int deleteById(String id) {
            return 1;
        }

        @Override
        public int updateById(ChunkBgeM3 chunkBgeM3) {
            return 1;
        }

        @Override
        public List<ChunkBgeM3> similaritySearch(String kbId, String vectorLiteral, int limit) {
            return List.of();
        }
    }

    private static class FakeStorageService implements DocumentStorageService {
        private final Path root;

        private FakeStorageService(Path root) {
            this.root = root;
        }

        @Override
        public String saveFile(String kbId, String documentId, MultipartFile file) throws IOException {
            Path target = root.resolve(documentId + ".md");
            Files.write(target, file.getBytes());
            return target.getFileName().toString();
        }

        @Override
        public void deleteFile(String filePath) throws IOException {
            Files.deleteIfExists(getFilePath(filePath));
        }

        @Override
        public Path getFilePath(String filePath) {
            return root.resolve(filePath);
        }

        @Override
        public boolean fileExists(String filePath) {
            return Files.exists(getFilePath(filePath));
        }
    }

    private static class RecordingEmbeddingService implements EmbeddingService {
        private final List<Integer> batchSizes = new ArrayList<>();
        private int next = 1;

        @Override
        public float[] embed(String text) {
            throw new AssertionError("Knowledge RAG import should use embedBatch");
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
}
