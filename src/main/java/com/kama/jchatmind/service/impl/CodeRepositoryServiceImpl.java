package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.exception.CodeRepositoryImportException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.CodeChunkMapper;
import com.kama.jchatmind.mapper.CodeFileMapper;
import com.kama.jchatmind.mapper.CodeRepositoryMapper;
import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.model.dto.ImportQualitySummary;
import com.kama.jchatmind.model.dto.ParsedCodeFile;
import com.kama.jchatmind.model.entity.CodeChunk;
import com.kama.jchatmind.model.entity.CodeFile;
import com.kama.jchatmind.model.entity.CodeRepository;
import com.kama.jchatmind.model.request.ImportCodeRepositoryRequest;
import com.kama.jchatmind.model.response.GetCodeRepositoriesResponse;
import com.kama.jchatmind.model.response.ImportCodeRepositoryResponse;
import com.kama.jchatmind.service.CodeChunkEmbeddingTextBuilder;
import com.kama.jchatmind.service.CodeChunkParser;
import com.kama.jchatmind.service.CodeFileScanner;
import com.kama.jchatmind.service.CodeRepositoryService;
import com.kama.jchatmind.service.EmbeddingService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@AllArgsConstructor
@Slf4j
public class CodeRepositoryServiceImpl implements CodeRepositoryService {
    private static final String STATUS_IMPORTING = "IMPORTING";
    private static final String STATUS_READY = "READY";
    private static final String STATUS_FAILED = "FAILED";

    private final CodeRepositoryMapper codeRepositoryMapper;
    private final CodeFileMapper codeFileMapper;
    private final CodeChunkMapper codeChunkMapper;
    private final CodeFileScanner codeFileScanner;
    private final CodeChunkParser codeChunkParser;
    private final CodeChunkEmbeddingTextBuilder codeChunkEmbeddingTextBuilder;
    private final EmbeddingService embeddingService;
    private final PlatformTransactionManager transactionManager;
    private final ObjectMapper objectMapper;
    private final CodeRagProperties codeRagProperties;

    @Override
    public ImportCodeRepositoryResponse importRepository(ImportCodeRepositoryRequest request) {
        if (request == null || !StringUtils.hasLength(request.getName()) || !StringUtils.hasLength(request.getRootPath())) {
            throw new BizException("name 和 rootPath 不能为空");
        }

        CodeFileScanner.ScanResult scanResult = codeFileScanner.scan(Path.of(request.getRootPath()));
        String normalizedRoot = scanResult.getNormalizedRoot().toString().replace("\\", "/");
        CodeRepository repository = markImporting(request.getName(), normalizedRoot);
        ImportQualitySummaryBuilder summaryBuilder = new ImportQualitySummaryBuilder(scanResult.getFiles().size());
        ImportRuntimeStats runtimeStats = new ImportRuntimeStats(Math.max(1, codeRagProperties.getEmbeddingBatchSize()));
        long importStarted = System.currentTimeMillis();
        List<EmbeddingTarget> embeddingBuffer = new ArrayList<>();
        boolean parsingFile = false;
        try {
            for (Path filePath : scanResult.getFiles()) {
                parsingFile = true;
                ParsedCodeFile parsed = codeChunkParser.parse(scanResult.getNormalizedRoot(), filePath);
                parsingFile = false;
                summaryBuilder.recordParsedFile(parsed);
                CodeFile codeFile = persistCodeFile(repository, parsed, runtimeStats);
                addChunksToEmbeddingBuffer(repository, codeFile, parsed, embeddingBuffer);
                flushFullEmbeddingBatches(embeddingBuffer, summaryBuilder, runtimeStats);
            }
            flushRemainingEmbeddingBatch(embeddingBuffer, summaryBuilder, runtimeStats);
            transactionTemplate().executeWithoutResult(status ->
                codeRepositoryMapper.updateById(CodeRepository.builder()
                        .id(repository.getId())
                        .status(STATUS_READY)
                        .build()));
            ImportQualitySummary summary = summaryBuilder.status(STATUS_READY).build();
            long totalElapsedMs = System.currentTimeMillis() - importStarted;
            log.info("Code repository import quality summary: repoId={}, repositoryName={}, summary={}, embeddingBatchSize={}, embeddingBatchCount={}, embeddingElapsedMs={}, dbWriteElapsedMs={}, totalElapsedMs={}",
                    repository.getId(), repository.getName(), summary, runtimeStats.batchSize(),
                    runtimeStats.batchCount(), runtimeStats.embeddingElapsedMs(), runtimeStats.dbWriteElapsedMs(),
                    totalElapsedMs);
            return ImportCodeRepositoryResponse.builder()
                    .repoId(repository.getId())
                    .fileCount(summary.getParsedFiles())
                    .chunkCount(summary.getChunkCount())
                    .truncated(scanResult.isTruncated())
                    .message(scanResult.getMessage())
                    .importQualitySummary(summary)
                    .build();
        } catch (RuntimeException e) {
            if (parsingFile) {
                summaryBuilder.recordFailedFile();
            }
            markFailed(repository.getId());
            ImportQualitySummary summary = summaryBuilder.status(STATUS_FAILED).build();
            cleanupImportedIndex(repository.getId());
            long totalElapsedMs = System.currentTimeMillis() - importStarted;
            log.warn("Code repository import failed with quality summary: repoId={}, repositoryName={}, summary={}, embeddingBatchSize={}, embeddingBatchCount={}, embeddingElapsedMs={}, dbWriteElapsedMs={}, totalElapsedMs={}",
                    repository.getId(), repository.getName(), summary, runtimeStats.batchSize(),
                    runtimeStats.batchCount(), runtimeStats.embeddingElapsedMs(), runtimeStats.dbWriteElapsedMs(),
                    totalElapsedMs, e);
            throw new CodeRepositoryImportException(importFailureMessage(e), e,
                    ImportCodeRepositoryResponse.builder()
                            .repoId(repository.getId())
                            .message(importFailureMessage(e))
                            .importQualitySummary(summary)
                            .build());
        }
    }

    @Override
    public GetCodeRepositoriesResponse getRepositories() {
        List<CodeRepository> repositories = codeRepositoryMapper.selectAll();
        return GetCodeRepositoriesResponse.builder()
                .repositories(repositories.toArray(new CodeRepository[0]))
                .build();
    }

    @Override
    public void deleteRepository(String repoId) {
        if (!StringUtils.hasLength(repoId)) {
            throw new BizException("repoId 不能为空");
        }
        transactionTemplate().executeWithoutResult(status -> {
            codeChunkMapper.deleteByRepoId(repoId);
            codeFileMapper.deleteByRepoId(repoId);
            codeRepositoryMapper.deleteById(repoId);
        });
    }

    private CodeRepository markImporting(String name, String rootPath) {
        return transactionTemplate().execute(status -> prepareRepository(name, rootPath));
    }

    private void markFailed(String repoId) {
        transactionTemplate().executeWithoutResult(status -> codeRepositoryMapper.updateById(CodeRepository.builder()
                .id(repoId)
                .status(STATUS_FAILED)
                .build()));
    }

    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(transactionManager);
    }

    private CodeRepository prepareRepository(String name, String rootPath) {
        CodeRepository existing = codeRepositoryMapper.selectExisting(name, rootPath);
        LocalDateTime now = LocalDateTime.now();
        if (existing != null) {
            existing.setName(name);
            existing.setRootPath(rootPath);
            existing.setLanguage("java");
            existing.setStatus(STATUS_IMPORTING);
            codeRepositoryMapper.updateById(existing);
            codeChunkMapper.deleteByRepoId(existing.getId());
            codeFileMapper.deleteByRepoId(existing.getId());
            return existing;
        }
        CodeRepository repository = CodeRepository.builder()
                .name(name)
                .rootPath(rootPath)
                .language("java")
                .status(STATUS_IMPORTING)
                .createdAt(now)
                .updatedAt(now)
                .build();
        codeRepositoryMapper.insert(repository);
        return repository;
    }

    private CodeFile persistCodeFile(CodeRepository repository, ParsedCodeFile parsed, ImportRuntimeStats runtimeStats) {
        CodeFile codeFile = CodeFile.builder()
                .repoId(repository.getId())
                .filePath(parsed.getRelativePath())
                .fileType(parsed.getFileType())
                .packageName(parsed.getPackageName())
                .className(parsed.getClassName())
                .checksum(parsed.getChecksum())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        long started = System.currentTimeMillis();
        transactionTemplate().executeWithoutResult(status -> codeFileMapper.insert(codeFile));
        runtimeStats.addDbWriteElapsed(System.currentTimeMillis() - started);
        return codeFile;
    }

    private void addChunksToEmbeddingBuffer(CodeRepository repository,
                                            CodeFile codeFile,
                                            ParsedCodeFile parsed,
                                            List<EmbeddingTarget> embeddingBuffer) {
        if (parsed.getChunks() == null) {
            return;
        }
        for (CodeChunk chunk : parsed.getChunks()) {
            chunk.setRepoId(repository.getId());
            chunk.setFileId(codeFile.getId());
            if (chunk.getCreatedAt() == null) {
                chunk.setCreatedAt(LocalDateTime.now());
            }
            embeddingBuffer.add(new EmbeddingTarget(parsed, chunk));
        }
    }

    private void flushFullEmbeddingBatches(List<EmbeddingTarget> embeddingBuffer,
                                           ImportQualitySummaryBuilder summaryBuilder,
                                           ImportRuntimeStats runtimeStats) {
        while (embeddingBuffer.size() >= runtimeStats.batchSize()) {
            flushEmbeddingBatch(embeddingBuffer, runtimeStats.batchSize(), summaryBuilder, runtimeStats);
        }
    }

    private void flushRemainingEmbeddingBatch(List<EmbeddingTarget> embeddingBuffer,
                                              ImportQualitySummaryBuilder summaryBuilder,
                                              ImportRuntimeStats runtimeStats) {
        if (!embeddingBuffer.isEmpty()) {
            flushEmbeddingBatch(embeddingBuffer, embeddingBuffer.size(), summaryBuilder, runtimeStats);
        }
    }

    private void flushEmbeddingBatch(List<EmbeddingTarget> embeddingBuffer,
                                     int batchSize,
                                     ImportQualitySummaryBuilder summaryBuilder,
                                     ImportRuntimeStats runtimeStats) {
        List<EmbeddingTarget> batch = new ArrayList<>(embeddingBuffer.subList(0, batchSize));
        List<String> texts = batch.stream()
                .map(target -> codeChunkEmbeddingTextBuilder.build(target.parsed(), target.chunk()))
                .toList();
        long embeddingStarted = System.currentTimeMillis();
        List<float[]> embeddings = embeddingService.embedBatch(texts);
        runtimeStats.addEmbeddingElapsed(System.currentTimeMillis() - embeddingStarted);
        runtimeStats.recordBatch();
        if (embeddings.size() != batch.size()) {
            throw new IllegalStateException("Embedding batch size mismatch: expected "
                    + batch.size() + ", actual " + embeddings.size());
        }
        for (int i = 0; i < embeddings.size(); i++) {
            batch.get(i).chunk().setEmbedding(embeddings.get(i));
        }

        long dbStarted = System.currentTimeMillis();
        List<CodeChunk> chunks = batch.stream().map(EmbeddingTarget::chunk).toList();
        Integer inserted = transactionTemplate().execute(status -> codeChunkMapper.insertBatch(chunks));
        runtimeStats.addDbWriteElapsed(System.currentTimeMillis() - dbStarted);
        if (inserted == null || inserted != chunks.size()) {
            throw new IllegalStateException("Code chunk batch insert size mismatch: expected "
                    + chunks.size() + ", actual " + inserted);
        }
        for (int i = 0; i < embeddings.size(); i++) {
            summaryBuilder.recordEmbeddedChunk();
        }
        embeddingBuffer.subList(0, batchSize).clear();
    }

    private void cleanupImportedIndex(String repoId) {
        try {
            transactionTemplate().executeWithoutResult(status -> {
                codeChunkMapper.deleteByRepoId(repoId);
                codeFileMapper.deleteByRepoId(repoId);
            });
        } catch (RuntimeException cleanupError) {
            log.error("Failed to cleanup partial Code RAG import index: repoId={}", repoId, cleanupError);
        }
    }

    private Map<String, Object> metadata(CodeChunk chunk) {
        if (chunk == null || !StringUtils.hasLength(chunk.getMetadata())) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(chunk.getMetadata(), new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private boolean isTrue(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private String importFailureMessage(RuntimeException e) {
        if (e instanceof BizException && StringUtils.hasLength(e.getMessage())) {
            return e.getMessage();
        }
        return "代码库导入失败: " + (StringUtils.hasLength(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName());
    }

    private record EmbeddingTarget(ParsedCodeFile parsed, CodeChunk chunk) {
    }

    private static class ImportRuntimeStats {
        private final int batchSize;
        private int batchCount;
        private long embeddingElapsedMs;
        private long dbWriteElapsedMs;

        private ImportRuntimeStats(int batchSize) {
            this.batchSize = batchSize;
        }

        private int batchSize() {
            return batchSize;
        }

        private int batchCount() {
            return batchCount;
        }

        private long embeddingElapsedMs() {
            return embeddingElapsedMs;
        }

        private long dbWriteElapsedMs() {
            return dbWriteElapsedMs;
        }

        private void recordBatch() {
            batchCount++;
        }

        private void addEmbeddingElapsed(long elapsedMs) {
            embeddingElapsedMs += elapsedMs;
        }

        private void addDbWriteElapsed(long elapsedMs) {
            dbWriteElapsedMs += elapsedMs;
        }
    }

    private class ImportQualitySummaryBuilder {
        private final int totalFiles;
        private int parsedFiles;
        private int fallbackFiles;
        private int javaFallbackCount;
        private int xmlFallbackCount;
        private int includeWarningCount;
        private int failedFiles;
        private int chunkCount;
        private int embeddedChunkCount;
        private String status = STATUS_IMPORTING;

        private ImportQualitySummaryBuilder(int totalFiles) {
            this.totalFiles = totalFiles;
        }

        private void recordParsedFile(ParsedCodeFile parsed) {
            parsedFiles++;
            if (parsed == null || parsed.getChunks() == null) {
                return;
            }
            chunkCount += parsed.getChunks().size();
            boolean fallbackFile = false;
            boolean javaFallbackFile = false;
            boolean xmlFallbackFile = false;
            for (CodeChunk chunk : parsed.getChunks()) {
                Map<String, Object> metadata = metadata(chunk);
                if (isTrue(metadata.get("parserFallback"))) {
                    fallbackFile = true;
                    if ("JAVA_AST".equals(metadata.get("parserType"))) {
                        javaFallbackFile = true;
                    }
                    if ("MYBATIS_XML".equals(metadata.get("parserType"))) {
                        xmlFallbackFile = true;
                    }
                }
                if (isTrue(metadata.get("parserWarning"))
                        && "MYBATIS_XML_INCLUDE".equals(metadata.get("parserWarningType"))) {
                    includeWarningCount++;
                }
            }
            if (fallbackFile) {
                fallbackFiles++;
            }
            if (javaFallbackFile) {
                javaFallbackCount++;
            }
            if (xmlFallbackFile) {
                xmlFallbackCount++;
            }
        }

        private void recordEmbeddedChunk() {
            embeddedChunkCount++;
        }

        private void recordFailedFile() {
            failedFiles++;
        }

        private ImportQualitySummaryBuilder status(String status) {
            this.status = status;
            return this;
        }

        private ImportQualitySummary build() {
            return ImportQualitySummary.builder()
                    .totalFiles(totalFiles)
                    .scannedFiles(totalFiles)
                    .parsedFiles(parsedFiles)
                    .fallbackFiles(fallbackFiles)
                    .javaFallbackCount(javaFallbackCount)
                    .xmlFallbackCount(xmlFallbackCount)
                    .includeWarningCount(includeWarningCount)
                    .failedFiles(failedFiles)
                    .chunkCount(chunkCount)
                    .embeddedChunkCount(embeddedChunkCount)
                    .status(status)
                    .build();
        }
    }

}
