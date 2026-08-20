package com.kama.jchatmind.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

class AgentTaskEvaluationReportWriter {
    static final List<String> DETAIL_HEADERS = List.of(
            "case_id", "category", "difficulty", "status", "failure_type", "task_id", "finish_reason",
            "think_steps", "tool_steps", "requested_tool_calls", "executed_tool_calls", "rejected_tool_calls",
            "execution_count_reliable", "tool_names", "tool_arguments", "required_tool_hit",
            "forbidden_tool_used", "argument_hit", "evidence_hit", "final_answer_present",
            "duplicate_reject_count", "timeout_count", "result_truncated_count", "hard_stop_count",
            "reasonable_steps_exceeded", "latency_ms", "token_usage_status", "prompt_tokens",
            "completion_tokens", "total_tokens", "trajectory"
    );
    static final List<String> SUMMARY_HEADERS = List.of(
            "group", "case_count", "task_success_count", "task_success_rate", "runtime_success_rate",
            "evidence_success_rate", "required_tool_hit_rate", "forbidden_tool_usage_rate",
            "avg_think_steps", "avg_executed_tool_calls", "think_p50", "think_p95", "think_max",
            "requested_p50", "requested_p95", "requested_max", "executed_p50", "executed_p95",
            "executed_max", "latency_p50_ms", "latency_p95_ms", "latency_p99_ms",
            "duplicate_reject_count", "duplicate_affected_cases", "timeout_count", "timeout_affected_cases",
            "result_truncated_count", "result_truncated_affected_cases", "hard_stop_count", "token_status",
            "total_tokens_p50", "total_tokens_p95", "total_tokens_sum"
    );

    private final AgentTaskMetricCalculator metrics = new AgentTaskMetricCalculator();

    void write(Path outputDirectory, List<AgentTaskEvalResult> results, Environment environment) throws IOException {
        Files.createDirectories(outputDirectory);
        List<AgentTaskMetricCalculator.Summary> summaries = summaries(results);
        Files.writeString(outputDirectory.resolve("agent-task-eval-detail.csv"),
                detailCsv(results), StandardCharsets.UTF_8);
        Files.writeString(outputDirectory.resolve("agent-task-eval-summary.csv"),
                summaryCsv(summaries), StandardCharsets.UTF_8);
        Files.writeString(outputDirectory.resolve("agent-task-evaluation-report.md"),
                markdown(results, summaries, environment), StandardCharsets.UTF_8);
    }

    String detailCsv(List<AgentTaskEvalResult> results) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(DETAIL_HEADERS);
        for (AgentTaskEvalResult result : results) {
            rows.add(List.of(
                    result.evalCase().id, result.evalCase().category, result.evalCase().difficulty,
                    bool(result.taskSuccess()), result.failureType().name(), safe(result.taskId()),
                    safe(result.finishReason()), Integer.toString(result.thinkSteps()),
                    Integer.toString(result.toolSteps()), Integer.toString(result.requestedToolCalls()),
                    Integer.toString(result.executedToolCalls()), Integer.toString(result.rejectedToolCalls()),
                    bool(result.executionCountReliable()), join(result.toolNames()), join(result.toolArguments()),
                    nullable(result.requiredToolHit()), bool(result.forbiddenToolUsed()), nullable(result.argumentHit()),
                    nullable(result.evidenceHit()), bool(result.finalAnswerPresent()),
                    Integer.toString(result.duplicateRejectCount()), Integer.toString(result.timeoutCount()),
                    Integer.toString(result.resultTruncatedCount()), Integer.toString(result.hardStopCount()),
                    bool(result.reasonableStepsExceeded()), Long.toString(result.totalLatencyMs()),
                    result.tokenUsageAvailable() ? "AVAILABLE" : "UNAVAILABLE",
                    nullable(result.promptTokens()), nullable(result.completionTokens()), nullable(result.totalTokens()),
                    safe(result.trajectorySummary())
            ));
        }
        return csv(rows);
    }

    String summaryCsv(List<AgentTaskMetricCalculator.Summary> summaries) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(SUMMARY_HEADERS);
        summaries.forEach(summary -> rows.add(summaryRow(summary)));
        return csv(rows);
    }

    List<AgentTaskMetricCalculator.Summary> summaries(List<AgentTaskEvalResult> results) {
        Map<String, List<AgentTaskEvalResult>> groups = new LinkedHashMap<>();
        groups.put("ALL", results);
        results.stream().map(result -> result.evalCase().difficulty).distinct().sorted()
                .forEach(value -> groups.put("DIFFICULTY:" + value, results.stream()
                        .filter(result -> value.equals(result.evalCase().difficulty)).toList()));
        results.stream().map(result -> result.evalCase().category).distinct().sorted()
                .forEach(value -> groups.put("CATEGORY:" + value, results.stream()
                        .filter(result -> value.equals(result.evalCase().category)).toList()));
        return groups.entrySet().stream().map(entry -> metrics.summarize(entry.getKey(), entry.getValue())).toList();
    }

    String markdown(List<AgentTaskEvalResult> results,
                    List<AgentTaskMetricCalculator.Summary> summaries,
                    Environment environment) {
        AgentTaskMetricCalculator.Summary all = summaries.get(0);
        StringBuilder out = new StringBuilder();
        out.append("# Agent Task Eval V1 报告\n\n");
        out.append("## 评测环境\n\n")
                .append("- 时间：").append(environment.runAt()).append('\n')
                .append("- Java：").append(environment.javaVersion()).append('\n')
                .append("- Repository：").append(environment.repositoryName()).append(" (")
                .append(environment.repositoryId()).append(")\n")
                .append("- repository files/chunks/embeddings：").append(environment.fileCount()).append('/')
                .append(environment.chunkCount()).append('/').append(environment.embeddingCount()).append('\n')
                .append("- Agent：").append(environment.agentId()).append('\n')
                .append("- Model：").append(environment.model()).append('\n')
                .append("- Case 数：").append(results.size()).append("\n\n")
                .append("> V1 的 Task Success 是 trajectory/evidence-oriented，")
                .append("不等价于完整自然语言答案质量 Judge。\n\n")
                .append("> ToolCallLog.resultTruncated 是 Runtime Guard 与 Trace summary 的综合标记；")
                .append("当前 Trace 无法单独拆分两者。POLICY_REJECTED 出现时，Callback 是否进入过业务校验也无法完全区分。\n\n");

        out.append("## Overall\n\n")
                .append("| Task Success | Runtime Success | Evidence Success | Required Tool Hit | Forbidden Tool Usage |\n")
                .append("| ---: | ---: | ---: | ---: | ---: |\n")
                .append("| ").append(percent(all.taskSuccessRate())).append(" | ")
                .append(percent(all.runtimeSuccessRate())).append(" | ")
                .append(percent(all.evidenceSuccessRate())).append(" | ")
                .append(percent(all.requiredToolHitRate())).append(" | ")
                .append(percent(all.forbiddenToolUsageRate())).append(" |\n\n");

        out.append("## Trajectory\n\n")
                .append("| Metric | P50 | P95 | Max |\n| --- | ---: | ---: | ---: |\n");
        distributionRow(out, "Think Steps", all.thinkSteps());
        distributionRow(out, "Requested Tool Calls", all.requestedToolCalls());
        distributionRow(out, "Executed Tool Calls", all.executedToolCalls());
        out.append("\n| Latency | P50 ms | P95 ms | P99 ms |\n| --- | ---: | ---: | ---: |\n")
                .append("| Task | ").append(all.latencyMs().p50()).append(" | ")
                .append(all.latencyMs().p95()).append(" | ").append(all.latencyMs().p99()).append(" |\n\n");

        out.append("## Governance\n\n")
                .append("- Duplicate rejects：").append(all.duplicateRejectCount()).append(" events / ")
                .append(all.duplicateAffectedCases()).append(" cases\n")
                .append("- Tool timeout：").append(all.timeoutCount()).append(" events / ")
                .append(all.timeoutAffectedCases()).append(" cases\n")
                .append("- Result truncated：").append(all.resultTruncatedCount()).append(" events / ")
                .append(all.resultTruncatedAffectedCases()).append(" cases\n")
                .append("- Hard stop：").append(all.hardStopCount()).append("\n\n")
                .append("## Token Usage\n\n")
                .append("Token Usage：").append(all.tokenStatus()).append("\n\n");

        appendGroupedResults(out, summaries);
        appendFailures(out, results);
        appendTypicalFailures(out, results);
        appendSignals(out, results, all);
        return out.toString();
    }

    private void appendGroupedResults(StringBuilder out, List<AgentTaskMetricCalculator.Summary> summaries) {
        out.append("## Difficulty / Category\n\n")
                .append("| Group | Cases | Success | Success Rate | Avg Steps | Avg Executed Calls |\n")
                .append("| --- | ---: | ---: | ---: | ---: | ---: |\n");
        summaries.stream().skip(1).forEach(summary -> out.append("| ").append(summary.group())
                .append(" | ").append(summary.cases()).append(" | ").append(summary.taskSuccessCount())
                .append(" | ").append(percent(summary.taskSuccessRate())).append(" | ")
                .append(decimal(summary.avgThinkSteps())).append(" | ")
                .append(decimal(summary.avgExecutedToolCalls())).append(" |\n"));
        out.append('\n');
    }

    private void appendFailures(StringBuilder out, List<AgentTaskEvalResult> results) {
        out.append("## Failure Distribution\n\n| Failure Type | Count | Cases |\n| --- | ---: | --- |\n");
        Arrays.stream(AgentTaskFailureType.values())
                .map(type -> Map.entry(type, results.stream().filter(result -> result.failureType() == type).toList()))
                .filter(entry -> !entry.getValue().isEmpty())
                .forEach(entry -> out.append("| ").append(entry.getKey()).append(" | ")
                        .append(entry.getValue().size()).append(" | ")
                        .append(entry.getValue().stream().map(result -> result.evalCase().id)
                                .collect(Collectors.joining(", "))).append(" |\n"));
        out.append('\n');
    }

    private void appendTypicalFailures(StringBuilder out, List<AgentTaskEvalResult> results) {
        out.append("## Typical Failures\n\n");
        List<AgentTaskEvalResult> failures = results.stream().filter(result -> !result.taskSuccess())
                .sorted(Comparator.comparing(result -> result.evalCase().difficulty)).limit(5).toList();
        if (failures.isEmpty()) {
            out.append("No failed cases.\n\n");
            return;
        }
        failures.forEach(result -> out.append("### ").append(result.evalCase().id).append("\n\n")
                .append("- Query：").append(result.evalCase().query).append('\n')
                .append("- Trajectory：").append(result.trajectorySummary()).append('\n')
                .append("- Primary failure：").append(result.failureType()).append("\n\n"));
    }

    private void appendSignals(StringBuilder out, List<AgentTaskEvalResult> results,
                               AgentTaskMetricCalculator.Summary all) {
        List<AgentTaskEvalResult> queryRewriteCases = results.stream()
                .filter(result -> AgentTaskEvalCase.safe(result.evalCase().requiredTools).stream()
                        .anyMatch("searchProjectCode"::equalsIgnoreCase))
                .filter(result -> Boolean.TRUE.equals(result.requiredToolHit()))
                .filter(result -> Boolean.FALSE.equals(result.evidenceHit()))
                .toList();
        long applicableCodeCases = results.stream().filter(result -> result.evalCase().evidenceApplicable())
                .filter(result -> AgentTaskEvalCase.safe(result.evalCase().requiredTools).stream()
                        .anyMatch("searchProjectCode"::equalsIgnoreCase)).count();
        String querySignal = queryRewriteCases.size() >= 3 ? "YES"
                : queryRewriteCases.isEmpty() && applicableCodeCases >= 5 ? "NO" : "INSUFFICIENT_EVIDENCE";
        out.append("## Query Rewrite Signal\n\n")
                .append("Signal：").append(querySignal).append("\n\n")
                .append("Supporting cases：")
                .append(queryRewriteCases.stream().map(result -> result.evalCase().id)
                        .collect(Collectors.joining(", "))).append("\n\n");

        String toolBudgetSignal = all.requestedToolCalls().p95() >= 8 || all.requestedToolCalls().max() >= 12
                ? "REVIEW_REQUIRED" : "NO_CURRENT_SIGNAL";
        out.append("## maxToolCalls Signal\n\n")
                .append("Signal：").append(toolBudgetSignal).append("\n\n")
                .append("Requested calls P95/Max：").append(all.requestedToolCalls().p95()).append('/')
                .append(all.requestedToolCalls().max()).append("\n\n");
    }

    private List<String> summaryRow(AgentTaskMetricCalculator.Summary value) {
        return List.of(value.group(), Integer.toString(value.cases()),
                Integer.toString(value.taskSuccessCount()), decimal(value.taskSuccessRate()),
                decimal(value.runtimeSuccessRate()), decimal(value.evidenceSuccessRate()),
                decimal(value.requiredToolHitRate()), decimal(value.forbiddenToolUsageRate()),
                decimal(value.avgThinkSteps()), decimal(value.avgExecutedToolCalls()),
                Long.toString(value.thinkSteps().p50()), Long.toString(value.thinkSteps().p95()),
                Long.toString(value.thinkSteps().max()), Long.toString(value.requestedToolCalls().p50()),
                Long.toString(value.requestedToolCalls().p95()), Long.toString(value.requestedToolCalls().max()),
                Long.toString(value.executedToolCalls().p50()), Long.toString(value.executedToolCalls().p95()),
                Long.toString(value.executedToolCalls().max()), Long.toString(value.latencyMs().p50()),
                Long.toString(value.latencyMs().p95()), Long.toString(value.latencyMs().p99()),
                Integer.toString(value.duplicateRejectCount()), Integer.toString(value.duplicateAffectedCases()),
                Integer.toString(value.timeoutCount()), Integer.toString(value.timeoutAffectedCases()),
                Integer.toString(value.resultTruncatedCount()), Integer.toString(value.resultTruncatedAffectedCases()),
                Integer.toString(value.hardStopCount()), value.tokenStatus(),
                Long.toString(value.totalTokens().p50()), Long.toString(value.totalTokens().p95()),
                Long.toString(value.totalTokens().sum()));
    }

    private void distributionRow(StringBuilder out, String name, AgentTaskMetricCalculator.Distribution value) {
        out.append("| ").append(name).append(" | ").append(value.p50()).append(" | ")
                .append(value.p95()).append(" | ").append(value.max()).append(" |\n");
    }

    private String csv(List<List<String>> rows) {
        return rows.stream().map(row -> row.stream().map(this::escapeCsv)
                .collect(Collectors.joining(","))).collect(Collectors.joining("\n", "", "\n"));
    }

    private String escapeCsv(String value) {
        String safe = safe(value);
        return safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")
                ? "\"" + safe.replace("\"", "\"\"") + "\"" : safe;
    }

    private String join(List<String> values) {
        return values == null ? "" : String.join(" | ", values);
    }

    private String nullable(Object value) {
        return value == null ? "UNSUPPORTED" : String.valueOf(value);
    }

    private String bool(boolean value) {
        return Boolean.toString(value);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String percent(double value) {
        return String.format(Locale.ROOT, "%.2f%%", value * 100);
    }

    private String decimal(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    record Environment(OffsetDateTime runAt, String javaVersion, String repositoryId,
                       String repositoryName, long fileCount, long chunkCount, long embeddingCount,
                       String agentId, String model) {
    }
}
