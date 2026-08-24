package com.kama.jchatmind.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolAwareMessageWindowChatMemoryTest {

    private static final String SESSION_ID = "session-1";

    @Test
    void messageLengthTenKeepsEveryRetainedToolBatchComplete() {
        ProtocolAwareMessageWindowChatMemory memory = new ProtocolAwareMessageWindowChatMemory(10);
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("system"));
        messages.add(assistant("previous"));
        messages.add(new UserMessage("current"));
        messages.add(toolAssistant("A", "B"));
        messages.add(toolResponse("A"));
        messages.add(toolResponse("B"));
        messages.add(toolAssistant("C", "D"));
        messages.add(toolResponse("C"));
        messages.add(toolResponse("D"));
        messages.add(toolAssistant("E"));
        messages.add(toolResponse("E"));

        memory.add(SESSION_ID, messages);

        List<Message> retained = memory.get(SESSION_ID);
        assertEquals(10, retained.size());
        assertFalse(retained.contains(messages.get(1)), "oldest normal group should be evicted");
        assertProtocolComplete(retained);
    }

    @Test
    void evictionRemovesAWholeOldToolBatch() {
        ProtocolAwareMessageWindowChatMemory memory = new ProtocolAwareMessageWindowChatMemory(4);
        AssistantMessage oldAssistant = toolAssistant("A", "B");
        ToolResponseMessage oldResponse = toolResponse("A", "B");
        AssistantMessage latestAssistant = toolAssistant("C");
        ToolResponseMessage latestResponse = toolResponse("C");

        memory.add(SESSION_ID, List.of(
                new SystemMessage("system"),
                new UserMessage("question"),
                oldAssistant,
                oldResponse,
                latestAssistant,
                latestResponse));

        List<Message> retained = memory.get(SESSION_ID);
        assertFalse(retained.contains(oldAssistant));
        assertFalse(retained.contains(oldResponse));
        assertTrue(retained.contains(latestAssistant));
        assertTrue(retained.contains(latestResponse));
        assertProtocolComplete(retained);
    }

    @Test
    void realFiveBatchShapeRemainsSafeForFinalSanitizer() {
        ProtocolAwareMessageWindowChatMemory memory = new ProtocolAwareMessageWindowChatMemory(10);
        List<Message> messages = new ArrayList<>(List.of(
                new SystemMessage("system"),
                assistant("previous answer"),
                new UserMessage("current question")));
        messages.addAll(batch("A", "B"));
        messages.addAll(batch("C", "D"));
        messages.addAll(batch("E", "F"));
        messages.addAll(batch("G", "H"));
        messages.addAll(batch("I"));

        memory.add(SESSION_ID, messages);

        List<Message> retained = memory.get(SESSION_ID);
        assertEquals(9, retained.size());
        assertProtocolComplete(retained);
        assertDoesNotThrow(() -> new FinalContextCompiler().compile(
                new FinalSynthesisRequestFactory().create(retained, "current question")));
    }

    @Test
    void ordinaryConversationStillUsesTheConfiguredWindow() {
        ProtocolAwareMessageWindowChatMemory memory = new ProtocolAwareMessageWindowChatMemory(4);
        UserMessage firstUser = new UserMessage("one");
        AssistantMessage firstAssistant = assistant("one answer");
        UserMessage secondUser = new UserMessage("two");
        AssistantMessage secondAssistant = assistant("two answer");

        memory.add(SESSION_ID, List.of(
                new SystemMessage("system"),
                firstUser,
                firstAssistant,
                secondUser,
                secondAssistant));

        List<Message> retained = memory.get(SESSION_ID);
        assertEquals(4, retained.size());
        assertFalse(retained.contains(firstUser));
        assertTrue(retained.contains(firstAssistant));
        assertTrue(retained.contains(secondUser));
        assertTrue(retained.contains(secondAssistant));
    }

    @Test
    void invalidInputHistoryFailsClosed() {
        ProtocolAwareMessageWindowChatMemory memory = new ProtocolAwareMessageWindowChatMemory(10);
        assertThrows(IllegalStateException.class,
                () -> memory.add(SESSION_ID, List.of(toolResponse("orphan"))));
        assertThrows(IllegalStateException.class,
                () -> memory.add(SESSION_ID, List.of(toolAssistant("missing"))));
    }

    private static List<Message> batch(String... ids) {
        return List.of(toolAssistant(ids), toolResponse(ids));
    }

    private static AssistantMessage assistant(String text) {
        return AssistantMessage.builder().content(text).toolCalls(List.of()).build();
    }

    private static AssistantMessage toolAssistant(String... ids) {
        return AssistantMessage.builder()
                .content("")
                .toolCalls(java.util.Arrays.stream(ids)
                        .map(id -> new AssistantMessage.ToolCall(
                                id, "function", "searchProjectCode", "{}"))
                        .toList())
                .build();
    }

    private static ToolResponseMessage toolResponse(String... ids) {
        return ToolResponseMessage.builder()
                .responses(java.util.Arrays.stream(ids)
                        .map(id -> new ToolResponseMessage.ToolResponse(
                                id, "searchProjectCode", "evidence-" + id))
                        .toList())
                .build();
    }

    private static void assertProtocolComplete(List<Message> messages) {
        Set<String> expected = messages.stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .flatMap(message -> message.getToolCalls().stream())
                .map(AssistantMessage.ToolCall::id)
                .collect(Collectors.toSet());
        Set<String> actual = messages.stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .flatMap(message -> message.getResponses().stream())
                .map(ToolResponseMessage.ToolResponse::id)
                .collect(Collectors.toSet());
        assertEquals(expected, actual);
    }
}
