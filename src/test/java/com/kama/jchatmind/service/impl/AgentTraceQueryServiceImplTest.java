package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.mapper.AgentStepMapper;
import com.kama.jchatmind.mapper.AgentTaskMapper;
import com.kama.jchatmind.mapper.ToolCallLogMapper;
import com.kama.jchatmind.model.entity.AgentStep;
import com.kama.jchatmind.model.entity.AgentTask;
import com.kama.jchatmind.model.entity.ToolCallLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentTraceQueryServiceImplTest {
    @Mock
    private AgentTaskMapper agentTaskMapper;
    @Mock
    private AgentStepMapper agentStepMapper;
    @Mock
    private ToolCallLogMapper toolCallLogMapper;

    private AgentTraceQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AgentTraceQueryServiceImpl(agentTaskMapper, agentStepMapper, toolCallLogMapper);
    }

    @Test
    void getTracesReturnsSessionTasksWithStepsAndToolCalls() {
        LocalDateTime startedAt = LocalDateTime.now();
        AgentTask task = AgentTask.builder()
                .id("task-1")
                .sessionId("session-1")
                .agentId("agent-1")
                .status("SUCCESS")
                .finishReason("NO_TOOL_CALLS")
                .traceId("trace-1")
                .latencyMs(42L)
                .startedAt(startedAt)
                .build();
        AgentStep step = AgentStep.builder()
                .id("step-1")
                .taskId("task-1")
                .stepNo(1)
                .stepType("THINK")
                .status("SUCCESS")
                .latencyMs(21L)
                .build();
        ToolCallLog toolCall = ToolCallLog.builder()
                .id("tool-call-log-1")
                .taskId("task-1")
                .stepId("step-1")
                .toolName("searchProjectCode")
                .actualToolName("searchProjectCode")
                .status("SUCCESS")
                .latencyMs(12L)
                .resultSummary("ok")
                .blockedByPolicy(false)
                .build();

        when(agentTaskMapper.selectRecentBySessionId("session-1", 5)).thenReturn(List.of(task));
        when(agentStepMapper.selectByTaskId("task-1")).thenReturn(List.of(step));
        when(toolCallLogMapper.selectByTaskId("task-1")).thenReturn(List.of(toolCall));

        var response = service.getTraces("session-1", 5);

        assertEquals(1, response.getTraces().size());
        var trace = response.getTraces().get(0);
        assertEquals("task-1", trace.getId());
        assertEquals("session-1", trace.getSessionId());
        assertEquals("trace-1", trace.getTraceId());
        assertEquals(1, trace.getSteps().size());
        assertEquals("THINK", trace.getSteps().get(0).getStepType());
        assertEquals(1, trace.getToolCalls().size());
        assertEquals("searchProjectCode", trace.getToolCalls().get(0).getActualToolName());
        assertEquals("ok", trace.getToolCalls().get(0).getResultSummary());
        verify(agentTaskMapper, never()).selectRecent(5);
    }

    @Test
    void getTracesClampsGlobalLimit() {
        when(agentTaskMapper.selectRecent(100)).thenReturn(List.of());

        var response = service.getTraces("", 200);

        assertTrue(response.getTraces().isEmpty());
        verify(agentTaskMapper).selectRecent(100);
    }
}
