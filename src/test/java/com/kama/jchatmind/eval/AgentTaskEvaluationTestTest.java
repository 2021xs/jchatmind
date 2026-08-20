package com.kama.jchatmind.eval;

import com.kama.jchatmind.model.dto.ChatMessageDTO;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AgentTaskEvaluationTestTest {

    @Test
    void finalAnswerIgnoresAssistantToolCallMessages() {
        ChatMessageDTO toolRequest = ChatMessageDTO.builder()
                .role(ChatMessageDTO.RoleType.ASSISTANT)
                .content("intermediate")
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .metadata(ChatMessageDTO.MetaData.builder()
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-1", "function", "searchProjectCode", "{}")))
                        .build())
                .build();
        ChatMessageDTO finalAnswer = ChatMessageDTO.builder()
                .role(ChatMessageDTO.RoleType.ASSISTANT)
                .content("final answer")
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 1))
                .metadata(ChatMessageDTO.MetaData.builder().toolCalls(List.of()).build())
                .build();

        assertEquals("final answer", AgentTaskEvaluationTest.finalAnswer(List.of(toolRequest, finalAnswer)));
    }

    @Test
    void finalAnswerIsUnavailableWhenOnlyToolCallMessagesExist() {
        ChatMessageDTO toolRequest = ChatMessageDTO.builder()
                .role(ChatMessageDTO.RoleType.ASSISTANT)
                .content("intermediate")
                .metadata(ChatMessageDTO.MetaData.builder()
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-1", "function", "searchProjectCode", "{}")))
                        .build())
                .build();

        assertNull(AgentTaskEvaluationTest.finalAnswer(List.of(toolRequest)));
    }
}
