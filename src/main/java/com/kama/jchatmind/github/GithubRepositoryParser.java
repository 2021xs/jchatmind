package com.kama.jchatmind.github;

import java.net.URI;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Converts a repository URL into its structural domain representation.
 */
@Component
public final class GithubRepositoryParser {

    public GithubRepository parse(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new GithubRepositoryUrlException(rawUrl, "URL is blank");
        }

        String normalizedInput = rawUrl.trim();
        URI uri;
        try {
            uri = URI.create(normalizedInput);
        } catch (IllegalArgumentException e) {
            throw new GithubRepositoryUrlException(rawUrl, "URL syntax is invalid");
        }

        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new GithubRepositoryUrlException(rawUrl, "scheme must be https");
        }
        if (uri.isOpaque() || uri.getHost() == null) {
            throw new GithubRepositoryUrlException(rawUrl, "URL must contain a hostname and path");
        }
        if (uri.getUserInfo() != null) {
            throw new GithubRepositoryUrlException(rawUrl, "userinfo is not allowed");
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            throw new GithubRepositoryUrlException(rawUrl, "non-standard port is not allowed");
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw new GithubRepositoryUrlException(rawUrl, "query and fragment are not allowed");
        }

        String path = uri.getPath();
        if (path == null || path.isBlank()) {
            throw new GithubRepositoryUrlException(rawUrl, "repository path is missing");
        }
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (!path.startsWith("/")) {
            throw new GithubRepositoryUrlException(rawUrl, "repository path must be absolute");
        }

        String[] segments = path.substring(1).split("/", -1);
        if (segments.length != 2 || segments[0].isBlank() || segments[1].isBlank()) {
            throw new GithubRepositoryUrlException(rawUrl, "path must be /owner/repository");
        }

        String repository = segments[1];
        if (repository.endsWith(".git")) {
            repository = repository.substring(0, repository.length() - ".git".length());
        }
        if (repository.isBlank()) {
            throw new GithubRepositoryUrlException(rawUrl, "repository name is missing");
        }

        return new GithubRepository(
                uri.getHost().toLowerCase(Locale.ROOT),
                segments[0],
                repository);
    }
}
