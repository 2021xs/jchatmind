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
            Current Task Working Summary

            - Original Goal
              - Diagnose order 123 without losing the original question.
            - Confirmed Facts
              - Search confirmed the handler location.
            - Exact Constraints / Values
              - status=206, rowLimit=50, hasMore=true.
            - Important Relationships / Decisions
              - Narrow the SQL before concluding.
            - Stable References
              - repoId: repo-1
              - chunkId: chunk-1
            - Unresolved Questions
              - Verify the exact database row.
            - Next Planning Needs
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
        properties.setMaxContextTokens(180);
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
                MODEL, currentUser, null, List.of(currentUser), protocol, result.state());
        assertThat(retryCheck.needed()).isFalse();
        assertThat(retryCheck.reason()).isEqualTo("previous_failure");

        ConversationContextCompressor.CurrentTaskCompression retry = compressor.compressCurrentTaskIfNeeded(
                "session-1", MODEL, currentUser, null, List.of(currentUser), protocol, result.state());
        assertThat(retry.compressed()).isFalse();
        assertThat(retry.state()).isEqualTo(result.state());
        assertThat(summaryClient.callCount).isEqualTo(2);
    }

    @Test
    void overLengthSummaryGetsOneCorrectiveRetryAndThenAdvancesCoverage() {
        List<ChatMessageDTO> protocol = group("g1", "searchProjectCode",
                longBody("repoId: repo-1\nchunkId: chunk-1\nEXACT=42"));
        summaryClient.queuedResponses.add(SUMMARY_V1 + "x".repeat(properties.getMaxSummaryChars()));
        summaryClient.queuedResponses.add(SUMMARY_V1);

        ConversationContextCompressor.CurrentTaskCompression result = compress(
                protocol, ConversationContextCompressor.CurrentTaskWorkingState.empty());

        assertThat(result.compressed()).isTrue();
        assertThat(result.correctiveRetryCount()).isEqualTo(1);
        assertThat(result.state().coveredThroughLogicalGroup()).isEqualTo(1);
        assertThat(result.state().compressionSuppressed()).isFalse();
        assertThat(result.uncoveredProtocolMessages()).isEmpty();
        assertThat(summaryClient.callCount).isEqualTo(2);
        assertThat(summaryClient.lastPrompt)
                .contains("plain text only", "must not exceed 1200 characters",
                        "Do not translate, rename, omit, or add headings",
                        "repoId: repo-1", "chunkId: chunk-1");
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

    private ConversationContextCompressor.CurrentTaskCompression compress(
            List<ChatMessageDTO> protocol,
            ConversationContextCompressor.CurrentTaskWorkingState state) {
        ChatMessageDTO currentUser = ChatMessageDTO.builder()
                .id("current-user").role(ChatMessageDTO.RoleType.USER)
                .content("Original current user question").build();
        return compressor.compressCurrentTaskIfNeeded(
                "session-1", MODEL, currentUser, "Conversation Context\ncompleted summary",
                List.of(currentUser), protocol, state);
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

    private static final class RecordingSummaryClient implements ConversationSummaryClient {
        private String nextSummary = SUMMARY_V1;
        private final List<String> queuedResponses = new ArrayList<>();
        private String lastPrompt;
        private int callCount;

        @Override
        public String summarize(String model, String prompt) {
            callCount++;
            lastPrompt = prompt;
            return queuedResponses.isEmpty() ? nextSummary : queuedResponses.remove(0);
        }
    }
}
