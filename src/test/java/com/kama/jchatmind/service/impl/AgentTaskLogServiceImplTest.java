package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.AgentStepMapper;
import com.kama.jchatmind.mapper.AgentTaskMapper;
import com.kama.jchatmind.mapper.ToolCallLogMapper;
import com.kama.jchatmind.model.entity.AgentStep;
import com.kama.jchatmind.model.entity.AgentTask;
import com.kama.jchatmind.model.entity.ToolCallLog;
import com.kama.jchatmind.service.AgentTaskLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentTaskLogServiceImplTest {
    @Mock
    private AgentTaskMapper agentTaskMapper;
    @Mock
    private AgentStepMapper agentStepMapper;
    @Mock
    private ToolCallLogMapper toolCallLogMapper;

    @Test
    void startTaskAndFinishTaskWriteEnhancedFields() {
        AgentTaskLogServiceImpl service = service();
        AgentTask task = service.startTask("11111111-1111-1111-1111-111111111111",
                "22222222-2222-2222-2222-222222222222",
                "33333333-3333-3333-3333-333333333333",
                "goal", "deepseek-chat", 20, "trace-1");

        ArgumentCaptor<AgentTask> insertCaptor = ArgumentCaptor.forClass(AgentTask.class);
        verify(agentTaskMapper).insert(insertCaptor.capture());
        assertEquals(AgentTaskLogService.STATUS_RUNNING, task.getStatus());
        assertEquals("deepseek-chat", insertCaptor.getValue().getModelName());
        assertEquals(20, insertCaptor.getValue().getMaxSteps());
        assertEquals("trace-1", insertCaptor.getValue().getTraceId());
        assertNotNull(insertCaptor.getValue().getHeartbeatAt());

        when(agentTaskMapper.selectById("task-1")).thenReturn(AgentTask.builder()
                .id("task-1")
                .startedAt(LocalDateTime.now().minusSeconds(2))
                .build());
        service.finishTask("task-1", AgentTaskLogService.FINISH_REASON_NO_TOOL_CALLS, 2, 0);

        ArgumentCaptor<AgentTask> updateCaptor = ArgumentCaptor.forClass(AgentTask.class);
        verify(agentTaskMapper).updateById(updateCaptor.capture());
        AgentTask update = updateCaptor.getValue();
        assertEquals(AgentTaskLogService.STATUS_SUCCESS, update.getStatus());
        assertEquals(AgentTaskLogService.FINISH_REASON_NO_TOOL_CALLS, update.getFinishReason());
        assertEquals(2, update.getActualSteps());
        assertEquals(0, update.getToolCallCount());
        assertTrue(update.getLatencyMs() >= 0);
    }

    @Test
    void startStepAndFailStepWriteLatencyAndFinishReason() {
        AgentTaskLogServiceImpl service = service();
        service.startStep("task-1", 1, "THINK", "input", "model");

        ArgumentCaptor<AgentStep> insertCaptor = ArgumentCaptor.forClass(AgentStep.class);
        verify(agentStepMapper).insert(insertCaptor.capture());
        assertEquals(AgentTaskLogService.STATUS_RUNNING, insertCaptor.getValue().getStatus());
        assertEquals("model", insertCaptor.getValue().getModelName());

        when(agentStepMapper.selectById("step-1")).thenReturn(AgentStep.builder()
                .id("step-1")
                .taskId("task-1")
                .startedAt(LocalDateTime.now().minusSeconds(1))
                .build());
        service.failStep("step-1", "boom");

        ArgumentCaptor<AgentStep> updateCaptor = ArgumentCaptor.forClass(AgentStep.class);
        verify(agentStepMapper).updateById(updateCaptor.capture());
        AgentStep update = updateCaptor.getValue();
        assertEquals(AgentTaskLogService.STATUS_FAILED, update.getStatus());
        assertEquals(AgentTaskLogService.FINISH_REASON_ERROR, update.getFinishReason());
        assertTrue(update.getLatencyMs() >= 0);
    }

    @Test
    void startToolCallAndFailToolCallWriteErrorTypeAndPolicyFlag() {
        AgentTaskLogServiceImpl service = service();
        when(agentStepMapper.selectById("step-1")).thenReturn(AgentStep.builder()
                .id("step-1")
                .taskId("task-1")
                .build());

        service.startToolCall("task-1", "step-1", "searchProjectCode", "searchProjectCode",
                "call-1", "{\"repoId\":\"r\"}", false);

        ArgumentCaptor<ToolCallLog> insertCaptor = ArgumentCaptor.forClass(ToolCallLog.class);
        verify(toolCallLogMapper).insert(insertCaptor.capture());
        assertEquals(AgentTaskLogService.STATUS_RUNNING, insertCaptor.getValue().getStatus());
        assertEquals("call-1", insertCaptor.getValue().getToolCallId());
        assertEquals(false, insertCaptor.getValue().getArgumentTruncated());

        service.failToolCall("tool-log-1", "denied", 3,
                AgentTaskLogService.ERROR_TYPE_POLICY_REJECTED, true);

        ArgumentCaptor<ToolCallLog> updateCaptor = ArgumentCaptor.forClass(ToolCallLog.class);
        verify(toolCallLogMapper).updateById(updateCaptor.capture());
        ToolCallLog update = updateCaptor.getValue();
        assertEquals(AgentTaskLogService.STATUS_FAILED, update.getStatus());
        assertEquals(AgentTaskLogService.ERROR_TYPE_POLICY_REJECTED, update.getErrorType());
        assertEquals(true, update.getBlockedByPolicy());
        assertEquals(3, update.getLatencyMs());
    }

    @Test
    void startAndFailToolCallWritesOneFailedPreflightTransition() {
        AgentTaskLogServiceImpl service = service();
        when(agentStepMapper.selectById("step-1")).thenReturn(AgentStep.builder()
                .id("step-1")
                .taskId("task-1")
                .build());

        service.startAndFailToolCall("task-1", "step-1", "databaseQuery", "databaseQuery",
                "call-1", "{\"sql\":\"drop table t\"}", false,
                "Tool is not allowed", 0,
                AgentTaskLogService.ERROR_TYPE_POLICY_REJECTED, true);

        ArgumentCaptor<ToolCallLog> insertCaptor = ArgumentCaptor.forClass(ToolCallLog.class);
        ArgumentCaptor<ToolCallLog> updateCaptor = ArgumentCaptor.forClass(ToolCallLog.class);
        verify(toolCallLogMapper).insert(insertCaptor.capture());
        verify(toolCallLogMapper).updateById(updateCaptor.capture());
        assertEquals(AgentTaskLogService.STATUS_RUNNING, insertCaptor.getValue().getStatus());
        assertEquals(AgentTaskLogService.STATUS_FAILED, updateCaptor.getValue().getStatus());
        assertEquals(AgentTaskLogService.ERROR_TYPE_POLICY_REJECTED, updateCaptor.getValue().getErrorType());
        assertEquals(true, updateCaptor.getValue().getBlockedByPolicy());
    }

    @Test
    void failStepAndTaskWritesBothFailuresInOneServiceCall() {
        AgentTaskLogServiceImpl service = service();
        when(agentStepMapper.selectById("step-1")).thenReturn(AgentStep.builder()
                .id("step-1")
                .taskId("task-1")
                .startedAt(LocalDateTime.now().minusSeconds(3))
                .build());
        when(agentTaskMapper.selectById("task-1")).thenReturn(AgentTask.builder()
                .id("task-1")
                .startedAt(LocalDateTime.now().minusSeconds(5))
                .build());

        service.failStepAndTask("step-1", "task-1", "boom", 4, 2);

        ArgumentCaptor<AgentStep> stepUpdate = ArgumentCaptor.forClass(AgentStep.class);
        ArgumentCaptor<AgentTask> taskUpdate = ArgumentCaptor.forClass(AgentTask.class);
        verify(agentStepMapper).updateById(stepUpdate.capture());
        verify(agentTaskMapper).updateById(taskUpdate.capture());
        assertEquals(AgentTaskLogService.STATUS_FAILED, stepUpdate.getValue().getStatus());
        assertEquals(AgentTaskLogService.STATUS_FAILED, taskUpdate.getValue().getStatus());
        assertEquals(AgentTaskLogService.FINISH_REASON_ERROR, stepUpdate.getValue().getFinishReason());
        assertEquals(AgentTaskLogService.FINISH_REASON_ERROR, taskUpdate.getValue().getFinishReason());
        assertEquals(4, taskUpdate.getValue().getActualSteps());
        assertEquals(2, taskUpdate.getValue().getToolCallCount());
    }

    @Test
    void startToolCallRejectsStepFromDifferentTask() {
        AgentTaskLogServiceImpl service = service();
        when(agentStepMapper.selectById("step-1")).thenReturn(AgentStep.builder()
                .id("step-1")
                .taskId("other-task")
                .build());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.startToolCall("task-1", "step-1", "searchProjectCode", "{}"));

        assertEquals("tool_call_log step_id does not belong to task_id", error.getMessage());
    }

    @Test
    void finishToolCallWritesLatencyFinishedAtAndTruncationFlag() {
        AgentTaskLogServiceImpl service = service();

        service.finishToolCall("tool-log-1", "ok", 15, true);

        ArgumentCaptor<ToolCallLog> updateCaptor = ArgumentCaptor.forClass(ToolCallLog.class);
        verify(toolCallLogMapper).updateById(updateCaptor.capture());
        ToolCallLog update = updateCaptor.getValue();
        assertEquals(AgentTaskLogService.STATUS_SUCCESS, update.getStatus());
        assertEquals(15, update.getLatencyMs());
        assertEquals(true, update.getResultTruncated());
        assertNotNull(update.getFinishedAt());
        assertNotNull(update.getUpdatedAt());
    }

    @Test
    void recoverStaleRunningTasksMarksTaskStepAndToolAsCrashed() {
        AgentTaskLogServiceImpl service = service();
        AgentTask staleTask = AgentTask.builder()
                .id("task-1")
                .startedAt(LocalDateTime.now().minusMinutes(30))
                .build();
        when(agentTaskMapper.selectStaleRunningBefore(any())).thenReturn(List.of(staleTask));
        when(agentStepMapper.selectRunningByTaskId("task-1")).thenReturn(List.of(AgentStep.builder()
                .id("step-1")
                .startedAt(LocalDateTime.now().minusMinutes(20))
                .build()));
        when(toolCallLogMapper.selectRunningByTaskId("task-1")).thenReturn(List.of(ToolCallLog.builder()
                .id("tool-1")
                .startedAt(LocalDateTime.now().minusMinutes(10))
                .build()));

        int recovered = service.recoverStaleRunningTasks(10);

        assertEquals(1, recovered);
        ArgumentCaptor<AgentTask> taskUpdate = ArgumentCaptor.forClass(AgentTask.class);
        ArgumentCaptor<AgentStep> stepUpdate = ArgumentCaptor.forClass(AgentStep.class);
        ArgumentCaptor<ToolCallLog> toolUpdate = ArgumentCaptor.forClass(ToolCallLog.class);
        verify(agentTaskMapper).updateById(taskUpdate.capture());
        verify(agentStepMapper).updateById(stepUpdate.capture());
        verify(toolCallLogMapper).updateById(toolUpdate.capture());
        assertEquals(AgentTaskLogService.STATUS_CRASHED, taskUpdate.getValue().getStatus());
        assertEquals(AgentTaskLogService.STATUS_CRASHED, stepUpdate.getValue().getStatus());
        assertEquals(AgentTaskLogService.STATUS_CRASHED, toolUpdate.getValue().getStatus());
        assertEquals(AgentTaskLogService.ERROR_TYPE_PROCESS_CRASHED, toolUpdate.getValue().getErrorType());
    }

    private AgentTaskLogServiceImpl service() {
        return new AgentTaskLogServiceImpl(agentTaskMapper, agentStepMapper, toolCallLogMapper, new ObjectMapper());
    }
}
