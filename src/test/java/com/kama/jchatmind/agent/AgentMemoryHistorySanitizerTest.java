package com.kama.jchatmind.agent;

import com.kama.jchatmind.model.dto.ChatMessageDTO;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMemoryHistorySanitizerTest {

    @Test
    void filtersPersistedToolHistoryAndAssistantToolCallsDuringReplay() {
        List<Message> memory = AgentMemoryHistorySanitizer.toSafeReplayMessages("previous summary", List.of(
                ChatMessageDTO.builder()
                        .id("user-1")
                        .role(ChatMessageDTO.RoleType.USER)
                        .content("What is the seckill flow?")
                        .build(),
                ChatMessageDTO.builder()
                        .id("assistant-tool-call")
                        .role(ChatMessageDTO.RoleType.ASSISTANT)
                        .content("")
                        .metadata(ChatMessageDTO.MetaData.builder()
                                .toolCalls(List.of(new AssistantMessage.ToolCall(
                                        "call-1",
                                        "function",
                                        "searchProjectCode",
                                        "{\"query\":\"seckill\"}"
                                )))
                                .build())
                        .build(),
                ChatMessageDTO.builder()
                        .id("tool-1")
                        .role(ChatMessageDTO.RoleType.TOOL)
                        .content("tool observation")
                        .metadata(ChatMessageDTO.MetaData.builder()
                                .toolResponse(new ToolResponseMessage.ToolResponse(
                                        "call-1",
                                        "searchProjectCode",
                                        "tool observation"))
                                .build())
                        .build(),
                ChatMessageDTO.builder()
                        .id("assistant-final")
                        .role(ChatMessageDTO.RoleType.ASSISTANT)
                        .content("Final answer")
                        .build()
        ));

        assertThat(memory).hasSize(3);
        assertThat(memory.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(memory.get(1)).isInstanceOf(UserMessage.class);
        assertThat(memory.get(2)).isInstanceOf(AssistantMessage.class);
        AssistantMessage assistant = (AssistantMessage) memory.get(2);
        assertThat(assistant.getText()).isEqualTo("Final answer");
        assertThat(assistant.getToolCalls()).isEmpty();
        assertThat(memory).noneMatch(ToolResponseMessage.class::isInstance);
    }

    @Test
    void dropsOrphanToolResponsesAndKeepsValidToolPairsDuringModelReplay() {
        AssistantMessage pairedAssistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1",
                        "function",
                        "searchProjectCode",
                        "{\"query\":\"seckill\"}"
                )))
                .build();
        ToolResponseMessage pairedToolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call-1",
                        "searchProjectCode",
                        "tool result"
                )))
                .build();
        ToolResponseMessage orphanToolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call-orphan",
                        "databaseQuery",
                        "orphan result"
                )))
                .build();
        AssistantMessage unpairedAssistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-missing-response",
                        "function",
                        "databaseQuery",
                        "{\"sql\":\"SELECT 1\"}"
                )))
                .build();

        List<Message> memory = AgentMemoryHistorySanitizer.toSafeModelMessages(List.of(
                new SystemMessage("system"),
                orphanToolResponse,
                new UserMessage("question"),
                pairedAssistant,
                pairedToolResponse,
                unpairedAssistant,
                AssistantMessage.builder().content("final answer").toolCalls(List.of()).build()
        ));

        assertThat(memory).hasSize(5);
        assertThat(memory.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(memory.get(1)).isInstanceOf(UserMessage.class);
        assertThat(memory.get(2)).isSameAs(pairedAssistant);
        assertThat(memory.get(3)).isSameAs(pairedToolResponse);
        assertThat(((AssistantMessage) memory.get(4)).getText()).isEqualTo("final answer");
    }
}
