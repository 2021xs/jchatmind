package com.kama.jchatmind.github;

public class GithubCloneException extends RuntimeException {
    private final String errorType;

    public GithubCloneException(String errorType, String message) {
        super(message);
        this.errorType = errorType;
    }

    public String getErrorType() {
        return errorType;
    }
}
