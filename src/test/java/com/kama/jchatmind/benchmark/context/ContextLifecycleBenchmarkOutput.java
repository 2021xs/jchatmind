package com.kama.jchatmind.benchmark.context;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;

final class ContextLifecycleBenchmarkOutput {
    static final String RAW_JSON = "context-lifecycle-raw.json";
    static final String CASE_CSV = "context-lifecycle-cases.csv";
    static final String REPORT_MD = "context-lifecycle-legacy-baseline.md";
    static final String ANOMALIES_CSV = "context-lifecycle-anomalies.csv";

    private final ObjectMapper objectMapper;

    ContextLifecycleBenchmarkOutput(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    Artifacts write(Path directory, ContextLifecycleBenchmarkResult result) throws IOException {
        Files.createDirectories(directory);
        Path raw = directory.resolve(RAW_JSON);
        Path csv = directory.resolve(CASE_CSV);
        Path report = directory.resolve(REPORT_MD);
        Path anomalies = directory.resolve(ANOMALIES_CSV);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(raw.toFile(), result);
        Files.writeString(csv, caseCsv(result), StandardCharsets.UTF_8);
        Files.writeString(report, report(result), StandardCharsets.UTF_8);
        Files.writeString(anomalies, anomaliesCsv(result), StandardCharsets.UTF_8);
        return new Artifacts(raw, csv, report, anomalies);
    }

    String caseCsv(ContextLifecycleBenchmarkResult result) {
        StringBuilder csv = new StringBuilder();
        row(csv, List.of(
                "run_id", "architecture", "git_commit", "case_id", "category", "repeat_index", "status",
                "task_latency_ms", "think_latency_ms", "tool_latency_ms", "compression_latency_ms", "final_latency_ms",
                "total_input_tokens_actual", "total_input_tokens_estimated",
                "total_output_tokens_actual", "total_output_tokens_estimated",
                "max_context_tokens", "final_context_tokens", "tool_call_count", "tool_result_tokens",
                "tool_result_context_tokens", "cross_task_tool_tokens", "compression_count",
                "compression_input_tokens", "compression_output_tokens", "compression_tokens_removed",
                "max_summary_depth", "critical_fact_recall", "exact_value_accuracy", "forbidden_claim_count",
                "orphan_protocol_count", "protocol_failure_count"));
        for (ContextLifecycleBenchmarkResult.CaseResult value : result.cases()) {
            row(csv, List.of(
                    result.run().benchmarkRunId(), result.run().architectureLabel(), result.run().gitCommit(),
                    value.benchmarkCaseId(), value.caseCategory(), value.repeatIndex(), safe(value.taskStatus()),
                    value.taskTotalLatencyMs(), value.thinkTotalLatencyMs(), value.toolTotalLatencyMs(),
                    value.compressionTotalLatencyMs(), value.finalLatencyMs(),
                    nullable(value.tokens().taskInput().actualTokens()), nullable(value.tokens().taskInput().estimatedTokens()),
                    nullable(value.tokens().taskOutput().actualTokens()), nullable(value.tokens().taskOutput().estimatedTokens()),
                    value.context().maxWorkingContextTokensObserved(), value.context().finalContextTokens(),
                    value.tools().toolCallCount(), value.tools().toolResultTokensProduced(),
                    value.tools().toolResultTokensInjectedIntoModelContext(), value.tools().crossTaskToolResultTokens(),
                    value.compression().compressionCountPerTask(), value.compression().compressionInputTokens(),
                    value.compression().compressionOutputTokens(), value.compression().compressionTokensRemoved(),
                    value.compression().maxSummaryDepth(), format(value.correctness().criticalFactRecall()),
                    format(value.correctness().exactValueAccuracy()), value.correctness().forbiddenClaimCount(),
                    value.stability().orphanToolProtocolCount(), value.stability().protocolValidationFailureCount()));
        }
        return csv.toString();
    }

    String anomaliesCsv(ContextLifecycleBenchmarkResult result) {
        StringBuilder csv = new StringBuilder();
        row(csv, List.of("run_id", "case_id", "repeat_index", "anomaly_type", "detail"));
        for (ContextLifecycleBenchmarkResult.CaseResult value : result.cases()) {
            if (!"SUCCESS".equalsIgnoreCase(value.taskStatus())) {
                row(csv, List.of(result.run().benchmarkRunId(), value.benchmarkCaseId(), value.repeatIndex(),
                        "TASK_STATUS", safe(value.taskStatus()) + "/" + safe(value.finishReason())));
            }
            if (value.correctness().criticalFactRecall() < 1.0) {
                row(csv, List.of(result.run().benchmarkRunId(), value.benchmarkCaseId(), value.repeatIndex(),
                        "CRITICAL_FACT_MISS", format(value.correctness().criticalFactRecall())));
            }
            if (value.correctness().forbiddenClaimCount() > 0) {
                row(csv, List.of(result.run().benchmarkRunId(), value.benchmarkCaseId(), value.repeatIndex(),
                        "FORBIDDEN_CLAIM", value.correctness().forbiddenClaimCount()));
            }
            ContextLifecycleBenchmarkResult.StabilityMetrics stability = value.stability();
            if (stability.orphanToolProtocolCount() > 0 || stability.protocolValidationFailureCount() > 0) {
                row(csv, List.of(result.run().benchmarkRunId(), value.benchmarkCaseId(), value.repeatIndex(),
                        "PROTOCOL", "orphan=" + stability.orphanToolProtocolCount()
                                + ",validation=" + stability.protocolValidationFailureCount()));
            }
            for (String failure : value.failures()) {
                row(csv, List.of(result.run().benchmarkRunId(), value.benchmarkCaseId(), value.repeatIndex(),
                        "FAILURE", failure));
            }
        }
        return csv.toString();
    }

    String report(ContextLifecycleBenchmarkResult result) {
        ContextLifecycleBenchmarkResult.RunMetadata run = result.run();
        List<ContextLifecycleBenchmarkResult.CaseResult> cases = result.cases();
        Map<String, Long> categoryCounts = new LinkedHashMap<>();
        cases.forEach(value -> categoryCounts.merge(value.caseCategory(), 1L, Long::sum));
        long successful = cases.stream().filter(value -> "SUCCESS".equalsIgnoreCase(value.taskStatus())).count();
        long compressed = cases.stream().filter(value -> value.compression().compressionCountPerTask() > 0).count();
        int orphan = cases.stream().mapToInt(value -> value.stability().orphanToolProtocolCount()).sum();
        int protocol = cases.stream().mapToInt(value -> value.stability().protocolValidationFailureCount()).sum();
        int failures = cases.stream().mapToInt(value -> value.failures().size()).sum();
        String p95Note = cases.size() < 20
                ? "样本数少于 20；P95 仅按 nearest-rank 输出，不代表稳定尾延迟。"
                : "P95 使用 nearest-rank；建议结合三次重复的分布解释。";

        StringBuilder md = new StringBuilder();
        md.append("# JChatMind Context Lifecycle Legacy Baseline Report\n\n")
                .append("> 本报告由固定 Benchmark Runner 从真实执行结果生成，不包含手工填写的指标。\n\n")
                .append("## Environment\n\n")
                .append("| Field | Value |\n| --- | --- |\n")
                .append("| benchmark run | ").append(escapeMd(run.benchmarkRunId())).append(" |\n")
                .append("| architecture | ").append(escapeMd(run.architectureLabel())).append(" |\n")
                .append("| git commit | `").append(run.gitCommit()).append("` |\n")
                .append("| working tree | ").append(escapeMd(run.workingTreeStatus())).append(" |\n")
                .append("| suite version | ").append(escapeMd(run.benchmarkSuiteVersion())).append(" |\n")
                .append("| model | ").append(escapeMd(run.model())).append(" |\n")
                .append("| temperature / seed | ").append(nullable(run.temperature())).append(" / ")
                .append(nullable(run.seed())).append(" |\n")
                .append("| repo | ").append(escapeMd(run.repoName())).append(" / `").append(run.repoId()).append("` |\n")
                .append("| repo snapshot | `").append(run.repoFileManifestDigest()).append("` / `")
                .append(run.repoChunkManifestDigest()).append("` |\n")
                .append("| repo external HEAD / tree | `").append(run.repoExternalHead()).append("` / ")
                .append(escapeMd(run.repoWorkingTreeStatus())).append(" |\n")
                .append("| started / ended | ").append(run.startedAt()).append(" / ").append(run.endedAt()).append(" |\n")
                .append("| repeats | recommended ").append(run.recommendedRepeats()).append(", actual ")
                .append(run.actualRepeats()).append(" |\n")
                .append("| token measurement | ").append(escapeMd(run.tokenMeasurement())).append(" |\n\n")
                .append("## Suite\n\n")
                .append("总执行数：").append(cases.size()).append("。\n\n")
                .append("| Category | Executions |\n| --- | ---: |\n");
        categoryCounts.forEach((category, count) -> md.append("| ").append(escapeMd(category)).append(" | ")
                .append(count).append(" |\n"));

        md.append("\n## Correctness\n\n")
                .append("| Metric | Value |\n| --- | ---: |\n")
                .append("| task success | ").append(successful).append("/").append(cases.size()).append(" |\n")
                .append("| critical fact recall mean | ").append(format(mean(cases,
                        value -> value.correctness().criticalFactRecall()))).append(" |\n")
                .append("| exact value accuracy mean | ").append(format(mean(cases,
                        value -> value.correctness().exactValueAccuracy()))).append(" |\n")
                .append("| forbidden claim count | ").append(cases.stream()
                        .mapToInt(value -> value.correctness().forbiddenClaimCount()).sum()).append(" |\n")
                .append("| judge | NOT_USED_DETERMINISTIC_ONLY |\n\n")
                .append("## Context\n\n")
                .append("| Metric | Median | P95 | Max |\n| --- | ---: | ---: | ---: |\n")
                .append(metricRow(cases, "working context tokens", value -> value.context().maxWorkingContextTokensObserved()))
                .append(metricRow(cases, "final context tokens", value -> value.context().finalContextTokens()))
                .append(metricRow(cases, "cross-task tool tokens", value -> value.tools().crossTaskToolResultTokens()))
                .append(metricRow(cases, "TaskToolTranscript estimated tokens", value -> value.context().taskToolTranscriptEstimatedTokens()))
                .append("\n").append(p95Note).append("\n\n")
                .append("## Tool\n\n")
                .append("| Metric | Total / Max |\n| --- | ---: |\n")
                .append("| tool calls | ").append(cases.stream().mapToInt(value -> value.tools().toolCallCount()).sum()).append(" |\n")
                .append("| produced estimated tokens | ").append(cases.stream().mapToInt(value -> value.tools().toolResultTokensProduced()).sum()).append(" |\n")
                .append("| injected estimated tokens | ").append(cases.stream().mapToInt(value -> value.tools().toolResultTokensInjectedIntoModelContext()).sum()).append(" |\n")
                .append("| largest single result | ").append(cases.stream().mapToInt(value -> value.tools().singleLargestToolResultTokens()).max().orElse(0)).append(" |\n\n")
                .append("## Compression\n\n")
                .append("| Metric | Value |\n| --- | ---: |\n")
                .append("| tasks with compression | ").append(compressed).append("/").append(cases.size()).append(" |\n")
                .append("| compression event count | ").append(cases.stream().mapToInt(value -> value.compression().compressionCountPerTask()).sum()).append(" |\n")
                .append("| max events per task | ").append(cases.stream().mapToInt(value -> value.compression().compressionCountPerTask()).max().orElse(0)).append(" |\n")
                .append("| max summary depth | ").append(cases.stream().mapToInt(value -> value.compression().maxSummaryDepth()).max().orElse(0)).append(" |\n")
                .append("| estimated tokens removed | ").append(cases.stream().mapToInt(value -> value.compression().compressionTokensRemoved()).sum()).append(" |\n")
                .append("| latency ms | ").append(cases.stream().mapToLong(ContextLifecycleBenchmarkResult.CaseResult::compressionTotalLatencyMs).sum()).append(" |\n\n")
                .append("## Latency\n\n")
                .append("| Phase | Median ms | P95 ms | Max ms |\n| --- | ---: | ---: | ---: |\n")
                .append(metricRow(cases, "task", ContextLifecycleBenchmarkResult.CaseResult::taskTotalLatencyMs))
                .append(metricRow(cases, "think", ContextLifecycleBenchmarkResult.CaseResult::thinkTotalLatencyMs))
                .append(metricRow(cases, "tool", ContextLifecycleBenchmarkResult.CaseResult::toolTotalLatencyMs))
                .append(metricRow(cases, "compression", ContextLifecycleBenchmarkResult.CaseResult::compressionTotalLatencyMs))
                .append(metricRow(cases, "final", ContextLifecycleBenchmarkResult.CaseResult::finalLatencyMs))
                .append("\n").append(p95Note).append("\n\n")
                .append("## Stability\n\n")
                .append("| Metric | Count |\n| --- | ---: |\n")
                .append("| recorded failures | ").append(failures).append(" |\n")
                .append("| orphan tool protocol | ").append(orphan).append(" |\n")
                .append("| protocol validation failure | ").append(protocol).append(" |\n")
                .append("| context overflow | ").append(cases.stream().mapToInt(value -> value.stability().contextOverflowCount()).sum()).append(" |\n")
                .append("| compression failure | ").append(cases.stream().mapToInt(value -> value.stability().compressionFailureCount()).sum()).append(" |\n")
                .append("| tool execution failure | ").append(cases.stream().mapToInt(value -> value.stability().toolExecutionFailureCount()).sum()).append(" |\n\n")
                .append("## Legacy Architecture Observations\n\n")
                .append("- Final request 中 TaskToolTranscript 的估算 token 合计：")
                .append(cases.stream().mapToInt(value -> value.context().taskToolTranscriptEstimatedTokens()).sum()).append("。\n")
                .append("- Final request 因 transcript merge 增加的可归因估算 token 合计：")
                .append(cases.stream().mapToInt(value -> value.context().finalTranscriptContributionTokens()).sum()).append("。\n")
                .append("- Task2 首轮 THINK 中归因到 completed-task tool protocol 的估算 token 合计：")
                .append(cases.stream().mapToInt(value -> value.tools().crossTaskToolResultTokens()).sum()).append("。\n")
                .append("- 以上 token 均使用统一 message estimator；provider usage 仅保存在 raw JSON 的 actual 字段，不与估算值混算。\n\n")
                .append("## Measurement Limitations\n\n")
                .append("- Compression 调用当前 provider usage 不可取得，其 input/output token 仅为估算。\n")
                .append("- Working context、来源归因、Tool Result 与 TaskToolTranscript 均为 `ESTIMATED_MESSAGE_CHARS_V1`。\n")
                .append("- `summaryDepth` 定义为同一 task 内按发生顺序累计的 compression generation。\n")
                .append("- Correctness 使用确定性 structured fact coverage，不使用 LLM judge；语义同义词覆盖受 case 词表限制。\n")
                .append("- 外部 repo 工作区状态被记录但 snapshot 由数据库 file/chunk manifests 冻结。\n\n")
                .append("## Anomalies\n\n")
                .append("详见 `").append(ANOMALIES_CSV).append("`；空数据行表示本次未发现异常。\n");
        return md.toString();
    }

    private String metricRow(List<ContextLifecycleBenchmarkResult.CaseResult> cases,
                             String label,
                             ToLongFunction<ContextLifecycleBenchmarkResult.CaseResult> getter) {
        List<Long> values = cases.stream().mapToLong(getter).sorted().boxed().toList();
        return "| " + label + " | " + percentile(values, 0.50) + " | "
                + percentile(values, 0.95) + " | " + (values.isEmpty() ? 0 : values.get(values.size() - 1)) + " |\n";
    }

    private long percentile(List<Long> sorted, double quantile) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int rank = Math.max(1, (int) Math.ceil(quantile * sorted.size()));
        return sorted.get(rank - 1);
    }

    private double mean(List<ContextLifecycleBenchmarkResult.CaseResult> values,
                        ToDoubleFunction<ContextLifecycleBenchmarkResult.CaseResult> getter) {
        return values.stream().mapToDouble(getter).average().orElse(0.0);
    }

    private void row(StringBuilder output, List<?> values) {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                output.append(',');
            }
            output.append(csv(String.valueOf(values.get(index))));
        }
        output.append('\n');
    }

    private String csv(String value) {
        String safe = value == null ? "" : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private Object nullable(Object value) {
        return value == null ? "unavailable" : value;
    }

    private String safe(String value) {
        return value == null ? "unavailable" : value;
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private String escapeMd(String value) {
        return safe(value).replace("|", "\\|").replace("\n", " ");
    }

    record Artifacts(Path rawJson, Path caseCsv, Path markdownReport, Path anomaliesCsv) {
    }
}
