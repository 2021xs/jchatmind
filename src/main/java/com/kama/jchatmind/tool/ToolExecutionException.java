package com.kama.jchatmind.tool;

import com.kama.jchatmind.service.AgentTaskLogService;

public class ToolExecutionException extends ToolFailureException {
    private static final String HINT =
            "This failure is not suitable for model self-correction; stop the current tool execution.";

    public ToolExecutionException(String message, Throwable cause) {
        super(message, cause, AgentTaskLogService.ERROR_TYPE_TOOL_EXCEPTION, false, HINT);
    }
}
