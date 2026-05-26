package com.kama.jchatmind.service;

import com.kama.jchatmind.tool.ToolExecutionContext;
import com.kama.jchatmind.tool.ToolExecutionRecord;
import org.springframework.ai.chat.messages.AssistantMessage;

public interface ToolExecutionService {
    ToolExecutionRecord beforeToolCall(ToolExecutionContext context, AssistantMessage.ToolCall toolCall);

    void afterToolSuccess(ToolExecutionContext context, ToolExecutionRecord record, String result);

    void afterToolFailure(ToolExecutionContext context, ToolExecutionRecord record, Throwable error);

    default void afterToolFailure(ToolExecutionContext context, ToolExecutionRecord record, Throwable error,
                                  boolean correctionRequested) {
        afterToolFailure(context, record, error);
    }
}
