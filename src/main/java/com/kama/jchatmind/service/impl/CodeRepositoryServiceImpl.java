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
import com.kama.jchatmind.service.CodeChunkParser;
import com.kama.jchatmind.service.CodeFileScanner;
import com.kama.jchatmind.service.CodeRepositoryService;
import com.kama.jchatmind.service.RagService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

@Service
@AllArgsConstructor
public class CodeRepositoryServiceImpl implements CodeRepositoryService {
    private static final String STATUS_IMPORTING = "IMPORTING";
    private static final String STATUS_READY = "READY";
    private static final String STATUS_FAILED = "FAILED";
    private static final int MAX_EMBED_TEXT_LENGTH = 8000;

    private final CodeRepositoryMapper codeRepositoryMapper;
    private final CodeFileMapper codeFileMapper;
    private final CodeChunkMapper codeChunkMapper;
    private final CodeFileScanner codeFileScanner;
    private final CodeChunkParser codeChunkParser;
    private final RagService ragService;

    @Override
    public ImportCodeRepositoryResponse importRepository(ImportCodeRepositoryRequest request) {
        if (request == null || !StringUtils.hasLength(request.getName()) || !StringUtils.hasLength(request.getRootPath())) {
            throw new BizException("name 和 rootPath 不能为空");
        }

        CodeFileScanner.ScanResult scanResult = codeFileScanner.scan(Path.of(request.getRootPath()));
        String normalizedRoot = scanResult.getNormalizedRoot().toString().replace("\\", "/");
        CodeRepository repository = prepareRepository(request.getName(), normalizedRoot);

        int fileCount = 0;
        int chunkCount = 0;
        try {
            for (Path filePath : scanResult.getFiles()) {
                ParsedCodeFile parsed = codeChunkParser.parse(scanResult.getNormalizedRoot(), filePath);
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
                fileCount++;

                for (CodeChunk chunk : parsed.getChunks()) {
                    chunk.setRepoId(repository.getId());
                    chunk.setFileId(codeFile.getId());
                    chunk.setEmbedding(ragService.embed(buildEmbeddingText(parsed, chunk)));
                    if (chunk.getCreatedAt() == null) {
                        chunk.setCreatedAt(LocalDateTime.now());
                    }
                    codeChunkMapper.insert(chunk);
                    chunkCount++;
                }
            }
            codeRepositoryMapper.updateById(CodeRepository.builder()
                    .id(repository.getId())
                    .status(STATUS_READY)
                    .build());
            return ImportCodeRepositoryResponse.builder()
                    .repoId(repository.getId())
                    .fileCount(fileCount)
                    .chunkCount(chunkCount)
                    .truncated(scanResult.isTruncated())
                    .message(scanResult.getMessage())
                    .build();
        } catch (RuntimeException e) {
            codeChunkMapper.deleteByRepoId(repository.getId());
            codeFileMapper.deleteByRepoId(repository.getId());
            codeRepositoryMapper.updateById(CodeRepository.builder()
                    .id(repository.getId())
                    .status(STATUS_FAILED)
                    .build());
            throw e;
        }
    }

    @Override
    public GetCodeRepositoriesResponse getRepositories() {
        List<CodeRepository> repositories = codeRepositoryMapper.selectAll();
        return GetCodeRepositoriesResponse.builder()
                .repositories(repositories.toArray(new CodeRepository[0]))
                .build();
    }

    private CodeRepository prepareRepository(String name, String rootPath) {
        CodeRepository existing = codeRepositoryMapper.selectExisting(name, rootPath);
        LocalDateTime now = LocalDateTime.now();
        if (existing != null) {
            codeChunkMapper.deleteByRepoId(existing.getId());
            codeFileMapper.deleteByRepoId(existing.getId());
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

    private String buildEmbeddingText(ParsedCodeFile parsed, CodeChunk chunk) {
        String text = "file: " + parsed.getRelativePath() + "\n"
                + "fileType: " + parsed.getFileType() + "\n"
                + "chunkType: " + chunk.getChunkType() + "\n"
                + "symbol: " + nullToEmpty(chunk.getSymbolName()) + "\n"
                + "api: " + nullToEmpty(chunk.getHttpMethod()) + " " + nullToEmpty(chunk.getApiPath()) + "\n"
                + "content:\n" + nullToEmpty(chunk.getContent());
        if (text.length() > MAX_EMBED_TEXT_LENGTH) {
            return text.substring(0, MAX_EMBED_TEXT_LENGTH);
        }
        return text;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
