package com.kama.jchatmind.agent;

import com.kama.jchatmind.service.ToolExecutionService;
import com.kama.jchatmind.tool.ToolExecutionRecord;
import lombok.AllArgsConstructor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@AllArgsConstructor
public class AgentToolCallExecutor {
    private final ToolExecutionService toolExecutionService;

    public AgentToolCallExecution execute(AgentToolCallExecutionRequest request) {
        List<ToolExecutionRecord> records = preflight(request);

        ToolExecutionResult toolExecutionResult;
        try {
            toolExecutionResult = request.getToolCallingManager()
                    .executeToolCalls(request.getPrompt(), request.getChatResponse());
        } catch (RuntimeException e) {
            return failed(records, e);
        } catch (Exception e) {
            return failed(records, new IllegalStateException(e.getMessage(), e));
        }

        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) toolExecutionResult
                .conversationHistory()
                .get(toolExecutionResult.conversationHistory().size() - 1);
        recordToolResponses(request, records, toolResponseMessage);
        return AgentToolCallExecution.builder()
                .status(AgentToolCallExecution.Status.SUCCESS)
                .records(records)
                .toolExecutionResult(toolExecutionResult)
                .toolResponseMessage(toolResponseMessage)
                .build();
    }

    private AgentToolCallExecution failed(List<ToolExecutionRecord> records, RuntimeException error) {
        return AgentToolCallExecution.builder()
                .status(AgentToolCallExecution.Status.FAILED)
                .records(records)
                .error(error)
                .build();
    }

    public void recordFailure(AgentToolCallExecutionRequest request,
                              List<ToolExecutionRecord> records,
                              Throwable error,
                              boolean correctionRequested) {
        for (ToolExecutionRecord record : records) {
            toolExecutionService.afterToolFailure(
                    request.getExecutionContext(),
                    record,
                    error,
                    correctionRequested
            );
        }
    }

    private List<ToolExecutionRecord> preflight(AgentToolCallExecutionRequest request) {
        List<ToolExecutionRecord> records = new ArrayList<>();
        try {
            List<AssistantMessage.ToolCall> toolCalls = request.getChatResponse()
                    .getResult()
                    .getOutput()
                    .getToolCalls();
            for (AssistantMessage.ToolCall toolCall : toolCalls) {
                records.add(toolExecutionService.beforeToolCall(request.getExecutionContext(), toolCall));
            }
            return records;
        } catch (Exception e) {
            recordFailure(request, records, e, false);
            throw e;
        }
    }

    private void recordToolResponses(AgentToolCallExecutionRequest request,
                                     List<ToolExecutionRecord> records,
                                     ToolResponseMessage toolResponseMessage) {
        List<ToolResponseMessage.ToolResponse> responses = toolResponseMessage.getResponses();
        for (int i = 0; i < responses.size() && i < records.size(); i++) {
            // Spring AI's ToolResponse is matched to the preflight record by response order here.
            // ToolCall.id is stored when present, but this response object is not relied on for id matching.
            toolExecutionService.afterToolSuccess(
                    request.getExecutionContext(),
                    records.get(i),
                    responses.get(i).responseData()
            );
        }
        for (int i = responses.size(); i < records.size(); i++) {
            toolExecutionService.afterToolFailure(
                    request.getExecutionContext(),
                    records.get(i),
                    new IllegalStateException("Tool response missing for call index " + i),
                    false
            );
        }
    }
}
