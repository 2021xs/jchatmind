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

import java.time.LocalDateTime;

@Service
@AllArgsConstructor
public class AgentTaskLogServiceImpl implements AgentTaskLogService {
    private static final int MAX_TEXT_LENGTH = 4000;

    private final AgentTaskMapper agentTaskMapper;
    private final AgentStepMapper agentStepMapper;
    private final ToolCallLogMapper toolCallLogMapper;
    private final ObjectMapper objectMapper;

    @Override
    public AgentTask startTask(String sessionId, String agentId, String userMessageId, String goal) {
        LocalDateTime now = LocalDateTime.now();
        AgentTask task = AgentTask.builder()
                .sessionId(sessionId)
                .agentId(agentId)
                .userMessageId(userMessageId)
                .status(STATUS_RUNNING)
                .goal(truncate(goal))
                .startedAt(now)
                .build();
        agentTaskMapper.insert(task);
        return task;
    }

    @Override
    public void finishTask(String taskId) {
        agentTaskMapper.updateById(AgentTask.builder()
                .id(taskId)
                .status(STATUS_SUCCESS)
                .finishedAt(LocalDateTime.now())
                .build());
    }

    @Override
    public void failTask(String taskId, String errorMessage) {
        agentTaskMapper.updateById(AgentTask.builder()
                .id(taskId)
                .status(STATUS_FAILED)
                .finishedAt(LocalDateTime.now())
                .errorMessage(truncate(errorMessage))
                .build());
    }

    @Override
    public AgentStep startStep(String taskId, int stepNo, String stepType, String inputSummary) {
        AgentStep step = AgentStep.builder()
                .taskId(taskId)
                .stepNo(stepNo)
                .stepType(stepType)
                .status(STATUS_RUNNING)
                .inputSummary(truncate(inputSummary))
                .startedAt(LocalDateTime.now())
                .build();
        agentStepMapper.insert(step);
        return step;
    }

    @Override
    public void finishStep(String stepId, String outputSummary) {
        agentStepMapper.updateById(AgentStep.builder()
                .id(stepId)
                .status(STATUS_SUCCESS)
                .outputSummary(truncate(outputSummary))
                .finishedAt(LocalDateTime.now())
                .build());
    }

    @Override
    public void failStep(String stepId, String errorMessage) {
        agentStepMapper.updateById(AgentStep.builder()
                .id(stepId)
                .status(STATUS_FAILED)
                .finishedAt(LocalDateTime.now())
                .errorMessage(truncate(errorMessage))
                .build());
    }

    @Override
    public ToolCallLog startToolCall(String taskId, String stepId, String toolName, String argumentsJson) {
        ToolCallLog log = ToolCallLog.builder()
                .taskId(taskId)
                .stepId(stepId)
                .toolName(toolName)
                .argumentsJson(normalizeJson(argumentsJson))
                .status(STATUS_RUNNING)
                .createdAt(LocalDateTime.now())
                .build();
        toolCallLogMapper.insert(log);
        return log;
    }

    @Override
    public void finishToolCall(String toolCallLogId, String resultSummary, long latencyMs) {
        toolCallLogMapper.updateById(ToolCallLog.builder()
                .id(toolCallLogId)
                .status(STATUS_SUCCESS)
                .resultSummary(truncate(resultSummary))
                .latencyMs(latencyMs)
                .build());
    }

    @Override
    public void failToolCall(String toolCallLogId, String errorMessage, long latencyMs) {
        toolCallLogMapper.updateById(ToolCallLog.builder()
                .id(toolCallLogId)
                .status(STATUS_FAILED)
                .errorMessage(truncate(errorMessage))
                .latencyMs(latencyMs)
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
