package com.kama.jchatmind.github;

import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Applies the business allow-list to a parsed repository identity.
 */
@Component
public final class GithubRepositoryValidator {
    private static final Set<String> ALLOWED_HOSTS = Set.of("github.com");

    public void validate(GithubRepository repository) {
        if (repository == null) {
            throw new GithubRepositoryUrlException("null", "repository is missing");
        }
        String host = repository.host() == null
                ? ""
                : repository.host().toLowerCase(Locale.ROOT);
        if (!ALLOWED_HOSTS.contains(host)) {
            throw new GithubRepositoryUrlException(host, "host is not allowed");
        }
        validateSegment(repository.owner(), "owner");
        validateSegment(repository.repository(), "repository");
    }

    private void validateSegment(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new GithubRepositoryUrlException(value, field + " is blank");
        }
        if (value.equals(".") || value.equals("..") || containsWhitespace(value)
                || value.indexOf('\\') >= 0 || containsUnsupportedNameCharacter(value)) {
            throw new GithubRepositoryUrlException(value, field + " contains an unsafe path segment");
        }
    }

    private boolean containsWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsUnsupportedNameCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!Character.isLetterOrDigit(ch) && ch != '-' && ch != '_' && ch != '.') {
                return true;
            }
        }
        return false;
    }
}
