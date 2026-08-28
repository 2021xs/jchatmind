package com.kama.jchatmind.agent.observability;

import lombok.extern.slf4j.Slf4j;
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

    private AgentLifecycleObservationPublisher() {
    }

    public static Registration register(Listener listener) {
        Listener required = Objects.requireNonNull(listener, "listener cannot be null");
        LISTENERS.add(required);
        return () -> LISTENERS.remove(required);
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

    @FunctionalInterface
    public interface Listener {
        void onModelCall(ModelCallObservation observation);
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
}
