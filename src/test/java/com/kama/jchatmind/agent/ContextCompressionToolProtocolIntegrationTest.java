package com.kama.jchatmind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.ContextCompressionProperties;
import com.kama.jchatmind.mapper.AgentTaskMapper;
import com.kama.jchatmind.mapper.ChatSessionMapper;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.service.ConversationContextCompressor;
import com.kama.jchatmind.service.impl.ConversationContextCompressorImpl;
import com.kama.jchatmind.service.impl.EstimatedTokenCounter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContextCompressionToolProtocolIntegrationTest {

    @Test
    void compressedContextPreservesToolProtocolForFinalSanitizer() {
        ContextCompressionProperties properties = new ContextCompressionProperties();
        properties.setEnabled(true);
        properties.setKeepRecentRounds(1);
        properties.setMaxHistoryMessages(4);
        properties.setCompressionTriggerTokens(40);
        properties.setWorkingContextHardLimitTokens(40);
        properties.setMaxSingleToolResultTokens(2000);
        properties.setCharsPerToken(3);
        properties.setMaxSummaryChars(1200);
        ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
        when(sessionMapper.selectById("session-1")).thenReturn(null);
        ConversationContextCompressorImpl compressor = new ConversationContextCompressorImpl(
                properties,
                (model, prompt) -> "summary",
                sessionMapper,
                mock(AgentTaskMapper.class),
                new ObjectMapper().findAndRegisterModules(),
                new EstimatedTokenCounter(properties));

        List<ChatMessageDTO> history = List.of(
                normal("msg-1", 1, ChatMessageDTO.RoleType.USER, "old " + "x".repeat(100)),
                normal("msg-2", 2, ChatMessageDTO.RoleType.ASSISTANT, "old " + "y".repeat(100)),
                normal("msg-3", 3, ChatMessageDTO.RoleType.USER, "old follow-up"),
                assistantToolCall("msg-4", 4),
                toolResponse("msg-5", 5, "call-a", "result-a"),
                toolResponse("msg-6", 6, "call-b", "result-b"),
                normal("msg-7", 7, ChatMessageDTO.RoleType.USER, "current question")
        );

        ConversationContextCompressor.CompressedContext compressed =
                compressor.compressIfNeeded("session-1", "deepseek-chat", history);

        assertThat(compressed.compressed()).isTrue();
        assertThat(compressed.recentMessages()).extracting(ChatMessageDTO::getId)
                .containsExactly("msg-4", "msg-5", "msg-6", "msg-7");
        List<Message> runtimeMessages = toRuntimeMessages(compressed);
        List<Message> safeMessages = AgentMemoryHistorySanitizer.toSafeModelMessages(runtimeMessages);
        assertThat(safeMessages).hasSameSizeAs(runtimeMessages);
        assertThatCode(() -> JChatMind.buildFinalSynthesisMessages(runtimeMessages))
                .doesNotThrowAnyException();
    }

    private List<Message> toRuntimeMessages(ConversationContextCompressor.CompressedContext compressed) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(ConversationContextCompressor.summaryMessageContent(compressed.summary())));
        for (ChatMessageDTO message : compressed.recentMessages()) {
            switch (message.getRole()) {
                case USER -> messages.add(new UserMessage(message.getContent()));
                case ASSISTANT -> messages.add(AssistantMessage.builder()
                        .content(message.getContent())
                        .toolCalls(message.getMetadata() == null
                                ? List.of()
                                : message.getMetadata().getToolCalls())
                        .build());
                case TOOL -> messages.add(ToolResponseMessage.builder()
                        .responses(List.of(message.getMetadata().getToolResponse()))
                        .build());
                case SYSTEM -> messages.add(new SystemMessage(message.getContent()));
            }
        }
        return messages;
    }

    private ChatMessageDTO normal(String id,
                                  int minute,
                                  ChatMessageDTO.RoleType role,
                                  String content) {
        return ChatMessageDTO.builder()
                .id(id)
                .sessionId("session-1")
                .role(role)
                .content(content)
                .createdAt(time(minute))
                .build();
    }

    private ChatMessageDTO assistantToolCall(String id, int minute) {
        return ChatMessageDTO.builder()
                .id(id)
                .sessionId("session-1")
                .role(ChatMessageDTO.RoleType.ASSISTANT)
                .content("")
                .metadata(ChatMessageDTO.MetaData.builder()
                        .toolCalls(List.of(
                                new AssistantMessage.ToolCall(
                                        "call-a", "function", "searchProjectCode", "{}"),
                                new AssistantMessage.ToolCall(
                                        "call-b", "function", "searchProjectCode", "{}")))
                        .build())
                .createdAt(time(minute))
                .build();
    }

    private ChatMessageDTO toolResponse(String id,
                                        int minute,
                                        String toolCallId,
                                        String content) {
        return ChatMessageDTO.builder()
                .id(id)
                .sessionId("session-1")
                .role(ChatMessageDTO.RoleType.TOOL)
                .content(content)
                .metadata(ChatMessageDTO.MetaData.builder()
                        .toolResponse(new ToolResponseMessage.ToolResponse(
                                toolCallId, "searchProjectCode", content))
                        .build())
                .createdAt(time(minute))
                .build();
    }

    private LocalDateTime time(int minute) {
        return LocalDateTime.of(2026, 8, 23, 22, 0).plusMinutes(minute);
    }
}
