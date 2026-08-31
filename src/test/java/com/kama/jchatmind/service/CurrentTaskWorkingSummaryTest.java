package com.kama.jchatmind.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.ContextCompressionProperties;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CurrentTaskWorkingSummaryTest {

    private static final String MODEL = "deepseek-chat";
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
        summaryClient.nextSummary = SUMMARY_V2;

        ConversationContextCompressor.CurrentTaskCompression result = compress(protocol, existing);

        assertThat(result.compressed()).isTrue();
        assertThat(result.state().coveredThroughLogicalGroup()).isEqualTo(2);
        assertThat(result.state().summaryDepth()).isEqualTo(2);
        assertThat(result.state().compressionCount()).isEqualTo(2);
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
        String overLegacyLimit = stateWithKnown("K".repeat(1_300));
        assertThat(overLegacyLimit.length()).isGreaterThan(properties.getMaxSummaryChars());
        summaryClient.nextSummary = overLegacyLimit;

        ConversationContextCompressor.CurrentTaskCompression result = compress(
                protocol, ConversationContextCompressor.CurrentTaskWorkingState.empty());

        assertThat(result.compressed()).isTrue();
        assertThat(result.correctiveRetryCount()).isZero();
        assertThat(result.state().coveredThroughLogicalGroup()).isEqualTo(1);
        assertThat(result.state().summary()).isEqualTo(overLegacyLimit.strip());
        assertThat(summaryClient.callCount).isEqualTo(1);
    }

    @Test
    void validPrimaryOverBudgetGetsOneStateOnlyCorrectiveAndFits() {
        properties.setMaxContextTokens(300);
        String rawMarker = "RAW_TOOL_BODY_MUST_NOT_REENTER_CORRECTIVE";
        List<ChatMessageDTO> protocol = group("g1", "searchProjectCode",
                rawMarker + "\n" + "detail ".repeat(600));
        String primary = stateWithKnown("P".repeat(900));
        summaryClient.queuedResponses.add(primary);
        summaryClient.queuedResponses.add(SUMMARY_V1);

        ConversationContextCompressor.CurrentTaskCompression result = compress(
                protocol, ConversationContextCompressor.CurrentTaskWorkingState.empty());

        assertThat(result.compressed()).isTrue();
        assertThat(result.correctiveRetryCount()).isEqualTo(1);
        assertThat(result.state().summary()).isEqualTo(SUMMARY_V1.strip());
        assertThat(result.state().coveredThroughLogicalGroup()).isEqualTo(1);
        assertThat(summaryClient.callCount).isEqualTo(2);
        assertThat(summaryClient.lastPrompt)
                .contains("Proposed Continuation State to compact", primary.strip(),
                        "Available estimated state token budget", "Original current user question")
                .doesNotContain(rawMarker);
    }

    @Test
    void budgetCorrectiveStillOverBudgetFailsClosedWithoutThirdCall() {
        properties.setMaxContextTokens(350);
        List<ChatMessageDTO> protocol = group("g1", "knowledgeQuery",
                "raw-marker\n" + "detail ".repeat(600));
        summaryClient.queuedResponses.add(stateWithKnown("P".repeat(900)));
        summaryClient.queuedResponses.add(stateWithKnown("C".repeat(700)));

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
        summaryClient.queuedResponses.add(stateWithKnown("C".repeat(700)));

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
        summaryClient.nextSummary = SUMMARY_V1;

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
        summaryClient.nextSummary = SUMMARY_V1;

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
        summaryClient.nextSummary = SUMMARY_V1;

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
        summaryClient.queuedResponses.add(stateWithRefs("repoId: repo-1"));
        summaryClient.queuedResponses.add(SUMMARY_V1);

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
        summaryClient.queuedResponses.add(stateWithKnown("P".repeat(900)));
        summaryClient.queuedResponses.add(SUMMARY_V1);
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

    private String stateWithKnown(String known) {
        return SUMMARY_V1.replace("Search confirmed the handler location.", known);
    }

    private String stateWithRefs(String refs) {
        return SUMMARY_V1.replace("repoId: repo-1\n  - chunkId: chunk-1", refs);
    }

    private static final class RecordingSummaryClient implements ConversationSummaryClient {
        private String nextSummary = SUMMARY_V1;
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
