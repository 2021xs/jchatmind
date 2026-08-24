package com.kama.jchatmind.github;

/**
 * Parsed public GitHub repository identity.
 */
public record GithubRepository(String host, String owner, String repository) {
    public String fullName() {
        return owner + "/" + repository;
    }

    public String cloneUrl() {
        return "https://github.com/" + fullName() + ".git";
    }
}
