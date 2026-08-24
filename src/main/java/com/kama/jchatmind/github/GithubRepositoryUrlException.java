package com.kama.jchatmind.github;

public class GithubRepositoryUrlException extends IllegalArgumentException {
    public GithubRepositoryUrlException(String rawUrl, String reason) {
        super("Invalid GitHub repository URL [" + rawUrl + "]: " + reason);
    }
}
