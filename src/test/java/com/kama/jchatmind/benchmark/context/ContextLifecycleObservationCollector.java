package com.kama.jchatmind.benchmark.context;

import com.kama.jchatmind.agent.observability.AgentLifecycleObservationPublisher;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

final class ContextLifecycleObservationCollector implements AutoCloseable {
    private final AtomicReference<CaseCapture> active = new AtomicReference<>();
    private final List<AgentLifecycleObservationPublisher.Registration> registrations;

    ContextLifecycleObservationCollector() {
        registrations = List.of(
                AgentLifecycleObservationPublisher.register(this::onModelCall),
                AgentLifecycleObservationPublisher.registerToolResult(this::onToolResult),
                AgentLifecycleObservationPublisher.registerCompression(this::onCompression),
                AgentLifecycleObservationPublisher.registerFinalProjection(this::onFinalProjection));
    }

    CaseCapture begin(String caseId, int repeatIndex, String sessionId) {
        CaseCapture capture = new CaseCapture(caseId, repeatIndex, sessionId, OffsetDateTime.now());
        if (!active.compareAndSet(null, capture)) {
            throw new IllegalStateException("A benchmark case is already active");
        }
        return capture;
    }

    void bindTask(String taskId) {
        CaseCapture capture = requireActive();
        capture.taskId = taskId;
    }

    CaseCapture end() {
        CaseCapture capture = active.getAndSet(null);
        if (capture == null) {
            throw new IllegalStateException("No benchmark case is active");
        }
        capture.endedAt = OffsetDateTime.now();
        return capture;
    }

    private void onModelCall(AgentLifecycleObservationPublisher.ModelCallObservation observation) {
        CaseCapture capture = matching(observation.sessionId(), observation.taskId());
        if (capture != null) {
            capture.modelCalls.add(observation);
        }
    }

    private void onToolResult(AgentLifecycleObservationPublisher.ToolResultObservation observation) {
        CaseCapture capture = matching(observation.sessionId(), observation.taskId());
        if (capture != null) {
            capture.toolResults.add(observation);
        }
    }

    private void onCompression(AgentLifecycleObservationPublisher.CompressionObservation observation) {
        CaseCapture capture = matching(observation.sessionId(), null);
        if (capture != null) {
            capture.compressions.add(observation);
        }
    }

    private void onFinalProjection(AgentLifecycleObservationPublisher.FinalProjectionObservation observation) {
        CaseCapture capture = matching(observation.sessionId(), observation.taskId());
        if (capture != null) {
            capture.finalProjection.set(observation);
        }
    }

    private CaseCapture matching(String sessionId, String taskId) {
        CaseCapture capture = active.get();
        if (capture == null) {
            return null;
        }
        if (sessionId != null && !capture.sessionId.equals(sessionId)) {
            return null;
        }
        if (taskId != null && capture.taskId != null && !capture.taskId.equals(taskId)) {
            return null;
        }
        return capture;
    }

    private CaseCapture requireActive() {
        CaseCapture capture = active.get();
        if (capture == null) {
            throw new IllegalStateException("No benchmark case is active");
        }
        return capture;
    }

    @Override
    public void close() {
        active.set(null);
        registrations.forEach(AgentLifecycleObservationPublisher.Registration::close);
    }

    static final class CaseCapture {
        final String caseId;
        final int repeatIndex;
        final String sessionId;
        final OffsetDateTime startedAt;
        volatile OffsetDateTime endedAt;
        volatile String taskId;
        final List<AgentLifecycleObservationPublisher.ModelCallObservation> modelCalls =
                new CopyOnWriteArrayList<>();
        final List<AgentLifecycleObservationPublisher.ToolResultObservation> toolResults =
                new CopyOnWriteArrayList<>();
        final List<AgentLifecycleObservationPublisher.CompressionObservation> compressions =
                new CopyOnWriteArrayList<>();
        final AtomicReference<AgentLifecycleObservationPublisher.FinalProjectionObservation> finalProjection =
                new AtomicReference<>();

        CaseCapture(String caseId, int repeatIndex, String sessionId, OffsetDateTime startedAt) {
            this.caseId = caseId;
            this.repeatIndex = repeatIndex;
            this.sessionId = sessionId;
            this.startedAt = startedAt;
        }
    }
}
