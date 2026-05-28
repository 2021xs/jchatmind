package com.kama.jchatmind.tool;

import com.kama.jchatmind.service.AgentTaskLogService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ToolFailureClassifier {
    private static final int MAX_SAFE_MESSAGE_LENGTH = 500;

    public ToolFailureDecision classify(Throwable error) {
        if (error instanceof ToolFailureException toolFailureException) {
            return new ToolFailureDecision(
                    toolFailureException.getErrorType(),
                    toolFailureException.isCorrectable(),
                    safeMessage(toolFailureException.getSafeMessage(), error),
                    toolFailureException.getCorrectionHint()
            );
        }
        String message = safeMessage(error);
        boolean correctable = isFrameworkArgumentFailure(error);
        String errorType = correctable
                ? AgentTaskLogService.ERROR_TYPE_ARGUMENT_PARSE_ERROR
                : AgentTaskLogService.ERROR_TYPE_TOOL_EXCEPTION;
        String hint = correctable
                ? "Please regenerate the tool call with valid JSON arguments and all required parameters."
                : "This failure is not suitable for model self-correction; stop the current tool execution.";
        return new ToolFailureDecision(errorType, correctable, message, hint);
    }

    private boolean isFrameworkArgumentFailure(Throwable error) {
        return error instanceof IllegalArgumentException;
    }

    private String safeMessage(Throwable error) {
        String message = error == null ? "Unknown tool execution error" : error.getMessage();
        return safeMessage(message, error);
    }

    private String safeMessage(String message, Throwable error) {
        if (!StringUtils.hasLength(message)) {
            message = error == null ? "Unknown tool execution error" : error.getClass().getSimpleName();
        }
        message = message.replaceAll("\\s+", " ").trim();
        if (message.length() <= MAX_SAFE_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_SAFE_MESSAGE_LENGTH - 16) + "...[truncated]";
    }
}
