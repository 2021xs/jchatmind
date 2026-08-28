package com.kama.jchatmind.benchmark.context;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EstimatedMessageTokenMeasurerTest {

    @Test
    void measuresMessageTextAdditionalSystemAndToolProtocolFields() {
        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "search", "{q:x}")))
                .build();
        ToolResponseMessage response = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "search", "result")))
                .build();
        EstimatedMessageTokenMeasurer measurer = new EstimatedMessageTokenMeasurer(3);

        EstimatedMessageTokenMeasurer.Measurement measurement = measurer.measure(
                List.of(new UserMessage("question"), assistant, response), "planner");

        long expectedChars = "question".length() + "planner".length()
                + "call-1".length() + "function".length() + "search".length() + "{q:x}".length()
                + "call-1".length() + "search".length() + "result".length();
        assertEquals(expectedChars, measurement.measuredChars());
        assertEquals((expectedChars + 2) / 3, measurement.tokens());
        assertEquals(EstimatedMessageTokenMeasurer.SOURCE, measurement.source());
    }

    @Test
    void estimatorIsMonotonicForContextComparison() {
        EstimatedMessageTokenMeasurer measurer = new EstimatedMessageTokenMeasurer(3);

        int shortContext = measurer.measure(List.of(new UserMessage("short")), null).tokens();
        int longContext = measurer.measure(List.of(new UserMessage("short plus additional evidence")), null).tokens();

        assertTrue(longContext > shortContext);
    }
}
