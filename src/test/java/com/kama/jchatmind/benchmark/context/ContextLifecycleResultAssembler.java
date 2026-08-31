package com.kama.jchatmind.benchmark.context;

import com.kama.jchatmind.agent.AgentToolProtocolInspector;
import com.kama.jchatmind.agent.FinalContextCompiler;
import com.kama.jchatmind.agent.FinalSynthesisRequest;
import com.kama.jchatmind.agent.FinalSynthesisRequestFactory;
import com.kama.jchatmind.agent.observability.AgentLifecycleObservationPublisher;
import com.kama.jchatmind.config.FinalSynthesisProperties;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.AgentStep;
import com.kama.jchatmind.model.entity.AgentTask;
import com.kama.jchatmind.model.entity.ToolCallLog;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

final class ContextLifecycleResultAssembler {
    private final EstimatedMessageTokenMeasurer measurer;
    private final ContextOriginAttributor attributor;
    private final DeterministicCorrectnessScorer correctnessScorer = new DeterministicCorrectnessScorer();
    private final FinalContextCompiler finalContextCompiler;
    private final ContextLifecycleBenchmarkResult.ExecutionArchitecture executionArchitecture;

    ContextLifecycleResultAssembler(int charsPerToken, FinalSynthesisProperties finalProperties) {
        this(charsPerToken, finalProperties, ContextLifecycleBenchmarkResult.ExecutionArchitecture.LEGACY);
    }

    ContextLifecycleResultAssembler(
            int charsPerToken,
            FinalSynthesisProperties finalProperties,
            ContextLifecycleBenchmarkResult.ExecutionArchitecture executionArchitecture) {
        measurer = new EstimatedMessageTokenMeasurer(charsPerToken);
        attributor = new ContextOriginAttributor(measurer);
        finalContextCompiler = new FinalContextCompiler(finalProperties);
        this.executionArchitecture = Objects.requireNonNull(
                executionArchitecture, "executionArchitecture cannot be null");
    }

    ContextLifecycleBenchmarkResult.CaseResult assemble(ContextLifecycleCaseExecution execution) {
        AgentTask task = execution.task();
        String taskId = task == null ? execution.capture().taskId : task.getId();
        String finalAnswer = finalAnswer(execution);
        List<ContextLifecycleBenchmarkResult.ModelCallMetric> modelCalls = modelCalls(execution, taskId);
        List<ContextLifecycleBenchmarkResult.CompressionMetric> compressionEvents =
                compressionEvents(execution.capture().compressions);
        TranscriptMetrics transcript = transcriptMetrics(execution.capture().finalProjection.get(), modelCalls);
        List<ContextLifecycleBenchmarkResult.ToolCallMetric> toolCalls = toolCalls(execution);
        ContextLifecycleBenchmarkResult.ToolMetrics tools = toolTotals(execution, modelCalls, transcript);
        ContextLifecycleBenchmarkResult.ContextMetrics context = contextMetrics(modelCalls, transcript);
        ContextLifecycleBenchmarkResult.TokenTotals tokens = tokenTotals(modelCalls);
        ContextLifecycleBenchmarkResult.StabilityMetrics stability = stability(execution, modelCalls);
        ContextLifecycleBenchmarkResult.CorrectnessMetrics correctness =
                correctnessScorer.score(execution.benchmarkCase(), finalAnswer);
        List<String> failures = failures(execution, modelCalls, compressionEvents);
        ContextLifecycleBenchmarkResult.EvidenceLifecycleDiagnostics diagnostics =
                evidenceLifecycleDiagnostics(execution, taskId);

        return new ContextLifecycleBenchmarkResult.CaseResult(
                execution.benchmarkCase().caseId,
                execution.benchmarkCase().category,
                execution.repeatIndex(), taskId, execution.sessionId(),
                task == null ? "INFRA_ERROR" : task.getStatus(),
                task == null ? "UNAVAILABLE" : task.getFinishReason(),
                taskLatency(task),
                stepLatency(execution.steps(), "THINK", true),
                stepLatency(execution.steps(), "TOOL_CALL", false),
                compressionEvents.stream().mapToLong(ContextLifecycleBenchmarkResult.CompressionMetric::compressionLatencyMs).sum(),
                modelCalls.stream().filter(value -> "FINAL".equals(value.phase()))
                        .map(ContextLifecycleBenchmarkResult.ModelCallMetric::latencyMs)
                        .filter(Objects::nonNull).mapToLong(Long::longValue).sum(),
                tokens, context, tools,
                compressionTotals(compressionEvents), stability, correctness,
                modelCalls, toolCalls, compressionEvents, diagnostics, finalAnswer, failures);
    }

    private ContextLifecycleBenchmarkResult.EvidenceLifecycleDiagnostics evidenceLifecycleDiagnostics(
            ContextLifecycleCaseExecution execution, String taskId) {
        List<ContextLifecycleBenchmarkResult.ToolResultDiagnostic> toolResults =
                execution.capture().toolResults.stream()
                        .map(value -> new ContextLifecycleBenchmarkResult.ToolResultDiagnostic(
                                value.taskId(), value.sessionId(), value.toolCallId(),
                                value.canonicalToolName(), value.actualToolName(), value.rawResult(),
                                value.contextResult(), value.status()))
                        .toList();
        List<ContextLifecycleBenchmarkResult.SelectorProvenanceDiagnostic> selectorProvenance =
                execution.capture().selectorProvenance.stream()
                        .map(value -> new ContextLifecycleBenchmarkResult.SelectorProvenanceDiagnostic(
                                value.taskId(), value.sessionId(), value.toolCallId(), value.query(),
                                evidenceIdentities(value.rawTopK()), evidenceIdentities(value.selectorInput()),
                                evidenceIdentities(value.selected()), evidenceIdentities(value.rejected())))
                        .toList();
        List<ContextLifecycleBenchmarkResult.CompressionDiagnostic> compressions =
                execution.capture().compressions.stream()
                        .map(value -> new ContextLifecycleBenchmarkResult.CompressionDiagnostic(
                                value.compressionAttemptId(),
                                value.taskId() == null ? taskId : value.taskId(), value.sessionId(),
                                value.compressionPrompt(), value.primaryState(), value.correctivePrompt(),
                                value.correctiveState(), value.acceptedState(), value.accepted(),
                                value.coveredThroughLogicalGroup(),
                                diagnosticDtos(value.selectedProtocolMessages()),
                                diagnosticDtos(value.remainingRawProtocolMessages()),
                                value.summaryDepth(), value.compressionCount(), value.correctiveRetryCount(),
                                measurer.measureText(value.compressionPrompt()),
                                measurer.measureText(value.acceptedState()), value.latencyMs(), value.failure()))
                        .toList();
        return new ContextLifecycleBenchmarkResult.EvidenceLifecycleDiagnostics(
                toolResults, selectorProvenance, compressions, finalDiagnostic(execution));
    }

    private List<ContextLifecycleBenchmarkResult.EvidenceIdentity> evidenceIdentities(
            List<AgentLifecycleObservationPublisher.CodeEvidenceIdentity> values) {
        return values.stream()
                .map(value -> new ContextLifecycleBenchmarkResult.EvidenceIdentity(
                        value.repoId(), value.chunkId(), value.filePath(), value.symbolName(),
                        value.rank(), value.score()))
                .toList();
    }

    private ContextLifecycleBenchmarkResult.FinalDiagnostic finalDiagnostic(
            ContextLifecycleCaseExecution execution) {
        AgentLifecycleObservationPublisher.FinalProjectionObservation projection =
                execution.capture().finalProjection.get();
        if (projection == null && execution.capture().finalProviderRequests.isEmpty()) {
            return null;
        }
        List<ContextLifecycleBenchmarkResult.ProviderRequestDiagnostic> providerRequests =
                execution.capture().finalProviderRequests.stream()
                        .map(value -> new ContextLifecycleBenchmarkResult.ProviderRequestDiagnostic(
                                value.attempt(), diagnosticMessages(value.compiledProviderMessages())))
                        .toList();
        String taskId = projection == null ? execution.capture().taskId : projection.taskId();
        String sessionId = projection == null ? execution.sessionId() : projection.sessionId();
        List<ContextLifecycleBenchmarkResult.DiagnosticMessage> executionContext = projection == null
                ? List.of() : diagnosticMessages(projection.executionTranscript());
        List<ContextLifecycleBenchmarkResult.DiagnosticMessage> transcript = projection == null
                ? List.of() : diagnosticMessages(projection.currentTaskToolTranscript());
        int requestMessageCount = providerRequests.isEmpty()
                ? 0 : providerRequests.get(providerRequests.size() - 1).messages().size();
        List<Message> lastProviderMessages = execution.capture().finalProviderRequests.isEmpty()
                ? List.of() : execution.capture().finalProviderRequests.get(
                execution.capture().finalProviderRequests.size() - 1).compiledProviderMessages();
        return new ContextLifecycleBenchmarkResult.FinalDiagnostic(
                taskId, sessionId, executionContext, transcript,
                projection == null ? null : projection.finalRequest(), providerRequests,
                requestMessageCount, transcript.size(),
                projection == null ? 0 : measurer.measure(projection.executionTranscript(), null).tokens(),
                projection == null ? 0 : measurer.measure(projection.currentTaskToolTranscript(), null).tokens(),
                measurer.measure(lastProviderMessages, null).tokens());
    }

    private List<ContextLifecycleBenchmarkResult.DiagnosticMessage> diagnosticMessages(List<Message> messages) {
        return messages == null ? List.of() : messages.stream().map(this::diagnosticMessage).toList();
    }

    private ContextLifecycleBenchmarkResult.DiagnosticMessage diagnosticMessage(Message message) {
        List<ContextLifecycleBenchmarkResult.DiagnosticToolCall> toolCalls = message instanceof AssistantMessage assistant
                ? assistant.getToolCalls().stream()
                .map(call -> new ContextLifecycleBenchmarkResult.DiagnosticToolCall(
                        call.id(), call.name(), call.type(), call.arguments()))
                .toList() : List.of();
        List<ContextLifecycleBenchmarkResult.DiagnosticToolResponse> responses =
                message instanceof ToolResponseMessage toolResponse
                        ? toolResponse.getResponses().stream()
                        .map(value -> new ContextLifecycleBenchmarkResult.DiagnosticToolResponse(
                                value.id(), value.name(), value.responseData()))
                        .toList() : List.of();
        return new ContextLifecycleBenchmarkResult.DiagnosticMessage(
                null, message.getMessageType().name(), message.getText(), message.getMetadata(), toolCalls, responses);
    }

    private List<ContextLifecycleBenchmarkResult.DiagnosticMessage> diagnosticDtos(
            List<ChatMessageDTO> messages) {
        return messages == null ? List.of() : messages.stream().map(this::diagnosticDto).toList();
    }

    private ContextLifecycleBenchmarkResult.DiagnosticMessage diagnosticDto(ChatMessageDTO message) {
        List<ContextLifecycleBenchmarkResult.DiagnosticToolCall> calls =
                message.getMetadata() == null || message.getMetadata().getToolCalls() == null
                        ? List.of() : message.getMetadata().getToolCalls().stream()
                        .map(call -> new ContextLifecycleBenchmarkResult.DiagnosticToolCall(
                                call.id(), call.name(), call.type(), call.arguments()))
                        .toList();
        ToolResponseMessage.ToolResponse response = message.getMetadata() == null
                ? null : message.getMetadata().getToolResponse();
        List<ContextLifecycleBenchmarkResult.DiagnosticToolResponse> responses = response == null
                ? List.of() : List.of(new ContextLifecycleBenchmarkResult.DiagnosticToolResponse(
                response.id(), response.name(), response.responseData()));
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (message.getMetadata() != null) {
            if (message.getMetadata().getTaskId() != null) {
                metadata.put("taskId", message.getMetadata().getTaskId());
            }
            if (message.getMetadata().getModel() != null) {
                metadata.put("model", message.getMetadata().getModel());
            }
        }
        return new ContextLifecycleBenchmarkResult.DiagnosticMessage(
                message.getId(), message.getRole().name(), message.getContent(), metadata, calls, responses);
    }

    private List<ContextLifecycleBenchmarkResult.ModelCallMetric> modelCalls(
            ContextLifecycleCaseExecution execution, String taskId) {
        List<ContextLifecycleBenchmarkResult.ModelCallMetric> metrics = new ArrayList<>();
        int index = 0;
        for (AgentLifecycleObservationPublisher.ModelCallObservation observation
                : execution.capture().modelCalls) {
            EstimatedMessageTokenMeasurer.Measurement estimatedInput =
                    measurer.measure(observation.requestMessages(), observation.additionalSystemPrompt());
            int estimatedOutput = measurer.measureText(observation.outputText());
            Map<String, Integer> origins = attributor.attribute(
                    observation.requestMessages(), observation.additionalSystemPrompt(),
                    taskId, execution.sessionMessages());
            metrics.add(new ContextLifecycleBenchmarkResult.ModelCallMetric(
                    ++index, observation.startedAtEpochMs(), observation.phase().name(), observation.model(),
                    observation.latencyMs(), observation.finishReason(),
                    measurement(observation.actualInputTokens(), estimatedInput.tokens(),
                            observation.actualUsageSource(), estimatedInput.source()),
                    measurement(observation.actualOutputTokens(), estimatedOutput,
                            observation.actualUsageSource(), EstimatedMessageTokenMeasurer.SOURCE),
                    observation.requestMessages().size()
                            + (observation.additionalSystemPrompt() == null ? 0 : 1),
                    estimatedInput.tokens(), estimatedInput.source(), origins, observation.failure()));
        }
        for (AgentLifecycleObservationPublisher.CompressionObservation observation
                : execution.capture().compressions) {
            int estimatedInput = measurer.measureText(observation.compressionPrompt());
            int estimatedOutput = measurer.measureText(observation.outputSummary());
            metrics.add(new ContextLifecycleBenchmarkResult.ModelCallMetric(
                    ++index, 0, "COMPRESSION", observation.model(), observation.latencyMs(),
                    observation.succeeded() ? "COMPLETED" : "ERROR",
                    measurement(null, estimatedInput, "UNAVAILABLE", EstimatedMessageTokenMeasurer.SOURCE),
                    measurement(null, estimatedOutput, "UNAVAILABLE", EstimatedMessageTokenMeasurer.SOURCE),
                    1, estimatedInput, EstimatedMessageTokenMeasurer.SOURCE, Map.of(), observation.failure()));
        }
        return List.copyOf(metrics);
    }

    private ContextLifecycleBenchmarkResult.TokenMeasurement measurement(
            Integer actual, Integer estimated, String actualSource, String estimatedSource) {
        return new ContextLifecycleBenchmarkResult.TokenMeasurement(
                actual, estimated,
                actualSource == null || actualSource.isBlank() ? "UNAVAILABLE" : actualSource,
                estimated == null ? "UNAVAILABLE" : estimatedSource);
    }

    private ContextLifecycleBenchmarkResult.TokenTotals tokenTotals(
            List<ContextLifecycleBenchmarkResult.ModelCallMetric> calls) {
        return new ContextLifecycleBenchmarkResult.TokenTotals(
                aggregate(calls, value -> true, true),
                aggregate(calls, value -> true, false),
                aggregate(calls, value -> "THINK".equals(value.phase()), true),
                aggregate(calls, value -> "THINK".equals(value.phase()), false),
                aggregate(calls, value -> "COMPRESSION".equals(value.phase()), true),
                aggregate(calls, value -> "COMPRESSION".equals(value.phase()), false),
                aggregate(calls, value -> "FINAL".equals(value.phase()), true),
                aggregate(calls, value -> "FINAL".equals(value.phase()), false),
                aggregate(calls, value -> "SELECTOR".equals(value.phase()), true),
                aggregate(calls, value -> "SELECTOR".equals(value.phase()), false));
    }

    ContextLifecycleBenchmarkResult.TokenMeasurement aggregate(
            List<ContextLifecycleBenchmarkResult.ModelCallMetric> calls,
            Predicate<ContextLifecycleBenchmarkResult.ModelCallMetric> filter,
            boolean input) {
        List<ContextLifecycleBenchmarkResult.TokenMeasurement> values = calls.stream()
                .filter(filter).map(value -> input ? value.inputTokens() : value.outputTokens()).toList();
        int estimated = values.stream().map(ContextLifecycleBenchmarkResult.TokenMeasurement::estimatedTokens)
                .filter(Objects::nonNull).mapToInt(Integer::intValue).sum();
        List<Integer> actualValues = values.stream()
                .map(ContextLifecycleBenchmarkResult.TokenMeasurement::actualTokens)
                .filter(Objects::nonNull).toList();
        boolean complete = !values.isEmpty() && actualValues.size() == values.size();
        Integer actual = complete ? actualValues.stream().mapToInt(Integer::intValue).sum() : null;
        String actualSource = values.isEmpty() || actualValues.isEmpty() ? "UNAVAILABLE"
                : complete ? "PROVIDER_USAGE"
                : "UNAVAILABLE_INCOMPLETE_PROVIDER_USAGE_" + actualValues.size() + "_OF_" + values.size();
        return measurement(actual, estimated, actualSource, EstimatedMessageTokenMeasurer.SOURCE);
    }

    private TranscriptMetrics transcriptMetrics(
            AgentLifecycleObservationPublisher.FinalProjectionObservation projection,
            List<ContextLifecycleBenchmarkResult.ModelCallMetric> calls) {
        if (executionArchitecture == ContextLifecycleBenchmarkResult.ExecutionArchitecture.TASK_AWARE) {
            return TranscriptMetrics.removed(lastFinalTokens(calls));
        }
        if (projection == null) {
            return TranscriptMetrics.present(0, 0, null, lastFinalTokens(calls), 0);
        }
        int transcriptTokens = measurer.measure(projection.currentTaskToolTranscript(), null).tokens();
        Integer before = null;
        try {
            FinalSynthesisRequest withoutTranscript = new FinalSynthesisRequestFactory().create(
                    projection.executionTranscript(), projection.finalRequest().originalUserQuestion());
            before = measurer.measure(finalContextCompiler.compile(withoutTranscript), null).tokens();
        } catch (RuntimeException ignored) {
            // The benchmark must report unavailable rather than repair an invalid Legacy transcript.
        }
        Integer after = lastFinalTokens(calls);
        int contribution = before == null || after == null ? 0 : Math.max(0, after - before);
        return TranscriptMetrics.present(projection.currentTaskToolTranscript().size(), transcriptTokens,
                before, after, contribution);
    }

    private Integer lastFinalTokens(List<ContextLifecycleBenchmarkResult.ModelCallMetric> calls) {
        return calls.stream().filter(value -> "FINAL".equals(value.phase()))
                .reduce((first, second) -> second)
                .map(ContextLifecycleBenchmarkResult.ModelCallMetric::requestContextEstimatedTokens)
                .orElse(null);
    }

    private ContextLifecycleBenchmarkResult.ContextMetrics contextMetrics(
            List<ContextLifecycleBenchmarkResult.ModelCallMetric> calls, TranscriptMetrics transcript) {
        List<ContextLifecycleBenchmarkResult.ModelCallMetric> thinkCalls = calls.stream()
                .filter(value -> "THINK".equals(value.phase())).toList();
        List<Integer> beforeThink = thinkCalls.stream()
                .map(ContextLifecycleBenchmarkResult.ModelCallMetric::requestContextEstimatedTokens).toList();
        Map<String, Integer> firstOrigins = thinkCalls.isEmpty() ? Map.of()
                : thinkCalls.get(0).contextTokensByOrigin();
        return new ContextLifecycleBenchmarkResult.ContextMetrics(
                beforeThink.stream().mapToInt(Integer::intValue).max().orElse(0), beforeThink,
                transcript.after() == null ? 0 : transcript.after(),
                value(firstOrigins, ContextOriginAttributor.CURRENT_USER)
                        + value(firstOrigins, ContextOriginAttributor.CURRENT_TASK_PLANNING)
                        + value(firstOrigins, ContextOriginAttributor.CURRENT_TASK_TOOL),
                value(firstOrigins, ContextOriginAttributor.COMPLETED_TASK_USER_FINAL),
                value(firstOrigins, ContextOriginAttributor.COMPLETED_TASK_TOOL),
                value(firstOrigins, ContextOriginAttributor.SESSION_SUMMARY),
                value(firstOrigins, ContextOriginAttributor.UNKNOWN),
                transcript.entries(), transcript.tokens(), transcript.tokenStatus(), transcript.before(),
                transcript.after(), transcript.contribution(), transcript.contributionStatus());
    }

    private int value(Map<String, Integer> values, String key) {
        return values.getOrDefault(key, 0);
    }

    private ContextLifecycleBenchmarkResult.ToolMetrics toolTotals(
            ContextLifecycleCaseExecution execution,
            List<ContextLifecycleBenchmarkResult.ModelCallMetric> calls,
            TranscriptMetrics transcript) {
        Map<String, Integer> countByTool = new LinkedHashMap<>();
        execution.toolCalls().forEach(value -> countByTool.merge(
                safeToolName(value), 1, Integer::sum));
        int produced = execution.capture().toolResults.stream()
                .mapToInt(value -> measurer.measureText(value.rawResult())).sum();
        int currentAndCompletedToolContext = calls.stream()
                .filter(value -> "THINK".equals(value.phase()))
                .map(ContextLifecycleBenchmarkResult.ModelCallMetric::contextTokensByOrigin)
                .mapToInt(value -> value.getOrDefault(ContextOriginAttributor.CURRENT_TASK_TOOL, 0)
                        + value.getOrDefault(ContextOriginAttributor.COMPLETED_TASK_TOOL, 0)).sum();
        int largest = execution.capture().toolResults.stream()
                .mapToInt(value -> measurer.measureText(value.rawResult())).max().orElse(0);
        int crossTask = calls.stream().filter(value -> "THINK".equals(value.phase())).findFirst()
                .map(value -> value.contextTokensByOrigin()
                        .getOrDefault(ContextOriginAttributor.COMPLETED_TASK_TOOL, 0)).orElse(0);
        return new ContextLifecycleBenchmarkResult.ToolMetrics(
                execution.toolCalls().size(), countByTool, produced,
                currentAndCompletedToolContext + (transcript.contribution() == null
                        ? 0 : transcript.contribution()), largest, crossTask);
    }

    private List<ContextLifecycleBenchmarkResult.ToolCallMetric> toolCalls(
            ContextLifecycleCaseExecution execution) {
        Map<String, AgentLifecycleObservationPublisher.ToolResultObservation> observed = new LinkedHashMap<>();
        execution.capture().toolResults.forEach(value -> observed.put(value.toolCallId(), value));
        List<ContextLifecycleBenchmarkResult.ToolCallMetric> metrics = new ArrayList<>();
        int index = 0;
        for (ToolCallLog call : execution.toolCalls()) {
            AgentLifecycleObservationPublisher.ToolResultObservation result = observed.get(call.getToolCallId());
            metrics.add(new ContextLifecycleBenchmarkResult.ToolCallMetric(
                    ++index, call.getToolName(), call.getActualToolName(), call.getToolCallId(), call.getStatus(),
                    call.getLatencyMs() == null ? 0 : call.getLatencyMs(),
                    result == null ? 0 : measurer.measureText(result.rawResult()),
                    result == null ? 0 : measurer.measureText(result.contextResult()),
                    Boolean.TRUE.equals(call.getResultTruncated()) || result != null && result.truncated(),
                    call.getErrorType()));
        }
        return List.copyOf(metrics);
    }

    private List<ContextLifecycleBenchmarkResult.CompressionMetric> compressionEvents(
            List<AgentLifecycleObservationPublisher.CompressionObservation> observations) {
        List<ContextLifecycleBenchmarkResult.CompressionMetric> metrics = new ArrayList<>();
        int index = 0;
        for (AgentLifecycleObservationPublisher.CompressionObservation value : observations) {
            int removed = Math.max(0, value.tokensBeforeCompression() - value.tokensAfterCompression());
            double ratio = value.tokensBeforeCompression() == 0 ? 1.0
                    : value.tokensAfterCompression() / (double) value.tokensBeforeCompression();
            metrics.add(new ContextLifecycleBenchmarkResult.CompressionMetric(
                    ++index, value.reason(), value.tokensBeforeCompression(), value.tokensAfterCompression(),
                    removed, ratio, measurer.measureText(value.compressionPrompt()),
                    measurer.measureText(value.outputSummary()), value.latencyMs(), index,
                    value.contextTokenSource(), value.succeeded(), value.failure()));
        }
        return List.copyOf(metrics);
    }

    private ContextLifecycleBenchmarkResult.CompressionTotals compressionTotals(
            List<ContextLifecycleBenchmarkResult.CompressionMetric> events) {
        return new ContextLifecycleBenchmarkResult.CompressionTotals(
                events.size(), events.stream().mapToInt(ContextLifecycleBenchmarkResult.CompressionMetric::compressionInputTokens).sum(),
                events.stream().mapToInt(ContextLifecycleBenchmarkResult.CompressionMetric::compressionOutputTokens).sum(),
                events.stream().mapToInt(ContextLifecycleBenchmarkResult.CompressionMetric::tokensRemoved).sum(),
                events.stream().mapToInt(ContextLifecycleBenchmarkResult.CompressionMetric::summaryDepth).max().orElse(0));
    }

    private ContextLifecycleBenchmarkResult.StabilityMetrics stability(
            ContextLifecycleCaseExecution execution,
            List<ContextLifecycleBenchmarkResult.ModelCallMetric> modelCalls) {
        int orphan = 0;
        int protocolFailure = 0;
        for (AgentLifecycleObservationPublisher.ModelCallObservation call : execution.capture().modelCalls) {
            AgentToolProtocolInspector.Inspection inspection = AgentToolProtocolInspector.inspect(call.requestMessages());
            orphan += inspection.orphanToolProtocolCount();
            protocolFailure += inspection.protocolValidationFailureCount();
        }
        AgentLifecycleObservationPublisher.FinalProjectionObservation projection =
                execution.capture().finalProjection.get();
        if (projection != null) {
            AgentToolProtocolInspector.Inspection executionInspection =
                    AgentToolProtocolInspector.inspect(projection.executionTranscript());
            AgentToolProtocolInspector.Inspection transcriptInspection =
                    AgentToolProtocolInspector.inspect(projection.currentTaskToolTranscript());
            orphan += executionInspection.orphanToolProtocolCount() + transcriptInspection.orphanToolProtocolCount();
            protocolFailure += executionInspection.protocolValidationFailureCount()
                    + transcriptInspection.protocolValidationFailureCount();
        }
        int compressionFailures = (int) execution.capture().compressions.stream()
                .filter(value -> !value.succeeded()).count();
        int toolFailures = (int) execution.toolCalls().stream()
                .filter(value -> value.getStatus() != null && !"SUCCESS".equalsIgnoreCase(value.getStatus())).count();
        int overflow = containsContextOverflow(execution.executionFailure())
                || execution.task() != null && containsContextOverflow(execution.task().getErrorMessage()) ? 1 : 0;
        return new ContextLifecycleBenchmarkResult.StabilityMetrics(
                overflow, compressionFailures, orphan, protocolFailure, toolFailures);
    }

    private boolean containsContextOverflow(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("context overflow") || normalized.contains("context length")
                || normalized.contains("maximum context");
    }

    private List<String> failures(
            ContextLifecycleCaseExecution execution,
            List<ContextLifecycleBenchmarkResult.ModelCallMetric> calls,
            List<ContextLifecycleBenchmarkResult.CompressionMetric> compressions) {
        List<String> values = new ArrayList<>();
        if (execution.executionFailure() != null) {
            values.add(execution.executionFailure());
        }
        if (execution.task() != null && execution.task().getErrorMessage() != null) {
            values.add("TASK: " + execution.task().getErrorMessage());
        }
        calls.stream().filter(value -> value.failure() != null)
                .forEach(value -> values.add(value.phase() + ": " + value.failure()));
        compressions.stream().filter(value -> value.failure() != null)
                .forEach(value -> values.add("COMPRESSION: " + value.failure()));
        return List.copyOf(values);
    }

    private String finalAnswer(ContextLifecycleCaseExecution execution) {
        LocalDateTime started = execution.task() == null ? null : execution.task().getStartedAt();
        return execution.sessionMessages().stream()
                .filter(value -> value.getRole() == ChatMessageDTO.RoleType.ASSISTANT)
                .filter(this::userVisible)
                .filter(value -> started == null || value.getCreatedAt() == null || !value.getCreatedAt().isBefore(started))
                .filter(value -> value.getContent() != null && !value.getContent().isBlank())
                .max(Comparator.comparing(ChatMessageDTO::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(ChatMessageDTO::getContent).orElse("");
    }

    private boolean userVisible(ChatMessageDTO message) {
        return message.getMetadata() == null || message.getMetadata().getToolCalls() == null
                || message.getMetadata().getToolCalls().isEmpty();
    }

    private long taskLatency(AgentTask task) {
        if (task == null) {
            return 0;
        }
        if (task.getLatencyMs() != null) {
            return task.getLatencyMs();
        }
        if (task.getStartedAt() != null && task.getFinishedAt() != null) {
            return Math.max(0, Duration.between(task.getStartedAt(), task.getFinishedAt()).toMillis());
        }
        return 0;
    }

    private long stepLatency(List<AgentStep> steps, String type, boolean llmOnly) {
        return steps.stream().filter(value -> type.equals(value.getStepType()))
                .mapToLong(value -> llmOnly && value.getLlmLatencyMs() != null
                        ? value.getLlmLatencyMs()
                        : value.getLatencyMs() == null ? 0 : value.getLatencyMs()).sum();
    }

    private String safeToolName(ToolCallLog value) {
        if (value.getToolName() != null) {
            return value.getToolName();
        }
        return value.getActualToolName() == null ? "UNKNOWN" : value.getActualToolName();
    }

    private record TranscriptMetrics(
            int entries,
            Integer tokens,
            ContextLifecycleBenchmarkResult.TranscriptMetricStatus tokenStatus,
            Integer before,
            Integer after,
            Integer contribution,
            ContextLifecycleBenchmarkResult.TranscriptMetricStatus contributionStatus) {

        static TranscriptMetrics present(
                int entries, int tokens, Integer before, Integer after, int contribution) {
            return new TranscriptMetrics(entries, tokens,
                    ContextLifecycleBenchmarkResult.TranscriptMetricStatus.PRESENT,
                    before, after, contribution,
                    ContextLifecycleBenchmarkResult.TranscriptMetricStatus.PRESENT);
        }

        static TranscriptMetrics removed(Integer finalContextTokens) {
            return new TranscriptMetrics(0, null,
                    ContextLifecycleBenchmarkResult.TranscriptMetricStatus.REMOVED_NOT_APPLICABLE,
                    null, finalContextTokens, null,
                    ContextLifecycleBenchmarkResult.TranscriptMetricStatus.REMOVED_NOT_APPLICABLE);
        }
    }
}
