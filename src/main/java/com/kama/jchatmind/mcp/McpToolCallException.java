package com.kama.jchatmind.mcp;

import com.kama.jchatmind.service.AgentTaskLogService;
import com.kama.jchatmind.tool.ToolFailureException;

/**
 * Typed failure for an MCP invocation. The cause is retained for internal
 * diagnostics, while the message exposed to the Agent runtime is fixed and
 * does not contain remote exception details.
 */
public class McpToolCallException extends ToolFailureException {
    private static final String HINT =
            "This external MCP tool failed; do not repeat the same call unless the user changes the request.";

    public McpToolCallException(String exposedToolName, Throwable cause) {
        super("MCP_TOOL_CALL_FAILED: External MCP tool invocation failed for "
                        + safeToolName(exposedToolName),
                cause,
                AgentTaskLogService.ERROR_TYPE_MCP_TOOL_CALL_FAILED,
                false,
                HINT);
    }

    private static String safeToolName(String exposedToolName) {
        if (exposedToolName == null || exposedToolName.isBlank()) {
            return "external tool";
        }
        return exposedToolName.length() <= 120
                ? exposedToolName
                : exposedToolName.substring(0, 120);
    }
}
