package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.message.AgentSseEvent;
import com.kama.jchatmind.model.entity.ToolCallLog;
import com.kama.jchatmind.service.AgentTaskLogService;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.ToolExecutionService;
import com.kama.jchatmind.tool.ToolDefinition;
import com.kama.jchatmind.tool.ToolExecutionContext;
import com.kama.jchatmind.tool.ToolExecutionRecord;
import com.kama.jchatmind.tool.ToolFailureClassifier;
import com.kama.jchatmind.tool.ToolFailureDecision;
import com.kama.jchatmind.tool.ToolRegistry;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@AllArgsConstructor
public class ToolExecutionServiceImpl implements ToolExecutionService {
    private static final int MAX_ARGUMENT_PREVIEW = 4000;

    private final ToolRegistry toolRegistry;
    private final AgentTaskLogService agentTaskLogService;
    private final SseService sseService;
    private final ToolFailureClassifier toolFailureClassifier;

    @Override
    public ToolExecutionRecord beforeToolCall(ToolExecutionContext context, AssistantMessage.ToolCall toolCall) {
        String toolCallId = resolveToolCallId(toolCall);
        boolean argumentTruncated = toolCall.arguments() != null && toolCall.arguments().length() > MAX_ARGUMENT_PREVIEW;
        ToolDefinition definition = toolRegistry.find(toolCall.name()).orElse(null);
        if (definition == null) {
            ToolCallLog failedLog = agentTaskLogService.startAndFailToolCall(
                    context.getTaskId(),
                    context.getStepId(),
                    toolCall.name(),
                    toolCall.name(),
                    toolCallId,
                    toolCall.arguments(),
                    argumentTruncated,
                    "Unknown tool: " + toolCall.name(),
                    0,
                    AgentTaskLogService.ERROR_TYPE_UNKNOWN_TOOL,
                    false
            );
            sendEvent(context, AgentSseEvent.Type.TOOL_CALL_RESULT, payload(
                    "taskId", context.getTaskId(),
                    "stepId", context.getStepId(),
                    "toolCallLogId", failedLog.getId(),
                    "toolCallId", toolCallId,
                    "toolName", toolCall.name(),
                    "actualToolName", toolCall.name(),
                    "status", AgentTaskLogService.STATUS_FAILED,
                    "errorType", AgentTaskLogService.ERROR_TYPE_UNKNOWN_TOOL,
                    "errorMessage", "Unknown tool: " + toolCall.name(),
                    "latencyMs", 0
            ));
            throw new IllegalStateException("Unknown tool: " + toolCall.name());
        }
        if (!toolRegistry.isAllowedForRuntime(toolCall.name(), context.getRuntimeToolNames())) {
            ToolCallLog blockedLog = agentTaskLogService.startAndFailToolCall(
                    context.getTaskId(),
                    context.getStepId(),
                    definition.getToolName(),
                    toolCall.name(),
                    toolCallId,
                    toolCall.arguments(),
                    argumentTruncated,
                    "Tool is not allowed in current agent runtime: " + toolCall.name(),
                    0,
                    AgentTaskLogService.ERROR_TYPE_POLICY_REJECTED,
                    true
            );
            sendEvent(context, AgentSseEvent.Type.TOOL_CALL_RESULT, payload(
                    "taskId", context.getTaskId(),
                    "stepId", context.getStepId(),
                    "toolCallLogId", blockedLog.getId(),
                    "toolCallId", toolCallId,
                    "toolName", definition.getToolName(),
                    "actualToolName", toolCall.name(),
                    "status", AgentTaskLogService.STATUS_FAILED,
                    "errorType", AgentTaskLogService.ERROR_TYPE_POLICY_REJECTED,
                    "blockedByPolicy", true,
                    "errorMessage", "Tool is not allowed in current agent runtime: " + toolCall.name(),
                    "latencyMs", 0
            ));
            throw new IllegalStateException("Tool is not allowed in current agent runtime: " + toolCall.name());
        }

        ToolCallLog toolCallLog = agentTaskLogService.startToolCall(
                context.getTaskId(),
                context.getStepId(),
                definition.getToolName(),
                toolCall.name(),
                toolCallId,
                toolCall.arguments(),
                argumentTruncated
        );
        ToolExecutionRecord record = ToolExecutionRecord.builder()
                .toolCallId(toolCallId)
                .actualToolName(toolCall.name())
                .canonicalToolName(definition.getToolName())
                .toolCallLogId(toolCallLog.getId())
                .startedAtMillis(System.currentTimeMillis())
                .argumentTruncated(argumentTruncated)
                .build();
        sendEvent(context, AgentSseEvent.Type.TOOL_CALL_START, payload(
                "taskId", context.getTaskId(),
                "stepId", context.getStepId(),
                "toolCallLogId", toolCallLog.getId(),
                "toolCallId", record.getToolCallId(),
                "toolName", definition.getToolName(),
                "actualToolName", toolCall.name(),
                "status", AgentTaskLogService.STATUS_RUNNING,
                "argumentTruncated", argumentTruncated,
                "argumentsPreview", truncate(toolCall.arguments(), MAX_ARGUMENT_PREVIEW)
        ));
        return record;
    }

    @Override
    public void afterToolSuccess(ToolExecutionContext context, ToolExecutionRecord record, String result) {
        long latencyMs = System.currentTimeMillis() - record.getStartedAtMillis();
        String resultSummary = toolRegistry.truncateResult(record.getCanonicalToolName(), result);
        boolean resultTruncated = result != null && resultSummary != null && resultSummary.length() < result.length();
        agentTaskLogService.finishToolCall(record.getToolCallLogId(), resultSummary, latencyMs, resultTruncated);
        sendEvent(context, AgentSseEvent.Type.TOOL_CALL_RESULT, payload(
                "taskId", context.getTaskId(),
                "stepId", context.getStepId(),
                "toolCallLogId", record.getToolCallLogId(),
                "toolCallId", record.getToolCallId(),
                "toolName", record.getCanonicalToolName(),
                "actualToolName", record.getActualToolName(),
                "status", AgentTaskLogService.STATUS_SUCCESS,
                "resultTruncated", resultTruncated,
                "resultSummary", resultSummary,
                "latencyMs", latencyMs
        ));
    }

    @Override
    public void afterToolFailure(ToolExecutionContext context, ToolExecutionRecord record, Throwable error) {
        afterToolFailure(context, record, error, false);
    }

    @Override
    public void afterToolFailure(ToolExecutionContext context, ToolExecutionRecord record, Throwable error,
                                 boolean correctionRequested) {
        long latencyMs = System.currentTimeMillis() - record.getStartedAtMillis();
        ToolFailureDecision decision = toolFailureClassifier.classify(error);
        String errorMessage = correctionRequested
                ? decision.sanitizedMessage() + " (fed back to model for self-correction)"
                : decision.sanitizedMessage();
        String errorType = decision.errorType();
        agentTaskLogService.failToolCall(record.getToolCallLogId(), errorMessage, latencyMs, errorType, false);
        sendEvent(context, AgentSseEvent.Type.TOOL_CALL_RESULT, payload(
                "taskId", context.getTaskId(),
                "stepId", context.getStepId(),
                "toolCallLogId", record.getToolCallLogId(),
                "toolCallId", record.getToolCallId(),
                "toolName", record.getCanonicalToolName(),
                "actualToolName", record.getActualToolName(),
                "status", AgentTaskLogService.STATUS_FAILED,
                "errorType", errorType,
                "correctionRequested", correctionRequested,
                "errorMessage", truncate(errorMessage, MAX_ARGUMENT_PREVIEW),
                "latencyMs", latencyMs
        ));
    }

    private String resolveToolCallId(AssistantMessage.ToolCall toolCall) {
        try {
            Object value = toolCall.getClass().getMethod("id").invoke(toolCall);
            return value == null ? null : String.valueOf(value);
        } catch (Exception e) {
            log.debug("Current Spring AI ToolCall does not expose id(), using null correlation id.");
            return null;
        }
    }

    private void sendEvent(ToolExecutionContext context, AgentSseEvent.Type type, Map<String, Object> payload) {
        if (context.getTaskId() == null || context.getSessionId() == null) {
            return;
        }
        try {
            sseService.sendEvent(context.getSessionId(), AgentSseEvent.of(
                    context.getTaskId(),
                    context.getSessionId(),
                    type,
                    payload
            ));
        } catch (Exception e) {
            log.warn("Failed to send tool execution event: type={}, error={}", type, e.getMessage());
        }
    }

    private Map<String, Object> payload(Object... keyValues) {
        Map<String, Object> payload = new HashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            payload.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return payload;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        int keep = Math.max(0, maxLength - 32);
        return value.substring(0, keep) + "\n...[truncated]";
    }
}
