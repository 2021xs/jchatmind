package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.config.GithubImportProperties;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.github.GitRepositoryCloner;
import com.kama.jchatmind.github.GithubRepositoryParser;
import com.kama.jchatmind.github.GithubRepositoryValidator;
import com.kama.jchatmind.github.GithubWorkspaceManager;
import com.kama.jchatmind.mapper.CodeRepositoryMapper;
import com.kama.jchatmind.model.common.RepositorySourceType;
import com.kama.jchatmind.model.entity.CodeRepository;
import com.kama.jchatmind.model.request.GithubRepositoryImportRequest;
import com.kama.jchatmind.model.response.ImportCodeRepositoryResponse;
import com.kama.jchatmind.service.CodeRepositoryService;
import com.kama.jchatmind.service.GithubRepositoryImportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GithubRepositoryImportServiceImplTest {
    @TempDir
    Path tempDir;

    @Test
    void invalidUrlDoesNotCreateRepositoryOrWorkspace() {
        CodeRepositoryMapper mapper = mock(CodeRepositoryMapper.class);
        GitRepositoryCloner cloner = mock(GitRepositoryCloner.class);
        GithubRepositoryImportService service = service(mapper, mock(CodeRepositoryService.class), cloner);
        GithubRepositoryImportRequest request = new GithubRepositoryImportRequest();
        request.setUrl("https://github.com.evil.com/user/repo");

        assertThatThrownBy(() -> service.importRepository(request))
                .isInstanceOf(BizException.class)
                .hasMessage("INVALID_GITHUB_URL");
        verify(mapper, never()).insertWithId(any());
        verify(cloner, never()).cloneRepository(any(), any());
    }

    @Test
    void successPersistsGithubProvenanceAndCallsSharedIndexing() {
        CodeRepositoryMapper mapper = mock(CodeRepositoryMapper.class);
        CodeRepositoryService indexService = mock(CodeRepositoryService.class);
        GitRepositoryCloner cloner = mock(GitRepositoryCloner.class);
        when(cloner.cloneRepository(any(), any())).thenReturn(
                new GitRepositoryCloner.CloneResult("main", "abc123"));
        when(indexService.indexRepository(any(), any())).thenReturn(
                ImportCodeRepositoryResponse.builder().repoId("repo-id").build());
        GithubRepositoryImportService service = service(mapper, indexService, cloner);
        GithubRepositoryImportRequest request = new GithubRepositoryImportRequest();
        request.setUrl("https://github.com/Spring-Projects/spring-boot");
        request.setName("Custom display name");

        ImportCodeRepositoryResponse response = service.importRepository(request);

        assertThat(response.getRepoId()).isEqualTo("repo-id");
        ArgumentCaptor<CodeRepository> repositoryCaptor = ArgumentCaptor.forClass(CodeRepository.class);
        verify(mapper).insertWithId(repositoryCaptor.capture());
        CodeRepository repository = repositoryCaptor.getValue();
        assertThat(repository.getSourceType()).isEqualTo(RepositorySourceType.GITHUB);
        assertThat(repository.getRemoteUrl()).isEqualTo("https://github.com/Spring-Projects/spring-boot.git");
        assertThat(repository.getName()).isEqualTo("Custom display name");
        assertThat(Path.of(repository.getRootPath()).getFileName().toString())
                .isEqualTo("spring-projects--spring-boot");
        ArgumentCaptor<Path> pathCaptor = ArgumentCaptor.forClass(Path.class);
        verify(cloner).cloneRepository(any(), pathCaptor.capture());
        assertThat(pathCaptor.getValue().getFileName().toString())
                .isEqualTo("spring-projects--spring-boot");
        verify(indexService).indexRepository(any(CodeRepository.class), any(Path.class));
    }

    @Test
    void existingCanonicalWorkspaceBlocksImportWithoutOverwriting() throws Exception {
        CodeRepositoryMapper mapper = mock(CodeRepositoryMapper.class);
        CodeRepositoryService indexService = mock(CodeRepositoryService.class);
        GitRepositoryCloner cloner = mock(GitRepositoryCloner.class);
        Path existing = tempDir.resolve("github").resolve("spring-projects--spring-boot");
        Files.createDirectories(existing);
        Files.writeString(existing.resolve("marker.txt"), "keep");
        GithubRepositoryImportService service = service(mapper, indexService, cloner);
        GithubRepositoryImportRequest request = new GithubRepositoryImportRequest();
        request.setUrl("https://github.com/Spring-Projects/spring-boot");

        assertThatThrownBy(() -> service.importRepository(request))
                .isInstanceOf(BizException.class)
                .hasMessage("GITHUB_WORKSPACE_ALREADY_EXISTS");
        assertThat(Files.readString(existing.resolve("marker.txt"))).isEqualTo("keep");
        verify(mapper, never()).insertWithId(any());
        verify(cloner, never()).cloneRepository(any(), any());
        verify(indexService, never()).indexRepository(any(), any());
    }

    private GithubRepositoryImportService service(CodeRepositoryMapper mapper,
                                                   CodeRepositoryService indexService,
                                                   GitRepositoryCloner cloner) {
        GithubImportProperties properties = new GithubImportProperties();
        properties.setWorkspaceRoot(tempDir.resolve("github").toString());
        CodeRagProperties codeRagProperties = new CodeRagProperties();
        codeRagProperties.setAllowedRoots(List.of(tempDir.toString()));
        GithubWorkspaceManager workspaceManager = new GithubWorkspaceManager(properties, codeRagProperties);
        return new GithubRepositoryImportServiceImpl(
                mapper, indexService, new GithubRepositoryParser(), new GithubRepositoryValidator(),
                workspaceManager, cloner, new NoopTransactionManager());
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
}
