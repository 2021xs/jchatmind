package com.kama.jchatmind.github;

import com.kama.jchatmind.config.GithubImportProperties;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

@Component
@AllArgsConstructor
public class GitRepositoryCloner {
    private final GitCommandRunner commandRunner;
    private final GithubImportProperties properties;

    public CloneResult cloneRepository(String canonicalUrl, Path targetPath) {
        GitCommandRunner.GitCommandResult clone = commandRunner.run(
                List.of("git", "clone", "--depth", "1", "--single-branch", "--no-tags",
                        canonicalUrl, targetPath.toString()),
                null, properties.getCloneTimeout());
        ensureSuccessful(clone, "clone");

        GitCommandRunner.GitCommandResult commit = commandRunner.run(
                List.of("git", "rev-parse", "HEAD"), targetPath, properties.getCloneTimeout());
        ensureSuccessful(commit, "commit");
        String commitSha = commit.output().trim();
        if (commitSha.isBlank()) {
            throw new GithubCloneException("CLONE_FAILED", "Git clone did not return a commit SHA");
        }

        GitCommandRunner.GitCommandResult branch = commandRunner.run(
                List.of("git", "branch", "--show-current"), targetPath, properties.getCloneTimeout());
        String branchName = branch.succeeded() ? branch.output().trim() : null;
        return new CloneResult(branchName == null || branchName.isBlank() ? null : branchName, commitSha);
    }

    private void ensureSuccessful(GitCommandRunner.GitCommandResult result, String stage) {
        if (result.executableUnavailable()) {
            throw new GithubCloneException("GIT_COMMAND_UNAVAILABLE", "Git CLI is not available");
        }
        if (result.timedOut()) {
            throw new GithubCloneException("CLONE_TIMEOUT", "Git operation timed out");
        }
        if (result.interrupted()) {
            throw new GithubCloneException("CLONE_FAILED", "Git operation was interrupted");
        }
        if (!result.succeeded()) {
            throw new GithubCloneException(
                    "clone".equals(stage) ? "REPOSITORY_NOT_ACCESSIBLE" : "CLONE_FAILED",
                    "Git repository " + stage + " failed");
        }
    }

    public record CloneResult(String branch, String commitSha) {
    }
}
