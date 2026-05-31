package com.kama.jchatmind.integration.feishu;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class FeishuRepoResolver {

    private final FeishuProperties properties;

    public Optional<String> resolveRepoId(String repoKey) {
        if (!StringUtils.hasText(repoKey)) {
            return Optional.empty();
        }
        String trimmed = repoKey.trim();
        if (isUuid(trimmed)) {
            return Optional.of(trimmed);
        }

        Map<String, String> aliases = properties.getRepoAliases();
        if (aliases == null || aliases.isEmpty()) {
            return Optional.empty();
        }

        String repoId = aliases.get(trimmed);
        if (!StringUtils.hasText(repoId)) {
            repoId = aliases.get(trimmed.toLowerCase());
        }
        if (!StringUtils.hasText(repoId)) {
            return Optional.empty();
        }

        String normalizedRepoId = repoId.trim();
        return isUuid(normalizedRepoId) ? Optional.of(normalizedRepoId) : Optional.empty();
    }

    private boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
