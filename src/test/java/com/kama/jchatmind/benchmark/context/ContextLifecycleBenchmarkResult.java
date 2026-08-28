package com.kama.jchatmind.benchmark.context;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Provider-independent raw result contract for the context lifecycle benchmark.
 * Nullable actual token fields mean that the provider did not expose usage; they
 * must never be filled from estimates.
 */
record ContextLifecycleBenchmarkResult(
        RunMetadata run,
        List<CaseResult> cases) {

    ContextLifecycleBenchmarkResult {
        cases = immutable(cases);
    }

    record RunMetadata(
            String benchmarkRunId,
            String benchmarkSuiteVersion,
            String architectureLabel,
            String gitCommit,
            String workingTreeStatus,
            String model,
            Double temperature,
            Integer seed,
            Map<String, Object> modelParameters,
            String repoId,
            String repoName,
            String repoExternalHead,
            String repoWorkingTreeStatus,
            String repoFileManifestDigest,
            String repoChunkManifestDigest,
            int repoFileCount,
            int repoChunkCount,
            int repoEmbeddingCount,
            int maxSteps,
            int retrievalTopK,
            Map<String, Object> selectorConfig,
            Map<String, Object> contextCompressionConfig,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt,
            int recommendedRepeats,
            int actualRepeats,
            String tokenMeasurement,
            String correctnessScoring) {

        RunMetadata {
            modelParameters = immutable(modelParameters);
            selectorConfig = immutable(selectorConfig);
            contextCompressionConfig = immutable(contextCompressionConfig);
        }
    }

    record CaseResult(
            String benchmarkCaseId,
            String caseCategory,
            int repeatIndex,
            String taskId,
            String sessionId,
            String taskStatus,
            String finishReason,
            long taskTotalLatencyMs,
            long thinkTotalLatencyMs,
            long toolTotalLatencyMs,
            long compressionTotalLatencyMs,
            long finalLatencyMs,
            TokenTotals tokens,
            ContextMetrics context,
            ToolMetrics tools,
            CompressionTotals compression,
            StabilityMetrics stability,
            CorrectnessMetrics correctness,
            List<ModelCallMetric> modelCalls,
            List<ToolCallMetric> toolCalls,
            List<CompressionMetric> compressionEvents,
            String finalAnswer,
            List<String> failures) {

        CaseResult {
            modelCalls = immutable(modelCalls);
            toolCalls = immutable(toolCalls);
            compressionEvents = immutable(compressionEvents);
            failures = immutable(failures);
        }
    }

    record TokenTotals(
            TokenMeasurement taskInput,
            TokenMeasurement taskOutput,
            TokenMeasurement thinkInput,
            TokenMeasurement thinkOutput,
            TokenMeasurement compressionInput,
            TokenMeasurement compressionOutput,
            TokenMeasurement finalInput,
            TokenMeasurement finalOutput,
            TokenMeasurement selectorInput,
            TokenMeasurement selectorOutput) {
    }

    /** Actual and estimated values are deliberately separate and carry separate provenance. */
    record TokenMeasurement(
            Integer actualTokens,
            Integer estimatedTokens,
            String actualSource,
            String estimatedSource) {

        TokenMeasurement {
            if (actualTokens != null && actualTokens < 0) {
                throw new IllegalArgumentException("actualTokens cannot be negative");
            }
            if (estimatedTokens != null && estimatedTokens < 0) {
                throw new IllegalArgumentException("estimatedTokens cannot be negative");
            }
            if (actualTokens != null && blank(actualSource)) {
                throw new IllegalArgumentException("actualSource is required when actualTokens is present");
            }
            if (estimatedTokens != null && blank(estimatedSource)) {
                throw new IllegalArgumentException("estimatedSource is required when estimatedTokens is present");
            }
        }

        static TokenMeasurement unavailable() {
            return new TokenMeasurement(null, null, "UNAVAILABLE", "UNAVAILABLE");
        }
    }

    record ContextMetrics(
            int maxWorkingContextTokensObserved,
            List<Integer> contextTokensBeforeEachThink,
            int finalContextTokens,
            int currentTaskTokens,
            int completedTaskUserFinalTokens,
            int completedTaskToolTokens,
            int sessionSummaryTokens,
            int unknownTokens,
            int taskToolTranscriptEntryCount,
            int taskToolTranscriptEstimatedTokens,
            Integer finalContextTokensBeforeTranscriptMerge,
            Integer finalContextTokensAfterTranscriptMerge,
            int finalTranscriptContributionTokens) {

        ContextMetrics {
            contextTokensBeforeEachThink = immutable(contextTokensBeforeEachThink);
        }
    }

    record ToolMetrics(
            int toolCallCount,
            Map<String, Integer> toolCallCountByTool,
            int toolResultTokensProduced,
            int toolResultTokensInjectedIntoModelContext,
            int singleLargestToolResultTokens,
            int crossTaskToolResultTokens) {

        ToolMetrics {
            toolCallCountByTool = immutable(toolCallCountByTool);
        }
    }

    record CompressionTotals(
            int compressionCountPerTask,
            int compressionInputTokens,
            int compressionOutputTokens,
            int compressionTokensRemoved,
            int maxSummaryDepth) {
    }

    record StabilityMetrics(
            int contextOverflowCount,
            int compressionFailureCount,
            int orphanToolProtocolCount,
            int protocolValidationFailureCount,
            int toolExecutionFailureCount) {
    }

    record CorrectnessMetrics(
            double criticalFactRecall,
            double exactValueAccuracy,
            int forbiddenClaimCount,
            List<FactCheck> criticalFacts,
            List<FactCheck> supportingFacts,
            List<FactCheck> exactValues,
            List<FactCheck> forbiddenClaims,
            String judgeStatus) {

        CorrectnessMetrics {
            criticalFacts = immutable(criticalFacts);
            supportingFacts = immutable(supportingFacts);
            exactValues = immutable(exactValues);
            forbiddenClaims = immutable(forbiddenClaims);
        }
    }

    record FactCheck(String id, String expected, boolean matched, String evidence) {
    }

    record ModelCallMetric(
            int callIndex,
            long startedAtEpochMs,
            String phase,
            String model,
            Long latencyMs,
            String finishReason,
            TokenMeasurement inputTokens,
            TokenMeasurement outputTokens,
            int requestMessageCount,
            int requestContextEstimatedTokens,
            String requestContextTokenSource,
            Map<String, Integer> contextTokensByOrigin,
            String failure) {

        ModelCallMetric {
            contextTokensByOrigin = immutable(contextTokensByOrigin);
        }
    }

    record ToolCallMetric(
            int callIndex,
            String toolName,
            String actualToolName,
            String toolCallId,
            String status,
            long latencyMs,
            int producedEstimatedTokens,
            int persistedEstimatedTokens,
            boolean resultTruncated,
            String failureType) {
    }

    record CompressionMetric(
            int eventIndex,
            String reason,
            int tokensBeforeCompression,
            int tokensAfterCompression,
            int tokensRemoved,
            double compressionRatio,
            int compressionInputTokens,
            int compressionOutputTokens,
            long compressionLatencyMs,
            int summaryDepth,
            String tokenSource,
            boolean succeeded,
            String failure) {
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static <K, V> Map<K, V> immutable(Map<K, V> values) {
        return values == null ? Map.of() : Map.copyOf(values);
    }
}
