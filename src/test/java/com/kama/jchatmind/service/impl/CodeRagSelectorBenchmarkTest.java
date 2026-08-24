package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.mapper.CodeRepositoryMapper;
import com.kama.jchatmind.model.dto.CodeEvidenceCandidateCard;
import com.kama.jchatmind.model.dto.CodeEvidenceSelectionResult;
import com.kama.jchatmind.model.dto.CodeRagExecutionResult;
import com.kama.jchatmind.model.dto.CodeSearchResult;
import com.kama.jchatmind.model.entity.CodeRepository;
import com.kama.jchatmind.service.CodeRagAnswerEvidenceService;
import com.kama.jchatmind.service.CodeSearchService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Real Code RAG selector benchmark. Disabled unless explicitly enabled because it calls external services.
 */
@Tag("rag-selector-benchmark")
@EnabledIf("benchmarkEnabled")
@SpringBootTest
class CodeRagSelectorBenchmarkTest {
    private static final String REPOSITORY_NAME = "FlashDeal";
    private static final String QUERY = "详细介绍一下";
    private static final int[] RAW_TOP_K_CASES = {50, 20};
    private static final long SHUFFLE_SEED = 20260821L;
    private static final Path OUTPUT_DIR = Path.of("target", "eval");

    @Autowired
    private CodeRagAnswerEvidenceService answerEvidenceService;

    @Autowired
    private CodeSearchService codeSearchService;

    @Autowired
    private CodeLlmEvidenceSelector selector;

    @Autowired
    private CodeRepositoryMapper codeRepositoryMapper;

    @Autowired
    private CodeRagProperties properties;

    @Test
    void benchmarkRawTopKVariants() throws Exception {
        CodeRepository repository = resolveRepository();
        int runs = Math.max(20, Integer.getInteger("code.rag.selector.benchmark.runs", 20));
        int directRuns = Math.max(1, Integer.getInteger("code.rag.selector.benchmark.direct-runs", 3));
        int originalRawTopK = properties.getAnswerEvidence().getRawTopK();
        List<Record> records = new ArrayList<>();
        List<Integer> executionOrder = interleavedOrder(runs);
        Map<Integer, Integer> caseRunCounts = new LinkedHashMap<>();
        try {
            for (int runIndex = 1; runIndex <= executionOrder.size(); runIndex++) {
                int rawTopK = executionOrder.get(runIndex - 1);
                properties.getAnswerEvidence().setRawTopK(rawTopK);
                int caseRun = caseRunCounts.merge(rawTopK, 1, Integer::sum);
                CodeRagExecutionResult execution = answerEvidenceService.execute(repository.getId(), QUERY);
                assertNotNull(execution.getAnswerEvidence(), "Code RAG returned no answer evidence");
                records.add(Record.from(runIndex, "A" + rawTopK, caseRun, rawTopK, execution));
            }
        } finally {
            properties.getAnswerEvidence().setRawTopK(originalRawTopK);
        }

        DirectStabilitySummary direct = directSelectorStability(repository.getId(), directRuns);
        writeCsv(records);
        writeReport(repository, runs, directRuns, executionOrder, records, direct);
        printSummary(repository, runs, directRuns, records, direct);
    }

    private List<Integer> interleavedOrder(int runs) {
        List<Integer> order = new ArrayList<>(runs * RAW_TOP_K_CASES.length);
        for (int rawTopK : RAW_TOP_K_CASES) {
            for (int run = 0; run < runs; run++) {
                order.add(rawTopK);
            }
        }
        Collections.shuffle(order, new Random(SHUFFLE_SEED));
        return order;
    }

    private DirectStabilitySummary directSelectorStability(String repoId, int runs) {
        List<CodeSearchResult> raw = codeSearchService.searchWithTrace(repoId, QUERY, 20).getCandidates();
        List<CodeEvidenceCandidateCard> cards = toCards(raw);
        List<DirectRecord> directRecords = new ArrayList<>();
        for (int run = 1; run <= runs; run++) {
            CodeEvidenceSelectionResult result = selector.select(QUERY, cards);
            directRecords.add(DirectRecord.from(run, result));
        }
        return DirectStabilitySummary.from(directRecords, cards.size());
    }

    private List<CodeEvidenceCandidateCard> toCards(List<CodeSearchResult> raw) {
        int maxSnippetChars = properties.getLlmSelector().getMaxCandidateChars();
        List<CodeEvidenceCandidateCard> cards = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            CodeSearchResult result = raw.get(i);
            String chunkId = result.getChunkId() == null || result.getChunkId().isBlank()
                    ? "candidate-" + i : result.getChunkId();
            cards.add(CodeEvidenceCandidateCard.builder()
                    .chunkId(chunkId)
                    .chunkType(result.getChunkType())
                    .filePath(result.getFilePath())
                    .symbolName(result.getSymbolName())
                    .apiPath(result.getApiPath())
                    .httpMethod(result.getHttpMethod())
                    .startLine(result.getStartLine())
                    .endLine(result.getEndLine())
                    .metadataSummary(result.getMetadata())
                    .snippet(truncate(result.getContentPreview(), maxSnippetChars))
                    .source("RAW_VECTOR")
                    .rawRank(i + 1)
                    .candidateRank(i + 1)
                    .candidateScore(result.getFinalScore() == null ? result.getScore() : result.getFinalScore())
                    .build());
        }
        return cards;
    }

    private CodeRepository resolveRepository() {
        return codeRepositoryMapper.selectAll().stream()
                .filter(repository -> REPOSITORY_NAME.equalsIgnoreCase(repository.getName()))
                .filter(repository -> "READY".equalsIgnoreCase(repository.getStatus()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No READY FlashDeal repository found"));
    }

    private void writeCsv(List<Record> records) throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        StringBuilder csv = new StringBuilder(
                "timestamp,run_index,case,case_run,raw_top_k,candidate_count,selected_count,prompt_chars,candidate_section_chars,response_chars,prompt_tokens,completion_tokens,total_tokens,selector_latency_ms,latency_per_1k_prompt_tokens,json_parse_ok,fallback,fallback_reason,execution_error,empty_selector_result\n");
        for (Record record : records) {
            csv.append(record.csv()).append('\n');
        }
        Files.writeString(OUTPUT_DIR.resolve("code-rag-selector-benchmark.csv"), csv.toString(), StandardCharsets.UTF_8);
    }

    private void writeReport(CodeRepository repository, int runs, int directRuns, List<Integer> executionOrder,
                             List<Record> records,
                             DirectStabilitySummary direct) throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        StringBuilder report = new StringBuilder();
        report.append("# Code RAG Selector Benchmark\n\n")
                .append("- timestamp: ").append(OffsetDateTime.now()).append('\n')
                .append("- repository: ").append(repository.getName()).append(" (").append(repository.getId()).append(")\n")
                .append("- query: ").append(QUERY).append('\n')
                .append("- selector model: ").append(properties.getLlmSelector().getModel()).append('\n')
                .append("- runs per rawTopK: ").append(runs).append("\n")
                .append("- total selector calls: ").append(records.size()).append("\n")
                .append("- interleave strategy: fixed seed ").append(SHUFFLE_SEED).append("\n")
                .append("- timeout-ms: ").append(properties.getLlmSelector().getTimeoutMs()).append("\n\n")
                .append("## Execution Order\n\n")
                .append("| runIndex | rawTopK | selectorLatencyMs | promptTokens | fallback | fallbackReason |\n")
                .append("| ---: | ---: | ---: | ---: | --- | --- |\n");
        for (Record record : records) {
            report.append("| ").append(record.runIndex()).append(" | ").append(record.rawTopK())
                    .append(" | ").append(record.selectorLatencyMs()).append(" | ")
                    .append(nullable(record.promptTokens())).append(" | ")
                    .append(record.fallback()).append(" | ").append(escapeTable(record.fallbackReason()))
                    .append(" |\n");
        }
        report.append("\n## A/B Summary\n\n")
                .append("| Case | runs | success | fallback | timeout | error | mean ms | P50 ms | P90 ms | P95 ms | max ms | avg prompt chars | avg prompt tokens | avg total tokens |\n")
                .append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (int rawTopK : RAW_TOP_K_CASES) {
            List<Record> group = records.stream().filter(record -> record.rawTopK() == rawTopK).toList();
            report.append(summaryRow("A" + rawTopK, group)).append('\n');
        }
        report.append("\n## Per-case Success Statistics\n\n");
        for (int rawTopK : RAW_TOP_K_CASES) {
            List<Record> group = records.stream().filter(record -> record.rawTopK() == rawTopK).toList();
            report.append(successStatistics("A" + rawTopK, group));
        }
        report.append("\n## Per-case Fallback Statistics\n\n");
        for (int rawTopK : RAW_TOP_K_CASES) {
            List<Record> group = records.stream().filter(record -> record.rawTopK() == rawTopK).toList();
            report.append(fallbackStatistics("A" + rawTopK, group));
        }
        report.append("\n## Input Scale\n\n").append(inputScaleSummary(records));
        report.append("\n## Direct Selector Stability\n\n")
                .append("- candidate count: ").append(direct.candidateCount()).append('\n')
                .append("- runs: ").append(directRuns).append('\n')
                .append("- success: ").append(direct.success()).append('\n')
                .append("- fallback: ").append(direct.fallback()).append('\n')
                .append("- timeout: ").append(direct.timeout()).append('\n')
                .append("- error types: ").append(direct.errorTypes()).append('\n')
                .append("- P50 latency ms: ").append(direct.p50LatencyMs()).append('\n')
                .append("- P95 latency ms: ").append(direct.p95LatencyMs()).append('\n')
                .append("- reasons: ").append(direct.reasons()).append('\n');
        Files.writeString(OUTPUT_DIR.resolve("code-rag-selector-benchmark.md"), report.toString(), StandardCharsets.UTF_8);
    }

    private String summaryRow(String name, List<Record> group) {
        List<Long> successfulLatencies = group.stream().filter(record -> !record.fallback())
                .map(Record::selectorLatencyMs).sorted().toList();
        long timeout = group.stream().filter(record -> record.fallbackReason().startsWith("SELECTOR_TIMEOUT:")).count();
        long errors = group.stream().filter(record -> record.fallback()
                && !record.fallbackReason().startsWith("SELECTOR_TIMEOUT:")).count();
        return "| " + name + " | " + group.size()
                + " | " + successfulLatencies.size()
                + " | " + group.stream().filter(Record::fallback).count()
                + " | " + timeout
                + " | " + errors
                + " | " + averageLong(successfulLatencies)
                + " | " + percentile(successfulLatencies, 0.50)
                + " | " + percentile(successfulLatencies, 0.90)
                + " | " + percentile(successfulLatencies, 0.95)
                + " | " + max(successfulLatencies)
                + " | " + average(group.stream().map(Record::promptChars).toList())
                + " | " + averageNullable(group.stream().map(Record::promptTokens).toList())
                + " | " + averageNullable(group.stream().map(Record::totalTokens).toList()) + " |";
    }

    private String successStatistics(String name, List<Record> group) {
        List<Record> success = group.stream().filter(record -> !record.fallback()).toList();
        return "### " + name + " success (" + success.size() + ")\n\n"
                + "- latency mean/P50/P90/P95/max ms: " + format(averageLong(success.stream().map(Record::selectorLatencyMs).toList()))
                + " / " + percentile(success.stream().map(Record::selectorLatencyMs).sorted().toList(), 0.50)
                + " / " + percentile(success.stream().map(Record::selectorLatencyMs).sorted().toList(), 0.90)
                + " / " + percentile(success.stream().map(Record::selectorLatencyMs).sorted().toList(), 0.95)
                + " / " + max(success.stream().map(Record::selectorLatencyMs).sorted().toList()) + "\n"
                + "- promptChars mean: " + average(success.stream().map(Record::promptChars).toList()) + "\n"
                + "- promptTokens mean: " + averageNullable(success.stream().map(Record::promptTokens).toList()) + "\n"
                + "- completionTokens mean: " + averageNullable(success.stream().map(Record::completionTokens).toList()) + "\n"
                + "- latencyPer1kPromptTokens mean: " + format(averageDouble(success.stream()
                .map(Record::latencyPer1kPromptTokens).filter(value -> value != null).toList())) + "\n\n";
    }

    private String fallbackStatistics(String name, List<Record> group) {
        List<Record> fallback = group.stream().filter(Record::fallback).toList();
        List<Long> latencies = fallback.stream().map(Record::selectorLatencyMs).sorted().toList();
        Map<String, Long> reasons = new LinkedHashMap<>();
        fallback.forEach(record -> reasons.merge(reasonType(record.fallbackReason()), 1L, Long::sum));
        return "### " + name + " fallback (" + fallback.size() + ")\n\n"
                + "- fallbackRate: " + format(group.isEmpty() ? 0 : fallback.size() * 100.0 / group.size()) + "%\n"
                + "- latency P50/P95 ms: " + percentile(latencies, 0.50) + " / " + percentile(latencies, 0.95) + "\n"
                + "- reason counts: " + reasons + "\n\n";
    }

    private String inputScaleSummary(List<Record> records) {
        List<Record> k20 = records.stream().filter(record -> record.rawTopK() == 20).toList();
        List<Record> k50 = records.stream().filter(record -> record.rawTopK() == 50).toList();
        return "| Metric | K20 mean | K50 mean | K50/K20 |\n| --- | ---: | ---: | ---: |\n"
                + scaleRow("candidateCount", average(k20.stream().map(Record::candidateCount).toList()), average(k50.stream().map(Record::candidateCount).toList()))
                + scaleRow("candidateSectionChars", average(k20.stream().map(Record::candidateSectionChars).toList()), average(k50.stream().map(Record::candidateSectionChars).toList()))
                + scaleRow("promptChars", average(k20.stream().map(Record::promptChars).toList()), average(k50.stream().map(Record::promptChars).toList()))
                + scaleRow("promptTokens", averageNullable(k20.stream().map(Record::promptTokens).toList()), averageNullable(k50.stream().map(Record::promptTokens).toList()))
                + scaleRow("totalTokens", averageNullable(k20.stream().map(Record::totalTokens).toList()), averageNullable(k50.stream().map(Record::totalTokens).toList()));
    }

    private String scaleRow(String metric, double k20, double k50) {
        return "| " + metric + " | " + format(k20) + " | " + format(k50) + " | " + format(k20 == 0 ? 0 : k50 / k20) + "x |\n";
    }

    private void printSummary(CodeRepository repository, int runs, int directRuns, List<Record> records,
                              DirectStabilitySummary direct) {
        System.out.println("Code RAG selector benchmark repository=" + repository.getName()
                + ", query=" + QUERY + ", model=" + properties.getLlmSelector().getModel()
                + ", runsPerCase=" + runs);
        for (int rawTopK : RAW_TOP_K_CASES) {
            List<Record> group = records.stream().filter(record -> record.rawTopK() == rawTopK).toList();
            System.out.println(summaryRow("A" + rawTopK, group));
            System.out.println(successStatistics("A" + rawTopK, group));
            System.out.println(fallbackStatistics("A" + rawTopK, group));
        }
        System.out.println("Direct selector stability: runs=" + direct.runs()
                + ", success=" + direct.success() + ", fallback=" + direct.fallback()
                + ", timeout=" + direct.timeout() + ", errors=" + direct.errorTypes()
                + ", p50Ms=" + direct.p50LatencyMs() + ", p95Ms=" + direct.p95LatencyMs()
                + ", reasons=" + direct.reasons());
        System.out.println("Benchmark outputs: " + OUTPUT_DIR.toAbsolutePath());
    }

    private static String reasonType(String reason) {
        if (reason == null || reason.isBlank()) {
            return "NONE";
        }
        int separator = reason.indexOf(':');
        return separator < 0 ? reason : reason.substring(0, separator);
    }

    private static String escapeTable(String value) {
        return value == null || value.isBlank() ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    private static String nullable(Integer value) {
        return value == null ? "UNAVAILABLE" : value.toString();
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static int percentile(List<Long> values, double quantile) {
        if (values.isEmpty()) {
            return 0;
        }
        int index = Math.min(values.size() - 1, (int) Math.ceil(values.size() * quantile) - 1);
        return Math.toIntExact(values.get(Math.max(0, index)));
    }

    private static long max(List<Long> values) {
        return values.isEmpty() ? 0 : values.get(values.size() - 1);
    }

    private static double averageLong(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    private static long average(List<Integer> values) {
        return values.isEmpty() ? 0 : Math.round(values.stream().mapToLong(Integer::longValue).average().orElse(0));
    }

    private static double averageNullable(List<Integer> values) {
        return values.stream().filter(value -> value != null).mapToInt(Integer::intValue).average().orElse(0);
    }

    private static double averageDouble(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars)) + "\n...[truncated]";
    }

    static boolean benchmarkEnabled() {
        return Boolean.getBoolean("code.rag.selector.benchmark.enabled");
    }

    private record Record(String timestamp, int runIndex, String testCase, int caseRun, int rawTopK, int candidateCount,
                          int selectedCount, int promptChars, int candidateSectionChars, int responseChars,
                          Integer promptTokens, Integer completionTokens, Integer totalTokens,
                          long selectorLatencyMs, Double latencyPer1kPromptTokens, boolean jsonParseOk,
                          boolean fallback, String fallbackReason,
                          boolean executionError, boolean emptySelectorResult) {
        static Record from(int runIndex, String testCase, int caseRun, int rawTopK, CodeRagExecutionResult execution) {
            Integer promptTokens = execution.getSelectorPromptTokens();
            Double latencyPer1kPromptTokens = promptTokens == null || promptTokens <= 0
                    ? null : execution.getSelectorLatencyMs() / (promptTokens / 1000.0);
            return new Record(OffsetDateTime.now().toString(), runIndex, testCase, caseRun, rawTopK,
                    execution.getRawCandidates() == null ? 0 : execution.getRawCandidates().size(),
                    execution.getAnswerEvidence().getSelectedEvidence() == null
                            ? 0 : execution.getAnswerEvidence().getSelectedEvidence().size(),
                    execution.getSelectorPromptChars(), execution.getSelectorCandidateSectionChars(),
                    execution.getSelectorResponseChars(), promptTokens,
                    execution.getSelectorCompletionTokens(), execution.getSelectorTotalTokens(),
                    execution.getSelectorLatencyMs(), latencyPer1kPromptTokens, execution.getAnswerEvidence().isJsonParseOk(),
                    execution.getAnswerEvidence().isFallback(), execution.getSelectorFallbackReason() == null
                            ? "" : execution.getSelectorFallbackReason(), execution.isSelectorExecutionError(),
                    execution.isEmptySelectorResult());
        }

        String csv() {
            return String.join(",", timestamp, Integer.toString(runIndex), testCase, Integer.toString(caseRun),
                    Integer.toString(rawTopK),
                    Integer.toString(candidateCount), Integer.toString(selectedCount), Integer.toString(promptChars),
                    Integer.toString(candidateSectionChars), Integer.toString(responseChars), nullable(promptTokens),
                    nullable(completionTokens), nullable(totalTokens), Long.toString(selectorLatencyMs),
                    latencyPer1kPromptTokens == null ? "UNAVAILABLE" : format(latencyPer1kPromptTokens),
                    Boolean.toString(jsonParseOk), Boolean.toString(fallback), csvValue(fallbackReason),
                    Boolean.toString(executionError), Boolean.toString(emptySelectorResult));
        }

        private static String csvValue(String value) {
            return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\"";
        }
    }

    private record DirectRecord(int run, boolean fallback, boolean jsonParseOk, long latencyMs, String reason) {
        static DirectRecord from(int run, CodeEvidenceSelectionResult result) {
            return new DirectRecord(run, result.isFallback(), result.isJsonParseOk(), result.getLatencyMs(),
                    result.getReason() == null ? "" : result.getReason());
        }
    }

    private record DirectStabilitySummary(int candidateCount, int runs, int success, int fallback, int timeout,
                                          Map<String, Long> errorTypes, List<String> reasons,
                                          int p50LatencyMs, int p95LatencyMs) {
        static DirectStabilitySummary from(List<DirectRecord> records, int candidateCount) {
            Map<String, Long> errors = new LinkedHashMap<>();
            records.stream().filter(DirectRecord::fallback).forEach(record -> {
                String reason = record.reason();
                String type = reason.contains(":") ? reason.substring(0, reason.indexOf(':')) : "UNKNOWN";
                errors.merge(type, 1L, Long::sum);
            });
            List<Long> latencies = records.stream().map(DirectRecord::latencyMs).sorted().toList();
            return new DirectStabilitySummary(candidateCount, records.size(),
                    (int) records.stream().filter(record -> !record.fallback()).count(),
                    (int) records.stream().filter(DirectRecord::fallback).count(),
                    (int) records.stream().filter(record -> record.reason().startsWith("SELECTOR_TIMEOUT:"))
                            .count(), errors, records.stream().map(DirectRecord::reason).filter(reason -> !reason.isBlank()).toList(),
                    percentile(latencies, 0.50), percentile(latencies, 0.95));
        }
    }
}
