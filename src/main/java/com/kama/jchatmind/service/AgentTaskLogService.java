package com.kama.jchatmind.service;

import com.kama.jchatmind.model.entity.AgentStep;
import com.kama.jchatmind.model.entity.AgentTask;
import com.kama.jchatmind.model.entity.ToolCallLog;

public interface AgentTaskLogService {
    String STATUS_RUNNING = "RUNNING";
    String STATUS_SUCCESS = "SUCCESS";
    String STATUS_FAILED = "FAILED";
    String STATUS_CRASHED = "CRASHED";
    String STATUS_INTERRUPTED = "INTERRUPTED";

    String FINISH_REASON_NO_TOOL_CALLS = "NO_TOOL_CALLS";
    String FINISH_REASON_TERMINATE_TOOL = "TERMINATE_TOOL";
    String FINISH_REASON_MAX_STEPS_REACHED = "MAX_STEPS_REACHED";
    String FINISH_REASON_ERROR = "ERROR";
    String FINISH_REASON_CRASHED = "CRASHED";
    String FINISH_REASON_INTERRUPTED = "INTERRUPTED";
    String STEP_FINISH_REASON_COMPLETED = "COMPLETED";
    String STEP_FINISH_REASON_TOOL_CALLS_REQUESTED = "TOOL_CALLS_REQUESTED";
    String STEP_FINISH_REASON_TOOLS_EXECUTED = "TOOLS_EXECUTED";
    String STEP_FINISH_REASON_TOOL_CORRECTION_REQUESTED = "TOOL_CORRECTION_REQUESTED";

    String ERROR_TYPE_UNKNOWN_TOOL = "UNKNOWN_TOOL";
    String ERROR_TYPE_POLICY_REJECTED = "POLICY_REJECTED";
    String ERROR_TYPE_ARGUMENT_PARSE_ERROR = "ARGUMENT_PARSE_ERROR";
    String ERROR_TYPE_TOOL_TIMEOUT = "TOOL_TIMEOUT";
    String ERROR_TYPE_TOOL_EXCEPTION = "TOOL_EXCEPTION";
    String ERROR_TYPE_RESULT_TOO_LARGE = "RESULT_TOO_LARGE";
    String ERROR_TYPE_PROCESS_CRASHED = "PROCESS_CRASHED";
    String ERROR_TYPE_UNKNOWN_ERROR = "UNKNOWN_ERROR";

    AgentTask startTask(String sessionId, String agentId, String userMessageId, String goal);

    AgentTask startTask(String sessionId, String agentId, String userMessageId, String goal,
                        String modelName, Integer maxSteps, String traceId);

    void finishTask(String taskId);

    void finishTask(String taskId, String finishReason, Integer actualSteps, Integer toolCallCount);

    void failTask(String taskId, String errorMessage);

    void failTask(String taskId, String errorMessage, Integer actualSteps, Integer toolCallCount);

    void failStepAndTask(String stepId, String taskId, String errorMessage,
                         Integer actualSteps, Integer toolCallCount);

    void heartbeatTask(String taskId);

    AgentStep startStep(String taskId, int stepNo, String stepType, String inputSummary);

    AgentStep startStep(String taskId, int stepNo, String stepType, String inputSummary, String modelName);

    void finishStep(String stepId, String outputSummary);

    void finishStep(String stepId, String outputSummary, String finishReason, Long llmLatencyMs);

    void failStep(String stepId, String errorMessage);

    void failStep(String stepId, String errorMessage, String finishReason);

    ToolCallLog startToolCall(String taskId, String stepId, String toolName, String argumentsJson);

    ToolCallLog startToolCall(String taskId, String stepId, String toolName, String actualToolName,
                              String toolCallId, String argumentsJson, boolean argumentTruncated);

    ToolCallLog startAndFailToolCall(String taskId, String stepId, String toolName, String actualToolName,
                                     String toolCallId, String argumentsJson, boolean argumentTruncated,
                                     String errorMessage, long latencyMs, String errorType,
                                     boolean blockedByPolicy);

    void finishToolCall(String toolCallLogId, String resultSummary, long latencyMs);

    void finishToolCall(String toolCallLogId, String resultSummary, long latencyMs, boolean resultTruncated);

    void failToolCall(String toolCallLogId, String errorMessage, long latencyMs);

    void failToolCall(String toolCallLogId, String errorMessage, long latencyMs,
                      String errorType, boolean blockedByPolicy);

    int recoverStaleRunningTasks(int thresholdMinutes);
}
