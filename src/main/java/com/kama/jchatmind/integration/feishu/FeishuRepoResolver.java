package com.kama.jchatmind.integration.feishu;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class FeishuRepoResolver {

    private static final Charset GBK = Charset.forName("GBK");

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

        String repoId = resolveAlias(aliases, trimmed);
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

    private String resolveAlias(Map<String, String> aliases, String repoKey) {
        for (String candidate : aliasCandidates(repoKey)) {
            String repoId = aliases.get(candidate);
            if (StringUtils.hasText(repoId)) {
                return repoId;
            }
            repoId = aliases.get(candidate.toLowerCase());
            if (StringUtils.hasText(repoId)) {
                return repoId;
            }
        }
        return null;
    }

    private Set<String> aliasCandidates(String repoKey) {
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(repoKey);
        String repaired = repairUtf8DecodedAsGbk(repoKey);
        if (StringUtils.hasText(repaired)) {
            candidates.add(repaired);
        }
        return candidates;
    }

    private String repairUtf8DecodedAsGbk(String value) {
        try {
            String repaired = new String(value.getBytes(GBK), StandardCharsets.UTF_8);
            return repaired.equals(value) ? null : repaired;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
