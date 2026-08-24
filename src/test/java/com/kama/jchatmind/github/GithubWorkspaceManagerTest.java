package com.kama.jchatmind.github;

import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.config.GithubImportProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GithubWorkspaceManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void createsRepositoryNamedWorkspaceBesideExistingLocalRepositoryAndCleansOnlyOwnedPath() throws Exception {
        GithubImportProperties properties = new GithubImportProperties();
        properties.setWorkspaceRoot(tempDir.toString());
        CodeRagProperties codeRagProperties = new CodeRagProperties();
        codeRagProperties.setAllowedRoots(List.of(tempDir.toString()));
        GithubWorkspaceManager manager = new GithubWorkspaceManager(properties, codeRagProperties);
        String repositoryId = UUID.randomUUID().toString();
        Path flashDeal = tempDir.resolve("FlashDeal");
        Files.createDirectories(flashDeal);
        Files.writeString(flashDeal.resolve("marker.txt"), "keep");

        GithubWorkspaceManager.PreparedWorkspace workspace = manager.prepare(
                repositoryId, "Spring-Projects", "Spring-Boot");
        Path target = workspace.path();
        Files.writeString(target.resolve("source.java"), "class Source {}");
        assertThat(target.getFileName().toString()).isEqualTo("spring-projects--spring-boot");
        assertThat(manager.isManagedWorkspace(workspace)).isTrue();

        manager.cleanup(workspace);

        assertThat(Files.exists(target)).isFalse();
        assertThat(Files.readString(flashDeal.resolve("marker.txt"))).isEqualTo("keep");
    }

    @Test
    void rejectsExistingRepositoryDirectoryWithoutOverwritingIt() throws Exception {
        GithubImportProperties properties = new GithubImportProperties();
        properties.setWorkspaceRoot(tempDir.toString());
        CodeRagProperties codeRagProperties = new CodeRagProperties();
        codeRagProperties.setAllowedRoots(List.of(tempDir.toString()));
        GithubWorkspaceManager manager = new GithubWorkspaceManager(properties, codeRagProperties);
        Path existing = tempDir.resolve("spring-projects--spring-boot");
        Files.createDirectories(existing);
        Files.writeString(existing.resolve("marker.txt"), "keep");

        assertThatThrownBy(() -> manager.prepare(
                UUID.randomUUID().toString(), "Spring-Projects", "Spring-Boot"))
                .isInstanceOf(com.kama.jchatmind.exception.BizException.class)
                .hasMessage("GITHUB_WORKSPACE_ALREADY_EXISTS");
        assertThat(Files.readString(existing.resolve("marker.txt"))).isEqualTo("keep");
    }

    @Test
    void restoresOnlyPathMatchingCanonicalGithubIdentity() throws Exception {
        GithubImportProperties properties = new GithubImportProperties();
        properties.setWorkspaceRoot(tempDir.toString());
        CodeRagProperties codeRagProperties = new CodeRagProperties();
        codeRagProperties.setAllowedRoots(List.of(tempDir.toString()));
        GithubWorkspaceManager manager = new GithubWorkspaceManager(properties, codeRagProperties);
        String repositoryId = UUID.randomUUID().toString();
        Path expected = tempDir.resolve("yangzongzhuan--ruoyi-cloud");
        Files.createDirectory(expected);

        GithubWorkspaceManager.PreparedWorkspace restored = manager.restore(
                repositoryId,
                "https://github.com/yangzongzhuan/RuoYi-Cloud.git",
                expected);

        assertThat(manager.isManagedWorkspace(restored)).isTrue();
        assertThatThrownBy(() -> manager.restore(
                repositoryId,
                "https://github.com/yangzongzhuan/RuoYi-Cloud.git",
                tempDir.resolve("unrelated--repository")))
                .isInstanceOf(com.kama.jchatmind.exception.BizException.class)
                .hasMessage("GITHUB_WORKSPACE_PATH_REJECTED");
    }

    @Test
    void restoresLegacyUuidWorkspaceWithoutMigratingIt() throws Exception {
        GithubImportProperties properties = new GithubImportProperties();
        properties.setWorkspaceRoot(tempDir.toString());
        CodeRagProperties codeRagProperties = new CodeRagProperties();
        codeRagProperties.setAllowedRoots(List.of(tempDir.toString()));
        GithubWorkspaceManager manager = new GithubWorkspaceManager(properties, codeRagProperties);
        String repositoryId = UUID.randomUUID().toString();
        Path legacyPath = tempDir.resolve(repositoryId);
        Files.createDirectory(legacyPath);

        GithubWorkspaceManager.PreparedWorkspace restored = manager.restore(
                repositoryId,
                "https://github.com/example/legacy-repository.git",
                legacyPath);

        assertThat(restored.path()).isEqualTo(legacyPath.toAbsolutePath().normalize());
        assertThat(manager.isManagedWorkspace(restored)).isTrue();
    }
}
