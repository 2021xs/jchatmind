package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.mapper.AgentStepMapper;
import com.kama.jchatmind.mapper.AgentTaskMapper;
import com.kama.jchatmind.mapper.ToolCallLogMapper;
import com.kama.jchatmind.model.entity.AgentStep;
import com.kama.jchatmind.model.entity.AgentTask;
import com.kama.jchatmind.model.entity.ToolCallLog;
import com.kama.jchatmind.model.response.GetAgentTracesResponse;
import com.kama.jchatmind.model.vo.AgentStepTraceVO;
import com.kama.jchatmind.model.vo.AgentTaskTraceVO;
import com.kama.jchatmind.model.vo.ToolCallTraceVO;
import com.kama.jchatmind.service.AgentTraceQueryService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@AllArgsConstructor
public class AgentTraceQueryServiceImpl implements AgentTraceQueryService {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final AgentTaskMapper agentTaskMapper;
    private final AgentStepMapper agentStepMapper;
    private final ToolCallLogMapper toolCallLogMapper;

    @Override
    public GetAgentTracesResponse getTraces(String sessionId, int limit) {
        int effectiveLimit = Math.max(1, Math.min(limit <= 0 ? DEFAULT_LIMIT : limit, MAX_LIMIT));
        List<AgentTask> tasks = StringUtils.hasText(sessionId)
                ? agentTaskMapper.selectRecentBySessionId(sessionId, effectiveLimit)
                : agentTaskMapper.selectRecent(effectiveLimit);
        List<AgentTaskTraceVO> traces = tasks.stream()
                .map(this::toTrace)
                .toList();
        return GetAgentTracesResponse.builder()
                .traces(traces)
                .build();
    }

    private AgentTaskTraceVO toTrace(AgentTask task) {
        List<AgentStepTraceVO> steps = agentStepMapper.selectByTaskId(task.getId()).stream()
                .map(this::toStep)
                .toList();
        List<ToolCallTraceVO> toolCalls = toolCallLogMapper.selectByTaskId(task.getId()).stream()
                .map(this::toToolCall)
                .toList();
        return AgentTaskTraceVO.builder()
                .id(task.getId())
                .sessionId(task.getSessionId())
                .agentId(task.getAgentId())
                .userMessageId(task.getUserMessageId())
                .status(task.getStatus())
                .goal(task.getGoal())
                .finishReason(task.getFinishReason())
                .modelName(task.getModelName())
                .maxSteps(task.getMaxSteps())
                .actualSteps(task.getActualSteps())
                .toolCallCount(task.getToolCallCount())
                .latencyMs(task.getLatencyMs())
                .traceId(task.getTraceId())
                .startedAt(task.getStartedAt())
                .finishedAt(task.getFinishedAt())
                .errorMessage(task.getErrorMessage())
                .steps(steps)
                .toolCalls(toolCalls)
                .build();
    }

    private AgentStepTraceVO toStep(AgentStep step) {
        return AgentStepTraceVO.builder()
                .id(step.getId())
                .taskId(step.getTaskId())
                .stepNo(step.getStepNo())
                .stepType(step.getStepType())
                .status(step.getStatus())
                .inputSummary(step.getInputSummary())
                .outputSummary(step.getOutputSummary())
                .latencyMs(step.getLatencyMs())
                .modelName(step.getModelName())
                .llmLatencyMs(step.getLlmLatencyMs())
                .inputTokens(step.getInputTokens())
                .outputTokens(step.getOutputTokens())
                .finishReason(step.getFinishReason())
                .startedAt(step.getStartedAt())
                .finishedAt(step.getFinishedAt())
                .errorMessage(step.getErrorMessage())
                .build();
    }

    private ToolCallTraceVO toToolCall(ToolCallLog toolCall) {
        return ToolCallTraceVO.builder()
                .id(toolCall.getId())
                .taskId(toolCall.getTaskId())
                .stepId(toolCall.getStepId())
                .toolName(toolCall.getToolName())
                .actualToolName(toolCall.getActualToolName())
                .toolCallId(toolCall.getToolCallId())
                .resultSummary(toolCall.getResultSummary())
                .status(toolCall.getStatus())
                .latencyMs(toolCall.getLatencyMs())
                .errorMessage(toolCall.getErrorMessage())
                .errorType(toolCall.getErrorType())
                .blockedByPolicy(toolCall.getBlockedByPolicy())
                .argumentTruncated(toolCall.getArgumentTruncated())
                .resultTruncated(toolCall.getResultTruncated())
                .startedAt(toolCall.getStartedAt())
                .finishedAt(toolCall.getFinishedAt())
                .build();
    }
}
