package com.kama.jchatmind.agent;

import com.kama.jchatmind.service.ToolExecutionService;
import com.kama.jchatmind.tool.ToolExecutionContext;
import com.kama.jchatmind.tool.ToolExecutionRecord;
import lombok.AllArgsConstructor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@AllArgsConstructor
public class ToolCallBatchExecutor {
    private final ToolExecutionService toolExecutionService;

    public ToolCallBatchResult execute(Prompt prompt,
                                       ChatResponse chatResponse,
                                       ToolCallingManager toolCallingManager,
                                       ToolExecutionContext executionContext) {
        List<ToolExecutionRecord> records = preflight(chatResponse, executionContext);

        ToolExecutionResult toolExecutionResult;
        try {
            toolExecutionResult = toolCallingManager.executeToolCalls(prompt, chatResponse);
        } catch (RuntimeException e) {
            return failed(records, e);
        } catch (Exception e) {
            return failed(records, new IllegalStateException(e.getMessage(), e));
        }

        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) toolExecutionResult
                .conversationHistory()
                .get(toolExecutionResult.conversationHistory().size() - 1);
        recordToolResponses(executionContext, records, toolResponseMessage);
        return ToolCallBatchResult.builder()
                .status(ToolCallBatchResult.Status.SUCCESS)
                .records(records)
                .toolExecutionResult(toolExecutionResult)
                .toolResponseMessage(toolResponseMessage)
                .build();
    }

    private ToolCallBatchResult failed(List<ToolExecutionRecord> records, RuntimeException error) {
        return ToolCallBatchResult.builder()
                .status(ToolCallBatchResult.Status.FAILED)
                .records(records)
                .error(error)
                .build();
    }

    public void recordFailure(ToolExecutionContext executionContext,
                              List<ToolExecutionRecord> records,
                              Throwable error,
                              boolean correctionRequested) {
        for (ToolExecutionRecord record : records) {
            toolExecutionService.afterToolFailure(
                    executionContext,
                    record,
                    error,
                    correctionRequested
            );
        }
    }

    private List<ToolExecutionRecord> preflight(ChatResponse chatResponse,
                                                ToolExecutionContext executionContext) {
        List<ToolExecutionRecord> records = new ArrayList<>();
        try {
            List<AssistantMessage.ToolCall> toolCalls = chatResponse
                    .getResult()
                    .getOutput()
                    .getToolCalls();
            for (AssistantMessage.ToolCall toolCall : toolCalls) {
                records.add(toolExecutionService.beforeToolCall(executionContext, toolCall));
            }
            return records;
        } catch (Exception e) {
            recordFailure(executionContext, records, e, false);
            throw e;
        }
    }

    private void recordToolResponses(ToolExecutionContext executionContext,
                                     List<ToolExecutionRecord> records,
                                     ToolResponseMessage toolResponseMessage) {
        List<ToolResponseMessage.ToolResponse> responses = toolResponseMessage.getResponses();
        for (int i = 0; i < responses.size() && i < records.size(); i++) {
            // Spring AI's ToolResponse is matched to the preflight record by response order here.
            // ToolCall.id is stored when present, but this response object is not relied on for id matching.
            toolExecutionService.afterToolSuccess(
                    executionContext,
                    records.get(i),
                    responses.get(i).responseData()
            );
        }
        for (int i = responses.size(); i < records.size(); i++) {
            toolExecutionService.afterToolFailure(
                    executionContext,
                    records.get(i),
                    new IllegalStateException("Tool response missing for call index " + i),
                    false
            );
        }
    }
}
