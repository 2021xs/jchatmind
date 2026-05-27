package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.AgentStepMapper;
import com.kama.jchatmind.mapper.AgentTaskMapper;
import com.kama.jchatmind.mapper.ToolCallLogMapper;
import com.kama.jchatmind.model.entity.AgentStep;
import com.kama.jchatmind.model.entity.AgentTask;
import com.kama.jchatmind.model.entity.ToolCallLog;
import com.kama.jchatmind.service.AgentTaskLogService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
@AllArgsConstructor
public class AgentTaskLogServiceImpl implements AgentTaskLogService {
    private static final int MAX_TEXT_LENGTH = 4000;

    private final AgentTaskMapper agentTaskMapper;
    private final AgentStepMapper agentStepMapper;
    private final ToolCallLogMapper toolCallLogMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AgentTask startTask(String sessionId, String agentId, String userMessageId, String goal) {
        return startTask(sessionId, agentId, userMessageId, goal, null, null, null);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AgentTask startTask(String sessionId, String agentId, String userMessageId, String goal,
                               String modelName, Integer maxSteps, String traceId) {
        LocalDateTime now = LocalDateTime.now();
        AgentTask task = AgentTask.builder()
                .sessionId(sessionId)
                .agentId(agentId)
                .userMessageId(userMessageId)
                .status(STATUS_RUNNING)
                .goal(truncate(goal))
                .modelName(modelName)
                .maxSteps(maxSteps)
                .actualSteps(0)
                .toolCallCount(0)
                .traceId(traceId)
                .heartbeatAt(now)
                .startedAt(now)
                .updatedAt(now)
                .build();
        agentTaskMapper.insert(task);
        return task;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishTask(String taskId) {
        finishTask(taskId, null, null, null);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishTask(String taskId, String finishReason, Integer actualSteps, Integer toolCallCount) {
        LocalDateTime now = LocalDateTime.now();
        AgentTask existing = agentTaskMapper.selectById(taskId);
        agentTaskMapper.updateById(AgentTask.builder()
                .id(taskId)
                .status(STATUS_SUCCESS)
                .finishReason(finishReason)
                .actualSteps(actualSteps)
                .toolCallCount(toolCallCount)
                .latencyMs(latencyMs(existing == null ? null : existing.getStartedAt(), now))
                .heartbeatAt(now)
                .finishedAt(now)
                .updatedAt(now)
                .build());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failTask(String taskId, String errorMessage) {
        failTask(taskId, errorMessage, null, null);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failTask(String taskId, String errorMessage, Integer actualSteps, Integer toolCallCount) {
        LocalDateTime now = LocalDateTime.now();
        failTask(taskId, errorMessage, actualSteps, toolCallCount, now);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failStepAndTask(String stepId, String taskId, String errorMessage,
                                Integer actualSteps, Integer toolCallCount) {
        LocalDateTime now = LocalDateTime.now();
        if (stepId != null) {
            failStep(stepId, errorMessage, FINISH_REASON_ERROR, now, false);
        }
        failTask(taskId, errorMessage, actualSteps, toolCallCount, now);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void heartbeatTask(String taskId) {
        LocalDateTime now = LocalDateTime.now();
        agentTaskMapper.updateById(AgentTask.builder()
                .id(taskId)
                .heartbeatAt(now)
                .updatedAt(now)
                .build());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AgentStep startStep(String taskId, int stepNo, String stepType, String inputSummary) {
        return startStep(taskId, stepNo, stepType, inputSummary, null);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AgentStep startStep(String taskId, int stepNo, String stepType, String inputSummary, String modelName) {
        LocalDateTime now = LocalDateTime.now();
        AgentStep step = AgentStep.builder()
                .taskId(taskId)
                .stepNo(stepNo)
                .stepType(stepType)
                .status(STATUS_RUNNING)
                .inputSummary(truncate(inputSummary))
                .modelName(modelName)
                .startedAt(now)
                .updatedAt(now)
                .build();
        agentStepMapper.insert(step);
        heartbeatTask(taskId);
        return step;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishStep(String stepId, String outputSummary) {
        finishStep(stepId, outputSummary, null, null);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishStep(String stepId, String outputSummary, String finishReason, Long llmLatencyMs) {
        LocalDateTime now = LocalDateTime.now();
        AgentStep existing = agentStepMapper.selectById(stepId);
        agentStepMapper.updateById(AgentStep.builder()
                .id(stepId)
                .status(STATUS_SUCCESS)
                .outputSummary(truncate(outputSummary))
                .latencyMs(latencyMs(existing == null ? null : existing.getStartedAt(), now))
                .finishReason(finishReason)
                .llmLatencyMs(llmLatencyMs)
                .finishedAt(now)
                .updatedAt(now)
                .build());
        if (existing != null) {
            heartbeatTask(existing.getTaskId());
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failStep(String stepId, String errorMessage) {
        failStep(stepId, errorMessage, FINISH_REASON_ERROR);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failStep(String stepId, String errorMessage, String finishReason) {
        LocalDateTime now = LocalDateTime.now();
        failStep(stepId, errorMessage, finishReason, now, true);
    }

    private void failStep(String stepId, String errorMessage, String finishReason,
                          LocalDateTime now, boolean heartbeatTask) {
        AgentStep existing = agentStepMapper.selectById(stepId);
        agentStepMapper.updateById(AgentStep.builder()
                .id(stepId)
                .status(STATUS_FAILED)
                .latencyMs(latencyMs(existing == null ? null : existing.getStartedAt(), now))
                .finishReason(finishReason)
                .finishedAt(now)
                .updatedAt(now)
                .errorMessage(truncate(errorMessage))
                .build());
        if (heartbeatTask && existing != null) {
            heartbeatTask(existing.getTaskId());
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ToolCallLog startToolCall(String taskId, String stepId, String toolName, String argumentsJson) {
        return startToolCall(taskId, stepId, toolName, toolName, null, argumentsJson,
                argumentsJson != null && argumentsJson.length() > MAX_TEXT_LENGTH);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ToolCallLog startToolCall(String taskId, String stepId, String toolName, String actualToolName,
                                     String toolCallId, String argumentsJson, boolean argumentTruncated) {
        validateStepBelongsToTask(taskId, stepId);
        LocalDateTime now = LocalDateTime.now();
        ToolCallLog log = newRunningToolCall(taskId, stepId, toolName, actualToolName,
                toolCallId, argumentsJson, argumentTruncated, now);
        toolCallLogMapper.insert(log);
        heartbeatTask(taskId);
        return log;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ToolCallLog startAndFailToolCall(String taskId, String stepId, String toolName, String actualToolName,
                                            String toolCallId, String argumentsJson, boolean argumentTruncated,
                                            String errorMessage, long latencyMs, String errorType,
                                            boolean blockedByPolicy) {
        validateStepBelongsToTask(taskId, stepId);
        LocalDateTime now = LocalDateTime.now();
        ToolCallLog log = newRunningToolCall(taskId, stepId, toolName, actualToolName,
                toolCallId, argumentsJson, argumentTruncated, now);
        toolCallLogMapper.insert(log);
        toolCallLogMapper.updateById(ToolCallLog.builder()
                .id(log.getId())
                .status(STATUS_FAILED)
                .errorMessage(truncate(errorMessage))
                .latencyMs(latencyMs)
                .errorType(errorType)
                .blockedByPolicy(blockedByPolicy)
                .finishedAt(now)
                .updatedAt(now)
                .build());
        heartbeatTask(taskId);
        return log;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishToolCall(String toolCallLogId, String resultSummary, long latencyMs) {
        finishToolCall(toolCallLogId, resultSummary, latencyMs,
                resultSummary != null && resultSummary.length() > MAX_TEXT_LENGTH);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishToolCall(String toolCallLogId, String resultSummary, long latencyMs, boolean resultTruncated) {
        LocalDateTime now = LocalDateTime.now();
        toolCallLogMapper.updateById(ToolCallLog.builder()
                .id(toolCallLogId)
                .status(STATUS_SUCCESS)
                .resultSummary(truncate(resultSummary))
                .latencyMs(latencyMs)
                .resultTruncated(resultTruncated)
                .finishedAt(now)
                .updatedAt(now)
                .build());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failToolCall(String toolCallLogId, String errorMessage, long latencyMs) {
        failToolCall(toolCallLogId, errorMessage, latencyMs, ERROR_TYPE_TOOL_EXCEPTION, false);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failToolCall(String toolCallLogId, String errorMessage, long latencyMs,
                             String errorType, boolean blockedByPolicy) {
        LocalDateTime now = LocalDateTime.now();
        toolCallLogMapper.updateById(ToolCallLog.builder()
                .id(toolCallLogId)
                .status(STATUS_FAILED)
                .errorMessage(truncate(errorMessage))
                .latencyMs(latencyMs)
                .errorType(errorType)
                .blockedByPolicy(blockedByPolicy)
                .finishedAt(now)
                .updatedAt(now)
                .build());
    }

    private void failTask(String taskId, String errorMessage, Integer actualSteps,
                          Integer toolCallCount, LocalDateTime now) {
        AgentTask existing = agentTaskMapper.selectById(taskId);
        agentTaskMapper.updateById(AgentTask.builder()
                .id(taskId)
                .status(STATUS_FAILED)
                .finishReason(FINISH_REASON_ERROR)
                .actualSteps(actualSteps)
                .toolCallCount(toolCallCount)
                .latencyMs(latencyMs(existing == null ? null : existing.getStartedAt(), now))
                .heartbeatAt(now)
                .finishedAt(now)
                .updatedAt(now)
                .errorMessage(truncate(errorMessage))
                .build());
    }

    private ToolCallLog newRunningToolCall(String taskId, String stepId, String toolName, String actualToolName,
                                           String toolCallId, String argumentsJson, boolean argumentTruncated,
                                           LocalDateTime now) {
        return ToolCallLog.builder()
                .taskId(taskId)
                .stepId(stepId)
                .toolName(toolName)
                .actualToolName(actualToolName)
                .toolCallId(toolCallId)
                .argumentsJson(normalizeJson(argumentsJson))
                .status(STATUS_RUNNING)
                .blockedByPolicy(false)
                .argumentTruncated(argumentTruncated)
                .resultTruncated(false)
                .retryCount(0)
                .startedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recoverStaleRunningTasks(int thresholdMinutes) {
        LocalDateTime staleBefore = LocalDateTime.now().minusMinutes(Math.max(1, thresholdMinutes));
        List<AgentTask> tasks = agentTaskMapper.selectStaleRunningBefore(staleBefore);
        for (AgentTask task : tasks) {
            recoverTask(task);
        }
        return tasks.size();
    }

    private void recoverTask(AgentTask task) {
        LocalDateTime now = LocalDateTime.now();
        String message = "Recovered stale RUNNING record after application restart; previous process may have crashed or been killed.";
        List<AgentStep> steps = agentStepMapper.selectRunningByTaskId(task.getId());
        for (AgentStep step : steps) {
            agentStepMapper.updateById(AgentStep.builder()
                    .id(step.getId())
                    .status(STATUS_CRASHED)
                    .finishReason(FINISH_REASON_CRASHED)
                    .latencyMs(latencyMs(step.getStartedAt(), now))
                    .finishedAt(now)
                    .updatedAt(now)
                    .errorMessage(message)
                    .build());
        }
        List<ToolCallLog> toolLogs = toolCallLogMapper.selectRunningByTaskId(task.getId());
        for (ToolCallLog toolLog : toolLogs) {
            toolCallLogMapper.updateById(ToolCallLog.builder()
                    .id(toolLog.getId())
                    .status(STATUS_CRASHED)
                    .latencyMs(latencyMs(toolLog.getStartedAt(), now))
                    .errorMessage(message)
                    .errorType(ERROR_TYPE_PROCESS_CRASHED)
                    .finishedAt(now)
                    .updatedAt(now)
                    .build());
        }
        agentTaskMapper.updateById(AgentTask.builder()
                .id(task.getId())
                .status(STATUS_CRASHED)
                .finishReason(FINISH_REASON_CRASHED)
                .latencyMs(latencyMs(task.getStartedAt(), now))
                .heartbeatAt(now)
                .finishedAt(now)
                .updatedAt(now)
                .errorMessage(message)
                .build());
    }

    private String normalizeJson(String value) {
        if (value == null) {
            return value;
        }
        if (value.length() <= MAX_TEXT_LENGTH && isJson(value)) {
            return value;
        }
        return "{\"truncated\":" + (value.length() > MAX_TEXT_LENGTH)
                + ",\"preview\":\"" + escapeJson(truncate(value)) + "\"}";
    }

    private Long latencyMs(LocalDateTime start, LocalDateTime finish) {
        if (start == null || finish == null) {
            return null;
        }
        return Math.max(0, Duration.between(start, finish).toMillis());
    }

    private void validateStepBelongsToTask(String taskId, String stepId) {
        if (taskId == null || stepId == null) {
            return;
        }
        AgentStep step = agentStepMapper.selectById(stepId);
        if (step != null && step.getTaskId() != null && !taskId.equals(step.getTaskId())) {
            throw new IllegalStateException("tool_call_log step_id does not belong to task_id");
        }
    }

    private boolean isJson(String value) {
        try {
            objectMapper.readTree(value);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MAX_TEXT_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_TEXT_LENGTH - 32) + "\n...[truncated]";
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
