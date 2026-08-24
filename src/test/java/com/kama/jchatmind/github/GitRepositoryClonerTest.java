package com.kama.jchatmind.github;

import com.kama.jchatmind.config.GithubImportProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitRepositoryClonerTest {
    @Test
    void usesShallowNonInteractiveCloneAndRecordsCommit() {
        FakeRunner runner = new FakeRunner(
                new GitCommandRunner.GitCommandResult(0, "cloned", false, false, false),
                new GitCommandRunner.GitCommandResult(0, "abc123\n", false, false, false),
                new GitCommandRunner.GitCommandResult(0, "main\n", false, false, false));
        GithubImportProperties properties = new GithubImportProperties();
        properties.setCloneTimeout(Duration.ofSeconds(4));
        GitRepositoryCloner cloner = new GitRepositoryCloner(runner, properties);

        GitRepositoryCloner.CloneResult result = cloner.cloneRepository(
                "https://github.com/user/repo.git", Path.of("workspace", "repo-id"));

        assertThat(result.commitSha()).isEqualTo("abc123");
        assertThat(result.branch()).isEqualTo("main");
        assertThat(runner.calls.get(0)).containsExactly(
                "git", "clone", "--depth", "1", "--single-branch", "--no-tags",
                "https://github.com/user/repo.git", "workspace\\repo-id");
        assertThat(runner.calls.get(1)).containsExactly("git", "rev-parse", "HEAD");
    }

    @Test
    void mapsTimeoutAndMissingGitToStableErrors() {
        GithubImportProperties properties = new GithubImportProperties();
        GitRepositoryCloner timeoutCloner = new GitRepositoryCloner(
                new FakeRunner(new GitCommandRunner.GitCommandResult(-1, "", true, false, false)), properties);
        assertThatThrownBy(() -> timeoutCloner.cloneRepository("https://github.com/u/r.git", Path.of("target")))
                .isInstanceOf(GithubCloneException.class)
                .hasMessage("Git operation timed out")
                .extracting("errorType").isEqualTo("CLONE_TIMEOUT");

        GitRepositoryCloner missingGitCloner = new GitRepositoryCloner(
                new FakeRunner(new GitCommandRunner.GitCommandResult(-1, "", false, true, false)), properties);
        assertThatThrownBy(() -> missingGitCloner.cloneRepository("https://github.com/u/r.git", Path.of("target")))
                .isInstanceOf(GithubCloneException.class)
                .extracting("errorType").isEqualTo("GIT_COMMAND_UNAVAILABLE");
    }

    private static class FakeRunner implements GitCommandRunner {
        private final List<GitCommandRunner.GitCommandResult> results;
        private final List<List<String>> calls = new ArrayList<>();

        private FakeRunner(GitCommandRunner.GitCommandResult... results) {
            this.results = List.of(results);
        }

        @Override
        public GitCommandRunner.GitCommandResult run(List<String> arguments, Path workingDirectory, Duration timeout) {
            calls.add(List.copyOf(arguments));
            return results.get(Math.min(calls.size() - 1, results.size() - 1));
        }
    }
}
