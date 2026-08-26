package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.mapper.ChatMessageMapper;
import com.kama.jchatmind.mapper.ChatSessionMapper;
import com.kama.jchatmind.model.entity.ChatSession;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMessageCancellationCleanupTest {

    private final ChatMessageMapper chatMessageMapper = mock(ChatMessageMapper.class);
    private final ChatSessionMapper chatSessionMapper = mock(ChatSessionMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChatMessageFacadeServiceImpl service = new ChatMessageFacadeServiceImpl(
            chatMessageMapper,
            mock(ChatMessageConverter.class),
            mock(ApplicationEventPublisher.class),
            chatSessionMapper,
            objectMapper);

    @Test
    void discardsOnlyMapperSelectedTaskToolMessagesAndInvalidatesSummary() throws Exception {
        ChatSession session = ChatSession.builder()
                .id("session-1")
                .metadata("""
                        {
                          "channel": "WEB_CONSOLE",
                          "repoId": "repo-1",
                          "contextSummary": "summary containing cancelled tool evidence",
                          "contextSummaryLastMessageId": "tool-message-2",
                          "contextSummaryUpdatedAt": "2026-08-26T12:00:00",
                          "customFlag": true
                        }
                        """)
                .build();
        when(chatSessionMapper.selectByIdForUpdate("session-1")).thenReturn(session);
        when(chatMessageMapper.deleteTaskToolMessages("session-1", "task-1")).thenReturn(2);
        when(chatSessionMapper.updateById(org.mockito.ArgumentMatchers.any(ChatSession.class))).thenReturn(1);

        int deleted = service.discardTaskToolMessages("session-1", "task-1");

        assertThat(deleted).isEqualTo(2);
        InOrder order = inOrder(chatSessionMapper, chatMessageMapper);
        order.verify(chatSessionMapper).selectByIdForUpdate("session-1");
        order.verify(chatMessageMapper).deleteTaskToolMessages("session-1", "task-1");
        ArgumentCaptor<ChatSession> updatedSession = ArgumentCaptor.forClass(ChatSession.class);
        order.verify(chatSessionMapper).updateById(updatedSession.capture());

        JsonNode metadata = objectMapper.readTree(updatedSession.getValue().getMetadata());
        assertThat(metadata.get("channel").asText()).isEqualTo("WEB_CONSOLE");
        assertThat(metadata.get("repoId").asText()).isEqualTo("repo-1");
        assertThat(metadata.get("customFlag").asBoolean()).isTrue();
        assertThat(metadata.has("contextSummary")).isFalse();
        assertThat(metadata.has("contextSummaryLastMessageId")).isFalse();
        assertThat(metadata.has("contextSummaryUpdatedAt")).isFalse();
    }

    @Test
    void keepsExistingSummaryWhenTaskHasNoPersistedToolMessages() {
        ChatSession session = ChatSession.builder()
                .id("session-1")
                .metadata("{\"contextSummary\":\"safe previous summary\"}")
                .build();
        when(chatSessionMapper.selectByIdForUpdate("session-1")).thenReturn(session);
        when(chatMessageMapper.deleteTaskToolMessages("session-1", "task-1")).thenReturn(0);

        int deleted = service.discardTaskToolMessages("session-1", "task-1");

        assertThat(deleted).isZero();
        verify(chatSessionMapper, never()).updateById(org.mockito.ArgumentMatchers.any(ChatSession.class));
    }
}
