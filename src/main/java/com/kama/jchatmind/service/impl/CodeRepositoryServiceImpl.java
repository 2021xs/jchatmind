package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.CodeChunkMapper;
import com.kama.jchatmind.mapper.CodeFileMapper;
import com.kama.jchatmind.mapper.CodeRepositoryMapper;
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
import com.kama.jchatmind.service.RagService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@AllArgsConstructor
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
    private final RagService ragService;
    private final PlatformTransactionManager transactionManager;

    @Override
    public ImportCodeRepositoryResponse importRepository(ImportCodeRepositoryRequest request) {
        if (request == null || !StringUtils.hasLength(request.getName()) || !StringUtils.hasLength(request.getRootPath())) {
            throw new BizException("name 和 rootPath 不能为空");
        }

        CodeFileScanner.ScanResult scanResult = codeFileScanner.scan(Path.of(request.getRootPath()));
        String normalizedRoot = scanResult.getNormalizedRoot().toString().replace("\\", "/");
        CodeRepository repository = markImporting(request.getName(), normalizedRoot);
        List<ParsedCodeFile> parsedFiles = new ArrayList<>();
        try {
            for (Path filePath : scanResult.getFiles()) {
                ParsedCodeFile parsed = codeChunkParser.parse(scanResult.getNormalizedRoot(), filePath);
                for (CodeChunk chunk : parsed.getChunks()) {
                    chunk.setEmbedding(ragService.embed(codeChunkEmbeddingTextBuilder.build(parsed, chunk)));
                }
                parsedFiles.add(parsed);
            }
            int fileCount = parsedFiles.size();
            int chunkCount = parsedFiles.stream()
                    .mapToInt(parsed -> parsed.getChunks() == null ? 0 : parsed.getChunks().size())
                    .sum();
            transactionTemplate().executeWithoutResult(status -> {
                codeChunkMapper.deleteByRepoId(repository.getId());
                codeFileMapper.deleteByRepoId(repository.getId());
                persistParsedFiles(repository, parsedFiles);
                codeRepositoryMapper.updateById(CodeRepository.builder()
                        .id(repository.getId())
                        .status(STATUS_READY)
                        .build());
            });
            return ImportCodeRepositoryResponse.builder()
                    .repoId(repository.getId())
                    .fileCount(fileCount)
                    .chunkCount(chunkCount)
                    .truncated(scanResult.isTruncated())
                    .message(scanResult.getMessage())
                    .build();
        } catch (RuntimeException e) {
            markFailed(repository.getId());
            throw e;
        }
    }

    private void persistParsedFiles(CodeRepository repository, List<ParsedCodeFile> parsedFiles) {
        for (ParsedCodeFile parsed : parsedFiles) {
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
            codeFileMapper.insert(codeFile);

            for (CodeChunk chunk : parsed.getChunks()) {
                chunk.setRepoId(repository.getId());
                chunk.setFileId(codeFile.getId());
                if (chunk.getCreatedAt() == null) {
                    chunk.setCreatedAt(LocalDateTime.now());
                }
                codeChunkMapper.insert(chunk);
            }
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
            throw new BizException("repoId 涓嶈兘涓虹┖");
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

}
