package com.kama.jchatmind.benchmark.context;

import com.kama.jchatmind.agent.FinalConversationMessage;
import com.kama.jchatmind.agent.FinalEvidence;
import com.kama.jchatmind.agent.FinalEvidenceBatch;
import com.kama.jchatmind.agent.FinalSynthesisRequest;
import com.kama.jchatmind.agent.observability.AgentLifecycleObservationPublisher;
import com.kama.jchatmind.config.FinalSynthesisProperties;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.AgentTask;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextLifecycleResultAssemblerTest {

    @Test
    void doesNotExposePartialProviderUsageAsAnActualTaskTotal() {
        ContextLifecycleResultAssembler assembler =
                new ContextLifecycleResultAssembler(3, new FinalSynthesisProperties());
        List<ContextLifecycleBenchmarkResult.ModelCallMetric> calls = List.of(
                call(1, new ContextLifecycleBenchmarkResult.TokenMeasurement(
                        100, 90, "PROVIDER_USAGE", EstimatedMessageTokenMeasurer.SOURCE)),
                call(2, new ContextLifecycleBenchmarkResult.TokenMeasurement(
                        null, 80, "UNAVAILABLE", EstimatedMessageTokenMeasurer.SOURCE)));

        ContextLifecycleBenchmarkResult.TokenMeasurement total =
                assembler.aggregate(calls, value -> true, true);

        assertNull(total.actualTokens());
        assertEquals(170, total.estimatedTokens());
        assertEquals("UNAVAILABLE_INCOMPLETE_PROVIDER_USAGE_1_OF_2", total.actualSource());
    }

    @Test
    void marksRemovedTranscriptMetricsNotApplicableForLegacyLabelledRuns() {
        ContextLifecycleBenchmarkResult.ContextMetrics context = new ContextLifecycleResultAssembler(
                3, new FinalSynthesisProperties(),
                ContextLifecycleBenchmarkResult.ExecutionArchitecture.LEGACY)
                .assemble(execution()).context();

        assertNull(context.taskToolTranscriptEstimatedTokens());
        assertEquals(ContextLifecycleBenchmarkResult.TranscriptMetricStatus.REMOVED_NOT_APPLICABLE,
                context.taskToolTranscriptStatus());
        assertNull(context.finalTranscriptContributionTokens());
        assertEquals(ContextLifecycleBenchmarkResult.TranscriptMetricStatus.REMOVED_NOT_APPLICABLE,
                context.finalTranscriptContributionStatus());
    }

    @Test
    void marksTaskAwareTranscriptMetricsRemovedWithoutNumericZero() {
        ContextLifecycleBenchmarkResult.ContextMetrics context = new ContextLifecycleResultAssembler(
                3, new FinalSynthesisProperties(),
                ContextLifecycleBenchmarkResult.ExecutionArchitecture.TASK_AWARE)
                .assemble(execution()).context();

        assertNull(context.taskToolTranscriptEstimatedTokens());
        assertEquals(ContextLifecycleBenchmarkResult.TranscriptMetricStatus.REMOVED_NOT_APPLICABLE,
                context.taskToolTranscriptStatus());
        assertNull(context.finalTranscriptContributionTokens());
        assertEquals(ContextLifecycleBenchmarkResult.TranscriptMetricStatus.REMOVED_NOT_APPLICABLE,
                context.finalTranscriptContributionStatus());
    }

    @Test
    void assemblesActualEvidenceLifecycleBodiesAndJoinIdentities() {
        ContextLifecycleCaseExecution execution = execution();
        ContextLifecycleObservationCollector.CaseCapture capture = execution.capture();
        ChatMessageDTO selected = ChatMessageDTO.builder()
                .id("tool-message-1").role(ChatMessageDTO.RoleType.TOOL).content("selected model-view body")
                .metadata(ChatMessageDTO.MetaData.builder().taskId("task-1").build()).build();
        capture.compressions.add(new AgentLifecycleObservationPublisher.CompressionObservation(
                "task-1", "session-1", "model", "current_task_pressure", 500, 300, 400,
                "ESTIMATED", "rendered compression input", null, "accepted state", 25, true, null,
                "session-1:1", "primary state", "corrective input", "corrected state",
                "accepted state", true, 1, List.of(selected), List.of(), 1, 1, 1));
        AgentLifecycleObservationPublisher.CodeEvidenceIdentity c1 =
                new AgentLifecycleObservationPublisher.CodeEvidenceIdentity(
                        "repo-1", "chunk-1", "A.java", "A#run", 1, 0.9);
        AgentLifecycleObservationPublisher.CodeEvidenceIdentity c2 =
                new AgentLifecycleObservationPublisher.CodeEvidenceIdentity(
                        "repo-1", "chunk-2", "B.java", "B#run", 2, 0.8);
        capture.selectorProvenance.add(
                new AgentLifecycleObservationPublisher.SelectorProvenanceObservation(
                        "task-1", "session-1", "call-1", "query", List.of(c1, c2),
                        List.of(c1, c2), List.of(c1), List.of(c2)));
        capture.toolResults.add(new AgentLifecycleObservationPublisher.ToolResultObservation(
                "task-1", "session-1", "call-1", "searchProjectCode", "searchProjectCode",
                "canonical body", "projected body", 14, 14, false, "SUCCESS"));
        FinalSynthesisRequest finalRequest = finalRequest();
        List<Message> managedWorkingContext = List.of(new UserMessage("managed context"));
        capture.finalProjection.set(new AgentLifecycleObservationPublisher.FinalProjectionObservation(
                "task-1", "session-1", "model", managedWorkingContext,
                finalRequest, "accepted state", 2));
        capture.finalProviderRequests.add(
                new AgentLifecycleObservationPublisher.FinalProviderRequestObservation(
                        "task-1", "session-1", "model", 1,
                        List.of(new UserMessage("actual provider request"))));

        ContextLifecycleBenchmarkResult.EvidenceLifecycleDiagnostics diagnostics =
                new ContextLifecycleResultAssembler(3, new FinalSynthesisProperties())
                        .assemble(execution).diagnostics();

        assertEquals("canonical body", diagnostics.toolResults().get(0).canonicalBody());
        assertEquals("projected body", diagnostics.toolResults().get(0).projectedModelViewBody());
        assertEquals("chunk-1", diagnostics.selectorProvenance().get(0).selected().get(0).chunkId());
        assertEquals("chunk-2", diagnostics.selectorProvenance().get(0).rejected().get(0).chunkId());
        ContextLifecycleBenchmarkResult.CompressionDiagnostic compression = diagnostics.compressions().get(0);
        assertEquals("rendered compression input", compression.inputBody());
        assertEquals("primary state", compression.primaryState());
        assertEquals("corrected state", compression.correctiveState());
        assertEquals("accepted state", compression.acceptedState());
        assertEquals("selected model-view body",
                compression.selectedLogicalGroupMessages().get(0).text());
        assertTrue(compression.accepted());
        assertEquals("actual provider request",
                diagnostics.finalRequest().compiledProviderRequests().get(0).messages().get(0).text());
        assertEquals("managed context", diagnostics.finalRequest().managedWorkingContext().get(0).text());
        assertEquals(finalRequest, diagnostics.finalRequest().managedFinalRequest());
        assertEquals("actual provider request",
                diagnostics.finalRequest().managedFinalProviderMessages().get(0).text());
        assertEquals("accepted state", diagnostics.finalRequest().acceptedState());
        assertEquals(2, diagnostics.finalRequest().coveredThroughLogicalGroup());
    }

    private ContextLifecycleCaseExecution execution() {
        ContextLifecycleBenchmarkCase benchmarkCase = new ContextLifecycleBenchmarkCase();
        benchmarkCase.caseId = "case-1";
        benchmarkCase.category = "A";
        ContextLifecycleObservationCollector.CaseCapture capture =
                new ContextLifecycleObservationCollector.CaseCapture(
                        benchmarkCase.caseId, 1, "session-1", OffsetDateTime.now());
        capture.taskId = "task-1";
        return new ContextLifecycleCaseExecution(
                benchmarkCase,
                1,
                "session-1",
                AgentTask.builder().id("task-1").status("SUCCESS").build(),
                List.of(), List.of(), List.of(), capture, null);
    }

    private ContextLifecycleBenchmarkResult.ModelCallMetric call(
            int index, ContextLifecycleBenchmarkResult.TokenMeasurement input) {
        return new ContextLifecycleBenchmarkResult.ModelCallMetric(
                index, index, index == 1 ? "THINK" : "FINAL", "model", 1L, "STOP", input,
                ContextLifecycleBenchmarkResult.TokenMeasurement.unavailable(),
                1, input.estimatedTokens(), EstimatedMessageTokenMeasurer.SOURCE, Map.of(), null);
    }

    private FinalSynthesisRequest finalRequest() {
        return new FinalSynthesisRequest(
                "question",
                List.of(new FinalConversationMessage(FinalConversationMessage.Role.USER, "history")),
                List.of(new FinalEvidenceBatch(1, List.of(new FinalEvidence(
                        "evidence-1", "call-1", "searchProjectCode", "evidence body", Map.of())))),
                "answer directly");
    }
}
