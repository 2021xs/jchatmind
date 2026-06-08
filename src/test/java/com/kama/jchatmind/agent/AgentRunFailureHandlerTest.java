package com.kama.jchatmind.agent;

import com.kama.jchatmind.message.AgentSseEvent;
import com.kama.jchatmind.model.entity.AgentStep;
import com.kama.jchatmind.service.AgentTaskLogService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
}
