package com.kama.jchatmind.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.ContextCompressionProperties;
import com.kama.jchatmind.agent.observability.AgentLifecycleObservationPublisher;
import com.kama.jchatmind.mapper.AgentTaskMapper;
import com.kama.jchatmind.mapper.ChatSessionMapper;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.service.impl.ConversationContextCompressorImpl;
import com.kama.jchatmind.service.impl.EstimatedTokenCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CurrentTaskWorkingSummaryTest {

    private static final String MODEL = "deepseek-chat";
    private static final String KEEP = "KEEP";
    private static final String SUMMARY_V1 = """
            Current Task Continuation State

            - Goal
              - Diagnose order 123 without losing the original question.
            - Known
              - Search confirmed the handler location.
            - Constraints
              - status=206, rowLimit=50, hasMore=true.
            - Refs
              - repoId: repo-1
              - chunkId: chunk-1
            - Open
              - Verify the exact database row.
            - Next
              - Call getCodeChunk and run a narrower query.
            """;
    private static final String SUMMARY_V2 = SUMMARY_V1.replace(
            "Verify the exact database row.", "The exact database row is now confirmed.");
    private static final String DELTA_V1 = """
            Current Task Continuation State Delta

            - Goal
              - Diagnose order 123 without losing the original question.
            - KnownAdd
              - Search confirmed the handler location.
            - KnownRemove
              - none
            - ConstraintsAdd
              - status=206, rowLimit=50, hasMore=true.
            - ConstraintsRemove
              - none
            - RefsAdd
              - repoId: repo-1
              - chunkId: chunk-1
            - RefsRemove
              - none
            - Open
              - Verify the exact database row.
            - Next
              - Call getCodeChunk and run a narrower query.
            """;
    private static final String KEEP_DELTA = """
            Current Task Continuation State Delta

            - Goal
              - KEEP
            - KnownAdd
              - none
            - KnownRemove
              - none
            - ConstraintsAdd
              - none
            - ConstraintsRemove
              - none
            - RefsAdd
              - none
            - RefsRemove
              - none
            - Open
              - KEEP
            - Next
              - KEEP
            """;
    private static final String SPARSE_ADD_DELTA = """
            Current Task Continuation State Delta

            - KnownAdd
              - Fact A
            - RefsAdd
              - repoId: sparse-repo, chunkId: sparse-chunk
            - Next
              - inspect B
            """;

    private ContextCompressionProperties properties;
    private RecordingSummaryClient summaryClient;
    private ChatSessionMapper chatSessionMapper;
    private ConversationContextCompressorImpl compressor;

    @BeforeEach
    void setUp() {
        properties = new ContextCompressionProperties();
        properties.setMaxContextTokens(400);
        properties.setMaxSingleToolResultTokens(120);
        properties.setMaxHistoryMessages(50);
        properties.setMaxSummaryChars(1200);
        properties.setCharsPerToken(3);
        summaryClient = new RecordingSummaryClient();
        chatSessionMapper = mock(ChatSessionMapper.class);
        compressor = new ConversationContextCompressorImpl(
                properties, summaryClient, chatSessionMapper, mock(AgentTaskMapper.class),
                new ObjectMapper().findAndRegisterModules(), new EstimatedTokenCounter(properties));
    }

    @Test
    void noPressureKeepsAllRawGroupsAndDoesNotSummarize() {
        properties.setMaxContextTokens(20_000);
        properties.setMaxSingleToolResultTokens(20_000);
        properties.setMaxHistoryMessages(1);
        List<ChatMessageDTO> protocol = new ArrayList<>();
        protocol.addAll(group("g1", "searchProjectCode", "small result one"));
        protocol.addAll(group("g2", "databaseQuery", "small result two"));

        ConversationContextCompressor.CurrentTaskCompression result = compress(
                protocol, ConversationContextCompressor.CurrentTaskWorkingState.empty());

        assertThat(result.compressed()).isFalse();
        assertThat(result.state().summary()).isNull();
        assertThat(result.uncoveredProtocolMessages()).containsExactlyElementsOf(protocol);
        assertThat(summaryClient.callCount).isZero();
        assertThat(result.check().reason()).isEqualTo("not_needed");
    }

    @Test
    void firstCompressionCoversOnlyWholeGroupsAndRetainsStableDetails() {
        List<ChatMessageDTO> protocol = new ArrayList<>();
        protocol.addAll(group("g1", "searchProjectCode",
                longBody("repoId: repo-1\nchunkId: chunk-1\nEXACT=42")));
        protocol.addAll(group("g2", "databaseQuery",
                longBody("PARTIAL\nrowsReturned=50\nhasMore=true")));
        protocol.addAll(group("g3", "knowledgeQuery", longBody("confirmed relationship A -> B")));

        ConversationContextCompressor.CurrentTaskCompression result = compress(
                protocol, ConversationContextCompressor.CurrentTaskWorkingState.empty());

        assertThat(result.compressed()).isTrue();
        assertThat(result.state().coveredThroughLogicalGroup()).isEqualTo(3);
        assertThat(result.state().summaryDepth()).isEqualTo(1);
        assertThat(result.state().compressionCount()).isEqualTo(1);
        assertThat(result.correctiveRetryCount()).isZero();
        assertThat(result.uncoveredProtocolMessages()).isEmpty();
        assertThat(result.state().summary())
                .contains("status=206", "rowLimit=50", "hasMore=true", "repoId: repo-1", "chunkId: chunk-1");
        assertThat(summaryClient.lastPrompt)
                .contains("Original current user question", "PARTIAL", "hasMore=true", "repoId: repo-1")
                .doesNotContain("completed-secret-tool-body");
        properties.setMaxContextTokens(400);
        assertThatCode(() -> compressor.assertPlanningContextWithinBudget(MODEL, List.of(
                new UserMessage("Original current user question"),
                new SystemMessage(ConversationContextCompressor.currentTaskSummaryMessageContent(
                        result.state().summary())))))
                .doesNotThrowAnyException();
        verify(chatSessionMapper, never()).updateById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void incrementalCompressionUsesExistingSummaryAndOnlyNewGroups() {
        List<ChatMessageDTO> protocol = new ArrayList<>();
        protocol.addAll(group("g1", "searchProjectCode", longBody("already covered body")));
        protocol.addAll(group("g2", "getCodeChunk", longBody("new exact source body")));
        ConversationContextCompressor.CurrentTaskWorkingState existing =
                new ConversationContextCompressor.CurrentTaskWorkingState(SUMMARY_V1, 1, 1, 1);
        summaryClient.nextSummary = keepDeltaWithOpen("The exact database row is now confirmed.", KEEP);

        ConversationContextCompressor.CurrentTaskCompression result = compress(protocol, existing);

        assertThat(result.compressed()).isTrue();
        assertThat(result.state().coveredThroughLogicalGroup()).isEqualTo(2);
        assertThat(result.state().summaryDepth()).isEqualTo(2);
        assertThat(result.state().compressionCount()).isEqualTo(2);
        assertThat(result.state().summary()).isEqualTo(SUMMARY_V2.strip());
        assertThat(summaryClient.lastPrompt)
                .contains(SUMMARY_V1, "new exact source body")
                .doesNotContain("already covered body");
    }

    @Test
    void failedSummaryDoesNotAdvanceCoverageOrDropRawGroups() {
        List<ChatMessageDTO> protocol = group("g1", "mcp.snapshot", longBody("remote body"));
        summaryClient.nextSummary = "invalid summary";
        ConversationContextCompressor.CurrentTaskWorkingState state =
                ConversationContextCompressor.CurrentTaskWorkingState.empty();

        ConversationContextCompressor.CurrentTaskCompression result = compress(protocol, state);

        assertThat(result.compressed()).isFalse();
        assertThat(result.state().summary()).isEqualTo(state.summary());
        assertThat(result.state().coveredThroughLogicalGroup())
                .isEqualTo(state.coveredThroughLogicalGroup());
        assertThat(result.state().summaryDepth()).isEqualTo(state.summaryDepth());
        assertThat(result.state().compressionCount()).isEqualTo(state.compressionCount());
        assertThat(result.state().compressionSuppressed()).isTrue();
        assertThat(result.correctiveRetryCount()).isEqualTo(1);
        assertThat(result.uncoveredProtocolMessages()).containsExactlyElementsOf(protocol);

        ChatMessageDTO currentUser = ChatMessageDTO.builder()
                .id("current-user").role(ChatMessageDTO.RoleType.USER)
                .content("Original current user question").build();
        ConversationContextCompressor.CompressionCheck retryCheck = compressor.checkCurrentTask(
                MODEL, currentUser, null, List.of(currentUser), protocol, List.of(), result.state());
        assertThat(retryCheck.needed()).isFalse();
        assertThat(retryCheck.reason()).isEqualTo("previous_failure");

        ConversationContextCompressor.CurrentTaskCompression retry = compressor.compressCurrentTaskIfNeeded(
                "session-1", MODEL, currentUser, null, List.of(currentUser), protocol, List.of(), result.state());
        assertThat(retry.compressed()).isFalse();
        assertThat(retry.state()).isEqualTo(result.state());
        assertThat(summaryClient.callCount).isEqualTo(2);
    }

    @Test
    void stateOverLegacyCharLimitIsAcceptedWhenFullRequestFits() {
        properties.setMaxContextTokens(2_000);
        List<ChatMessageDTO> protocol = group("g1", "searchProjectCode",
                "repoId: repo-1\nchunkId: chunk-1\n" + "raw-evidence ".repeat(800));
        String overLegacyLimit = deltaWithKnown("K".repeat(1_300));
        summaryClient.nextSummary = overLegacyLimit;

        ConversationContextCompressor.CurrentTaskCompression result = compress(
                protocol, ConversationContextCompressor.CurrentTaskWorkingState.empty());

        assertThat(result.compressed()).isTrue();
        assertThat(result.correctiveRetryCount()).isZero();
        assertThat(result.state().coveredThroughLogicalGroup()).isEqualTo(1);
        assertThat(result.state().summary().length()).isGreaterThan(properties.getMaxSummaryChars());
        assertThat(result.state().summary()).contains("K".repeat(1_300));
        assertThat(summaryClient.callCount).isEqualTo(1);
    }

    @Test
    void validPrimaryOverBudgetGetsOneStateOnlyCorrectiveAndFits() {
        properties.setMaxContextTokens(300);
        String rawMarker = "RAW_TOOL_BODY_MUST_NOT_REENTER_CORRECTIVE";
        List<ChatMessageDTO> protocol = group("g1", "searchProjectCode",
                rawMarker + "\n" + "detail ".repeat(600));
        String primary = deltaWithOpen("P".repeat(900), "N".repeat(300));
        String corrective = keepDeltaWithOpen("Verify one remaining item.", "Continue with the next exact check.");
        summaryClient.queuedResponses.add(primary);
        summaryClient.queuedResponses.add(corrective);

        ConversationContextCompressor.CurrentTaskCompression result = compress(
                protocol, ConversationContextCompressor.CurrentTaskWorkingState.empty());

        assertThat(result.compressed()).isTrue();
        assertThat(result.correctiveRetryCount()).isEqualTo(1);
        assertThat(result.state().summary())
                .contains("Verify one remaining item.", "Continue with the next exact check.");
        assertThat(result.state().coveredThroughLogicalGroup()).isEqualTo(1);
        assertThat(summaryClient.callCount).isEqualTo(2);
        assertThat(summaryClient.lastPrompt)
                .contains("Proposed merged Continuation State",
                        "Available estimated state token budget", "Original current user question")
                .doesNotContain(rawMarker);
    }

    @Test
    void benchmarkObservationCapturesActualCompressionBodiesWithoutChangingAcceptedState() {
        properties.setMaxContextTokens(300);
        String rawMarker = "DIAGNOSTIC_RAW_TOOL_BODY";
        List<ChatMessageDTO> protocol = group("g1", "searchProjectCode",
                rawMarker + "\n" + "detail ".repeat(600));
        String primary = deltaWithOpen("P".repeat(900), "N".repeat(300));
        String corrective = keepDeltaWithOpen("Verify one remaining item.", "Continue with the next exact check.");
        summaryClient.queuedResponses.add(primary);
        summaryClient.queuedResponses.add(corrective);
        AtomicReference<AgentLifecycleObservationPublisher.CompressionObservation> captured =
                new AtomicReference<>();

        ConversationContextCompressor.CurrentTaskCompression result;
        try (AgentLifecycleObservationPublisher.Registration ignored =
                     AgentLifecycleObservationPublisher.registerCompression(captured::set)) {
            result = compress(protocol, ConversationContextCompressor.CurrentTaskWorkingState.empty());
        }

        AgentLifecycleObservationPublisher.CompressionObservation observation = captured.get();
        assertThat(observation).isNotNull();
        assertThat(observation.taskId()).isEqualTo("task-1");
        assertThat(observation.sessionId()).isEqualTo("session-1");
        assertThat(observation.compressionAttemptId()).isEqualTo("session-1:1");
        assertThat(observation.compressionPrompt()).isEqualTo(summaryClient.prompts.get(0));
        assertThat(observation.compressionPrompt()).contains(rawMarker);
        assertThat(observation.primaryState()).isEqualTo(primary.strip());
        assertThat(observation.correctivePrompt()).isEqualTo(summaryClient.prompts.get(1));
        assertThat(observation.correctivePrompt()).doesNotContain(rawMarker);
        assertThat(observation.correctiveState()).isEqualTo(corrective.strip());
        assertThat(observation.acceptedState()).isEqualTo(result.state().summary());
        assertThat(observation.accepted()).isTrue();
        assertThat(observation.coveredThroughLogicalGroup()).isEqualTo(1);
        assertThat(observation.selectedProtocolMessages()).containsExactlyElementsOf(protocol);
        assertThat(observation.remainingRawProtocolMessages()).isEmpty();
        assertThat(observation.summaryDepth()).isEqualTo(1);
        assertThat(observation.compressionCount()).isEqualTo(1);
        assertThat(observation.correctiveRetryCount()).isEqualTo(1);
        assertThat(result.state().summary())
                .contains("Verify one remaining item.", "Continue with the next exact check.");
    }

    @Test
    void budgetCorrectiveStillOverBudgetFailsClosedWithoutThirdCall() {
        properties.setMaxContextTokens(350);
        List<ChatMessageDTO> protocol = group("g1", "knowledgeQuery",
                "raw-marker\n" + "detail ".repeat(600));
        summaryClient.queuedResponses.add(deltaWithKnown("P".repeat(900)));
        summaryClient.queuedResponses.add(keepDeltaWithOpen("C".repeat(700), "still too large"));

        ConversationContextCompressor.CurrentTaskCompression result = compress(
                protocol, ConversationContextCompressor.CurrentTaskWorkingState.empty());

        assertThat(result.compressed()).isFalse();
        assertThat(result.correctiveRetryCount()).isEqualTo(1);
        assertThat(result.state().coveredThroughLogicalGroup()).isZero();
        assertThat(result.uncoveredProtocolMessages()).containsExactlyElementsOf(protocol);
        assertThat(summaryClient.callCount).isEqualTo(2);
    }

    @Test
    void structureCorrectiveConsumesOnlyRetryAndCannotTriggerThirdBudgetCall() {
        properties.setMaxContextTokens(300);
        List<ChatMessageDTO> protocol = group("g1", "databaseQuery",
                "raw-db-body\n" + "detail ".repeat(600));
        summaryClient.queuedResponses.add("invalid primary");
        summaryClient.queuedResponses.add(deltaWithKnown("C".repeat(700)));

        ConversationContextCompressor.CurrentTaskCompression result = compress(
                protocol, ConversationContextCompressor.CurrentTaskWorkingState.empty());

        assertThat(result.compressed()).isFalse();
        assertThat(result.correctiveRetryCount()).isEqualTo(1);
        assertThat(summaryClient.callCount).isEqualTo(2);
        assertThat(result.state().coveredThroughLogicalGroup()).isZero();
        assertThat(result.uncoveredProtocolMessages()).containsExactlyElementsOf(protocol);
    }

    @Test
    void emptySummaryFailsClosedWithoutCorrectiveRetry() {
        List<ChatMessageDTO> protocol = group("g1", "knowledgeQuery", longBody("confirmed fact"));
        summaryClient.nextSummary = "";

        ConversationContextCompressor.CurrentTaskCompression result = compress(
                protocol, ConversationContextCompressor.CurrentTaskWorkingState.empty());

        assertThat(result.compressed()).isFalse();
        assertThat(result.correctiveRetryCount()).isZero();
        assertThat(result.state().coveredThroughLogicalGroup()).isZero();
        assertThat(result.uncoveredProtocolMessages()).containsExactlyElementsOf(protocol);
        assertThat(summaryClient.callCount).isEqualTo(1);
    }

    @Test
    void planningBudgetGateUsesConfiguredTokenThreshold() {
        properties.setMaxContextTokens(20);

        assertThatCode(() -> compressor.assertPlanningContextWithinBudget(
                MODEL, List.of(new UserMessage("short"), new SystemMessage("prompt"))))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> compressor.assertPlanningContextWithinBudget(
                MODEL, List.of(new UserMessage("x".repeat(100)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maxContextTokens=20");
        ToolResponseMessage oversizedToolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call-budget", "searchProjectCode", "y".repeat(100))))
                .build();
        assertThatThrownBy(() -> compressor.assertPlanningContextWithinBudget(
                MODEL, List.of(oversizedToolResponse)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maxContextTokens=20");
    }

    @Test
    void multiCallBatchIsOneCoverageUnit() {
        AssistantMessage.ToolCall a = call("a", "searchProjectCode");
        AssistantMessage.ToolCall b = call("b", "databaseQuery");
        AssistantMessage.ToolCall c = call("c", "knowledgeQuery");
        ChatMessageDTO assistant = assistant("batch", List.of(a, b, c));
        List<ChatMessageDTO> protocol = List.of(
                assistant,
                response("batch-a", "a", "searchProjectCode", longBody("SUCCESS exact fact")),
                response("batch-b", "b", "databaseQuery", longBody("ERROR timeout")),
                response("batch-c", "c", "knowledgeQuery", longBody("SKIPPED batch aborted")));

        ConversationContextCompressor.CurrentTaskCompression result = compress(
                protocol, ConversationContextCompressor.CurrentTaskWorkingState.empty());

        assertThat(result.compressed()).isTrue();
        assertThat(result.state().coveredThroughLogicalGroup()).isEqualTo(1);
        assertThat(result.uncoveredProtocolMessages()).isEmpty();
        assertThat(summaryClient.lastPrompt).contains("SUCCESS", "ERROR timeout", "SKIPPED batch aborted");
    }

    @Test
    void pressureAssimilatesOlderGroupsAndRetainsRecentRawTailWhenBudgetAllows() {
        properties.setMaxContextTokens(5_000);
        List<ChatMessageDTO> protocol = new ArrayList<>();
        protocol.addAll(group("g1", "searchProjectCode", "old-one\n" + "detail ".repeat(180)));
        protocol.addAll(group("g2", "databaseQuery", "old-two\n" + "detail ".repeat(180)));
        List<ChatMessageDTO> recent = group("g3", "knowledgeQuery", "RECENT_RAW\n" + "detail ".repeat(30));
        protocol.addAll(recent);
        summaryClient.nextSummary = DELTA_V1;

        ConversationContextCompressor.CurrentTaskCompression result = compress(
                protocol, ConversationContextCompressor.CurrentTaskWorkingState.empty());

        assertThat(result.compressed()).isTrue();
        assertThat(result.state().coveredThroughLogicalGroup()).isEqualTo(2);
        assertThat(result.uncoveredProtocolMessages()).containsExactlyElementsOf(recent);
        assertThat(summaryClient.lastPrompt).contains("old-one", "old-two").doesNotContain("RECENT_RAW");
    }

    @Test
    void cumulativeContextPressureSelectsMaximumEligiblePrefixBeforeCorrectiveCompaction() {
        properties.setMaxContextTokens(700);
        List<ChatMessageDTO> protocol = new ArrayList<>();
        protocol.addAll(group("g1", "searchProjectCode", "OLD_RAW\n" + "detail ".repeat(180)));
        protocol.addAll(group("g2", "knowledgeQuery", "RECENT_RAW\n" + "detail ".repeat(180)));
        summaryClient.nextSummary = DELTA_V1;

        ConversationContextCompressor.CurrentTaskCompression result = compress(
                protocol, ConversationContextCompressor.CurrentTaskWorkingState.empty());

        assertThat(result.compressed()).isTrue();
        assertThat(result.state().coveredThroughLogicalGroup()).isEqualTo(2);
        assertThat(result.uncoveredProtocolMessages()).isEmpty();
        assertThat(summaryClient.lastPrompt).contains("OLD_RAW", "RECENT_RAW");
    }

    @Test
    void singleOverBudgetGroupCanBeAssimilated() {
        properties.setMaxContextTokens(300);
        List<ChatMessageDTO> protocol = group("g1", "mcp.snapshot",
                "ONLY_GROUP\n" + "detail ".repeat(600));
        summaryClient.nextSummary = DELTA_V1;

        ConversationContextCompressor.CurrentTaskCompression result = compress(
                protocol, ConversationContextCompressor.CurrentTaskWorkingState.empty());

        assertThat(result.compressed()).isTrue();
        assertThat(result.state().coveredThroughLogicalGroup()).isEqualTo(1);
        assertThat(result.uncoveredProtocolMessages()).isEmpty();
    }

    @Test
    void incompleteLocatorUsesSingleStructureCorrectiveAndKeepsPair() {
        List<ChatMessageDTO> protocol = group("g1", "searchProjectCode",
                longBody("repoId: repo-1\nchunkId: chunk-1"));
        summaryClient.queuedResponses.add(deltaWithRefs("repoId: repo-1"));
        summaryClient.queuedResponses.add(DELTA_V1);

        ConversationContextCompressor.CurrentTaskCompression result = compress(
                protocol, ConversationContextCompressor.CurrentTaskWorkingState.empty());

        assertThat(result.compressed()).isTrue();
        assertThat(result.correctiveRetryCount()).isEqualTo(1);
        assertThat(result.state().summary()).contains("repoId: repo-1", "chunkId: chunk-1");
        assertThat(summaryClient.callCount).isEqualTo(2);
    }

    @Test
    void fixedPlanningMaterialParticipatesInPressureAndDynamicBudget() {
        properties.setMaxContextTokens(350);
        List<ChatMessageDTO> protocol = group("g1", "searchProjectCode",
                "raw-fixed-marker\n" + "detail ".repeat(600));
        summaryClient.queuedResponses.add(deltaWithOpen("P".repeat(900), "N".repeat(300)));
        summaryClient.queuedResponses.add(keepDeltaWithOpen("compact", "next"));
        List<ChatMessageDTO> fixed = List.of(ChatMessageDTO.builder()
                .role(ChatMessageDTO.RoleType.SYSTEM)
                .content("FIXED_PLANNING\n" + "instruction ".repeat(20))
                .build());

        ConversationContextCompressor.CurrentTaskCompression result = compress(
                protocol, ConversationContextCompressor.CurrentTaskWorkingState.empty(), fixed);

        assertThat(result.compressed()).isTrue();
        assertThat(summaryClient.lastPrompt).contains("Available estimated state token budget");
        assertThat(summaryClient.callCount).isEqualTo(2);
    }

    @Test
    void confirmedLuaMappingProducerConsumerRelationshipAndExactRefsSurviveFiveUnrelatedUpdates() {
        properties.setMaxContextTokens(2_000);
        String luaRepoId = "bf4ef891-330b-4ce8-9002-ba4c43ffe210";
        String producerRepoId = "12ea4f96-8096-47ce-a230-d54ddb75042c";
        String consumerRepoId = "727834fa-2971-4625-a0ef-edf5f54eed93";
        String initialState = """
                Current Task Continuation State

                - Goal
                  - Explain the complete seckill order lifecycle.
                - Known
                  - VoucherOrderProducer#sendSeckillOrder -> SECKILL_ORDER_QUEUE -> VoucherOrderConsumer#handleSeckillOrderBatch.
                - Constraints
                  - seckill.lua: 3 = stock key missing; 1 = stock <= 0; 2 = duplicate user; 0 = success.
                - Refs
                  - lua repoId: %s
                  - lua chunkId: lua-chunk
                  - producer repoId: %s
                  - producer chunkId: producer-chunk
                  - consumer repoId: %s
                  - consumer chunkId: consumer-chunk
                - Open
                  - Verify timeout close behavior.
                - Next
                  - Inspect the next unrelated component.
                """.formatted(luaRepoId, producerRepoId, consumerRepoId).strip();
        ConversationContextCompressor.CurrentTaskWorkingState state =
                new ConversationContextCompressor.CurrentTaskWorkingState(initialState, 0, 1, 1);
        List<ChatMessageDTO> protocol = new ArrayList<>();

        for (int update = 1; update <= 5; update++) {
            protocol.addAll(group("unrelated-" + update, "knowledgeQuery",
                    longBody("unrelated additive evidence " + update)));
            summaryClient.queuedResponses.add(keepDeltaWithOpen(
                    "Unrelated question " + update + " remains open.",
                    "Inspect unrelated component " + (update + 1) + "."));

            ConversationContextCompressor.CurrentTaskCompression result = compress(protocol, state);

            assertThat(result.compressed()).isTrue();
            assertThat(result.state().coveredThroughLogicalGroup()).isEqualTo(update);
            state = result.state();
        }

        assertThat(state.summary())
                .contains("3 = stock key missing", "1 = stock <= 0", "2 = duplicate user", "0 = success")
                .contains("VoucherOrderProducer#sendSeckillOrder")
                .contains("SECKILL_ORDER_QUEUE")
                .contains("VoucherOrderConsumer#handleSeckillOrderBatch")
                .contains("lua repoId: " + luaRepoId, "lua chunkId: lua-chunk")
                .contains("producer repoId: " + producerRepoId, "producer chunkId: producer-chunk")
                .contains("consumer repoId: " + consumerRepoId, "consumer chunkId: consumer-chunk");
        assertThat(summaryClient.callCount).isEqualTo(5);
    }

    @Test
    void additionsMergeWhileOpenAndNextReplaceDynamically() {
        properties.setMaxContextTokens(2_000);
        ConversationContextCompressor.CurrentTaskWorkingState existing =
                new ConversationContextCompressor.CurrentTaskWorkingState(SUMMARY_V1, 0, 1, 1);
        summaryClient.nextSummary = """
                Current Task Continuation State Delta

                - Goal
                  - KEEP
                - KnownAdd
                  - VoucherOrderConsumer handles the queue batch.
                - KnownRemove
                  - none
                - ConstraintsAdd
                  - returnCode=2 means duplicate user.
                - ConstraintsRemove
                  - none
                - RefsAdd
                  - repoId: bf4ef891-330b-4ce8-9002-ba4c43ffe210
                  - chunkId: consumer-chunk
                - RefsRemove
                  - none
                - Open
                  - none
                - Next
                  - Inspect timeout close behavior.
                """;

        ConversationContextCompressor.CurrentTaskCompression result = compress(
                group("add", "getCodeChunk", longBody("new confirmed consumer evidence")), existing);

        assertThat(result.compressed()).isTrue();
        assertThat(result.state().summary())
                .contains("Search confirmed the handler location.")
                .contains("VoucherOrderConsumer handles the queue batch.")
                .contains("status=206, rowLimit=50, hasMore=true.")
                .contains("returnCode=2 means duplicate user.")
                .contains("repoId: repo-1", "chunkId: chunk-1")
                .contains("repoId: bf4ef891-330b-4ce8-9002-ba4c43ffe210", "chunkId: consumer-chunk")
                .contains("Inspect timeout close behavior.")
                .doesNotContain("Verify the exact database row.")
                .doesNotContain("Call getCodeChunk and run a narrower query.");
    }

    @Test
    void explicitRemovalOfExistingKnownWithReasonIsApplied() {
        properties.setMaxContextTokens(2_000);
        ConversationContextCompressor.CurrentTaskWorkingState existing =
                new ConversationContextCompressor.CurrentTaskWorkingState(SUMMARY_V1, 0, 1, 1);
        summaryClient.nextSummary = deltaRemovingKnown(
                "Search confirmed the handler location. || reason: contradicted by exact source");

        ConversationContextCompressor.CurrentTaskCompression result = compress(
                group("remove", "getCodeChunk", longBody("contradicting exact source")), existing);

        assertThat(result.compressed()).isTrue();
        assertThat(result.state().summary())
                .doesNotContain("Search confirmed the handler location.")
                .contains("status=206, rowLimit=50, hasMore=true.", "repoId: repo-1", "chunkId: chunk-1");
    }

    @Test
    void removalWithoutReasonFailsClosedAndKeepsOldStateAndRawGroup() {
        properties.setMaxContextTokens(2_000);
        List<ChatMessageDTO> protocol = group("invalid-remove", "getCodeChunk",
                longBody("contradicting exact source"));
        ConversationContextCompressor.CurrentTaskWorkingState existing =
                new ConversationContextCompressor.CurrentTaskWorkingState(SUMMARY_V1, 0, 1, 1);
        summaryClient.nextSummary = deltaRemovingKnown("Search confirmed the handler location.");

        ConversationContextCompressor.CurrentTaskCompression result = compress(protocol, existing);

        assertThat(result.compressed()).isFalse();
        assertThat(result.state().summary()).isEqualTo(SUMMARY_V1);
        assertThat(result.state().coveredThroughLogicalGroup()).isZero();
        assertThat(result.uncoveredProtocolMessages()).containsExactlyElementsOf(protocol);
    }

    @Test
    void removalOfMissingTargetFailsClosedWithoutChangingOtherState() {
        properties.setMaxContextTokens(2_000);
        List<ChatMessageDTO> protocol = group("missing-remove", "getCodeChunk",
                longBody("unrelated exact source"));
        ConversationContextCompressor.CurrentTaskWorkingState existing =
                new ConversationContextCompressor.CurrentTaskWorkingState(SUMMARY_V1, 0, 1, 1);
        summaryClient.nextSummary = deltaRemovingKnown(
                "A fact that never existed. || reason: invalidated by exact source");

        ConversationContextCompressor.CurrentTaskCompression result = compress(protocol, existing);

        assertThat(result.compressed()).isFalse();
        assertThat(result.state().summary()).isEqualTo(SUMMARY_V1);
        assertThat(result.state().coveredThroughLogicalGroup()).isZero();
        assertThat(result.uncoveredProtocolMessages()).containsExactlyElementsOf(protocol);
    }

    @Test
    void noNewConfirmedInformationKeepsProtectedStateUnchanged() {
        properties.setMaxContextTokens(2_000);
        ConversationContextCompressor.CurrentTaskWorkingState existing =
                new ConversationContextCompressor.CurrentTaskWorkingState(SUMMARY_V1, 0, 1, 1);
        summaryClient.nextSummary = KEEP_DELTA;

        ConversationContextCompressor.CurrentTaskCompression result = compress(
                group("no-new", "knowledgeQuery", longBody("no new confirmed information")), existing);

        assertThat(result.compressed()).isTrue();
        assertThat(result.state().summary()).isEqualTo(SUMMARY_V1.strip());
        assertThat(result.state().coveredThroughLogicalGroup()).isEqualTo(1);
    }

    @Test
    void sparseDeltaWithoutGoalKeepsExistingGoalWithoutCorrectiveRetry() {
        properties.setMaxContextTokens(2_000);
        ConversationContextCompressor.CurrentTaskWorkingState existing =
                new ConversationContextCompressor.CurrentTaskWorkingState(SUMMARY_V1, 0, 1, 1);
        summaryClient.nextSummary = SPARSE_ADD_DELTA;

        ConversationContextCompressor.CurrentTaskCompression result = compress(
                group("sparse-existing", "searchProjectCode", longBody("new evidence")), existing);

        assertThat(result.compressed()).isTrue();
        assertThat(result.correctiveRetryCount()).isZero();
        assertThat(summaryClient.callCount).isEqualTo(1);
        assertThat(result.state().summary())
                .contains("Diagnose order 123 without losing the original question.")
                .contains("Fact A", "repoId: sparse-repo, chunkId: sparse-chunk", "inspect B")
                .contains("Verify the exact database row.");
    }

    @Test
    void initialSparseDeltaWithoutGoalUsesDeterministicRawUserReference() {
        properties.setMaxContextTokens(2_000);
        summaryClient.nextSummary = SPARSE_ADD_DELTA;

        ConversationContextCompressor.CurrentTaskCompression result = compress(
                group("sparse-initial", "searchProjectCode", longBody("first evidence")),
                ConversationContextCompressor.CurrentTaskWorkingState.empty());

        assertThat(result.compressed()).isTrue();
        assertThat(result.correctiveRetryCount()).isZero();
        assertThat(summaryClient.callCount).isEqualTo(1);
        assertThat(result.state().summary())
                .contains("Complete the original current User question retained separately in raw form.")
                .doesNotContain("Original current user question")
                .contains("Fact A", "repoId: sparse-repo, chunkId: sparse-chunk");
    }

    @Test
    void omittedNoOpSectionsKeepMonotonicAndDynamicState() {
        properties.setMaxContextTokens(2_000);
        ConversationContextCompressor.CurrentTaskWorkingState existing =
                new ConversationContextCompressor.CurrentTaskWorkingState(SUMMARY_V1, 0, 1, 1);
        summaryClient.nextSummary = """
                Current Task Continuation State Delta

                - KnownAdd
                  - A newly confirmed fact.
                - Next
                  - Inspect the newly identified component.
                """;

        ConversationContextCompressor.CurrentTaskCompression result = compress(
                group("sparse-noops", "knowledgeQuery", longBody("new fact body")), existing);

        assertThat(result.compressed()).isTrue();
        assertThat(result.state().summary())
                .contains("Search confirmed the handler location.", "A newly confirmed fact.")
                .contains("status=206, rowLimit=50, hasMore=true.")
                .contains("repoId: repo-1", "chunkId: chunk-1")
                .contains("Verify the exact database row.")
                .contains("Inspect the newly identified component.")
                .doesNotContain("Call getCodeChunk and run a narrower query.");
    }

    @Test
    void headerOnlySparseDeltaIsNoOpAndCanAssimilateNoNewInformation() {
        properties.setMaxContextTokens(2_000);
        ConversationContextCompressor.CurrentTaskWorkingState existing =
                new ConversationContextCompressor.CurrentTaskWorkingState(SUMMARY_V1, 0, 1, 1);
        summaryClient.nextSummary = "Current Task Continuation State Delta";

        ConversationContextCompressor.CurrentTaskCompression result = compress(
                group("sparse-empty", "knowledgeQuery", longBody("no new confirmed information")), existing);

        assertThat(result.compressed()).isTrue();
        assertThat(result.state().summary()).isEqualTo(SUMMARY_V1.strip());
        assertThat(result.state().coveredThroughLogicalGroup()).isEqualTo(1);
        assertThat(result.correctiveRetryCount()).isZero();
    }

    @Test
    void unknownSparseSectionFailsClosedWithoutChangingStateOrCoverage() {
        properties.setMaxContextTokens(2_000);
        List<ChatMessageDTO> protocol = group(
                "sparse-unknown", "knowledgeQuery", longBody("unrecognized patch"));
        ConversationContextCompressor.CurrentTaskWorkingState existing =
                new ConversationContextCompressor.CurrentTaskWorkingState(SUMMARY_V1, 0, 1, 1);
        summaryClient.nextSummary = """
                Current Task Continuation State Delta

                - EvidenceManifest
                  - forbidden second truth
                """;

        ConversationContextCompressor.CurrentTaskCompression result = compress(protocol, existing);

        assertThat(result.compressed()).isFalse();
        assertThat(result.state().summary()).isEqualTo(SUMMARY_V1);
        assertThat(result.state().coveredThroughLogicalGroup()).isZero();
        assertThat(result.uncoveredProtocolMessages()).containsExactlyElementsOf(protocol);
        assertThat(summaryClient.callCount).isEqualTo(2);
    }

    @Test
    void sparseDeltaOmitsNoOpSerializationAtLowerMeasuredSize() {
        String fullForm = KEEP_DELTA
                .replace("- KnownAdd\n  - none", "- KnownAdd\n  - Fact A")
                .replace("- Next\n  - KEEP", "- Next\n  - inspect B");
        String sparse = """
                Current Task Continuation State Delta

                - KnownAdd
                  - Fact A
                - Next
                  - inspect B
                """.strip();
        EstimatedTokenCounter counter = new EstimatedTokenCounter(properties);
        int fullTokens = counter.countText(MODEL, fullForm).tokens();
        int sparseTokens = counter.countText(MODEL, sparse).tokens();

        assertThat(sparse.length()).isLessThan(fullForm.length());
        assertThat(sparseTokens).isLessThan(fullTokens);
        System.out.printf("SPARSE_DELTA_SIZE oldChars=%d oldTokens=%d newChars=%d newTokens=%d%n",
                fullForm.length(), fullTokens, sparse.length(), sparseTokens);
    }

    private ConversationContextCompressor.CurrentTaskCompression compress(
            List<ChatMessageDTO> protocol,
            ConversationContextCompressor.CurrentTaskWorkingState state) {
        return compress(protocol, state, List.of());
    }

    private ConversationContextCompressor.CurrentTaskCompression compress(
            List<ChatMessageDTO> protocol,
            ConversationContextCompressor.CurrentTaskWorkingState state,
            List<ChatMessageDTO> fixedPlanningMessages) {
        ChatMessageDTO currentUser = ChatMessageDTO.builder()
                .id("current-user").role(ChatMessageDTO.RoleType.USER)
                .content("Original current user question").build();
        return compressor.compressCurrentTaskIfNeeded(
                "session-1", MODEL, currentUser, "Conversation Context\ncompleted summary",
                List.of(currentUser), protocol, fixedPlanningMessages, state);
    }

    private List<ChatMessageDTO> group(String id, String toolName, String body) {
        String callId = "call-" + id;
        return List.of(assistant(id, List.of(call(callId, toolName))),
                response(id + "-response", callId, toolName, body));
    }

    private AssistantMessage.ToolCall call(String id, String name) {
        return new AssistantMessage.ToolCall(id, "function", name, "{}");
    }

    private ChatMessageDTO assistant(String id, List<AssistantMessage.ToolCall> calls) {
        return ChatMessageDTO.builder().id(id).role(ChatMessageDTO.RoleType.ASSISTANT).content("")
                .metadata(ChatMessageDTO.MetaData.builder().taskId("task-1").toolCalls(calls).build()).build();
    }

    private ChatMessageDTO response(String id, String callId, String toolName, String body) {
        ToolResponseMessage.ToolResponse response =
                new ToolResponseMessage.ToolResponse(callId, toolName, body);
        return ChatMessageDTO.builder().id(id).role(ChatMessageDTO.RoleType.TOOL).content(body)
                .metadata(ChatMessageDTO.MetaData.builder().taskId("task-1").toolResponse(response).build()).build();
    }

    private String longBody(String marker) {
        return marker + "\n" + "detail ".repeat(180);
    }

    private String deltaWithKnown(String known) {
        return DELTA_V1.replace("Search confirmed the handler location.", known);
    }

    private String deltaWithRefs(String refs) {
        return DELTA_V1.replace("repoId: repo-1\n  - chunkId: chunk-1", refs);
    }

    private String deltaWithOpen(String open, String next) {
        return KEEP_DELTA
                .replace("- Goal\n  - KEEP", "- Goal\n  - Diagnose order 123 without losing the original question.")
                .replace("- Open\n  - KEEP", "- Open\n  - " + open)
                .replace("- Next\n  - KEEP", "- Next\n  - " + next);
    }

    private String keepDeltaWithOpen(String open, String next) {
        return KEEP_DELTA
                .replace("- Open\n  - KEEP", "- Open\n  - " + open)
                .replace("- Next\n  - KEEP", "- Next\n  - " + next);
    }

    private String deltaRemovingKnown(String removal) {
        return KEEP_DELTA.replace("- KnownRemove\n  - none", "- KnownRemove\n  - " + removal);
    }

    private static final class RecordingSummaryClient implements ConversationSummaryClient {
        private String nextSummary = DELTA_V1;
        private final List<String> queuedResponses = new ArrayList<>();
        private final List<String> prompts = new ArrayList<>();
        private String lastPrompt;
        private int callCount;

        @Override
        public String summarize(String model, String prompt) {
            callCount++;
            lastPrompt = prompt;
            prompts.add(prompt);
            return queuedResponses.isEmpty() ? nextSummary : queuedResponses.remove(0);
        }
    }
}
