package com.kama.jchatmind.tool;

import com.kama.jchatmind.service.AgentTaskLogService;

public class ToolTimeoutException extends ToolFailureException {
    private static final String HINT =
            "This failure is not suitable for model self-correction; stop the current tool execution.";

    public ToolTimeoutException(String message, Throwable cause) {
        super(message, cause, AgentTaskLogService.ERROR_TYPE_TOOL_TIMEOUT, false, HINT);
    }
}
