package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.exception.CodeRepositoryImportException;
import com.kama.jchatmind.github.GitRepositoryCloner;
import com.kama.jchatmind.github.GithubCloneException;
import com.kama.jchatmind.github.GithubRepository;
import com.kama.jchatmind.github.GithubRepositoryParser;
import com.kama.jchatmind.github.GithubRepositoryUrlException;
import com.kama.jchatmind.github.GithubRepositoryValidator;
import com.kama.jchatmind.github.GithubWorkspaceManager;
import com.kama.jchatmind.github.GithubWorkspaceManager.PreparedWorkspace;
import com.kama.jchatmind.mapper.CodeRepositoryMapper;
import com.kama.jchatmind.model.common.RepositorySourceType;
import com.kama.jchatmind.model.dto.ImportQualitySummary;
import com.kama.jchatmind.model.entity.CodeRepository;
import com.kama.jchatmind.model.request.GithubRepositoryImportRequest;
import com.kama.jchatmind.model.response.ImportCodeRepositoryResponse;
import com.kama.jchatmind.service.CodeRepositoryService;
import com.kama.jchatmind.service.GithubRepositoryImportService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@AllArgsConstructor
@Slf4j
public class GithubRepositoryImportServiceImpl implements GithubRepositoryImportService {
    private static final String STATUS_IMPORTING = "IMPORTING";
    private static final String STATUS_FAILED = "FAILED";

    private final CodeRepositoryMapper codeRepositoryMapper;
    private final CodeRepositoryService codeRepositoryService;
    private final GithubRepositoryParser parser;
    private final GithubRepositoryValidator validator;
    private final GithubWorkspaceManager workspaceManager;
    private final GitRepositoryCloner cloner;
    private final PlatformTransactionManager transactionManager;

    @Override
    public ImportCodeRepositoryResponse importRepository(GithubRepositoryImportRequest request) {
        if (request == null || !StringUtils.hasText(request.getUrl())) {
            throw new BizException("INVALID_GITHUB_URL");
        }

        GithubRepository githubRepository;
        try {
            githubRepository = parser.parse(request.getUrl());
            validator.validate(githubRepository);
        } catch (GithubRepositoryUrlException e) {
            throw new BizException("INVALID_GITHUB_URL");
        }

        String canonicalUrl = githubRepository.cloneUrl();
        String name = StringUtils.hasText(request.getName())
                ? request.getName().trim()
                : githubRepository.repository();
        String repositoryId = UUID.randomUUID().toString();
        PreparedWorkspace workspace = workspaceManager.prepare(
                repositoryId, githubRepository.owner(), githubRepository.repository());
        Path workspacePath = workspace.path();
        CodeRepository repository = CodeRepository.builder()
                .id(repositoryId)
                .name(name)
                .rootPath(workspacePath.toString().replace("\\", "/"))
                .language("java")
                .status(STATUS_IMPORTING)
                .sourceType(RepositorySourceType.GITHUB)
                .remoteUrl(canonicalUrl)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(
                    status -> codeRepositoryMapper.insertWithId(repository));
            GitRepositoryCloner.CloneResult cloneResult = cloner.cloneRepository(canonicalUrl, workspacePath);
            repository.setBranch(cloneResult.branch());
            repository.setCommitSha(cloneResult.commitSha());
            new TransactionTemplate(transactionManager).executeWithoutResult(
                    status -> codeRepositoryMapper.updateById(repository));
            return codeRepositoryService.indexRepository(repository, workspacePath);
        } catch (GithubCloneException e) {
            markFailed(repositoryId);
            cleanupWorkspace(workspace);
            throw importFailure(repositoryId, e.getErrorType(), e);
        } catch (CodeRepositoryImportException e) {
            cleanupWorkspace(workspace);
            throw importFailure(repositoryId, "IMPORT_FAILED", e);
        } catch (RuntimeException e) {
            markFailed(repositoryId);
            cleanupWorkspace(workspace);
            throw importFailure(repositoryId, "IMPORT_FAILED", e);
        }
    }

    private void markFailed(String repositoryId) {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(
                    status -> codeRepositoryMapper.updateById(CodeRepository.builder()
                            .id(repositoryId)
                            .status(STATUS_FAILED)
                            .build()));
        } catch (RuntimeException e) {
            log.warn("Failed to mark GitHub repository FAILED: repositoryId={}", repositoryId, e);
        }
    }

    private void cleanupWorkspace(PreparedWorkspace workspace) {
        try {
            workspaceManager.cleanup(workspace);
        } catch (RuntimeException cleanupFailure) {
            log.warn("Failed to cleanup GitHub workspace: workspace={}", workspace.path(), cleanupFailure);
        }
    }

    private CodeRepositoryImportException importFailure(String repositoryId, String errorType, RuntimeException cause) {
        return new CodeRepositoryImportException(errorType,
                cause,
                ImportCodeRepositoryResponse.builder()
                        .repoId(repositoryId)
                        .message(errorType)
                        .importQualitySummary(ImportQualitySummary.builder().status(STATUS_FAILED).build())
                        .build());
    }
}
