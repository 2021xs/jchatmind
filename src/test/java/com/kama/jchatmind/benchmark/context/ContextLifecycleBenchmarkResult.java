package com.kama.jchatmind.benchmark.context;

import com.kama.jchatmind.agent.FinalSynthesisRequest;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
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
            ExecutionArchitecture executionArchitecture,
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
            if (executionArchitecture == null) {
                throw new IllegalArgumentException("executionArchitecture is required");
            }
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
            EvidenceLifecycleDiagnostics diagnostics,
            String finalAnswer,
            List<String> failures) {

        CaseResult {
            modelCalls = immutable(modelCalls);
            toolCalls = immutable(toolCalls);
            compressionEvents = immutable(compressionEvents);
            diagnostics = diagnostics == null ? EvidenceLifecycleDiagnostics.empty() : diagnostics;
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
            Integer taskToolTranscriptEstimatedTokens,
            TranscriptMetricStatus taskToolTranscriptStatus,
            Integer finalContextTokensBeforeTranscriptMerge,
            Integer finalContextTokensAfterTranscriptMerge,
            Integer finalTranscriptContributionTokens,
            TranscriptMetricStatus finalTranscriptContributionStatus) {

        ContextMetrics {
            contextTokensBeforeEachThink = immutable(contextTokensBeforeEachThink);
            validateTranscriptMetric(taskToolTranscriptEstimatedTokens, taskToolTranscriptStatus,
                    "taskToolTranscriptEstimatedTokens");
            validateTranscriptMetric(finalTranscriptContributionTokens, finalTranscriptContributionStatus,
                    "finalTranscriptContributionTokens");
        }
    }

    enum ExecutionArchitecture {
        LEGACY,
        TASK_AWARE;

        static final String PROPERTY = "context.benchmark.architecture";

        static ExecutionArchitecture configured() {
            return parse(System.getProperty(PROPERTY, LEGACY.name()));
        }

        static ExecutionArchitecture parse(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(PROPERTY + " must be LEGACY or TASK_AWARE");
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException(
                        "Unsupported " + PROPERTY + ": " + value + "; expected LEGACY or TASK_AWARE",
                        error);
            }
        }
    }

    enum TranscriptMetricStatus {
        PRESENT,
        REMOVED_NOT_APPLICABLE
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

    record EvidenceLifecycleDiagnostics(
            List<ToolResultDiagnostic> toolResults,
            List<SelectorProvenanceDiagnostic> selectorProvenance,
            List<CompressionDiagnostic> compressions,
            FinalDiagnostic finalRequest) {

        EvidenceLifecycleDiagnostics {
            toolResults = immutable(toolResults);
            selectorProvenance = immutable(selectorProvenance);
            compressions = immutable(compressions);
        }

        static EvidenceLifecycleDiagnostics empty() {
            return new EvidenceLifecycleDiagnostics(List.of(), List.of(), List.of(), null);
        }
    }

    record ToolResultDiagnostic(
            String taskId,
            String sessionId,
            String toolCallId,
            String toolName,
            String actualToolName,
            String canonicalBody,
            String projectedModelViewBody,
            String status) {
    }

    record EvidenceIdentity(
            String repoId,
            String chunkId,
            String filePath,
            String symbolName,
            int rank,
            Double score) {
    }

    record SelectorProvenanceDiagnostic(
            String taskId,
            String sessionId,
            String toolCallId,
            String query,
            List<EvidenceIdentity> rawTopK,
            List<EvidenceIdentity> selectorInput,
            List<EvidenceIdentity> selected,
            List<EvidenceIdentity> rejected) {

        SelectorProvenanceDiagnostic {
            rawTopK = immutable(rawTopK);
            selectorInput = immutable(selectorInput);
            selected = immutable(selected);
            rejected = immutable(rejected);
        }
    }

    record CompressionDiagnostic(
            String compressionAttemptId,
            String taskId,
            String sessionId,
            String inputBody,
            String primaryState,
            String correctiveInputBody,
            String correctiveState,
            String acceptedState,
            boolean accepted,
            int coveredThroughLogicalGroup,
            List<DiagnosticMessage> selectedLogicalGroupMessages,
            List<DiagnosticMessage> remainingRawLogicalGroupMessages,
            int summaryDepth,
            int compressionCount,
            int correctiveRetryCount,
            int inputTokens,
            int outputTokens,
            long latencyMs,
            String failure) {

        CompressionDiagnostic {
            selectedLogicalGroupMessages = immutable(selectedLogicalGroupMessages);
            remainingRawLogicalGroupMessages = immutable(remainingRawLogicalGroupMessages);
        }
    }

    record FinalDiagnostic(
            String taskId,
            String sessionId,
            List<DiagnosticMessage> preTranscriptExecutionContext,
            List<DiagnosticMessage> taskToolTranscript,
            FinalSynthesisRequest postTranscriptFinalRequest,
            List<ProviderRequestDiagnostic> compiledProviderRequests,
            int requestMessageCount,
            int transcriptMessageCount,
            int preTranscriptEstimatedTokens,
            int transcriptEstimatedTokens,
            int finalProviderEstimatedTokens,
            List<DiagnosticMessage> managedWorkingContext,
            FinalSynthesisRequest managedFinalShadowRequest,
            List<DiagnosticMessage> managedFinalShadowProviderMessages,
            String managedFinalShadowFailure,
            String acceptedState,
            int coveredThroughLogicalGroup,
            int shadowTranscriptReadCount,
            int managedFinalShadowEstimatedTokens) {

        FinalDiagnostic {
            preTranscriptExecutionContext = immutable(preTranscriptExecutionContext);
            taskToolTranscript = immutable(taskToolTranscript);
            compiledProviderRequests = immutable(compiledProviderRequests);
            managedWorkingContext = immutable(managedWorkingContext);
            managedFinalShadowProviderMessages = immutable(managedFinalShadowProviderMessages);
        }

        FinalDiagnostic(
                String taskId,
                String sessionId,
                List<DiagnosticMessage> preTranscriptExecutionContext,
                List<DiagnosticMessage> taskToolTranscript,
                FinalSynthesisRequest postTranscriptFinalRequest,
                List<ProviderRequestDiagnostic> compiledProviderRequests,
                int requestMessageCount,
                int transcriptMessageCount,
                int preTranscriptEstimatedTokens,
                int transcriptEstimatedTokens,
                int finalProviderEstimatedTokens) {
            this(taskId, sessionId, preTranscriptExecutionContext, taskToolTranscript,
                    postTranscriptFinalRequest, compiledProviderRequests, requestMessageCount,
                    transcriptMessageCount, preTranscriptEstimatedTokens, transcriptEstimatedTokens,
                    finalProviderEstimatedTokens, List.of(), null, List.of(), null,
                    null, 0, 0, 0);
        }
    }

    record ProviderRequestDiagnostic(int attempt, List<DiagnosticMessage> messages) {
        ProviderRequestDiagnostic {
            messages = immutable(messages);
        }
    }

    record DiagnosticMessage(
            String messageId,
            String role,
            String text,
            Map<String, Object> metadata,
            List<DiagnosticToolCall> toolCalls,
            List<DiagnosticToolResponse> toolResponses) {

        DiagnosticMessage {
            metadata = immutable(metadata);
            toolCalls = immutable(toolCalls);
            toolResponses = immutable(toolResponses);
        }
    }

    record DiagnosticToolCall(String id, String name, String type, String arguments) {
    }

    record DiagnosticToolResponse(String id, String name, String responseData) {
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void validateTranscriptMetric(
            Integer value, TranscriptMetricStatus status, String field) {
        if (status == null) {
            throw new IllegalArgumentException(field + " status is required");
        }
        if (status == TranscriptMetricStatus.PRESENT && value == null) {
            throw new IllegalArgumentException(field + " value is required when status is PRESENT");
        }
        if (status == TranscriptMetricStatus.REMOVED_NOT_APPLICABLE && value != null) {
            throw new IllegalArgumentException(field + " value must be null when component is removed");
        }
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static <K, V> Map<K, V> immutable(Map<K, V> values) {
        return values == null ? Map.of() : Map.copyOf(values);
    }
}
