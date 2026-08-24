package com.kama.jchatmind.agent;

import com.kama.jchatmind.model.dto.CodeSearchResult;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ephemeral evidence coverage for one Agent task. Nothing in this state is persisted.
 */
public final class TaskEvidenceState {
    public static final String TOOL_CONTEXT_KEY = TaskEvidenceState.class.getName();
    public static final String TASK_ID_TOOL_CONTEXT_KEY = TaskEvidenceState.class.getName() + ".taskId";
    public static final String CODE_SEARCH_TOOL_NAME = "searchProjectCode";
    private static final int NO_NOVELTY_GUARD_THRESHOLD = 2;

    private final Set<String> seenChunkIds = new LinkedHashSet<>();
    private final Set<String> seenSymbols = new LinkedHashSet<>();
    private final Map<FileKey, FileCoverage> coveredFiles = new LinkedHashMap<>();
    private SearchObservation lastSearch;
    private int consecutiveNoNoveltySearches;
    private int searchCallCount;
    private int guardedSearchRequestCount;

    public synchronized SearchObservation observeSearch(String requestedRepoId,
                                                        String query,
                                                        List<CodeSearchResult> evidence) {
        List<CodeSearchResult> returned = evidence == null ? List.of() : evidence;
        int newEvidenceCount = 0;
        List<String> newFiles = new ArrayList<>();
        List<String> newSymbols = new ArrayList<>();

        for (CodeSearchResult item : returned) {
            if (item == null) {
                continue;
            }
            String repoId = firstText(item.getRepoId(), requestedRepoId, "<unknown-repo>");
            String filePath = normalizedText(item.getFilePath());
            String symbol = normalizedText(item.getSymbolName());
            String chunkId = normalizedText(item.getChunkId());
            FileKey fileKey = filePath == null ? null : new FileKey(repoId, filePath);
            FileCoverage coverage = fileKey == null ? null : coveredFiles.get(fileKey);
            LineRange range = LineRange.from(item.getStartLine(), item.getEndLine());

            boolean exactChunkDuplicate = chunkId != null && seenChunkIds.contains(chunkKey(repoId, chunkId));
            boolean newFile = fileKey != null && coverage == null;
            boolean newSymbol = symbol != null && !seenSymbols.contains(symbolKey(repoId, filePath, symbol));
            boolean uncoveredRange = range != null && (coverage == null || !coverage.covers(range));
            boolean newChunkWithoutRange = range == null && chunkId != null && !exactChunkDuplicate;
            boolean noStableIdentity = fileKey == null && symbol == null && chunkId == null;

            boolean novel = !exactChunkDuplicate
                    && (newFile || newSymbol || uncoveredRange || newChunkWithoutRange || noStableIdentity);
            if (novel) {
                newEvidenceCount++;
                if (newFile) {
                    newFiles.add(filePath);
                }
                if (newSymbol) {
                    newSymbols.add(symbol);
                }
            }

            if (chunkId != null) {
                seenChunkIds.add(chunkKey(repoId, chunkId));
            }
            if (symbol != null) {
                seenSymbols.add(symbolKey(repoId, filePath, symbol));
            }
            if (fileKey != null) {
                FileCoverage updated = coveredFiles.computeIfAbsent(fileKey, ignored -> new FileCoverage());
                updated.addSymbol(symbol);
                updated.addRange(range);
            }
        }

        int returnedEvidenceCount = (int) returned.stream().filter(item -> item != null).count();
        int duplicateEvidenceCount = returnedEvidenceCount - newEvidenceCount;
        searchCallCount++;
        if (newEvidenceCount == 0) {
            consecutiveNoNoveltySearches++;
        } else {
            consecutiveNoNoveltySearches = 0;
        }
        lastSearch = new SearchObservation(
                searchCallCount,
                query,
                returnedEvidenceCount,
                newEvidenceCount,
                duplicateEvidenceCount,
                List.copyOf(newFiles),
                List.copyOf(newSymbols),
                consecutiveNoNoveltySearches,
                isCodeSearchBlockedInternal());
        return lastSearch;
    }

    public synchronized boolean isCodeSearchBlocked() {
        return isCodeSearchBlockedInternal();
    }

    public synchronized void recordGuardedSearchRequest() {
        guardedSearchRequestCount++;
    }

    public synchronized Snapshot snapshot() {
        List<CoverageSummary> coverage = coveredFiles.entrySet().stream()
                .map(entry -> new CoverageSummary(
                        entry.getKey().repoId(),
                        entry.getKey().filePath(),
                        entry.getValue().rangeSummary(),
                        List.copyOf(entry.getValue().symbols)))
                .toList();
        return new Snapshot(
                searchCallCount,
                guardedSearchRequestCount,
                consecutiveNoNoveltySearches,
                isCodeSearchBlockedInternal(),
                lastSearch,
                coverage);
    }

    public synchronized void reset() {
        seenChunkIds.clear();
        seenSymbols.clear();
        coveredFiles.clear();
        lastSearch = null;
        consecutiveNoNoveltySearches = 0;
        searchCallCount = 0;
        guardedSearchRequestCount = 0;
    }

    private boolean isCodeSearchBlockedInternal() {
        return consecutiveNoNoveltySearches >= NO_NOVELTY_GUARD_THRESHOLD;
    }

    private String chunkKey(String repoId, String chunkId) {
        return repoId + '\u0000' + chunkId;
    }

    private String symbolKey(String repoId, String filePath, String symbol) {
        return repoId + '\u0000' + (filePath == null ? "" : filePath) + '\u0000' + symbol;
    }

    private String firstText(String first, String second, String fallback) {
        String normalizedFirst = normalizedText(first);
        if (normalizedFirst != null) {
            return normalizedFirst;
        }
        String normalizedSecond = normalizedText(second);
        return normalizedSecond == null ? fallback : normalizedSecond;
    }

    private String normalizedText(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public record SearchObservation(
            int searchCallNumber,
            String query,
            int returnedEvidenceCount,
            int newEvidenceCount,
            int duplicateEvidenceCount,
            List<String> newFiles,
            List<String> newSymbols,
            int consecutiveNoNoveltySearches,
            boolean guardActive) {

        public String toToolFeedback() {
            return "Code evidence novelty:\n"
                    + "returnedEvidenceCount=" + returnedEvidenceCount + "\n"
                    + "newEvidenceCount=" + newEvidenceCount + "\n"
                    + "duplicateEvidenceCount=" + duplicateEvidenceCount + "\n"
                    + "newFiles=" + newFiles + "\n"
                    + "newSymbols=" + newSymbols;
        }
    }

    public record Snapshot(
            int searchCallCount,
            int guardedSearchRequestCount,
            int consecutiveNoNoveltySearches,
            boolean codeSearchBlocked,
            SearchObservation lastSearch,
            List<CoverageSummary> coverage) {

        public String compactCoverage(int maxFiles) {
            if (coverage.isEmpty()) {
                return "- none";
            }
            int limit = Math.max(1, maxFiles);
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < coverage.size() && i < limit; i++) {
                CoverageSummary item = coverage.get(i);
                out.append("- file: ").append(item.filePath());
                if (StringUtils.hasText(item.lineRanges())) {
                    out.append("; lines: ").append(item.lineRanges());
                }
                if (!item.symbols().isEmpty()) {
                    out.append("; symbols: ").append(item.symbols());
                }
                out.append('\n');
            }
            if (coverage.size() > limit) {
                out.append("- ... ").append(coverage.size() - limit).append(" more covered files\n");
            }
            return out.toString().stripTrailing();
        }
    }

    public record CoverageSummary(String repoId, String filePath, String lineRanges, List<String> symbols) {
    }

    private record FileKey(String repoId, String filePath) {
    }

    private record LineRange(int start, int end) {
        private static LineRange from(Integer start, Integer end) {
            if (start == null && end == null) {
                return null;
            }
            int normalizedStart = start == null ? end : start;
            int normalizedEnd = end == null ? normalizedStart : end;
            return normalizedStart <= normalizedEnd
                    ? new LineRange(normalizedStart, normalizedEnd)
                    : new LineRange(normalizedEnd, normalizedStart);
        }
    }

    private static final class FileCoverage {
        private final List<LineRange> ranges = new ArrayList<>();
        private final Set<String> symbols = new LinkedHashSet<>();

        private boolean covers(LineRange candidate) {
            return ranges.stream().anyMatch(existing -> existing.start <= candidate.start && existing.end >= candidate.end);
        }

        private void addSymbol(String symbol) {
            if (symbol != null) {
                symbols.add(symbol);
            }
        }

        private void addRange(LineRange range) {
            if (range == null) {
                return;
            }
            ranges.add(range);
            ranges.sort(Comparator.comparingInt(LineRange::start));
            List<LineRange> merged = new ArrayList<>();
            for (LineRange current : ranges) {
                if (merged.isEmpty()) {
                    merged.add(current);
                    continue;
                }
                LineRange previous = merged.get(merged.size() - 1);
                if ((long) current.start <= (long) previous.end + 1L) {
                    merged.set(merged.size() - 1,
                            new LineRange(previous.start, Math.max(previous.end, current.end)));
                } else {
                    merged.add(current);
                }
            }
            ranges.clear();
            ranges.addAll(merged);
        }

        private String rangeSummary() {
            return ranges.stream()
                    .map(range -> range.start == range.end
                            ? Integer.toString(range.start)
                            : range.start + "-" + range.end)
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("");
        }
    }
}
