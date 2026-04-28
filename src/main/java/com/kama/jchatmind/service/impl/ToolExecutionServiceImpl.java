package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.message.AgentSseEvent;
import com.kama.jchatmind.model.entity.ToolCallLog;
import com.kama.jchatmind.service.AgentTaskLogService;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.ToolExecutionService;
import com.kama.jchatmind.tool.ToolDefinition;
import com.kama.jchatmind.tool.ToolExecutionContext;
import com.kama.jchatmind.tool.ToolExecutionRecord;
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

    @Override
    public ToolExecutionRecord beforeToolCall(ToolExecutionContext context, AssistantMessage.ToolCall toolCall) {
        ToolDefinition definition = toolRegistry.find(toolCall.name())
                .orElseThrow(() -> new IllegalStateException("Unknown tool: " + toolCall.name()));
        if (!definition.isEnabled() || !definition.isAllowInAgent()) {
            throw new IllegalStateException("Tool is disabled for agent: " + toolCall.name());
        }
        if (!toolRegistry.isAllowedForRuntime(toolCall.name(), context.getRuntimeToolNames())) {
            throw new IllegalStateException("Tool is not allowed in current agent runtime: " + toolCall.name());
        }

        ToolCallLog toolCallLog = agentTaskLogService.startToolCall(
                context.getTaskId(),
                context.getStepId(),
                definition.getToolName(),
                toolCall.arguments()
        );
        ToolExecutionRecord record = ToolExecutionRecord.builder()
                .toolCallId(resolveToolCallId(toolCall))
                .actualToolName(toolCall.name())
                .canonicalToolName(definition.getToolName())
                .toolCallLogId(toolCallLog.getId())
                .startedAtMillis(System.currentTimeMillis())
                .build();
        sendEvent(context, AgentSseEvent.Type.TOOL_CALL_START, payload(
                "stepId", context.getStepId(),
                "toolCallLogId", toolCallLog.getId(),
                "toolCallId", record.getToolCallId(),
                "toolName", definition.getToolName(),
                "actualToolName", toolCall.name(),
                "argumentsPreview", truncate(toolCall.arguments(), MAX_ARGUMENT_PREVIEW)
        ));
        return record;
    }

    @Override
    public void afterToolSuccess(ToolExecutionContext context, ToolExecutionRecord record, String result) {
        long latencyMs = System.currentTimeMillis() - record.getStartedAtMillis();
        String resultSummary = toolRegistry.truncateResult(record.getCanonicalToolName(), result);
        agentTaskLogService.finishToolCall(record.getToolCallLogId(), resultSummary, latencyMs);
        sendEvent(context, AgentSseEvent.Type.TOOL_CALL_RESULT, payload(
                "toolCallLogId", record.getToolCallLogId(),
                "toolCallId", record.getToolCallId(),
                "toolName", record.getCanonicalToolName(),
                "actualToolName", record.getActualToolName(),
                "status", AgentTaskLogService.STATUS_SUCCESS,
                "resultSummary", resultSummary,
                "latencyMs", latencyMs
        ));
    }

    @Override
    public void afterToolFailure(ToolExecutionContext context, ToolExecutionRecord record, Throwable error) {
        long latencyMs = System.currentTimeMillis() - record.getStartedAtMillis();
        String errorMessage = error == null ? "Unknown tool execution error" : error.getMessage();
        agentTaskLogService.failToolCall(record.getToolCallLogId(), errorMessage, latencyMs);
        sendEvent(context, AgentSseEvent.Type.TOOL_CALL_RESULT, payload(
                "toolCallLogId", record.getToolCallLogId(),
                "toolCallId", record.getToolCallId(),
                "toolName", record.getCanonicalToolName(),
                "actualToolName", record.getActualToolName(),
                "status", AgentTaskLogService.STATUS_FAILED,
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
