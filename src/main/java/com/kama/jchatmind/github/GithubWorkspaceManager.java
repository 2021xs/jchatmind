package com.kama.jchatmind.github;

import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.config.GithubImportProperties;
import com.kama.jchatmind.exception.BizException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;

@Component
@AllArgsConstructor
@Slf4j
public class GithubWorkspaceManager {
    private final GithubImportProperties properties;
    private final CodeRagProperties codeRagProperties;

    public PreparedWorkspace prepare(String repositoryId, String owner, String repository) {
        if (!isUuid(repositoryId)) {
            throw new BizException("GITHUB_WORKSPACE_REPOSITORY_ID_INVALID");
        }
        Path root = managedRoot();
        try {
            Files.createDirectories(root);
            ensureFreeSpace(root);
            Path target = managedPath(owner, repository);
            try {
                Files.createDirectory(target);
            } catch (FileAlreadyExistsException e) {
                throw new BizException("GITHUB_WORKSPACE_ALREADY_EXISTS");
            }
            return new PreparedWorkspace(repositoryId, owner, repository, target);
        } catch (IOException e) {
            throw new BizException("GITHUB_WORKSPACE_UNAVAILABLE");
        }
    }

    public PreparedWorkspace restore(String repositoryId, String remoteUrl, Path storedPath) {
        if (!isUuid(repositoryId) || !StringUtils.hasText(remoteUrl) || storedPath == null) {
            throw new BizException("GITHUB_WORKSPACE_PATH_REJECTED");
        }
        GithubRepository githubRepository;
        try {
            githubRepository = new GithubRepositoryParser().parse(remoteUrl);
            new GithubRepositoryValidator().validate(githubRepository);
        } catch (GithubRepositoryUrlException e) {
            throw new BizException("GITHUB_WORKSPACE_PATH_REJECTED");
        }
        Path expectedPath = managedPath(githubRepository.owner(), githubRepository.repository());
        Path legacyPath = managedRoot().resolve(repositoryId).normalize();
        Path normalizedStoredPath = storedPath.toAbsolutePath().normalize();
        if (!normalizedStoredPath.equals(expectedPath) && !normalizedStoredPath.equals(legacyPath)) {
            throw new BizException("GITHUB_WORKSPACE_PATH_REJECTED");
        }
        return new PreparedWorkspace(
                repositoryId, githubRepository.owner(), githubRepository.repository(), normalizedStoredPath);
    }

    public void cleanup(PreparedWorkspace workspace) {
        if (!isManagedWorkspace(workspace)) {
            throw new BizException("GITHUB_WORKSPACE_PATH_REJECTED");
        }
        Path target = workspace.path();
        if (!Files.exists(target)) {
            return;
        }
        if (Files.isSymbolicLink(target)) {
            throw new BizException("GITHUB_WORKSPACE_PATH_REJECTED");
        }
        try (var paths = Files.walk(target)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new WorkspaceCleanupRuntimeException(e);
                }
            });
        } catch (WorkspaceCleanupRuntimeException e) {
            throw new BizException("GITHUB_WORKSPACE_CLEANUP_FAILED");
        } catch (IOException e) {
            throw new BizException("GITHUB_WORKSPACE_CLEANUP_FAILED");
        }
    }

    public boolean isManagedWorkspace(PreparedWorkspace workspace) {
        if (workspace == null || !isUuid(workspace.repositoryId())
                || !StringUtils.hasText(properties.getWorkspaceRoot())) {
            return false;
        }
        try {
            Path normalized = workspace.path().toAbsolutePath().normalize();
            Path namedPath = managedPath(workspace.owner(), workspace.repository());
            Path legacyPath = managedRoot().resolve(workspace.repositoryId()).normalize();
            return normalized.equals(namedPath) || normalized.equals(legacyPath);
        } catch (BizException e) {
            return false;
        }
    }

    private Path managedPath(String owner, String repository) {
        if (!isSafeRepositorySegment(owner) || !isSafeRepositorySegment(repository)) {
            throw new BizException("GITHUB_WORKSPACE_NAME_INVALID");
        }
        String directoryName = (owner + "--" + repository).toLowerCase(Locale.ROOT);
        Path root = managedRoot();
        Path target = root.resolve(directoryName).normalize();
        if (target.getParent() == null || !target.getParent().equals(root)) {
            throw new BizException("GITHUB_WORKSPACE_PATH_REJECTED");
        }
        return target;
    }

    private Path managedRoot() {
        if (!StringUtils.hasText(properties.getWorkspaceRoot())) {
            throw new BizException("GITHUB_WORKSPACE_NOT_CONFIGURED");
        }
        Path root = Path.of(properties.getWorkspaceRoot()).toAbsolutePath().normalize();
        boolean allowed = codeRagProperties.getAllowedRoots() != null
                && codeRagProperties.getAllowedRoots().stream()
                .map(value -> Path.of(value).toAbsolutePath().normalize())
                .anyMatch(root::startsWith);
        if (!allowed) {
            throw new BizException("GITHUB_WORKSPACE_OUTSIDE_ALLOWED_ROOTS");
        }
        return root;
    }

    private void ensureFreeSpace(Path root) throws IOException {
        long minimum = Math.max(0, properties.getMinFreeSpaceBytes());
        if (minimum > 0 && Files.getFileStore(root).getUsableSpace() < minimum) {
            throw new BizException("GITHUB_WORKSPACE_INSUFFICIENT_SPACE");
        }
    }

    private boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }

    private boolean isSafeRepositorySegment(String value) {
        if (!StringUtils.hasText(value) || value.equals(".") || value.equals("..")) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!Character.isLetterOrDigit(ch) && ch != '-' && ch != '_' && ch != '.') {
                return false;
            }
        }
        return true;
    }

    public static final class PreparedWorkspace {
        private final String repositoryId;
        private final String owner;
        private final String repository;
        private final Path path;

        private PreparedWorkspace(String repositoryId, String owner, String repository, Path path) {
            if (!isUuidValue(repositoryId)) {
                throw new BizException("GITHUB_WORKSPACE_REPOSITORY_ID_INVALID");
            }
            this.repositoryId = repositoryId;
            this.owner = owner;
            this.repository = repository;
            this.path = path.toAbsolutePath().normalize();
        }

        public String repositoryId() {
            return repositoryId;
        }

        public String owner() {
            return owner;
        }

        public String repository() {
            return repository;
        }

        public Path path() {
            return path;
        }

        private static boolean isUuidValue(String value) {
            try {
                UUID.fromString(value);
                return true;
            } catch (IllegalArgumentException | NullPointerException e) {
                return false;
            }
        }
    }

    private static final class WorkspaceCleanupRuntimeException extends RuntimeException {
        private WorkspaceCleanupRuntimeException(IOException cause) {
            super(cause);
        }
    }
}
