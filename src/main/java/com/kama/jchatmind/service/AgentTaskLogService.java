package com.kama.jchatmind.service;

import com.kama.jchatmind.model.entity.AgentStep;
import com.kama.jchatmind.model.entity.AgentTask;
import com.kama.jchatmind.model.entity.ToolCallLog;

public interface AgentTaskLogService {
    String STATUS_RUNNING = "RUNNING";
    String STATUS_SUCCESS = "SUCCESS";
    String STATUS_FAILED = "FAILED";

    AgentTask startTask(String sessionId, String agentId, String userMessageId, String goal);

    void finishTask(String taskId);

    void failTask(String taskId, String errorMessage);

    AgentStep startStep(String taskId, int stepNo, String stepType, String inputSummary);

    void finishStep(String stepId, String outputSummary);

    void failStep(String stepId, String errorMessage);

    ToolCallLog startToolCall(String taskId, String stepId, String toolName, String argumentsJson);

    void finishToolCall(String toolCallLogId, String resultSummary, long latencyMs);

    void failToolCall(String toolCallLogId, String errorMessage, long latencyMs);
}
