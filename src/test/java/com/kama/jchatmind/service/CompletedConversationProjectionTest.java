package com.kama.jchatmind.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.ContextCompressionProperties;
import com.kama.jchatmind.mapper.AgentTaskMapper;
import com.kama.jchatmind.mapper.ChatSessionMapper;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.dto.ChatSessionDTO;
import com.kama.jchatmind.model.entity.AgentTask;
import com.kama.jchatmind.model.entity.ChatSession;
import com.kama.jchatmind.service.impl.ConversationContextCompressorImpl;
import com.kama.jchatmind.service.impl.EstimatedTokenCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompletedConversationProjectionTest {

    private static final String SESSION_ID = "session-1";
    private static final String MODEL = "deepseek-chat";
    private static final String TASK_AWARE_SUMMARY = """
            Conversation Context

            - User Goals / Requests
              - Preserve the confirmed conversation goal.
            - Confirmed Final Conclusions
              - The completed answer is available.
            - Important Constraints / Exact Values
              - Keep exact value 42.
            - Decisions / Preferences Relevant to Future Turns
              - Prefer the confirmed implementation.
            - Open Conversation-level Follow-ups
              - none
            """;

    @Mock
    private ChatSessionMapper chatSessionMapper;

    @Mock
    private AgentTaskMapper agentTaskMapper;

    private ObjectMapper objectMapper;
    private RecordingSummaryClient summaryClient;
    private ConversationContextCompressorImpl compressor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        ContextCompressionProperties properties = new ContextCompressionProperties();
        properties.setMaxSummaryChars(2000);
        properties.setMaxHistoryMessages(20);
        properties.setCompressionTriggerTokens(12000);
        properties.setWorkingContextHardLimitTokens(12000);
        properties.setCharsPerToken(3);
        summaryClient = new RecordingSummaryClient();
        compressor = new ConversationContextCompressorImpl(
                properties, summaryClient, chatSessionMapper, agentTaskMapper, objectMapper,
                new EstimatedTokenCounter(properties));
    }

    @Test
    void projectsOnlySuccessfulUserFinalAndCurrentUser() {
        List<ChatMessageDTO> messages = List.of(
                user("user-success", 1, longText("successful-user")),
                toolAssistant("tool-assistant-success", 2, "task-success", "call-success"),
                toolResponse("tool-response-success", 3, "task-success", "call-success", "SECRET_TOOL_BODY"),
                finalAnswer("final-success", 4, "task-success", longText("successful-final")),
                user("user-failed", 5, "failed-user"),
                toolAssistant("tool-assistant-failed", 6, "task-failed", "call-failed"),
                toolResponse("tool-response-failed", 7, "task-failed", "call-failed", "FAILED_TOOL_BODY"),
                user("user-cancelled", 8, "cancelled-user"),
                toolAssistant("tool-assistant-cancelled", 9, "task-cancelled", "call-cancelled"),
                toolResponse("tool-response-cancelled", 10, "task-cancelled", "call-cancelled", "CANCELLED_TOOL_BODY"),
                user("user-crashed", 11, "crashed-user"),
                user("current-user", 12, "current question"));
        when(agentTaskMapper.selectBySessionId(SESSION_ID)).thenReturn(List.of(
                task("task-success", "user-success", AgentTaskLogService.STATUS_SUCCESS),
                task("task-failed", "user-failed", AgentTaskLogService.STATUS_FAILED),
                task("task-cancelled", "user-cancelled", AgentTaskLogService.STATUS_CANCELLED),
                task("task-crashed", "user-crashed", AgentTaskLogService.STATUS_CRASHED)));
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(null);

        ConversationContextCompressor.CompletedConversationProjection projection =
                compressor.projectCompletedConversation(SESSION_ID, MODEL, "current-user", messages);

        assertThat(projection.freshRebuild()).isTrue();
        assertThat(projection.coverageBoundaryMessageId()).isEqualTo("final-success");
        assertThat(projection.messages()).extracting(ChatMessageDTO::getId)
                .containsExactly("current-user");
        assertThat(summaryClient.lastPrompt)
                .contains("successful-user", "successful-final")
                .doesNotContain("SECRET_TOOL_BODY", "FAILED_TOOL_BODY", "CANCELLED_TOOL_BODY")
                .doesNotContain("failed-user", "cancelled-user", "crashed-user")
                .doesNotContain("ToolResponse", "tool-assistant");
    }

    @Test
    void projectsSuccessfulNoToolTask() {
        List<ChatMessageDTO> messages = List.of(
                user("user-1", 1, longText("no-tool-user")),
                finalAnswer("final-1", 2, "task-1", longText("no-tool-final")),
                user("current-user", 3, "follow-up"));
        when(agentTaskMapper.selectBySessionId(SESSION_ID)).thenReturn(List.of(
                task("task-1", "user-1", AgentTaskLogService.STATUS_SUCCESS)));
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(null);

        ConversationContextCompressor.CompletedConversationProjection projection =
                compressor.projectCompletedConversation(SESSION_ID, MODEL, "current-user", messages);

        assertThat(projection.freshRebuild()).isTrue();
        assertThat(summaryClient.lastPrompt).contains("no-tool-user", "no-tool-final");
        assertThat(projection.messages()).extracting(ChatMessageDTO::getId).containsExactly("current-user");
    }

    @Test
    void rejectsLegacySummaryAtToolBoundaryAndRebuildsWithoutUsingItAsSeed() {
        ChatSessionDTO.MetaData metadata = metadata("LEGACY_SECRET_SUMMARY", "tool-response-1");
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(session(metadata));
        when(agentTaskMapper.selectBySessionId(SESSION_ID)).thenReturn(List.of(
                task("task-1", "user-1", AgentTaskLogService.STATUS_SUCCESS)));
        List<ChatMessageDTO> messages = List.of(
                user("user-1", 1, longText("trusted-user")),
                toolAssistant("tool-assistant-1", 2, "task-1", "call-1"),
                toolResponse("tool-response-1", 3, "task-1", "call-1", "TOOL_SECRET"),
                finalAnswer("final-1", 4, "task-1", longText("trusted-final")),
                user("current-user", 5, "current"));

        ConversationContextCompressor.CompletedConversationProjection projection =
                compressor.projectCompletedConversation(SESSION_ID, MODEL, "current-user", messages);

        assertThat(projection.freshRebuild()).isTrue();
        assertThat(summaryClient.lastPrompt)
                .doesNotContain("LEGACY_SECRET_SUMMARY", "TOOL_SECRET")
                .contains("trusted-user", "trusted-final");
        ChatSessionDTO.MetaData saved = lastSavedMetadata();
        assertThat(saved.getContextSummary()).isEqualTo(TASK_AWARE_SUMMARY.strip());
        assertThat(saved.getContextSummaryLastMessageId()).isEqualTo("final-1");
    }

    @Test
    void rejectsFinalLookingLegacySummaryWhenStructureIsInvalid() {
        ChatSessionDTO.MetaData metadata = metadata("legacy generic summary", "final-1");
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(session(metadata));
        when(agentTaskMapper.selectBySessionId(SESSION_ID)).thenReturn(List.of(
                task("task-1", "user-1", AgentTaskLogService.STATUS_SUCCESS)));
        List<ChatMessageDTO> messages = List.of(
                user("user-1", 1, longText("trusted-user")),
                finalAnswer("final-1", 2, "task-1", longText("trusted-final")),
                user("current-user", 3, "current"));

        ConversationContextCompressor.CompletedConversationProjection projection =
                compressor.projectCompletedConversation(SESSION_ID, MODEL, "current-user", messages);

        assertThat(projection.freshRebuild()).isTrue();
        assertThat(summaryClient.lastPrompt).doesNotContain("legacy generic summary");
        assertThat(projection.coverageBoundaryMessageId()).isEqualTo("final-1");
    }

    @Test
    void validCacheCoverageSkipsIneligibleRawMessagesWithoutClaimingTheyWereSummarized() {
        ChatSessionDTO.MetaData metadata = metadata(TASK_AWARE_SUMMARY, "final-1");
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(session(metadata));
        when(agentTaskMapper.selectBySessionId(SESSION_ID)).thenReturn(List.of(
                task("task-1", "user-1", AgentTaskLogService.STATUS_SUCCESS),
                task("task-cancelled", "user-cancelled", AgentTaskLogService.STATUS_CANCELLED),
                task("task-3", "user-3", AgentTaskLogService.STATUS_SUCCESS)));
        List<ChatMessageDTO> messages = List.of(
                user("user-1", 1, "user one"),
                finalAnswer("final-1", 2, "task-1", "final one"),
                user("user-cancelled", 3, "cancelled user"),
                toolAssistant("tool-cancelled", 4, "task-cancelled", "call-cancelled"),
                toolResponse("response-cancelled", 5, "task-cancelled", "call-cancelled", "CANCELLED_SECRET"),
                user("user-3", 6, "user three"),
                finalAnswer("final-3", 7, "task-3", "final three"),
                user("current-user", 8, "current"));

        ConversationContextCompressor.CompletedConversationProjection projection =
                compressor.projectCompletedConversation(SESSION_ID, MODEL, "current-user", messages);

        assertThat(projection.freshRebuild()).isFalse();
        assertThat(projection.coverageBoundaryMessageId()).isEqualTo("final-1");
        assertThat(projection.messages()).extracting(ChatMessageDTO::getId)
                .containsExactly("user-3", "final-3", "current-user");
        assertThat(projection.messages()).extracting(ChatMessageDTO::getContent)
                .doesNotContain("cancelled user", "CANCELLED_SECRET");
        verify(chatSessionMapper, never()).updateById(any());
        assertThat(summaryClient.callCount).isZero();
    }

    @Test
    void ignoresUnlinkedLegacyFinalAndClearsLegacyCacheWhenNoTrustedTaskExists() {
        ChatSessionDTO.MetaData metadata = metadata("legacy summary", "legacy-final");
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(session(metadata));
        when(agentTaskMapper.selectBySessionId(SESSION_ID)).thenReturn(List.of(
                task("task-failed", "failed-user", AgentTaskLogService.STATUS_FAILED)));
        List<ChatMessageDTO> messages = List.of(
                user("failed-user", 1, "failed"),
                unlinkedFinal("legacy-final", 2, "unlinked legacy answer"),
                user("current-user", 3, "current"));

        ConversationContextCompressor.CompletedConversationProjection projection =
                compressor.projectCompletedConversation(SESSION_ID, MODEL, "current-user", messages);

        assertThat(projection.unlinkedLegacyFinalCount()).isEqualTo(1);
        assertThat(projection.summary()).isNull();
        assertThat(projection.coverageBoundaryMessageId()).isNull();
        assertThat(projection.messages()).extracting(ChatMessageDTO::getId).containsExactly("current-user");
        assertThat(summaryClient.callCount).isZero();
        ChatSessionDTO.MetaData cleared = lastSavedMetadata();
        assertThat(cleared.getContextSummary()).isNull();
        assertThat(cleared.getContextSummaryLastMessageId()).isNull();
    }

    @Test
    void invalidGeneratedSummaryFailsClosedToRawEligibleUserFinalProjection() {
        summaryClient.nextSummary = "invalid output";
        ChatSessionDTO.MetaData metadata = metadata("legacy summary", "tool-response-1");
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(session(metadata));
        when(agentTaskMapper.selectBySessionId(SESSION_ID)).thenReturn(List.of(
                task("task-1", "user-1", AgentTaskLogService.STATUS_SUCCESS)));
        List<ChatMessageDTO> messages = List.of(
                user("user-1", 1, longText("trusted-user")),
                toolAssistant("tool-assistant-1", 2, "task-1", "call-1"),
                toolResponse("tool-response-1", 3, "task-1", "call-1", "TOOL_SECRET"),
                finalAnswer("final-1", 4, "task-1", longText("trusted-final")),
                user("current-user", 5, "current"));

        ConversationContextCompressor.CompletedConversationProjection projection =
                compressor.projectCompletedConversation(SESSION_ID, MODEL, "current-user", messages);

        assertThat(projection.summary()).isNull();
        assertThat(projection.coverageBoundaryMessageId()).isNull();
        assertThat(projection.messages()).extracting(ChatMessageDTO::getId)
                .containsExactly("user-1", "final-1", "current-user");
        assertThat(projection.messages()).extracting(ChatMessageDTO::getContent)
                .doesNotContain("TOOL_SECRET");
    }

    private ChatSessionDTO.MetaData lastSavedMetadata() {
        ArgumentCaptor<ChatSession> captor = ArgumentCaptor.forClass(ChatSession.class);
        verify(chatSessionMapper, org.mockito.Mockito.atLeastOnce()).updateById(captor.capture());
        try {
            List<ChatSession> updates = captor.getAllValues();
            return objectMapper.readValue(updates.get(updates.size() - 1).getMetadata(), ChatSessionDTO.MetaData.class);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private ChatSession session(ChatSessionDTO.MetaData metadata) {
        try {
            return ChatSession.builder().id(SESSION_ID).metadata(objectMapper.writeValueAsString(metadata)).build();
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private ChatSessionDTO.MetaData metadata(String summary, String boundary) {
        ChatSessionDTO.MetaData metadata = new ChatSessionDTO.MetaData();
        metadata.setContextSummary(summary);
        metadata.setContextSummaryLastMessageId(boundary);
        return metadata;
    }

    private AgentTask task(String taskId, String userMessageId, String status) {
        return AgentTask.builder().id(taskId).sessionId(SESSION_ID).userMessageId(userMessageId).status(status).build();
    }

    private ChatMessageDTO user(String id, int order, String content) {
        return message(id, order, ChatMessageDTO.RoleType.USER, content, null);
    }

    private ChatMessageDTO finalAnswer(String id, int order, String taskId, String content) {
        return message(id, order, ChatMessageDTO.RoleType.ASSISTANT, content,
                ChatMessageDTO.MetaData.builder().taskId(taskId).toolCalls(List.of()).build());
    }

    private ChatMessageDTO unlinkedFinal(String id, int order, String content) {
        return message(id, order, ChatMessageDTO.RoleType.ASSISTANT, content, null);
    }

    private ChatMessageDTO toolAssistant(String id, int order, String taskId, String callId) {
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(callId, "function", "searchProjectCode", "{}");
        return message(id, order, ChatMessageDTO.RoleType.ASSISTANT, "",
                ChatMessageDTO.MetaData.builder().taskId(taskId).toolCalls(List.of(call)).build());
    }

    private ChatMessageDTO toolResponse(String id, int order, String taskId, String callId, String content) {
        return message(id, order, ChatMessageDTO.RoleType.TOOL, content,
                ChatMessageDTO.MetaData.builder().taskId(taskId)
                        .toolResponse(new ToolResponseMessage.ToolResponse(callId, "searchProjectCode", content))
                        .build());
    }

    private ChatMessageDTO message(String id,
                                   int order,
                                   ChatMessageDTO.RoleType role,
                                   String content,
                                   ChatMessageDTO.MetaData metadata) {
        return ChatMessageDTO.builder()
                .id(id)
                .sessionId(SESSION_ID)
                .role(role)
                .content(content)
                .metadata(metadata)
                .createdAt(LocalDateTime.of(2026, 8, 30, 10, 0).plusMinutes(order))
                .build();
    }

    private String longText(String marker) {
        return marker + " " + "detail ".repeat(120);
    }

    private static final class RecordingSummaryClient implements ConversationSummaryClient {
        private String nextSummary = TASK_AWARE_SUMMARY;
        private String lastPrompt;
        private int callCount;

        @Override
        public String summarize(String model, String prompt) {
            callCount++;
            lastPrompt = prompt;
            return nextSummary;
        }
    }
}
