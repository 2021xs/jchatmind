package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.exception.CodeRepositoryImportException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.CodeChunkMapper;
import com.kama.jchatmind.mapper.CodeFileMapper;
import com.kama.jchatmind.mapper.CodeRepositoryMapper;
import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.config.GithubImportProperties;
import com.kama.jchatmind.github.GithubWorkspaceManager;
import com.kama.jchatmind.github.GithubWorkspaceManager.PreparedWorkspace;
import com.kama.jchatmind.model.dto.ImportQualitySummary;
import com.kama.jchatmind.model.common.RepositorySourceType;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
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
    private final GithubImportProperties githubImportProperties;
    private final GithubWorkspaceManager githubWorkspaceManager;

    public CodeRepositoryServiceImpl(CodeRepositoryMapper codeRepositoryMapper,
                                     CodeFileMapper codeFileMapper,
                                     CodeChunkMapper codeChunkMapper,
                                     CodeFileScanner codeFileScanner,
                                     CodeChunkParser codeChunkParser,
                                     CodeChunkEmbeddingTextBuilder codeChunkEmbeddingTextBuilder,
                                     EmbeddingService embeddingService,
                                     PlatformTransactionManager transactionManager,
                                     ObjectMapper objectMapper,
                                     CodeRagProperties codeRagProperties) {
        this(codeRepositoryMapper, codeFileMapper, codeChunkMapper, codeFileScanner, codeChunkParser,
                codeChunkEmbeddingTextBuilder, embeddingService, transactionManager, objectMapper,
                codeRagProperties, new GithubImportProperties(), null);
    }

    public CodeRepositoryServiceImpl(CodeRepositoryMapper codeRepositoryMapper,
                                     CodeFileMapper codeFileMapper,
                                     CodeChunkMapper codeChunkMapper,
                                     CodeFileScanner codeFileScanner,
                                     CodeChunkParser codeChunkParser,
                                     CodeChunkEmbeddingTextBuilder codeChunkEmbeddingTextBuilder,
                                     EmbeddingService embeddingService,
                                     PlatformTransactionManager transactionManager,
                                     ObjectMapper objectMapper,
                                     CodeRagProperties codeRagProperties,
                                     GithubImportProperties githubImportProperties) {
        this(codeRepositoryMapper, codeFileMapper, codeChunkMapper, codeFileScanner, codeChunkParser,
                codeChunkEmbeddingTextBuilder, embeddingService, transactionManager, objectMapper,
                codeRagProperties, githubImportProperties, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CodeRepositoryServiceImpl(CodeRepositoryMapper codeRepositoryMapper,
                                     CodeFileMapper codeFileMapper,
                                     CodeChunkMapper codeChunkMapper,
                                     CodeFileScanner codeFileScanner,
                                     CodeChunkParser codeChunkParser,
                                     CodeChunkEmbeddingTextBuilder codeChunkEmbeddingTextBuilder,
                                     EmbeddingService embeddingService,
                                     PlatformTransactionManager transactionManager,
                                     ObjectMapper objectMapper,
                                     CodeRagProperties codeRagProperties,
                                     GithubImportProperties githubImportProperties,
                                     GithubWorkspaceManager githubWorkspaceManager) {
        this.codeRepositoryMapper = codeRepositoryMapper;
        this.codeFileMapper = codeFileMapper;
        this.codeChunkMapper = codeChunkMapper;
        this.codeFileScanner = codeFileScanner;
        this.codeChunkParser = codeChunkParser;
        this.codeChunkEmbeddingTextBuilder = codeChunkEmbeddingTextBuilder;
        this.embeddingService = embeddingService;
        this.transactionManager = transactionManager;
        this.objectMapper = objectMapper;
        this.codeRagProperties = codeRagProperties;
        this.githubImportProperties = githubImportProperties;
        this.githubWorkspaceManager = githubWorkspaceManager;
    }

    @Override
    public ImportCodeRepositoryResponse importRepository(ImportCodeRepositoryRequest request) {
        if (request == null || !StringUtils.hasLength(request.getName()) || !StringUtils.hasLength(request.getRootPath())) {
            throw new BizException("name 和 rootPath 不能为空");
        }

        CodeFileScanner.ScanResult scanResult = codeFileScanner.scan(Path.of(request.getRootPath()));
        String normalizedRoot = scanResult.getNormalizedRoot().toString().replace("\\", "/");
        CodeRepository repository = markImporting(request.getName(), normalizedRoot, RepositorySourceType.LOCAL);
        return indexRepository(repository, scanResult);
    }

    @Override
    public ImportCodeRepositoryResponse indexRepository(CodeRepository repository, Path rootPath) {
        if (repository == null || !StringUtils.hasLength(repository.getId()) || rootPath == null) {
            throw new BizException("Repository and rootPath are required");
        }
        try {
            return indexRepository(repository, codeFileScanner.scan(rootPath));
        } catch (RuntimeException e) {
            markFailed(repository.getId());
            cleanupImportedIndex(repository.getId());
            throw new CodeRepositoryImportException(importFailureMessage(e), e,
                    ImportCodeRepositoryResponse.builder()
                            .repoId(repository.getId())
                            .message(importFailureMessage(e))
                            .importQualitySummary(ImportQualitySummary.builder().status(STATUS_FAILED).build())
                            .build());
        }
    }

    private ImportCodeRepositoryResponse indexRepository(CodeRepository repository,
                                                         CodeFileScanner.ScanResult scanResult) {
        ImportQualitySummaryBuilder summaryBuilder = new ImportQualitySummaryBuilder(
                scanResult.getFiles().size(), scanResult.getSkippedSqlFileCount());
        ImportRuntimeStats runtimeStats = new ImportRuntimeStats(Math.max(1, codeRagProperties.getEmbeddingBatchSize()));
        long importStarted = System.currentTimeMillis();
        List<EmbeddingTarget> embeddingBuffer = new ArrayList<>();
        boolean parsingFile = false;
        try {
            enforceGithubResourceGuards(repository, scanResult);
            for (Path filePath : scanResult.getFiles()) {
                parsingFile = true;
                ParsedCodeFile parsed = codeChunkParser.parse(scanResult.getNormalizedRoot(), filePath);
                parsingFile = false;
                summaryBuilder.recordParsedFile(parsed);
                CodeFile codeFile = persistCodeFile(repository, parsed, runtimeStats);
                addChunksToEmbeddingBuffer(repository, codeFile, parsed, embeddingBuffer);
                flushFullEmbeddingBatches(repository, embeddingBuffer, summaryBuilder, runtimeStats);
            }
            flushRemainingEmbeddingBatch(repository, embeddingBuffer, summaryBuilder, runtimeStats);
            transactionTemplate().executeWithoutResult(status ->
                codeRepositoryMapper.updateById(CodeRepository.builder()
                        .id(repository.getId())
                        .status(STATUS_READY)
                        .build()));
            ImportQualitySummary summary = summaryBuilder.status(STATUS_READY).build();
            long totalElapsedMs = System.currentTimeMillis() - importStarted;
            log.info("Code repository import quality summary: repoId={}, repositoryName={}, summary={}, skippedSqlFilePaths={}, embeddingBatchSize={}, embeddingBatchCount={}, embeddingElapsedMs={}, dbWriteElapsedMs={}, totalElapsedMs={}",
                    repository.getId(), repository.getName(), summary, scanResult.getSkippedSqlFilePaths(),
                    runtimeStats.batchSize(), runtimeStats.batchCount(), runtimeStats.embeddingElapsedMs(),
                    runtimeStats.dbWriteElapsedMs(), totalElapsedMs);
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
            log.warn("Code repository import failed with quality summary: repoId={}, repositoryName={}, summary={}, skippedSqlFilePaths={}, embeddingBatchSize={}, embeddingBatchCount={}, embeddingElapsedMs={}, dbWriteElapsedMs={}, totalElapsedMs={}",
                    repository.getId(), repository.getName(), summary, scanResult.getSkippedSqlFilePaths(),
                    runtimeStats.batchSize(), runtimeStats.batchCount(), runtimeStats.embeddingElapsedMs(),
                    runtimeStats.dbWriteElapsedMs(), totalElapsedMs, e);
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
        CodeRepository repository = codeRepositoryMapper.selectById(repoId);
        PreparedWorkspace managedWorkspace = null;
        if (repository != null && repository.getSourceType() == RepositorySourceType.GITHUB
                && githubWorkspaceManager != null) {
            Path rootPath = Path.of(repository.getRootPath()).toAbsolutePath().normalize();
            managedWorkspace = githubWorkspaceManager.restore(
                    repository.getId(), repository.getRemoteUrl(), rootPath);
        }
        PreparedWorkspace workspaceToDelete = managedWorkspace;
        transactionTemplate().executeWithoutResult(status -> {
            codeChunkMapper.deleteByRepoId(repoId);
            codeFileMapper.deleteByRepoId(repoId);
            codeRepositoryMapper.deleteById(repoId);
        });
        if (workspaceToDelete != null) {
            try {
                githubWorkspaceManager.cleanup(workspaceToDelete);
            } catch (RuntimeException e) {
                log.warn("GitHub repository deleted but workspace cleanup failed: repoId={}", repoId, e);
                throw e;
            }
        }
    }

    private CodeRepository markImporting(String name, String rootPath, RepositorySourceType sourceType) {
        return transactionTemplate().execute(status -> prepareRepository(name, rootPath, sourceType));
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

    private CodeRepository prepareRepository(String name, String rootPath, RepositorySourceType sourceType) {
        CodeRepository existing = codeRepositoryMapper.selectExisting(name, rootPath);
        LocalDateTime now = LocalDateTime.now();
        if (existing != null) {
            existing.setName(name);
            existing.setRootPath(rootPath);
            existing.setLanguage("java");
            existing.setStatus(STATUS_IMPORTING);
            existing.setSourceType(sourceType);
            codeRepositoryMapper.updateById(existing);
            if (sourceType == RepositorySourceType.LOCAL) {
                codeRepositoryMapper.clearProvenanceById(existing.getId());
            }
            codeChunkMapper.deleteByRepoId(existing.getId());
            codeFileMapper.deleteByRepoId(existing.getId());
            return existing;
        }
        CodeRepository repository = CodeRepository.builder()
                .name(name)
                .rootPath(rootPath)
                .language("java")
                .status(STATUS_IMPORTING)
                .sourceType(sourceType)
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

    private void flushFullEmbeddingBatches(CodeRepository repository,
                                           List<EmbeddingTarget> embeddingBuffer,
                                           ImportQualitySummaryBuilder summaryBuilder,
                                           ImportRuntimeStats runtimeStats) {
        while (embeddingBuffer.size() >= runtimeStats.batchSize()) {
            flushEmbeddingBatch(repository, embeddingBuffer, runtimeStats.batchSize(), summaryBuilder, runtimeStats);
        }
    }

    private void flushRemainingEmbeddingBatch(CodeRepository repository,
                                              List<EmbeddingTarget> embeddingBuffer,
                                              ImportQualitySummaryBuilder summaryBuilder,
                                              ImportRuntimeStats runtimeStats) {
        if (!embeddingBuffer.isEmpty()) {
            flushEmbeddingBatch(repository, embeddingBuffer, embeddingBuffer.size(), summaryBuilder, runtimeStats);
        }
    }

    private void flushEmbeddingBatch(CodeRepository repository,
                                     List<EmbeddingTarget> embeddingBuffer,
                                     int batchSize,
                                     ImportQualitySummaryBuilder summaryBuilder,
                                     ImportRuntimeStats runtimeStats) {
        List<EmbeddingTarget> batch = new ArrayList<>(embeddingBuffer.subList(0, batchSize));
        List<String> texts = batch.stream()
                .map(target -> codeChunkEmbeddingTextBuilder.build(target.parsed(), target.chunk()))
                .toList();
        long embeddingStarted = System.currentTimeMillis();
        List<float[]> embeddings;
        try {
            embeddings = embeddingService.embedBatch(texts);
            runtimeStats.recordBatch();
        } catch (RuntimeException e) {
            runtimeStats.addEmbeddingElapsed(System.currentTimeMillis() - embeddingStarted);
            diagnoseEmbeddingBatchFailure(repository, batch, texts, runtimeStats, e);
            throw e;
        }
        runtimeStats.addEmbeddingElapsed(System.currentTimeMillis() - embeddingStarted);
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

    private void diagnoseEmbeddingBatchFailure(CodeRepository repository,
                                               List<EmbeddingTarget> batch,
                                               List<String> texts,
                                               ImportRuntimeStats runtimeStats,
                                               RuntimeException batchFailure) {
        int nextBatchNumber = runtimeStats.batchCount() + 1;
        log.warn("Code RAG embedding batch failed, start single-item diagnostic retry: repoId={}, repositoryName={}, batchNumber={}, batchSize={}, error={}",
                repository.getId(), repository.getName(), nextBatchNumber, batch.size(), batchFailure.getMessage());
        boolean singleFailureFound = false;
        for (int i = 0; i < batch.size(); i++) {
            EmbeddingTarget target = batch.get(i);
            String text = texts.get(i);
            log.warn("Code RAG embedding failed batch item summary: repoId={}, repositoryName={}, batchNumber={}, batchIndex={}, {}",
                    repository.getId(), repository.getName(), nextBatchNumber, i, diagnosticSummary(target, text));
            try {
                long singleStarted = System.currentTimeMillis();
                List<float[]> single = embeddingService.embedBatch(List.of(text));
                runtimeStats.addEmbeddingElapsed(System.currentTimeMillis() - singleStarted);
                if (single.size() != 1) {
                    singleFailureFound = true;
                    log.warn("Code RAG embedding single diagnostic size mismatch: repoId={}, repositoryName={}, batchNumber={}, batchIndex={}, expected=1, actual={}, {}",
                            repository.getId(), repository.getName(), nextBatchNumber, i, single.size(), diagnosticSummary(target, text));
                }
            } catch (RuntimeException singleFailure) {
                singleFailureFound = true;
                log.warn("Code RAG embedding single diagnostic failed: repoId={}, repositoryName={}, batchNumber={}, batchIndex={}, singleFailure={}, {}",
                        repository.getId(), repository.getName(), nextBatchNumber, i, singleFailure.getMessage(),
                        diagnosticSummary(target, text), singleFailure);
            }
        }
        if (!singleFailureFound) {
            log.warn("Code RAG embedding batch-level failure: all single-item diagnostic retries succeeded, repoId={}, repositoryName={}, batchNumber={}, batchSize={}, batchFailure={}",
                    repository.getId(), repository.getName(), nextBatchNumber, batch.size(), batchFailure.getMessage());
        }
    }

    private String diagnosticSummary(EmbeddingTarget target, String text) {
        CodeChunk chunk = target.chunk();
        ParsedCodeFile parsed = target.parsed();
        Map<String, Object> metadata = metadata(chunk);
        return "filePath=" + safe(parsed.getRelativePath())
                + ", fileType=" + safe(parsed.getFileType())
                + ", className=" + safe(parsed.getClassName())
                + ", chunkType=" + safe(chunk.getChunkType())
                + ", symbolName=" + safe(chunk.getSymbolName())
                + ", apiPath=" + safe(chunk.getApiPath())
                + ", httpMethod=" + safe(chunk.getHttpMethod())
                + ", statementId=" + firstPresent(metadata, "sqlId", "statementId", "SQL_ID")
                + ", fallbackChunkType=" + firstPresent(metadata, "fallbackChunkType")
                + ", textLength=" + text.length()
                + ", textHash=" + sha256Prefix(text)
                + ", blank=" + !StringUtils.hasText(text)
                + ", hasControlChars=" + hasControlChars(text)
                + ", hasInvalidSurrogate=" + hasInvalidSurrogate(text)
                + ", maxLineLength=" + maxLineLength(text)
                + ", preview=" + summarize(text, 200);
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

    private String firstPresent(Map<String, Object> metadata, String... keys) {
        for (String key : keys) {
            Object value = metadata.get(key);
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                return String.valueOf(value);
            }
        }
        return "";
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String summarize(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
                .replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxChars)) + "...[truncated]";
    }

    private static String sha256Prefix(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }

    private static boolean hasControlChars(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isISOControl(ch) && ch != '\n' && ch != '\r' && ch != '\t') {
                return true;
            }
        }
        return false;
    }

    private static boolean hasInvalidSurrogate(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(ch)) {
                return true;
            }
        }
        return false;
    }

    private static int maxLineLength(String value) {
        int max = 0;
        int current = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\n' || ch == '\r') {
                max = Math.max(max, current);
                current = 0;
            } else {
                current++;
            }
        }
        return Math.max(max, current);
    }

    private String importFailureMessage(RuntimeException e) {
        if (e instanceof BizException && StringUtils.hasLength(e.getMessage())) {
            return e.getMessage();
        }
        return "代码库导入失败: " + (StringUtils.hasLength(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName());
    }

    private void enforceGithubResourceGuards(CodeRepository repository, CodeFileScanner.ScanResult scanResult) {
        if (repository.getSourceType() != RepositorySourceType.GITHUB) {
            return;
        }
        long maxTotalBytes = githubImportProperties.getMaxTotalSourceBytes();
        if (scanResult.isTruncated()
                || scanResult.getOversizedFileCount() > 0
                || (maxTotalBytes > 0 && scanResult.getEligibleSourceBytes() > maxTotalBytes)) {
            throw new BizException("REPOSITORY_TOO_LARGE");
        }
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
        private final int skippedSqlFiles;
        private int parsedFiles;
        private int fallbackFiles;
        private int javaFallbackCount;
        private int xmlFallbackCount;
        private int includeWarningCount;
        private int failedFiles;
        private int chunkCount;
        private int embeddedChunkCount;
        private String status = STATUS_IMPORTING;

        private ImportQualitySummaryBuilder(int totalFiles, int skippedSqlFiles) {
            this.totalFiles = totalFiles;
            this.skippedSqlFiles = skippedSqlFiles;
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
                    .skippedFiles(skippedSqlFiles)
                    .skippedSqlFiles(skippedSqlFiles)
                    .chunkCount(chunkCount)
                    .embeddedChunkCount(embeddedChunkCount)
                    .status(status)
                    .build();
        }
    }

}
