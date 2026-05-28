package com.kama.jchatmind.tool;

import com.kama.jchatmind.service.AgentTaskLogService;

public class ToolUnknownException extends ToolFailureException {
    private static final String HINT =
            "This failure is not suitable for model self-correction; stop the current tool execution.";

    public ToolUnknownException(String message) {
        super(message, null, AgentTaskLogService.ERROR_TYPE_UNKNOWN_TOOL, false, HINT);
    }
}
