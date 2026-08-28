package com.kama.jchatmind.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolProtocolInspectorTest {

    @Test
    void acceptsTheSameCompleteBatchUsedByRuntimeMemory() {
        List<Message> messages = List.of(
                new UserMessage("question"),
                assistantCall("call-1"),
                response("call-1"));

        AgentToolProtocolInspector.Inspection inspection = AgentToolProtocolInspector.inspect(messages);

        assertTrue(inspection.valid());
        assertEquals(0, inspection.orphanToolProtocolCount());
    }

    @Test
    void reportsAnOrphanToolResponseUsingProductionValidation() {
        AgentToolProtocolInspector.Inspection inspection = AgentToolProtocolInspector.inspect(
                List.of(new UserMessage("question"), response("call-1")));

        assertFalse(inspection.valid());
        assertEquals(1, inspection.orphanToolProtocolCount());
        assertEquals(1, inspection.protocolValidationFailureCount());
        assertTrue(inspection.diagnostic().contains("orphan tool response"));
    }

    @Test
    void reportsAnIncompleteAssistantToolBatchAsOrphanProtocolMaterial() {
        AgentToolProtocolInspector.Inspection inspection = AgentToolProtocolInspector.inspect(
                List.of(new UserMessage("question"), assistantCall("call-1")));

        assertFalse(inspection.valid());
        assertEquals(1, inspection.orphanToolProtocolCount());
        assertTrue(inspection.diagnostic().contains("incomplete tool response batch"));
    }

    private AssistantMessage assistantCall(String id) {
        return AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", "search", "{}")))
                .build();
    }

    private ToolResponseMessage response(String id) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(id, "search", "result")))
                .build();
    }
}
