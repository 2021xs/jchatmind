package com.kama.jchatmind.agent.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.model.dto.CodeSearchResult;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CodeSearchEvidenceFormatter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RESULT_HEADER = "Selected code evidence:\n";
    private static final String MODEL_VIEW_MARKER = "MODEL_VIEW_BOUNDED: Some snippet detail was omitted "
            + "from model view; every selected evidence header and semantic skeleton below is retained.\n"
            + "Use getCodeChunk(repoId, chunkId) for exact source details when needed.\n\n";
    private static final Pattern RETURNED_COUNT = Pattern.compile("(?m)^returnedEvidenceCount=(\\d+)$");
    private static final Pattern BLOCK_START = Pattern.compile("(?m)^\\[(\\d+)]\\n(?=repoId: )");
    private static final Pattern LUA_RETURN_LINE = Pattern.compile("(?m)^\\s*return\\b[^\\r\\n]*");
    private static final List<String> ALLOWED_FIELDS =
            List.of("repoId", "chunkId", "file", "symbol", "type", "lines", "api");
    private static final Set<String> METHOD_CHUNK_TYPES = Set.of(
            "METHOD", "CONTROLLER_API", "SERVICE_METHOD", "MAPPER_METHOD", "JAVA_METHOD");

    public String format(List<CodeSearchResult> evidence) {
        StringBuilder out = new StringBuilder("Selected code evidence:\n");
        for (int i = 0; i < evidence.size(); i++) {
            if (i > 0) {
                out.append('\n');
            }
            appendEvidence(out, i + 1, evidence.get(i));
        }
        return out.toString();
    }

    /**
     * Strictly parses the formatter-owned search contract. The production novelty
     * count is required so label-like source lines cannot be guessed as evidence
     * boundaries. Ambiguous or malformed input is left to the existing guard.
     */
    public Optional<ParsedSearchResult> parseForProjection(String canonicalResult) {
        if (!StringUtils.hasText(canonicalResult)) {
            return Optional.empty();
        }
        Optional<String> decoded = decodeToolStringEnvelope(canonicalResult);
        if (decoded.isEmpty()) {
            return Optional.empty();
        }
        String normalized = decoded.get().replace("\r\n", "\n").replace('\r', '\n');
        int header = normalized.indexOf(RESULT_HEADER);
        if (header < 0) {
            return Optional.empty();
        }
        String feedback = normalized.substring(0, header);
        Matcher countMatcher = RETURNED_COUNT.matcher(feedback);
        if (!countMatcher.find()) {
            return Optional.empty();
        }
        int expectedCount;
        try {
            expectedCount = Integer.parseInt(countMatcher.group(1));
        } catch (NumberFormatException error) {
            return Optional.empty();
        }
        if (expectedCount <= 0) {
            return Optional.empty();
        }

        String evidenceSection = normalized.substring(header + RESULT_HEADER.length());
        Matcher blockMatcher = BLOCK_START.matcher(evidenceSection);
        List<BlockBoundary> boundaries = new ArrayList<>();
        while (blockMatcher.find()) {
            boundaries.add(new BlockBoundary(Integer.parseInt(blockMatcher.group(1)), blockMatcher.start()));
        }
        if (boundaries.size() != expectedCount || !evidenceSection.substring(0,
                boundaries.isEmpty() ? evidenceSection.length() : boundaries.get(0).start()).isBlank()) {
            return Optional.empty();
        }

        List<ParsedEvidence> evidence = new ArrayList<>();
        for (int index = 0; index < boundaries.size(); index++) {
            BlockBoundary boundary = boundaries.get(index);
            if (boundary.index() != index + 1) {
                return Optional.empty();
            }
            int end = index + 1 < boundaries.size()
                    ? boundaries.get(index + 1).start() : evidenceSection.length();
            Optional<ParsedEvidence> parsed = parseEvidence(
                    evidenceSection.substring(boundary.start(), end), boundary.index());
            if (parsed.isEmpty()) {
                return Optional.empty();
            }
            evidence.add(parsed.get());
        }
        return Optional.of(new ParsedSearchResult(feedback, List.copyOf(evidence)));
    }

    /**
     * Renders the projected search view as mandatory header, chunk-aware semantic
     * skeleton, then optional bounded detail. The skeleton is deterministic and
     * never uses another relevance model.
     */
    public ProjectedSearchResult renderProjected(ParsedSearchResult parsed, int maxSnippetCodePoints) {
        int snippetLimit = Math.max(0, maxSnippetCodePoints);
        StringBuilder out = new StringBuilder();
        appendCompactFeedback(out, parsed.feedback());
        out.append(MODEL_VIEW_MARKER).append(RESULT_HEADER);
        boolean reduced = false;
        for (ParsedEvidence evidence : parsed.evidence()) {
            out.append('\n').append('[').append(evidence.index()).append("]\n");
            for (Map.Entry<String, String> field : evidence.fields().entrySet()) {
                out.append(field.getKey()).append(": ").append(field.getValue()).append('\n');
            }
            SemanticSkeleton skeleton = semanticSkeleton(evidence);
            if (!skeleton.lines().isEmpty()) {
                out.append("\nsemanticSkeleton:\n")
                        .append(skeleton.kind()).append(":\n");
                skeleton.lines().forEach(line -> out.append(line).append('\n'));
            }
            if (evidence.snippet() != null) {
                out.append("\nsnippet:\n");
                BoundedSnippet bounded = boundedSnippet(evidence.snippet(), snippetLimit);
                out.append(bounded.value()).append('\n');
                reduced |= bounded.reduced();
            }
        }
        return new ProjectedSearchResult(out.toString(), reduced, parsed.evidence().size());
    }

    public int maximumSnippetCodePoints(ParsedSearchResult parsed) {
        return parsed.evidence().stream()
                .map(ParsedEvidence::snippet)
                .filter(value -> value != null)
                .mapToInt(value -> value.codePointCount(0, value.length()))
                .max()
                .orElse(0);
    }

    private Optional<String> decodeToolStringEnvelope(String result) {
        String trimmed = result.trim();
        if (!trimmed.startsWith("\"")) {
            return Optional.of(result);
        }
        if (!trimmed.endsWith("\"")) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(JSON.readValue(trimmed, String.class));
        } catch (JsonProcessingException error) {
            return Optional.empty();
        }
    }

    private Optional<ParsedEvidence> parseEvidence(String block, int expectedIndex) {
        String value = stripTrailingNewlines(block);
        String header = "[" + expectedIndex + "]\n";
        if (!value.startsWith(header)) {
            return Optional.empty();
        }
        String body = value.substring(header.length());
        int snippetMarker = body.indexOf("\nsnippet:\n");
        String fieldsText = snippetMarker < 0 ? body : body.substring(0, snippetMarker);
        String snippet = snippetMarker < 0 ? null : stripTrailingNewlines(
                body.substring(snippetMarker + "\nsnippet:\n".length()));
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        for (String line : fieldsText.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            int separator = line.indexOf(": ");
            if (separator <= 0) {
                return Optional.empty();
            }
            String name = line.substring(0, separator);
            String fieldValue = line.substring(separator + 2);
            if (!ALLOWED_FIELDS.contains(name) || fields.putIfAbsent(name, fieldValue) != null) {
                return Optional.empty();
            }
        }
        if (!StringUtils.hasText(fields.get("repoId")) || !StringUtils.hasText(fields.get("chunkId"))) {
            return Optional.empty();
        }
        return Optional.of(new ParsedEvidence(expectedIndex, fields, snippet));
    }

    private SemanticSkeleton semanticSkeleton(ParsedEvidence evidence) {
        if (!StringUtils.hasText(evidence.snippet())) {
            return SemanticSkeleton.empty();
        }
        String chunkType = evidence.fields().getOrDefault("type", "").toUpperCase();
        if ("CLASS_SUMMARY".equals(chunkType)) {
            return classMethodInventory(evidence.snippet());
        }
        if ("LUA_SCRIPT".equals(chunkType)) {
            return luaReturnSurface(evidence.snippet());
        }
        if (METHOD_CHUNK_TYPES.contains(chunkType)) {
            return methodSurface(evidence.snippet());
        }
        return SemanticSkeleton.empty();
    }

    private SemanticSkeleton classMethodInventory(String snippet) {
        List<String> methods = snippet.lines()
                .map(String::strip)
                .filter(line -> line.startsWith("methods:"))
                .toList();
        return methods.isEmpty()
                ? SemanticSkeleton.empty()
                : new SemanticSkeleton("CLASS_METHOD_INVENTORY", methods);
    }

    private SemanticSkeleton luaReturnSurface(String snippet) {
        Matcher matcher = LUA_RETURN_LINE.matcher(snippet);
        List<String> returns = new ArrayList<>();
        while (matcher.find()) {
            returns.add(matcher.group().strip());
        }
        return returns.isEmpty()
                ? SemanticSkeleton.empty()
                : new SemanticSkeleton("LUA_RETURN_SURFACE", List.copyOf(returns));
    }

    private SemanticSkeleton methodSurface(String snippet) {
        List<String> sourceLines = snippet.lines().map(String::stripTrailing).toList();
        LinkedHashSet<String> surface = new LinkedHashSet<>();
        int openingBrace = -1;
        for (int index = 0; index < sourceLines.size(); index++) {
            String line = sourceLines.get(index);
            if (!line.isBlank()) {
                surface.add(line);
            }
            if (line.contains("{")) {
                openingBrace = index;
                break;
            }
        }
        if (openingBrace < 0) {
            return surface.isEmpty()
                    ? SemanticSkeleton.empty()
                    : new SemanticSkeleton("METHOD_SURFACE", List.copyOf(surface));
        }
        int firstBody = firstSubstantiveBodyLine(sourceLines, openingBrace + 1, sourceLines.size());
        int lastBody = lastSubstantiveBodyLine(sourceLines, sourceLines.size() - 1, openingBrace + 1);
        if (firstBody >= 0) {
            surface.add(sourceLines.get(firstBody).strip());
        }
        if (lastBody >= 0 && lastBody != firstBody) {
            if (lastBody - firstBody > 1) {
                surface.add("[METHOD_BODY_BOUNDED]");
            }
            surface.add(sourceLines.get(lastBody).strip());
        }
        return new SemanticSkeleton("METHOD_SURFACE", List.copyOf(surface));
    }

    private int firstSubstantiveBodyLine(List<String> lines, int start, int end) {
        for (int index = start; index < end; index++) {
            String line = lines.get(index).strip();
            if (!line.isEmpty() && !"}".equals(line)) {
                return index;
            }
        }
        return -1;
    }

    private int lastSubstantiveBodyLine(List<String> lines, int start, int minimum) {
        for (int index = start; index >= minimum; index--) {
            String line = lines.get(index).strip();
            if (!line.isEmpty() && !"}".equals(line)) {
                return index;
            }
        }
        return -1;
    }

    private void appendCompactFeedback(StringBuilder out, String feedback) {
        for (String line : feedback.split("\n")) {
            if (line.startsWith("returnedEvidenceCount=")
                    || line.startsWith("newEvidenceCount=")
                    || line.startsWith("duplicateEvidenceCount=")) {
                out.append(line).append('\n');
            }
        }
        if (!out.isEmpty()) {
            out.append('\n');
        }
    }

    private BoundedSnippet boundedSnippet(String snippet, int limit) {
        int originalChars = snippet.codePointCount(0, snippet.length());
        if (originalChars <= limit) {
            return new BoundedSnippet(snippet, false);
        }
        if (limit == 0) {
            return new BoundedSnippet("[SNIPPET_OMITTED_FROM_MODEL_VIEW: originalChars="
                    + originalChars + "]", true);
        }
        int headChars = (limit + 1) / 2;
        int tailChars = limit - headChars;
        int headEnd = snippet.offsetByCodePoints(0, headChars);
        int tailStart = snippet.offsetByCodePoints(0, originalChars - tailChars);
        String marker = "\n...[SNIPPET_BOUNDED: originalChars=" + originalChars
                + ", shownChars=" + limit + "]...\n";
        return new BoundedSnippet(snippet.substring(0, headEnd) + marker + snippet.substring(tailStart), true);
    }

    private String stripTrailingNewlines(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '\n') {
            end--;
        }
        return value.substring(0, end);
    }

    private void appendEvidence(StringBuilder out, int index, CodeSearchResult evidence) {
        requireStableLocator(evidence);
        out.append('\n').append('[').append(index).append("]\n");
        appendField(out, "repoId", evidence.getRepoId());
        appendField(out, "chunkId", evidence.getChunkId());
        appendField(out, "file", evidence.getFilePath());
        appendField(out, "symbol", evidence.getSymbolName());
        appendField(out, "type", evidence.getChunkType());
        appendField(out, "lines", lineRange(evidence));
        appendField(out, "api", api(evidence));
        if (StringUtils.hasText(evidence.getContentPreview())) {
            out.append("\nsnippet:\n").append(evidence.getContentPreview()).append('\n');
        }
    }

    private void requireStableLocator(CodeSearchResult evidence) {
        if (evidence == null
                || !StringUtils.hasText(evidence.getRepoId())
                || !StringUtils.hasText(evidence.getChunkId())) {
            throw new IllegalArgumentException("Selected code evidence is missing repoId or chunkId");
        }
    }

    private void appendField(StringBuilder out, String name, String value) {
        if (StringUtils.hasText(value)) {
            out.append(name).append(": ").append(value).append('\n');
        }
    }

    private String lineRange(CodeSearchResult evidence) {
        if (evidence.getStartLine() == null) {
            return null;
        }
        if (evidence.getEndLine() == null) {
            return evidence.getStartLine().toString();
        }
        return evidence.getStartLine() + "-" + evidence.getEndLine();
    }

    private String api(CodeSearchResult evidence) {
        boolean hasMethod = StringUtils.hasText(evidence.getHttpMethod());
        boolean hasPath = StringUtils.hasText(evidence.getApiPath());
        if (!hasMethod && !hasPath) {
            return null;
        }
        if (!hasMethod) {
            return evidence.getApiPath();
        }
        if (!hasPath) {
            return evidence.getHttpMethod();
        }
        return evidence.getHttpMethod() + " " + evidence.getApiPath();
    }

    public record ParsedSearchResult(String feedback, List<ParsedEvidence> evidence) {
    }

    public record ParsedEvidence(int index, LinkedHashMap<String, String> fields, String snippet) {
        public ParsedEvidence {
            fields = new LinkedHashMap<>(fields);
        }
    }

    public record ProjectedSearchResult(String value, boolean detailReduced, int evidenceCount) {
    }

    private record SemanticSkeleton(String kind, List<String> lines) {
        private static SemanticSkeleton empty() {
            return new SemanticSkeleton("NONE", List.of());
        }
    }

    private record BlockBoundary(int index, int start) {
    }

    private record BoundedSnippet(String value, boolean reduced) {
    }
}
