package com.kama.jchatmind.agent;

import com.kama.jchatmind.mcp.McpToolCallException;
import com.kama.jchatmind.message.AgentSseEvent;
import com.kama.jchatmind.model.entity.AgentStep;
import com.kama.jchatmind.service.AgentTaskLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(OutputCaptureExtension.class)
class AgentRunFailureHandlerTest {

    @Test
    void marksCurrentStepAndTaskFailedThenPublishesAndCompletesSse() {
        AgentTaskLogService taskLogService = mock(AgentTaskLogService.class);
        AgentEventPublisher eventPublisher = mock(AgentEventPublisher.class);
        AgentRunFailureHandler handler = new AgentRunFailureHandler(taskLogService, eventPublisher);
        AgentStep step = AgentStep.builder().id("step-1").stepNo(2).stepType("THINK").build();

        handler.handle("task-1", "session-1", step, 2, 1, new IllegalStateException("boom"));

        verify(taskLogService).failStepAndTask("step-1", "task-1", "boom", 2, 1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(eventPublisher).publish(eq("task-1"), eq("session-1"), eq(AgentSseEvent.Type.ERROR), payload.capture());
        assertEquals(AgentTaskLogService.STATUS_FAILED, payload.getValue().get("status"));
        assertEquals("step-1", payload.getValue().get("stepId"));
        verify(eventPublisher).complete("session-1", "task-1");
    }

    @Test
    void typedMcpFailureLogsMetadataWithoutExternalCause(CapturedOutput output) {
        String sentinel = "EXCEPTION_SECRET_SENTINEL_56789";
        AgentTaskLogService taskLogService = mock(AgentTaskLogService.class);
        AgentEventPublisher eventPublisher = mock(AgentEventPublisher.class);
        AgentRunFailureHandler handler = new AgentRunFailureHandler(taskLogService, eventPublisher);
        AgentStep step = AgentStep.builder().id("step-mcp").stepNo(3).stepType("TOOL").build();
        McpToolCallException failure = new McpToolCallException("mcp_docs_search_docs",
                new IllegalStateException(sentinel, new IllegalArgumentException("cause-" + sentinel)));

        handler.handle("task-mcp", "session-mcp", step, 3, 1, failure);

        verify(taskLogService).failStepAndTask("step-mcp", "task-mcp", failure.getMessage(), 3, 1);
        verify(eventPublisher).complete("session-mcp", "task-mcp");
        assertFalse(output.getAll().contains(sentinel));
        assertTrue(output.getAll().contains("failureClassification=MCP_TOOL_CALL_FAILED"));
        assertTrue(output.getAll().contains("exceptionClass=" + McpToolCallException.class.getName()));
    }
}
