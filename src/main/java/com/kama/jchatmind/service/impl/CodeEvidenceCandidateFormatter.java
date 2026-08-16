package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.model.dto.CodeEvidenceCandidateCard;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;

final class CodeEvidenceCandidateFormatter {
    static final int MAX_SNIPPET_CHARS = 420;
    private static final int MAX_SIGNALS_CHARS = 280;
    private static final int MAX_SIGNAL_VALUE_CHARS = 96;
    private static final int MAX_FALLBACK_SIGNAL_CHARS = 160;
    private static final String TRUNCATION_MARKER = "\n...[truncated]";
    private static final List<SignalSpec> SIGNALS = List.of(
            new SignalSpec("sqlId", List.of("sqlId", "fullSqlId")),
            new SignalSpec("namespace", List.of("namespace", "mapperClass")),
            new SignalSpec("tables", List.of("tables", "table")),
            new SignalSpec("methods", List.of("methods")),
            new SignalSpec("fields", List.of("fields")),
            new SignalSpec("fieldTypes", List.of("fieldTypes")),
            new SignalSpec("symbols", List.of("symbols")),
            new SignalSpec("literals", List.of("literalValues")),
            new SignalSpec("symbolTypes", List.of("symbolTypes")),
            new SignalSpec("annotations", List.of("annotations")),
            new SignalSpec("redisKeys", List.of("redisKeys", "redisKey")),
            new SignalSpec("redisCommands", List.of("redisCommands")),
            new SignalSpec("redisArgs", List.of("redisArgs")),
            new SignalSpec("returnCodes", List.of("returnCodes")),
            new SignalSpec("dynamicTags", List.of("dynamicTags")),
            new SignalSpec("includeRefs", List.of("includeRefs")),
            new SignalSpec("relatedSqlId", List.of("relatedSqlId")),
            new SignalSpec("relatedSymbol", List.of("relatedSymbol")),
            new SignalSpec("scriptName", List.of("scriptName"))
    );

    private final ObjectMapper objectMapper;

    CodeEvidenceCandidateFormatter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String format(String candidateId, CodeEvidenceCandidateCard candidate) {
        StringBuilder builder = new StringBuilder();
        builder.append('[').append(candidateId).append("]\n");
        append(builder, "file", candidate.getFilePath());
        append(builder, "symbol", candidate.getSymbolName());
        append(builder, "type", candidate.getChunkType());
        if (validLine(candidate.getStartLine()) && validLine(candidate.getEndLine())) {
            builder.append("lines: ").append(candidate.getStartLine()).append('-')
                    .append(candidate.getEndLine()).append('\n');
        }
        append(builder, "api", api(candidate));
        append(builder, "signals", compactSignals(candidate));
        String snippet = truncate(normalizeLineEndings(candidate.getSnippet()), MAX_SNIPPET_CHARS);
        if (!snippet.isBlank()) {
            builder.append("snippet:\n").append(snippet).append('\n');
        }
        return builder.append('\n').toString();
    }

    String compactSignals(CodeEvidenceCandidateCard candidate) {
        String metadata = candidate.getMetadataSummary();
        if (metadata == null || metadata.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(metadata);
            if (root == null || !root.isObject()) {
                return fallbackSignals(metadata, candidate);
            }
            StringJoiner signals = new StringJoiner("; ");
            for (SignalSpec signal : SIGNALS) {
                String value = firstValue(root, signal.sourceKeys(), candidate);
                if (!value.isBlank()) {
                    signals.add(signal.outputKey() + '=' + value);
                }
            }
            return truncateSingleLine(signals.toString(), MAX_SIGNALS_CHARS);
        } catch (Exception ignored) {
            return fallbackSignals(metadata, candidate);
        }
    }

    private String firstValue(JsonNode root, List<String> keys, CodeEvidenceCandidateCard candidate) {
        for (String key : keys) {
            String value = formatValue(root.get(key), candidate);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String formatValue(JsonNode node, CodeEvidenceCandidateCard candidate) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isArray()) {
            Set<String> values = new LinkedHashSet<>();
            for (JsonNode item : node) {
                String value = scalar(item);
                if (!value.isBlank() && !isDuplicate(value, candidate)) {
                    values.add(value);
                }
            }
            return truncateSingleLine(String.join(",", values), MAX_SIGNAL_VALUE_CHARS);
        }
        String value = scalar(node);
        return isDuplicate(value, candidate) ? "" : truncateSingleLine(value, MAX_SIGNAL_VALUE_CHARS);
    }

    private String scalar(JsonNode node) {
        if (node == null || node.isNull() || node.isContainerNode()) {
            return "";
        }
        return normalizeWhitespace(node.asText());
    }

    private boolean isDuplicate(String value, CodeEvidenceCandidateCard candidate) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        List<String> displayedValues = new ArrayList<>();
        displayedValues.add(candidate.getFilePath());
        displayedValues.add(candidate.getSymbolName());
        displayedValues.add(candidate.getChunkType());
        displayedValues.add(candidate.getApiPath());
        displayedValues.add(candidate.getHttpMethod());
        return displayedValues.stream().filter(item -> item != null && !item.isBlank())
                .map(item -> item.trim().toLowerCase(Locale.ROOT))
                .anyMatch(normalized::equals);
    }

    private String fallbackSignals(String metadata, CodeEvidenceCandidateCard candidate) {
        String value = normalizeWhitespace(metadata);
        if (isDuplicate(value, candidate)) {
            return "";
        }
        return truncateSingleLine(value, MAX_FALLBACK_SIGNAL_CHARS);
    }

    private String api(CodeEvidenceCandidateCard candidate) {
        String method = normalizeWhitespace(candidate.getHttpMethod());
        String path = normalizeWhitespace(candidate.getApiPath());
        if (method.isBlank()) {
            return path;
        }
        if (path.isBlank()) {
            return method;
        }
        return method + ' ' + path;
    }

    private void append(StringBuilder builder, String label, String value) {
        String normalized = normalizeWhitespace(value);
        if (!normalized.isBlank()) {
            builder.append(label).append(": ").append(normalized).append('\n');
        }
    }

    private boolean validLine(Integer line) {
        return line != null && line > 0;
    }

    private String normalizeLineEndings(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private String normalizeWhitespace(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String truncateSingleLine(String value, int maxChars) {
        return truncate(normalizeWhitespace(value), maxChars).replace('\n', ' ');
    }

    private String truncate(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars - TRUNCATION_MARKER.length()) + TRUNCATION_MARKER;
    }

    private record SignalSpec(String outputKey, List<String> sourceKeys) {
    }
}
