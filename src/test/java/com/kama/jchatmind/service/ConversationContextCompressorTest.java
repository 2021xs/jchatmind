package com.kama.jchatmind.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.ContextCompressionProperties;
import com.kama.jchatmind.agent.observability.AgentLifecycleObservationPublisher;
import com.kama.jchatmind.mapper.ChatSessionMapper;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.dto.ChatSessionDTO;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationContextCompressorTest {
    private static final String SESSION_ID = "session-1";
    private static final String MODEL = "deepseek-chat";

    @Mock
    private ChatSessionMapper chatSessionMapper;

    private ObjectMapper objectMapper;
    private ContextCompressionProperties properties;
    private FakeSummaryClient summaryClient;
    private ConversationContextCompressorImpl compressor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        properties = new ContextCompressionProperties();
        properties.setEnabled(true);
        properties.setKeepRecentRounds(2);
        properties.setMaxHistoryMessages(4);
        properties.setMaxSummaryChars(40);
        properties.setMaxContextTokens(12000);
        properties.setMaxSingleToolResultTokens(2000);
        properties.setCharsPerToken(3);

        summaryClient = new FakeSummaryClient();
        compressor = new ConversationContextCompressorImpl(properties, summaryClient, chatSessionMapper, objectMapper,
                new EstimatedTokenCounter(properties));
    }

    @Test
    void shouldSkipCompressionWhenOnlyMessageCountGrows() {
        List<ChatMessageDTO> messages = messages(20);
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(null);

        ConversationContextCompressor.CompressedContext result =
                compressor.compressIfNeeded(SESSION_ID, MODEL, messages);

        assertFalse(result.compressed());
        assertEquals(4, result.recentMessages().size());
        assertEquals("msg-17", result.recentMessages().get(0).getId());
        assertEquals(0, summaryClient.callCount);
        verify(chatSessionMapper, never()).updateById(any());
    }

    @Test
    void shouldReportCompressionNeededWhenContextTokensExceedThreshold() {
        properties.setMaxContextTokens(40);
        List<ChatMessageDTO> messages = messages(8);
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(chatSessionUnchecked(null));

        ConversationContextCompressor.CompressionCheck check = compressor.check(SESSION_ID, MODEL, messages);

        assertTrue(check.needed());
        assertTrue(check.reason().contains("context_tokens"));
        assertEquals(8, check.messageCount());
        assertEquals(4, check.newCompressibleMessages());
        assertEquals("ESTIMATED_CHARS", check.tokenSource());
        assertEquals(40, check.maxContextTokens());
    }

    @Test
    void shouldCompressWhenSingleToolResultExceedsThresholdEvenBelowMessageTrigger() {
        properties.setMaxContextTokens(12000);
        properties.setMaxSingleToolResultTokens(10);
        List<ChatMessageDTO> messages = messages(8);
        messages.set(0, assistantToolCall("msg-1", 1, "call-1"));
        messages.set(1, toolResponse("msg-2", 2, "call-1",
                "tool result with many many characters"));
        messages.get(1).setContent("tool result with many many characters");
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(chatSessionUnchecked(null));

        ConversationContextCompressor.CompressedContext result =
                compressor.compressIfNeeded(SESSION_ID, MODEL, messages);

        assertTrue(result.compressed());
        assertEquals(1, summaryClient.callCount);
        assertTrue(summaryClient.lastPrompt.contains("tool result with many many characters"));
    }

    @Test
    void shouldPublishCompressionMeasurementsWithoutChangingCompressedContext() {
        properties.setMaxContextTokens(40);
        List<ChatMessageDTO> messages = messages(8);
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(chatSessionUnchecked(null));
        AtomicReference<AgentLifecycleObservationPublisher.CompressionObservation> observed =
                new AtomicReference<>();

        ConversationContextCompressor.CompressedContext result;
        try (AgentLifecycleObservationPublisher.Registration ignored =
                     AgentLifecycleObservationPublisher.registerCompression(observed::set)) {
            result = compressor.compressIfNeeded(SESSION_ID, MODEL, messages);
        }

        assertTrue(result.compressed());
        assertNotNull(observed.get());
        assertEquals(SESSION_ID, observed.get().sessionId());
        assertEquals(summaryClient.lastPrompt, observed.get().compressionPrompt());
        assertEquals(result.summary(), observed.get().outputSummary());
        assertTrue(observed.get().tokensBeforeCompression() > 0);
        assertTrue(observed.get().tokensAfterCompression() > 0);
        assertTrue(observed.get().succeeded());
    }

    @Test
    void shouldMoveRecentWindowStartBeforeCompleteToolBatch() {
        properties.setMaxContextTokens(12000);
        properties.setMaxHistoryMessages(4);
        List<ChatMessageDTO> messages = List.of(
                normalMessage("msg-1", 1, ChatMessageDTO.RoleType.USER, "question"),
                assistantToolCall("msg-2", 2, "call-a", "call-b"),
                toolResponse("msg-3", 3, "call-a", "result-a"),
                toolResponse("msg-4", 4, "call-b", "result-b"),
                normalMessage("msg-5", 5, ChatMessageDTO.RoleType.ASSISTANT, "intermediate answer"),
                normalMessage("msg-6", 6, ChatMessageDTO.RoleType.USER, "follow-up")
        );
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(null);

        ConversationContextCompressor.CompressedContext result =
                compressor.compressIfNeeded(SESSION_ID, MODEL, messages);

        assertFalse(result.compressed());
        assertEquals(List.of("msg-2", "msg-3", "msg-4", "msg-5", "msg-6"),
                ids(result.recentMessages()));
        assertCompleteToolProtocol(result.recentMessages());
    }

    @Test
    void shouldKeepMultipleToolBatchesCompleteWhenBoundaryFallsInsideFirstBatch() {
        properties.setKeepRecentRounds(1);
        properties.setMaxHistoryMessages(5);
        properties.setMaxContextTokens(12000);
        List<ChatMessageDTO> messages = List.of(
                normalMessage("msg-1", 1, ChatMessageDTO.RoleType.USER, "question"),
                assistantToolCall("msg-2", 2, "call-a", "call-b"),
                toolResponse("msg-3", 3, "call-a", "result-a"),
                toolResponse("msg-4", 4, "call-b", "result-b"),
                assistantToolCall("msg-5", 5, "call-c", "call-d"),
                toolResponse("msg-6", 6, "call-c", "result-c"),
                toolResponse("msg-7", 7, "call-d", "result-d"),
                normalMessage("msg-8", 8, ChatMessageDTO.RoleType.ASSISTANT, "answer")
        );
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(null);

        ConversationContextCompressor.CompressedContext result =
                compressor.compressIfNeeded(SESSION_ID, MODEL, messages);

        assertEquals(List.of("msg-2", "msg-3", "msg-4", "msg-5", "msg-6", "msg-7", "msg-8"),
                ids(result.recentMessages()));
        assertCompleteToolProtocol(result.recentMessages());
    }

    @Test
    void shouldRejectRawHistoryThatAlreadyContainsOrphanToolResponse() {
        List<ChatMessageDTO> messages = List.of(
                normalMessage("msg-1", 1, ChatMessageDTO.RoleType.USER, "question"),
                toolResponse("msg-2", 2, "call-orphan", "orphan")
        );
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> compressor.compressIfNeeded(SESSION_ID, MODEL, messages));

        assertTrue(error.getMessage().contains("orphan tool response"));
        assertEquals(0, summaryClient.callCount);
    }

    @Test
    void shouldUseEffectiveContextAfterCompressionAndEventuallyRetrigger() throws Exception {
        properties.setMaxContextTokens(80);
        properties.setMaxHistoryMessages(4);
        summaryClient.nextSummary = "short summary";
        List<ChatMessageDTO> messages = messages(8);
        for (int index = 0; index < 4; index++) {
            messages.get(index).setContent("old-history-" + "x".repeat(100));
        }
        for (int index = 4; index < messages.size(); index++) {
            messages.get(index).setContent("tail");
        }
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(null);

        ConversationContextCompressor.CompressedContext first =
                compressor.compressIfNeeded(SESSION_ID, MODEL, messages);

        assertTrue(first.compressed());
        assertEquals(1, summaryClient.callCount);
        ArgumentCaptor<ChatSession> savedSession = ArgumentCaptor.forClass(ChatSession.class);
        verify(chatSessionMapper).updateById(savedSession.capture());
        ChatSessionDTO.MetaData savedMetadata = objectMapper.readValue(
                savedSession.getValue().getMetadata(), ChatSessionDTO.MetaData.class);
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(chatSession(savedMetadata));

        List<ChatMessageDTO> nextRound = new ArrayList<>(messages);
        nextRound.add(assistantToolCall("msg-9", 9, "call-a", "call-b"));
        nextRound.add(toolResponse("msg-10", 10, "call-a", "a"));
        nextRound.add(toolResponse("msg-11", 11, "call-b", "b"));
        ConversationContextCompressor.CompressionCheck nextCheck =
                compressor.check(SESSION_ID, MODEL, nextRound);

        assertTrue(nextCheck.rawHistoryTokens() > properties.getMaxContextTokens());
        assertTrue(nextCheck.effectiveContextTokens() < properties.getMaxContextTokens());
        assertFalse(nextCheck.needed());
        ConversationContextCompressor.CompressedContext nextResult =
                compressor.compressIfNeeded(SESSION_ID, MODEL, nextRound);
        assertFalse(nextResult.compressed());
        assertEquals(1, summaryClient.callCount);

        List<ChatMessageDTO> grownTail = new ArrayList<>(nextRound);
        for (int index = 12; index <= 16; index++) {
            grownTail.add(normalMessage("msg-" + index, index, ChatMessageDTO.RoleType.USER,
                    "new-unsummarized-" + "y".repeat(100)));
        }
        ConversationContextCompressor.CompressionCheck retrigger =
                compressor.check(SESSION_ID, MODEL, grownTail);

        assertTrue(retrigger.effectiveContextTokens() >= properties.getMaxContextTokens());
        assertTrue(retrigger.newCompressibleMessages() > 0);
        assertTrue(retrigger.needed());
    }

    @Test
    void shouldIgnoreAndReplaceSummaryWhoseBoundarySplitsToolBatch() throws Exception {
        properties.setMaxContextTokens(40);
        properties.setMaxHistoryMessages(4);
        ChatSessionDTO.MetaData metadata = new ChatSessionDTO.MetaData();
        metadata.setContextSummary("unsafe old summary");
        metadata.setContextSummaryLastMessageId("msg-2");
        List<ChatMessageDTO> messages = List.of(
                normalMessage("msg-1", 1, ChatMessageDTO.RoleType.USER, "old " + "x".repeat(100)),
                assistantToolCall("msg-2", 2, "call-a", "call-b"),
                toolResponse("msg-3", 3, "call-a", "result-a"),
                toolResponse("msg-4", 4, "call-b", "result-b"),
                normalMessage("msg-5", 5, ChatMessageDTO.RoleType.USER, "follow-up"),
                normalMessage("msg-6", 6, ChatMessageDTO.RoleType.ASSISTANT, "answer")
        );
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(chatSession(metadata));

        ConversationContextCompressor.CompressedContext result =
                compressor.compressIfNeeded(SESSION_ID, MODEL, messages);

        assertTrue(result.compressed());
        assertFalse(summaryClient.lastPrompt.contains("unsafe old summary"));
        assertCompleteToolProtocol(result.recentMessages());
        ArgumentCaptor<ChatSession> savedSession = ArgumentCaptor.forClass(ChatSession.class);
        verify(chatSessionMapper).updateById(savedSession.capture());
        ChatSessionDTO.MetaData replacement = objectMapper.readValue(
                savedSession.getValue().getMetadata(), ChatSessionDTO.MetaData.class);
        assertEquals("msg-1", replacement.getContextSummaryLastMessageId());
    }

    @Test
    void shouldCompressOldMessagesAndPersistSummaryWhenTokenThresholdExceeded() throws Exception {
        properties.setMaxContextTokens(40);
        List<ChatMessageDTO> messages = messages(8);
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(chatSession(null));
        summaryClient.nextSummary = "summary-user-goal-key-files";

        ConversationContextCompressor.CompressedContext result =
                compressor.compressIfNeeded(SESSION_ID, MODEL, messages);

        assertTrue(result.compressed());
        assertEquals("summary-user-goal-key-files", result.summary());
        assertEquals(4, result.recentMessages().size());
        assertEquals("msg-5", result.recentMessages().get(0).getId());
        assertEquals(1, summaryClient.callCount);
        assertTrue(summaryClient.lastPrompt.contains("user content 1"));
        assertTrue(summaryClient.lastPrompt.contains("assistant content 4"));
        assertFalse(summaryClient.lastPrompt.contains("user content 5"));

        ArgumentCaptor<ChatSession> captor = ArgumentCaptor.forClass(ChatSession.class);
        verify(chatSessionMapper).updateById(captor.capture());
        ChatSessionDTO.MetaData metadata = objectMapper.readValue(
                captor.getValue().getMetadata(), ChatSessionDTO.MetaData.class);
        assertEquals("summary-user-goal-key-files", metadata.getContextSummary());
        assertEquals("msg-4", metadata.getContextSummaryLastMessageId());
        assertNotNull(metadata.getContextSummaryUpdatedAt());
    }

    @Test
    void shouldNotCompressSameHistoryAgainWhenLastCompressedMessageIsStillLatestCandidate() throws Exception {
        properties.setMaxContextTokens(40);
        ChatSessionDTO.MetaData metadata = new ChatSessionDTO.MetaData();
        metadata.setContextSummary("old summary");
        metadata.setContextSummaryLastMessageId("msg-4");

        List<ChatMessageDTO> messages = messages(8);
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(chatSession(metadata));

        ConversationContextCompressor.CompressedContext result =
                compressor.compressIfNeeded(SESSION_ID, MODEL, messages);

        assertFalse(result.compressed());
        assertEquals("old summary", result.summary());
        assertEquals(4, result.recentMessages().size());
        assertEquals(0, summaryClient.callCount);
        verify(chatSessionMapper, never()).updateById(any());
    }

    @Test
    void shouldFallbackToRecentMessagesWhenSummaryGenerationFails() throws Exception {
        properties.setMaxContextTokens(40);
        ChatSessionDTO.MetaData metadata = new ChatSessionDTO.MetaData();
        metadata.setContextSummary("old summary");
        metadata.setContextSummaryLastMessageId("msg-2");

        List<ChatMessageDTO> messages = messages(8);
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(chatSession(metadata));
        summaryClient.fail = true;

        ConversationContextCompressor.CompressedContext result =
                compressor.compressIfNeeded(SESSION_ID, MODEL, messages);

        assertFalse(result.compressed());
        assertEquals("old summary", result.summary());
        assertEquals(6, result.recentMessages().size());
        assertEquals("msg-3", result.recentMessages().get(0).getId());
        assertEquals(1, summaryClient.callCount);
        verify(chatSessionMapper, never()).updateById(any());
    }

    @Test
    void shouldLimitPersistedSummaryLength() throws Exception {
        properties.setMaxContextTokens(40);
        List<ChatMessageDTO> messages = messages(8);
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(chatSession(null));
        summaryClient.nextSummary = "01234567890123456789012345678901234567890123456789";

        ConversationContextCompressor.CompressedContext result =
                compressor.compressIfNeeded(SESSION_ID, MODEL, messages);

        assertTrue(result.compressed());
        assertTrue(result.summary().length() <= properties.getMaxSummaryChars());

        ArgumentCaptor<ChatSession> captor = ArgumentCaptor.forClass(ChatSession.class);
        verify(chatSessionMapper).updateById(captor.capture());
        ChatSessionDTO.MetaData metadata = objectMapper.readValue(
                captor.getValue().getMetadata(), ChatSessionDTO.MetaData.class);
        assertTrue(metadata.getContextSummary().length() <= properties.getMaxSummaryChars());
    }

    @Test
    void shouldUseModelSpecificTokenThresholds() {
        ContextCompressionProperties.TokenThreshold modelThreshold =
                new ContextCompressionProperties.TokenThreshold(40, 10);
        properties.getModelThresholds().put(MODEL, modelThreshold);

        List<ChatMessageDTO> messages = messages(8);
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(chatSessionUnchecked(null));

        ConversationContextCompressor.CompressionCheck check = compressor.check(SESSION_ID, MODEL, messages);

        assertTrue(check.needed());
        assertEquals(40, check.maxContextTokens());
        assertEquals(10, check.maxSingleToolResultTokensThreshold());
    }

    private ChatSession chatSession(ChatSessionDTO.MetaData metadata) throws Exception {
        return ChatSession.builder()
                .id(SESSION_ID)
                .metadata(metadata == null ? null : objectMapper.writeValueAsString(metadata))
                .build();
    }

    private ChatSession chatSessionUnchecked(ChatSessionDTO.MetaData metadata) {
        try {
            return chatSession(metadata);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<ChatMessageDTO> messages(int count) {
        List<ChatMessageDTO> messages = new ArrayList<>();
        LocalDateTime base = LocalDateTime.of(2026, 5, 10, 10, 0);
        for (int i = 1; i <= count; i++) {
            ChatMessageDTO.RoleType role = i % 2 == 1
                    ? ChatMessageDTO.RoleType.USER
                    : ChatMessageDTO.RoleType.ASSISTANT;
            messages.add(ChatMessageDTO.builder()
                    .id("msg-" + i)
                    .sessionId(SESSION_ID)
                    .role(role)
                    .content(role.getRole() + " content " + i)
                    .createdAt(base.plusMinutes(i))
                    .build());
        }
        return messages;
    }

    private ChatMessageDTO normalMessage(String id,
                                         int minute,
                                         ChatMessageDTO.RoleType role,
                                         String content) {
        return ChatMessageDTO.builder()
                .id(id)
                .sessionId(SESSION_ID)
                .role(role)
                .content(content)
                .createdAt(LocalDateTime.of(2026, 5, 10, 10, 0).plusMinutes(minute))
                .build();
    }

    private ChatMessageDTO assistantToolCall(String id, int minute, String... toolCallIds) {
        List<AssistantMessage.ToolCall> toolCalls = java.util.Arrays.stream(toolCallIds)
                .map(toolCallId -> new AssistantMessage.ToolCall(
                        toolCallId, "function", "searchProjectCode", "{}"))
                .toList();
        return ChatMessageDTO.builder()
                .id(id)
                .sessionId(SESSION_ID)
                .role(ChatMessageDTO.RoleType.ASSISTANT)
                .content("")
                .metadata(ChatMessageDTO.MetaData.builder().toolCalls(toolCalls).build())
                .createdAt(LocalDateTime.of(2026, 5, 10, 10, 0).plusMinutes(minute))
                .build();
    }

    private ChatMessageDTO toolResponse(String id,
                                        int minute,
                                        String toolCallId,
                                        String content) {
        return ChatMessageDTO.builder()
                .id(id)
                .sessionId(SESSION_ID)
                .role(ChatMessageDTO.RoleType.TOOL)
                .content(content)
                .metadata(ChatMessageDTO.MetaData.builder()
                        .toolResponse(new ToolResponseMessage.ToolResponse(
                                toolCallId, "searchProjectCode", content))
                        .build())
                .createdAt(LocalDateTime.of(2026, 5, 10, 10, 0).plusMinutes(minute))
                .build();
    }

    private List<String> ids(List<ChatMessageDTO> messages) {
        return messages.stream().map(ChatMessageDTO::getId).toList();
    }

    private void assertCompleteToolProtocol(List<ChatMessageDTO> messages) {
        for (int index = 0; index < messages.size(); index++) {
            ChatMessageDTO message = messages.get(index);
            if (message.getRole() == ChatMessageDTO.RoleType.TOOL) {
                throw new AssertionError("orphan tool response at index " + index);
            }
            List<AssistantMessage.ToolCall> toolCalls = message.getMetadata() == null
                    ? null
                    : message.getMetadata().getToolCalls();
            if (toolCalls == null || toolCalls.isEmpty()) {
                continue;
            }
            List<String> expectedIds = toolCalls.stream().map(AssistantMessage.ToolCall::id).sorted().toList();
            List<String> actualIds = new ArrayList<>();
            int responseIndex = index + 1;
            while (responseIndex < messages.size()
                    && messages.get(responseIndex).getRole() == ChatMessageDTO.RoleType.TOOL) {
                actualIds.add(messages.get(responseIndex).getMetadata().getToolResponse().id());
                responseIndex++;
            }
            assertEquals(expectedIds, actualIds.stream().sorted().toList());
            index = responseIndex - 1;
        }
    }

    private static class FakeSummaryClient implements ConversationSummaryClient {
        private int callCount;
        private boolean fail;
        private String nextSummary = "new summary";
        private String lastPrompt;

        @Override
        public String summarize(String model, String prompt) {
            callCount++;
            lastPrompt = prompt;
            assertEquals(MODEL, model);
            if (fail) {
                throw new IllegalStateException("summary model unavailable");
            }
            return nextSummary;
        }
    }
}
