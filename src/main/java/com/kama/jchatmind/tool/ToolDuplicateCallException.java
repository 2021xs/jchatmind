package com.kama.jchatmind.tool;

import com.kama.jchatmind.service.AgentTaskLogService;

public class ToolDuplicateCallException extends ToolFailureException {
    private static final String HINT =
            "Use the existing tool result or change the tool or arguments before calling again.";

    public ToolDuplicateCallException(String toolName,
                                      int consecutiveCount,
                                      int maxConsecutiveSameCalls,
                                      boolean hardStop) {
        super("Duplicate tool call rejected before execution: tool=" + toolName
                        + ", consecutiveCount=" + consecutiveCount
                        + ", maxAllowed=" + maxConsecutiveSameCalls
                        + ", hardStop=" + hardStop,
                null,
                AgentTaskLogService.ERROR_TYPE_DUPLICATE_TOOL_CALL,
                false,
                HINT);
    }
}
