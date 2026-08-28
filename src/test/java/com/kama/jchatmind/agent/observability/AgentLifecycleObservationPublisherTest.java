package com.kama.jchatmind.agent.observability;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class AgentLifecycleObservationPublisherTest {

    @Test
    void listenerFailureCannotEscapeIntoAgentRuntime() {
        AtomicInteger delivered = new AtomicInteger();
        try (AgentLifecycleObservationPublisher.Registration first =
                     AgentLifecycleObservationPublisher.register(observation -> {
                         throw new IllegalStateException("benchmark sink failed");
                     });
             AgentLifecycleObservationPublisher.Registration second =
                     AgentLifecycleObservationPublisher.register(observation -> delivered.incrementAndGet())) {

            assertDoesNotThrow(() -> AgentLifecycleObservationPublisher.publishModelCall(observation()));
            assertEquals(1, delivered.get());
        }
    }

    private AgentLifecycleObservationPublisher.ModelCallObservation observation() {
        return new AgentLifecycleObservationPublisher.ModelCallObservation(
                "task", "session", "model",
                AgentLifecycleObservationPublisher.ModelCallPhase.THINK,
                1, List.of(), null, 1, 2,
                null, null, null, null,
                "STOP", "answer", null);
    }
}
