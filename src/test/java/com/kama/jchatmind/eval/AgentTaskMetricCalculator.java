package com.kama.jchatmind.eval;

import java.util.List;
import java.util.Objects;

class AgentTaskMetricCalculator {
    private final PercentileCalculator percentiles = new PercentileCalculator();

    Summary summarize(String group, List<AgentTaskEvalResult> results) {
        int total = results.size();
        int taskSuccess = count(results, AgentTaskEvalResult::taskSuccess);
        int runtimeSuccess = count(results, AgentTaskEvalResult::runtimeSuccess);
        List<AgentTaskEvalResult> evidenceCases = results.stream()
                .filter(result -> result.evidenceHit() != null).toList();
        List<AgentTaskEvalResult> requiredToolCases = results.stream()
                .filter(result -> result.requiredToolHit() != null).toList();
        int evidenceSuccess = count(evidenceCases, result -> Boolean.TRUE.equals(result.evidenceHit()));
        int requiredToolSuccess = count(requiredToolCases,
                result -> Boolean.TRUE.equals(result.requiredToolHit()));
        int forbiddenUsed = count(results, AgentTaskEvalResult::forbiddenToolUsed);

        return new Summary(
                group,
                total,
                taskSuccess,
                rate(taskSuccess, total),
                runtimeSuccess,
                rate(runtimeSuccess, total),
                evidenceSuccess,
                evidenceCases.size(),
                rate(evidenceSuccess, evidenceCases.size()),
                requiredToolSuccess,
                requiredToolCases.size(),
                rate(requiredToolSuccess, requiredToolCases.size()),
                forbiddenUsed,
                rate(forbiddenUsed, total),
                average(results.stream().mapToInt(AgentTaskEvalResult::thinkSteps).boxed().toList()),
                average(results.stream().mapToInt(AgentTaskEvalResult::executedToolCalls).boxed().toList()),
                distribution(results.stream().map(result -> (long) result.thinkSteps()).toList()),
                distribution(results.stream().map(result -> (long) result.requestedToolCalls()).toList()),
                distribution(results.stream().map(result -> (long) result.executedToolCalls()).toList()),
                distribution(results.stream().map(AgentTaskEvalResult::totalLatencyMs).toList()),
                results.stream().mapToInt(AgentTaskEvalResult::duplicateRejectCount).sum(),
                count(results, result -> result.duplicateRejectCount() > 0),
                results.stream().mapToInt(AgentTaskEvalResult::timeoutCount).sum(),
                count(results, result -> result.timeoutCount() > 0),
                results.stream().mapToInt(AgentTaskEvalResult::resultTruncatedCount).sum(),
                count(results, result -> result.resultTruncatedCount() > 0),
                results.stream().mapToInt(AgentTaskEvalResult::hardStopCount).sum(),
                tokenStatus(results),
                tokenStats(results, TokenType.PROMPT),
                tokenStats(results, TokenType.COMPLETION),
                tokenStats(results, TokenType.TOTAL));
    }

    private int count(List<AgentTaskEvalResult> results,
                      java.util.function.Predicate<AgentTaskEvalResult> predicate) {
        return (int) results.stream().filter(predicate).count();
    }

    private double rate(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private double average(List<Integer> values) {
        return values.stream().mapToInt(Integer::intValue).average().orElse(0.0);
    }

    private Distribution distribution(List<Long> values) {
        return new Distribution(
                percentiles.percentile(values, 0.50),
                percentiles.percentile(values, 0.95),
                percentiles.percentile(values, 0.99),
                values.stream().mapToLong(Long::longValue).max().orElse(0));
    }

    private String tokenStatus(List<AgentTaskEvalResult> results) {
        long available = results.stream().filter(AgentTaskEvalResult::tokenUsageAvailable).count();
        if (available == 0) {
            return "UNAVAILABLE";
        }
        return available == results.size() ? "AVAILABLE" : "PARTIAL";
    }

    private TokenStats tokenStats(List<AgentTaskEvalResult> results, TokenType type) {
        List<Long> values = results.stream()
                .map(result -> switch (type) {
                    case PROMPT -> result.promptTokens();
                    case COMPLETION -> result.completionTokens();
                    case TOTAL -> result.totalTokens();
                })
                .filter(Objects::nonNull)
                .map(Integer::longValue)
                .toList();
        return new TokenStats(
                percentiles.percentile(values, 0.50),
                percentiles.percentile(values, 0.95),
                values.stream().mapToLong(Long::longValue).sum());
    }

    enum TokenType { PROMPT, COMPLETION, TOTAL }

    record Distribution(long p50, long p95, long p99, long max) {
    }

    record TokenStats(long p50, long p95, long sum) {
    }

    record Summary(
            String group,
            int cases,
            int taskSuccessCount,
            double taskSuccessRate,
            int runtimeSuccessCount,
            double runtimeSuccessRate,
            int evidenceSuccessCount,
            int evidenceApplicableCount,
            double evidenceSuccessRate,
            int requiredToolHitCount,
            int requiredToolApplicableCount,
            double requiredToolHitRate,
            int forbiddenToolUsedCount,
            double forbiddenToolUsageRate,
            double avgThinkSteps,
            double avgExecutedToolCalls,
            Distribution thinkSteps,
            Distribution requestedToolCalls,
            Distribution executedToolCalls,
            Distribution latencyMs,
            int duplicateRejectCount,
            int duplicateAffectedCases,
            int timeoutCount,
            int timeoutAffectedCases,
            int resultTruncatedCount,
            int resultTruncatedAffectedCases,
            int hardStopCount,
            String tokenStatus,
            TokenStats promptTokens,
            TokenStats completionTokens,
            TokenStats totalTokens) {
    }
}
