package com.kama.jchatmind.tool;

import com.kama.jchatmind.service.AgentTaskLogService;

public class ToolArgumentException extends ToolFailureException {
    private static final String HINT =
            "Please regenerate the tool call with valid JSON arguments and all required parameters.";

    public ToolArgumentException(String message, Throwable cause) {
        super(message, cause, AgentTaskLogService.ERROR_TYPE_ARGUMENT_PARSE_ERROR, true, HINT);
    }
}
