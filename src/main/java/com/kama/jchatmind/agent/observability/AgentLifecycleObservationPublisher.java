package com.kama.jchatmind.agent.observability;

import lombok.extern.slf4j.Slf4j;
import com.kama.jchatmind.agent.FinalSynthesisRequest;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fail-safe observation boundary for reproducible runtime benchmarks.
 *
 * <p>Production behavior does not depend on listeners. Listener failures are
 * isolated so benchmark collection can never fail or alter an Agent run.</p>
 */
@Slf4j
public final class AgentLifecycleObservationPublisher {

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<ToolResultListener> TOOL_RESULT_LISTENERS =
            new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<CompressionListener> COMPRESSION_LISTENERS =
            new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<FinalProjectionListener> FINAL_PROJECTION_LISTENERS =
            new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<FinalProviderRequestListener> FINAL_PROVIDER_REQUEST_LISTENERS =
            new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<SelectorProvenanceListener> SELECTOR_PROVENANCE_LISTENERS =
            new CopyOnWriteArrayList<>();

    public static final String DIAGNOSTIC_TOOL_CALL_ID_CONTEXT_KEY =
            "jchatmind.benchmark.diagnostic.toolCallId";
    public static final String DIAGNOSTIC_SESSION_ID_CONTEXT_KEY =
            "jchatmind.benchmark.diagnostic.sessionId";

    private AgentLifecycleObservationPublisher() {
    }

    public static Registration register(Listener listener) {
        Listener required = Objects.requireNonNull(listener, "listener cannot be null");
        LISTENERS.add(required);
        return () -> LISTENERS.remove(required);
    }

    public static Registration registerToolResult(ToolResultListener listener) {
        ToolResultListener required = Objects.requireNonNull(listener, "listener cannot be null");
        TOOL_RESULT_LISTENERS.add(required);
        return () -> TOOL_RESULT_LISTENERS.remove(required);
    }

    public static Registration registerCompression(CompressionListener listener) {
        CompressionListener required = Objects.requireNonNull(listener, "listener cannot be null");
        COMPRESSION_LISTENERS.add(required);
        return () -> COMPRESSION_LISTENERS.remove(required);
    }

    public static Registration registerFinalProjection(FinalProjectionListener listener) {
        FinalProjectionListener required = Objects.requireNonNull(listener, "listener cannot be null");
        FINAL_PROJECTION_LISTENERS.add(required);
        return () -> FINAL_PROJECTION_LISTENERS.remove(required);
    }

    public static Registration registerFinalProviderRequest(FinalProviderRequestListener listener) {
        FinalProviderRequestListener required = Objects.requireNonNull(listener, "listener cannot be null");
        FINAL_PROVIDER_REQUEST_LISTENERS.add(required);
        return () -> FINAL_PROVIDER_REQUEST_LISTENERS.remove(required);
    }

    public static Registration registerSelectorProvenance(SelectorProvenanceListener listener) {
        SelectorProvenanceListener required = Objects.requireNonNull(listener, "listener cannot be null");
        SELECTOR_PROVENANCE_LISTENERS.add(required);
        return () -> SELECTOR_PROVENANCE_LISTENERS.remove(required);
    }

    public static boolean isCompressionObservationEnabled() {
        return !COMPRESSION_LISTENERS.isEmpty();
    }

    public static boolean isFinalProviderRequestObservationEnabled() {
        return !FINAL_PROVIDER_REQUEST_LISTENERS.isEmpty();
    }

    public static boolean isFinalProjectionObservationEnabled() {
        return !FINAL_PROJECTION_LISTENERS.isEmpty();
    }

    public static boolean isSelectorProvenanceObservationEnabled() {
        return !SELECTOR_PROVENANCE_LISTENERS.isEmpty();
    }

    public static void publishModelCall(ModelCallObservation observation) {
        if (observation == null || LISTENERS.isEmpty()) {
            return;
        }
        for (Listener listener : LISTENERS) {
            try {
                listener.onModelCall(observation);
            } catch (RuntimeException e) {
                log.warn("Agent lifecycle observation listener failed: phase={}, taskId={}, error={}",
                        observation.phase(), observation.taskId(), e.getMessage());
            }
        }
    }

    public static void publishToolResult(ToolResultObservation observation) {
        publishSafely(TOOL_RESULT_LISTENERS, observation,
                (listener, value) -> listener.onToolResult(value), "tool", observation == null ? null : observation.taskId());
    }

    public static void publishCompression(CompressionObservation observation) {
        publishSafely(COMPRESSION_LISTENERS, observation,
                (listener, value) -> listener.onCompression(value), "compression",
                observation == null ? null : observation.sessionId());
    }

    public static void publishFinalProjection(FinalProjectionObservation observation) {
        publishSafely(FINAL_PROJECTION_LISTENERS, observation,
                (listener, value) -> listener.onFinalProjection(value), "final_projection",
                observation == null ? null : observation.taskId());
    }

    public static void publishFinalProviderRequest(FinalProviderRequestObservation observation) {
        publishSafely(FINAL_PROVIDER_REQUEST_LISTENERS, observation,
                (listener, value) -> listener.onFinalProviderRequest(value), "final_provider_request",
                observation == null ? null : observation.taskId());
    }

    public static void publishSelectorProvenance(SelectorProvenanceObservation observation) {
        publishSafely(SELECTOR_PROVENANCE_LISTENERS, observation,
                (listener, value) -> listener.onSelectorProvenance(value), "selector_provenance",
                observation == null ? null : observation.taskId());
    }

    private static <L, O> void publishSafely(List<L> listeners,
                                              O observation,
                                              Delivery<L, O> delivery,
                                              String phase,
                                              String correlationId) {
        if (observation == null || listeners.isEmpty()) {
            return;
        }
        for (L listener : listeners) {
            try {
                delivery.deliver(listener, observation);
            } catch (RuntimeException e) {
                log.warn("Agent lifecycle observation listener failed: phase={}, correlationId={}, error={}",
                        phase, correlationId, e.getMessage());
            }
        }
    }

    @FunctionalInterface
    public interface Listener {
        void onModelCall(ModelCallObservation observation);
    }

    @FunctionalInterface
    public interface ToolResultListener {
        void onToolResult(ToolResultObservation observation);
    }

    @FunctionalInterface
    public interface CompressionListener {
        void onCompression(CompressionObservation observation);
    }

    @FunctionalInterface
    public interface FinalProjectionListener {
        void onFinalProjection(FinalProjectionObservation observation);
    }

    @FunctionalInterface
    public interface FinalProviderRequestListener {
        void onFinalProviderRequest(FinalProviderRequestObservation observation);
    }

    @FunctionalInterface
    public interface SelectorProvenanceListener {
        void onSelectorProvenance(SelectorProvenanceObservation observation);
    }

    @FunctionalInterface
    private interface Delivery<L, O> {
        void deliver(L listener, O observation);
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    public enum ModelCallPhase {
        THINK,
        COMPRESSION,
        FINAL,
        SELECTOR
    }

    public record ModelCallObservation(
            String taskId,
            String sessionId,
            String model,
            ModelCallPhase phase,
            int attempt,
            List<Message> requestMessages,
            String additionalSystemPrompt,
            long startedAtEpochMs,
            long latencyMs,
            Integer actualInputTokens,
            Integer actualOutputTokens,
            Integer actualTotalTokens,
            String actualUsageSource,
            String finishReason,
            String outputText,
            String failure) {

        public ModelCallObservation {
            requestMessages = requestMessages == null ? List.of() : List.copyOf(requestMessages);
            actualUsageSource = actualInputTokens == null && actualOutputTokens == null
                    && actualTotalTokens == null ? "UNAVAILABLE" : actualUsageSource;
        }
    }

    public record ToolResultObservation(
            String taskId,
            String sessionId,
            String toolCallId,
            String canonicalToolName,
            String actualToolName,
            String rawResult,
            String contextResult,
            int originalChars,
            int storedChars,
            boolean truncated,
            String status) {
    }

    public record CompressionObservation(
            String taskId,
            String sessionId,
            String model,
            String reason,
            int tokensBeforeCompression,
            int tokensAfterCompression,
            int rawHistoryTokens,
            String contextTokenSource,
            String compressionPrompt,
            String previousSummary,
            String outputSummary,
            long latencyMs,
            boolean succeeded,
            String failure,
            String compressionAttemptId,
            String primaryState,
            String correctivePrompt,
            String correctiveState,
            String acceptedState,
            boolean accepted,
            int coveredThroughLogicalGroup,
            List<ChatMessageDTO> selectedProtocolMessages,
            List<ChatMessageDTO> remainingRawProtocolMessages,
            int summaryDepth,
            int compressionCount,
            int correctiveRetryCount) {

        public CompressionObservation {
            selectedProtocolMessages = selectedProtocolMessages == null
                    ? List.of() : List.copyOf(selectedProtocolMessages);
            remainingRawProtocolMessages = remainingRawProtocolMessages == null
                    ? List.of() : List.copyOf(remainingRawProtocolMessages);
        }

        public CompressionObservation(
                String sessionId,
                String model,
                String reason,
                int tokensBeforeCompression,
                int tokensAfterCompression,
                int rawHistoryTokens,
                String contextTokenSource,
                String compressionPrompt,
                String previousSummary,
                String outputSummary,
                long latencyMs,
                boolean succeeded,
                String failure) {
            this(null, sessionId, model, reason, tokensBeforeCompression, tokensAfterCompression,
                    rawHistoryTokens, contextTokenSource, compressionPrompt, previousSummary,
                    outputSummary, latencyMs, succeeded, failure, null, null, null, null,
                    null, false, 0, List.of(), List.of(), 0, 0, 0);
        }
    }

    public record FinalProjectionObservation(
            String taskId,
            String sessionId,
            String model,
            List<Message> managedWorkingContext,
            FinalSynthesisRequest finalRequest,
            String acceptedState,
            int coveredThroughLogicalGroup) {

        public FinalProjectionObservation {
            managedWorkingContext = managedWorkingContext == null
                    ? List.of() : List.copyOf(managedWorkingContext);
        }
    }


    public record FinalProviderRequestObservation(
            String taskId,
            String sessionId,
            String model,
            int attempt,
            List<Message> compiledProviderMessages) {

        public FinalProviderRequestObservation {
            compiledProviderMessages = compiledProviderMessages == null
                    ? List.of() : List.copyOf(compiledProviderMessages);
        }
    }

    public record CodeEvidenceIdentity(
            String repoId,
            String chunkId,
            String filePath,
            String symbolName,
            int rank,
            Double score) {
    }

    public record SelectorProvenanceObservation(
            String taskId,
            String sessionId,
            String toolCallId,
            String query,
            List<CodeEvidenceIdentity> rawTopK,
            List<CodeEvidenceIdentity> selectorInput,
            List<CodeEvidenceIdentity> selected,
            List<CodeEvidenceIdentity> rejected) {

        public SelectorProvenanceObservation {
            rawTopK = rawTopK == null ? List.of() : List.copyOf(rawTopK);
            selectorInput = selectorInput == null ? List.of() : List.copyOf(selectorInput);
            selected = selected == null ? List.of() : List.copyOf(selected);
            rejected = rejected == null ? List.of() : List.copyOf(rejected);
        }
    }
}
