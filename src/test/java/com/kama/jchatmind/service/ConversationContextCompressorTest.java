package com.kama.jchatmind.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.ContextCompressionProperties;
import com.kama.jchatmind.mapper.ChatSessionMapper;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.dto.ChatSessionDTO;
import com.kama.jchatmind.model.entity.ChatSession;
import com.kama.jchatmind.service.impl.ConversationContextCompressorImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        properties.setTriggerMessageCount(6);
        properties.setMaxSummaryChars(40);
        properties.setMaxContextChars(16000);
        properties.setMaxSingleToolResultChars(4000);

        summaryClient = new FakeSummaryClient();
        compressor = new ConversationContextCompressorImpl(properties, summaryClient, chatSessionMapper, objectMapper);
    }

    @Test
    void shouldSkipCompressionWhenHistoryBelowTriggerCount() {
        List<ChatMessageDTO> messages = messages(5);
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(null);

        ConversationContextCompressor.CompressedContext result =
                compressor.compressIfNeeded(SESSION_ID, MODEL, messages);

        assertFalse(result.compressed());
        assertEquals(4, result.recentMessages().size());
        assertEquals("msg-2", result.recentMessages().get(0).getId());
        assertEquals(0, summaryClient.callCount);
        verify(chatSessionMapper, never()).updateById(any());
    }

    @Test
    void shouldReportCompressionNeededWhenContextCharsExceedThreshold() {
        properties.setTriggerMessageCount(100);
        properties.setMaxContextChars(40);
        List<ChatMessageDTO> messages = messages(8);
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(chatSessionUnchecked(null));

        ConversationContextCompressor.CompressionCheck check = compressor.check(SESSION_ID, messages);

        assertTrue(check.needed());
        assertTrue(check.reason().contains("context_chars"));
        assertEquals(8, check.messageCount());
        assertEquals(4, check.newCompressibleMessages());
    }

    @Test
    void shouldCompressWhenSingleToolResultExceedsThresholdEvenBelowMessageTrigger() {
        properties.setTriggerMessageCount(100);
        properties.setMaxContextChars(16000);
        properties.setMaxSingleToolResultChars(20);
        List<ChatMessageDTO> messages = messages(8);
        messages.get(1).setRole(ChatMessageDTO.RoleType.TOOL);
        messages.get(1).setContent("tool result with many many characters");
        when(chatSessionMapper.selectById(SESSION_ID)).thenReturn(chatSessionUnchecked(null));

        ConversationContextCompressor.CompressedContext result =
                compressor.compressIfNeeded(SESSION_ID, MODEL, messages);

        assertTrue(result.compressed());
        assertEquals(1, summaryClient.callCount);
        assertTrue(summaryClient.lastPrompt.contains("tool result with many many characters"));
    }

    @Test
    void shouldCompressOldMessagesAndPersistSummaryWhenHistoryExceedsTriggerCount() throws Exception {
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
        assertEquals(4, result.recentMessages().size());
        assertEquals(1, summaryClient.callCount);
        verify(chatSessionMapper, never()).updateById(any());
    }

    @Test
    void shouldLimitPersistedSummaryLength() throws Exception {
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
