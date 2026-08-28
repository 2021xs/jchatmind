package com.kama.jchatmind.benchmark.context;

import com.kama.jchatmind.model.dto.ChatMessageDTO;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextOriginAttributorTest {

    @Test
    void attributesCompletedAndCurrentToolProtocolByPersistedTaskId() {
        AssistantMessage previousCall = assistant("old-call");
        ToolResponseMessage previousResponse = response("old-call", "old evidence");
        AssistantMessage currentCall = assistant("new-call");
        ToolResponseMessage currentResponse = response("new-call", "new evidence");
        List<Message> request = List.of(
                new UserMessage("old question"), previousCall, previousResponse,
                new UserMessage("current question"), currentCall, currentResponse);
        List<ChatMessageDTO> persisted = List.of(
                dto("old-task", previousCall, null), dto("old-task", null, previousResponse),
                dto("new-task", currentCall, null), dto("new-task", null, currentResponse));

        Map<String, Integer> result = new ContextOriginAttributor(
                new EstimatedMessageTokenMeasurer(3)).attribute(request, "planner", "new-task", persisted);

        assertTrue(result.get(ContextOriginAttributor.COMPLETED_TASK_TOOL) > 0);
        assertTrue(result.get(ContextOriginAttributor.CURRENT_TASK_TOOL) > 0);
        assertTrue(result.get(ContextOriginAttributor.CURRENT_USER) > 0);
        assertTrue(result.get(ContextOriginAttributor.CURRENT_TASK_PLANNING) > 0);
        assertEquals(0, result.get(ContextOriginAttributor.UNKNOWN));
    }

    private AssistantMessage assistant(String id) {
        return AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", "search", "{}"))).build();
    }

    private ToolResponseMessage response(String id, String content) {
        return ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse(id, "search", content))).build();
    }

    private ChatMessageDTO dto(String taskId, AssistantMessage call, ToolResponseMessage response) {
        return ChatMessageDTO.builder().metadata(ChatMessageDTO.MetaData.builder()
                .taskId(taskId)
                .toolCalls(call == null ? null : call.getToolCalls())
                .toolResponse(response == null ? null : response.getResponses().get(0))
                .build()).build();
    }
}
