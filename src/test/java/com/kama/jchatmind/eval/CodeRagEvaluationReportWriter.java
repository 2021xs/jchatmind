package com.kama.jchatmind.eval;

import com.kama.jchatmind.model.dto.CodeSearchResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToLongFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

class CodeRagEvaluationReportWriter {
    static final List<String> DETAIL_HEADERS = List.of(
            "case_id", "category", "difficulty", "question", "ground_truth",
            "raw_candidate_count", "raw_candidates", "ground_truth_raw_rank",
            "retrieval_hit_1", "retrieval_hit_3", "retrieval_hit_5", "retrieval_hit_10",
            "reciprocal_rank", "selected_evidence", "ground_truth_selected_rank",
            "selector_hit_1", "selector_hit_3", "selector_hit_5", "failure_type",
            "fallback", "fallback_reason", "json_parse_ok", "selector_selected_chunk_ids",
            "selector_valid_chunk_ids", "selector_invalid_chunk_ids", "empty_selector_result",
            "selector_proposed_candidate_ids", "selector_valid_candidate_ids", "selector_invalid_candidate_ids",
            "cache_hit", "error",
            "embedding_latency_ms", "retrieval_latency_ms", "selector_latency_ms", "total_latency_ms",
            "selector_prompt_tokens", "selector_completion_tokens", "selector_total_tokens",
            "selector_usage_available", "selector_prompt_chars", "selector_candidate_section_chars"
    );
    static final List<String> SUMMARY_HEADERS = List.of(
            "group", "case_count", "recall_at_1", "recall_at_3", "recall_at_5", "recall_at_10", "mrr",
            "selected_at_1", "selected_at_3", "selected_at_5",
            "success_count", "retrieval_miss_count", "selector_miss_count", "fallback_count",
            "fallback_rate", "invalid_selector_id_count", "empty_selector_result_count",
            "selector_error_count", "retrieval_error_count", "ground_truth_invalid_count",
            "embedding_p50_ms", "embedding_p95_ms", "embedding_p99_ms",
            "retrieval_p50_ms", "retrieval_p95_ms", "retrieval_p99_ms",
            "selector_p50_ms", "selector_p95_ms", "selector_p99_ms",
            "total_p50_ms", "total_p95_ms", "total_p99_ms",
            "selector_usage_status", "selector_usage_case_count",
            "selector_prompt_tokens_p50", "selector_prompt_tokens_p95", "selector_prompt_tokens_sum",
            "selector_completion_tokens_p50", "selector_completion_tokens_p95", "selector_completion_tokens_sum",
            "selector_total_tokens_p50", "selector_total_tokens_p95", "selector_total_tokens_sum",
            "selector_prompt_chars_p50", "selector_prompt_chars_p95", "selector_prompt_chars_sum",
            "selector_candidate_section_chars_p50", "selector_candidate_section_chars_p95",
            "selector_candidate_section_chars_sum"
    );

    private final CodeRagMetricCalculator metrics = new CodeRagMetricCalculator();
    private final PercentileCalculator percentiles = new PercentileCalculator();

    void write(Path outputDirectory,
               List<CodeRagEvalCaseResult> results,
               Environment environment) throws IOException {
        Files.createDirectories(outputDirectory);
        List<Summary> summaries = summarize(results);
        Files.writeString(outputDirectory.resolve("code-rag-eval-detail.csv"),
                detailCsv(results), StandardCharsets.UTF_8);
        Files.writeString(outputDirectory.resolve("code-rag-eval-summary.csv"),
                summaryCsv(summaries), StandardCharsets.UTF_8);
        Files.writeString(outputDirectory.resolve("code-rag-evaluation-report.md"),
                markdown(results, summaries, environment), StandardCharsets.UTF_8);
    }

    String detailCsv(List<CodeRagEvalCaseResult> results) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(DETAIL_HEADERS);
        for (CodeRagEvalCaseResult result : results) {
            int rawRank = result.groundTruthRawRank();
            int selectedRank = result.groundTruthSelectedRank();
            rows.add(List.of(
                    result.evalCase().id,
                    result.evalCase().category,
                    result.evalCase().difficulty,
                    result.evalCase().query,
                    result.evalCase().groundTruthDescription(),
                    Integer.toString(result.rawCandidates().size()),
                    formatEvidence(result.rawCandidates()),
                    rank(rawRank),
                    bool(metrics.hitAt(rawRank, 1)), bool(metrics.hitAt(rawRank, 3)),
                    bool(metrics.hitAt(rawRank, 5)), bool(metrics.hitAt(rawRank, 10)),
                    decimal(metrics.reciprocalRank(rawRank)),
                    formatEvidence(result.selectedEvidence()),
                    rank(selectedRank),
                    bool(metrics.hitAt(selectedRank, 1)), bool(metrics.hitAt(selectedRank, 3)),
                    bool(metrics.hitAt(selectedRank, 5)),
                    result.failureType().name(), bool(result.fallback()), safe(result.fallbackReason()),
                    bool(result.jsonParseOk()), joinIds(result.selectorProposedChunkIds()),
                    joinIds(result.selectorValidChunkIds()), joinIds(result.selectorInvalidChunkIds()),
                    bool(result.emptySelectorResult()), joinIds(result.selectorProposedCandidateIds()),
                    joinIds(result.selectorValidCandidateIds()), joinIds(result.selectorInvalidCandidateIds()),
                    bool(result.cacheHit()), safe(result.error()),
                    Long.toString(result.embeddingLatencyMs()), Long.toString(result.retrievalLatencyMs()),
                    Long.toString(result.selectorLatencyMs()), Long.toString(result.totalLatencyMs()),
                    nullable(result.selectorPromptTokens()), nullable(result.selectorCompletionTokens()),
                    nullable(result.selectorTotalTokens()), bool(result.selectorUsageAvailable()),
                    Integer.toString(result.selectorPromptChars()),
                    Integer.toString(result.selectorCandidateSectionChars())
            ));
        }
        return csv(rows);
    }

    String summaryCsv(List<Summary> summaries) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(SUMMARY_HEADERS);
        for (Summary value : summaries) {
            rows.add(value.toCsvRow());
        }
        return csv(rows);
    }

    List<Summary> summarize(List<CodeRagEvalCaseResult> results) {
        Map<String, List<CodeRagEvalCaseResult>> groups = new LinkedHashMap<>();
        groups.put("ALL", results);
        results.stream()
                .map(result -> result.evalCase().category)
                .distinct()
                .sorted()
                .forEach(category -> groups.put(category, results.stream()
                        .filter(result -> category.equals(result.evalCase().category)).toList()));
        return groups.entrySet().stream()
                .map(entry -> summary(entry.getKey(), entry.getValue()))
                .toList();
    }

    private Summary summary(String group, List<CodeRagEvalCaseResult> results) {
        int total = results.size();
        int recall1 = countRawHit(results, 1);
        int recall3 = countRawHit(results, 3);
        int recall5 = countRawHit(results, 5);
        int recall10 = countRawHit(results, 10);
        int selected1 = countSelectedHit(results, 1);
        int selected3 = countSelectedHit(results, 3);
        int selected5 = countSelectedHit(results, 5);
        double mrr = results.stream().mapToDouble(r -> metrics.reciprocalRank(r.groundTruthRawRank())).average().orElse(0);
        EnumMap<CodeRagFailureType, Integer> failures = new EnumMap<>(CodeRagFailureType.class);
        for (CodeRagFailureType type : CodeRagFailureType.values()) {
            failures.put(type, 0);
        }
        results.forEach(result -> failures.compute(result.failureType(), (key, count) -> count + 1));
        return new Summary(group, total,
                metrics.rate(recall1, total), metrics.rate(recall3, total), metrics.rate(recall5, total),
                metrics.rate(recall10, total), mrr,
                metrics.rate(selected1, total), metrics.rate(selected3, total), metrics.rate(selected5, total),
                failures,
                results.stream().mapToInt(result -> result.selectorInvalidCandidateIds().size()).sum(),
                (int) results.stream().filter(CodeRagEvalCaseResult::emptySelectorResult).count(),
                latency(results, CodeRagEvalCaseResult::embeddingLatencyMs),
                latency(results, CodeRagEvalCaseResult::retrievalLatencyMs),
                latency(results, CodeRagEvalCaseResult::selectorLatencyMs),
                latency(results, CodeRagEvalCaseResult::totalLatencyMs),
                usageStatus(results),
                (int) results.stream().filter(CodeRagEvalCaseResult::selectorUsageAvailable).count(),
                tokens(results, CodeRagEvalCaseResult::selectorPromptTokens),
                tokens(results, CodeRagEvalCaseResult::selectorCompletionTokens),
                tokens(results, CodeRagEvalCaseResult::selectorTotalTokens),
                sizes(results, CodeRagEvalCaseResult::selectorPromptChars),
                sizes(results, CodeRagEvalCaseResult::selectorCandidateSectionChars));
    }

    private int countRawHit(List<CodeRagEvalCaseResult> results, int k) {
        return (int) results.stream().filter(result -> metrics.hitAt(result.groundTruthRawRank(), k)).count();
    }

    private int countSelectedHit(List<CodeRagEvalCaseResult> results, int k) {
        return (int) results.stream().filter(result -> metrics.hitAt(result.groundTruthSelectedRank(), k)).count();
    }

    private Latency latency(List<CodeRagEvalCaseResult> results, ToLongFunction<CodeRagEvalCaseResult> extractor) {
        List<Long> values = results.stream().mapToLong(extractor).boxed().toList();
        return new Latency(percentiles.percentile(values, 0.50), percentiles.percentile(values, 0.95),
                percentiles.percentile(values, 0.99));
    }

    private TokenStats tokens(List<CodeRagEvalCaseResult> results,
                              Function<CodeRagEvalCaseResult, Integer> extractor) {
        List<Long> values = results.stream().map(extractor).filter(java.util.Objects::nonNull)
                .map(Integer::longValue).toList();
        return new TokenStats(percentiles.percentile(values, 0.50), percentiles.percentile(values, 0.95),
                values.stream().mapToLong(Long::longValue).sum());
    }

    private TokenStats sizes(List<CodeRagEvalCaseResult> results,
                             java.util.function.ToIntFunction<CodeRagEvalCaseResult> extractor) {
        List<Long> values = results.stream().mapToInt(extractor).asLongStream().boxed().toList();
        return new TokenStats(percentiles.percentile(values, 0.50), percentiles.percentile(values, 0.95),
                values.stream().mapToLong(Long::longValue).sum());
    }

    private String usageStatus(List<CodeRagEvalCaseResult> results) {
        long available = results.stream().filter(CodeRagEvalCaseResult::selectorUsageAvailable).count();
        if (available == 0) {
            return "UNSUPPORTED";
        }
        return available == results.size() ? "AVAILABLE" : "PARTIAL";
    }

    private String markdown(List<CodeRagEvalCaseResult> results,
                            List<Summary> summaries,
                            Environment environment) {
        Summary all = summaries.get(0);
        StringBuilder builder = new StringBuilder();
        builder.append("# Code RAG 分层评测报告\n\n");
        builder.append("## 评测环境\n\n");
        builder.append("- 运行时间：").append(environment.runAt()).append('\n');
        builder.append("- Java：").append(environment.javaVersion()).append('\n');
        builder.append("- 数据库：").append(environment.database()).append('\n');
        builder.append("- embedding 模型：").append(environment.embeddingModel()).append('\n');
        builder.append("- selector 模型：").append(environment.selectorModel()).append('\n');
        builder.append("- selector 实际候选上限：").append(environment.rawTopK()).append('\n');
        builder.append("- selected evidence 上限：").append(environment.finalTopK()).append('\n');
        builder.append("- 实际 case 数：").append(results.size()).append("\n\n");
        builder.append("> Ground Truth 是现有 fixture 定义的 keyword-level acceptable evidence，不是精确 chunkId ground truth。")
                .append("命中规则与原 final eval 完全一致：chunkType 满足约束，并且 filePath 或 symbolName/apiPath/contentPreview/metadata 命中关键词。\n\n");
        builder.append("## Overall Results\n\n");
        builder.append("| Recall@1 | Recall@3 | Recall@5 | Recall@10 | MRR | selected@1 | selected@3 | selected@5 |\n");
        builder.append("| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        builder.append("| ").append(percent(all.recall1())).append(" | ").append(percent(all.recall3()))
                .append(" | ").append(percent(all.recall5())).append(" | ").append(percent(all.recall10()))
                .append(" | ").append(decimal(all.mrr())).append(" | ").append(percent(all.selected1()))
                .append(" | ").append(percent(all.selected3())).append(" | ").append(percent(all.selected5())).append(" |\n\n");
        builder.append("### Latency\n\n");
        builder.append("| 阶段 | P50 ms | P95 ms | P99 ms |\n| --- | ---: | ---: | ---: |\n");
        latencyRow(builder, "Embedding", all.embedding());
        latencyRow(builder, "Retrieval", all.retrieval());
        latencyRow(builder, "Selector", all.selector());
        latencyRow(builder, "Total", all.total());
        builder.append('\n');
        builder.append("### Token Usage\n\n");
        if ("UNSUPPORTED".equals(all.usageStatus())) {
            builder.append("Token Usage: UNSUPPORTED\n\n");
        } else {
            builder.append("- status: ").append(all.usageStatus()).append(" (")
                    .append(all.usageCaseCount()).append('/').append(all.caseCount()).append(")\n\n");
            builder.append("| Token | P50 | P95 | Sum |\n| --- | ---: | ---: | ---: |\n");
            tokenRow(builder, "Prompt", all.promptTokens());
            tokenRow(builder, "Completion", all.completionTokens());
            tokenRow(builder, "Total", all.totalTokens());
            builder.append('\n');
        }
        builder.append("### Prompt Size\n\n");
        builder.append("| Text | P50 chars | P95 chars | Sum chars |\n| --- | ---: | ---: | ---: |\n");
        tokenRow(builder, "Prompt", all.promptChars());
        tokenRow(builder, "Candidate section", all.candidateSectionChars());
        builder.append('\n');
        builder.append("## Category Results\n\n");
        builder.append("| Category | Cases | R@1 | R@3 | R@5 | R@10 | MRR | S@1 | S@3 | S@5 | Retrieval Miss | Selector Miss |\n");
        builder.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        summaries.stream().skip(1).forEach(summary -> builder.append("| ").append(summary.group())
                .append(" | ").append(summary.caseCount()).append(" | ").append(percent(summary.recall1()))
                .append(" | ").append(percent(summary.recall3())).append(" | ").append(percent(summary.recall5()))
                .append(" | ").append(percent(summary.recall10())).append(" | ").append(decimal(summary.mrr()))
                .append(" | ").append(percent(summary.selected1())).append(" | ").append(percent(summary.selected3()))
                .append(" | ").append(percent(summary.selected5())).append(" | ")
                .append(summary.count(CodeRagFailureType.RETRIEVAL_MISS)).append(" | ")
                .append(summary.count(CodeRagFailureType.SELECTOR_MISS)).append(" |\n"));
        builder.append("\n## Failure Breakdown\n\n");
        builder.append("| Type | Count |\n| --- | ---: |\n");
        for (CodeRagFailureType type : CodeRagFailureType.values()) {
            builder.append("| ").append(type).append(" | ").append(all.count(type)).append(" |\n");
        }
        builder.append("\n- FALLBACK rate: ").append(percent(metrics.rate(all.count(CodeRagFailureType.FALLBACK), all.caseCount()))).append('\n');
        builder.append("- invalid selector id count: ").append(all.invalidSelectorIdCount()).append('\n');
        builder.append("- empty selector result count: ").append(all.emptySelectorResultCount()).append("\n\n");
        builder.append("### Fallback Cases\n\n");
        List<CodeRagEvalCaseResult> fallbackCases = results.stream().filter(CodeRagEvalCaseResult::fallback).toList();
        if (fallbackCases.isEmpty()) {
            builder.append("无。\n\n");
        } else {
            for (CodeRagEvalCaseResult fallback : fallbackCases) {
                builder.append("- ").append(fallback.evalCase().id)
                        .append(": ").append(fallback.fallbackReason())
                        .append("; proposed=").append(fallback.selectorProposedChunkIds())
                        .append("; invalid=").append(fallback.selectorInvalidChunkIds())
                        .append("; proposedCandidateIds=").append(fallback.selectorProposedCandidateIds())
                        .append("; invalidCandidateIds=").append(fallback.selectorInvalidCandidateIds()).append('\n');
            }
            builder.append('\n');
        }
        builder.append("\n## Top Failure Cases\n\n");
        List<CodeRagEvalCaseResult> failures = results.stream()
                .filter(result -> result.failureType() != CodeRagFailureType.SUCCESS)
                .sorted(Comparator.comparing(result -> result.evalCase().id))
                .limit(20).toList();
        if (failures.isEmpty()) {
            builder.append("无。\n");
        } else {
            for (CodeRagEvalCaseResult failure : failures) {
                builder.append("### ").append(failure.evalCase().id).append('\n');
                builder.append("- 问题：").append(failure.evalCase().query).append('\n');
                builder.append("- 类别：").append(failure.evalCase().category).append('\n');
                builder.append("- Ground Truth：").append(failure.evalCase().groundTruthDescription()).append('\n');
                builder.append("- Raw rank：").append(rank(failure.groundTruthRawRank())).append('\n');
                builder.append("- Selected：").append(formatEvidence(failure.selectedEvidence())).append('\n');
                builder.append("- Failure Type：").append(failure.failureType()).append("\n\n");
            }
        }
        builder.append("## 结论\n\n");
        int retrievalMiss = all.count(CodeRagFailureType.RETRIEVAL_MISS);
        int selectorMiss = all.count(CodeRagFailureType.SELECTOR_MISS);
        if (retrievalMiss > selectorMiss) {
            builder.append("本次失败以 RETRIEVAL_MISS 为主，当前数据指向 Raw Retrieval 是优先瓶颈。\n");
        } else if (selectorMiss > retrievalMiss) {
            builder.append("本次失败以 SELECTOR_MISS 为主，当前数据指向 Evidence Selector 是优先瓶颈。\n");
        } else if (retrievalMiss == 0 && selectorMiss == 0) {
            builder.append("本次没有 RETRIEVAL_MISS 或 SELECTOR_MISS，暂时无法从失败分层判断主要瓶颈。\n");
        } else {
            builder.append("RETRIEVAL_MISS 与 SELECTOR_MISS 数量相同，暂时无法判断单一主要瓶颈。\n");
        }
        builder.append("P99 基于 ").append(results.size()).append(" 个样本，适合本机基线对比，不代表稳定的生产尾延迟。\n");
        return builder.toString();
    }

    private String formatEvidence(List<CodeSearchResult> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return "";
        }
        return java.util.stream.IntStream.range(0, evidence.size())
                .mapToObj(index -> {
                    CodeSearchResult item = evidence.get(index);
                    return "rank=" + (index + 1)
                            + "|chunkId=" + safe(item.getChunkId())
                            + "|file=" + safe(item.getFilePath())
                            + "|chunkType=" + safe(item.getChunkType())
                            + "|symbol=" + safe(item.getSymbolName())
                            + "|lines=" + nullable(item.getStartLine()) + "-" + nullable(item.getEndLine())
                            + "|score=" + nullable(item.getScore());
                })
                .collect(Collectors.joining("\n"));
    }

    private String csv(List<List<String>> rows) {
        return rows.stream().map(row -> row.stream().map(this::escapeCsv).collect(Collectors.joining(",")))
                .collect(Collectors.joining("\n", "", "\n"));
    }

    private String escapeCsv(String value) {
        String safeValue = safe(value);
        if (safeValue.contains(",") || safeValue.contains("\"") || safeValue.contains("\n") || safeValue.contains("\r")) {
            return "\"" + safeValue.replace("\"", "\"\"") + "\"";
        }
        return safeValue;
    }

    private void latencyRow(StringBuilder builder, String label, Latency latency) {
        builder.append("| ").append(label).append(" | ").append(latency.p50()).append(" | ")
                .append(latency.p95()).append(" | ").append(latency.p99()).append(" |\n");
    }

    private void tokenRow(StringBuilder builder, String label, TokenStats tokens) {
        builder.append("| ").append(label).append(" | ").append(tokens.p50()).append(" | ")
                .append(tokens.p95()).append(" | ").append(tokens.sum()).append(" |\n");
    }

    private String bool(boolean value) { return Boolean.toString(value); }
    private String joinIds(List<String> values) { return values == null ? "" : String.join("|", values); }
    private String rank(int value) { return value <= 0 ? "" : Integer.toString(value); }
    private String safe(String value) { return value == null ? "" : value; }
    private String nullable(Object value) { return value == null ? "" : value.toString(); }
    private String decimal(double value) { return String.format(Locale.ROOT, "%.6f", value); }
    private String percent(double value) { return String.format(Locale.ROOT, "%.2f%%", value * 100); }

    record Environment(OffsetDateTime runAt, String javaVersion, String database,
                       String embeddingModel, String selectorModel, int rawTopK, int finalTopK) {
    }

    record Latency(long p50, long p95, long p99) {
    }

    record TokenStats(long p50, long p95, long sum) {
    }

    record Summary(String group, int caseCount, double recall1, double recall3, double recall5,
                   double recall10, double mrr, double selected1, double selected3, double selected5,
                   EnumMap<CodeRagFailureType, Integer> failures, int invalidSelectorIdCount,
                   int emptySelectorResultCount, Latency embedding, Latency retrieval,
                   Latency selector, Latency total, String usageStatus, int usageCaseCount,
                   TokenStats promptTokens, TokenStats completionTokens, TokenStats totalTokens,
                   TokenStats promptChars, TokenStats candidateSectionChars) {
        int count(CodeRagFailureType type) { return failures.getOrDefault(type, 0); }

        List<String> toCsvRow() {
            return List.of(group, Integer.toString(caseCount), decimalValue(recall1), decimalValue(recall3),
                    decimalValue(recall5), decimalValue(recall10), decimalValue(mrr), decimalValue(selected1),
                    decimalValue(selected3), decimalValue(selected5),
                    countValue(CodeRagFailureType.SUCCESS), countValue(CodeRagFailureType.RETRIEVAL_MISS),
                    countValue(CodeRagFailureType.SELECTOR_MISS), countValue(CodeRagFailureType.FALLBACK),
                    decimalValue(caseCount == 0 ? 0 : (double) count(CodeRagFailureType.FALLBACK) / caseCount),
                    Integer.toString(invalidSelectorIdCount), Integer.toString(emptySelectorResultCount),
                    countValue(CodeRagFailureType.SELECTOR_ERROR), countValue(CodeRagFailureType.RETRIEVAL_ERROR),
                    countValue(CodeRagFailureType.GROUND_TRUTH_INVALID),
                    latencyValues(embedding), latencyValues(retrieval), latencyValues(selector), latencyValues(total),
                     usageStatus, Integer.toString(usageCaseCount), tokenValues(promptTokens),
                     tokenValues(completionTokens), tokenValues(totalTokens), tokenValues(promptChars),
                     tokenValues(candidateSectionChars))
                    .stream().flatMap(value -> value.contains("\u0000")
                            ? java.util.Arrays.stream(value.split("\u0000", -1))
                            : java.util.stream.Stream.of(value)).toList();
        }

        private String countValue(CodeRagFailureType type) { return Integer.toString(count(type)); }
        private static String decimalValue(double value) { return String.format(Locale.ROOT, "%.6f", value); }
        private static String latencyValues(Latency latency) {
            return latency.p50 + "\u0000" + latency.p95 + "\u0000" + latency.p99;
        }
        private static String tokenValues(TokenStats tokens) {
            return tokens.p50 + "\u0000" + tokens.p95 + "\u0000" + tokens.sum;
        }
    }
}
