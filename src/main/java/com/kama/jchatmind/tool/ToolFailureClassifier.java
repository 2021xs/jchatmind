package com.kama.jchatmind.tool;

import com.kama.jchatmind.service.AgentTaskLogService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;

@Component
public class ToolFailureClassifier {
    private static final int MAX_SAFE_MESSAGE_LENGTH = 500;

    public ToolFailureDecision classify(Throwable error) {
        String message = safeMessage(error);
        String normalized = message.toLowerCase(Locale.ROOT);
        String errorType = errorType(normalized);
        boolean correctable = isCorrectable(error, normalized, errorType);
        String hint = correctable
                ? "Please regenerate the tool call with valid JSON arguments and all required parameters."
                : "This failure is not suitable for model self-correction; stop the current tool execution.";
        return new ToolFailureDecision(errorType, correctable, message, hint);
    }

    private String errorType(String normalizedMessage) {
        if (normalizedMessage.contains("unknown tool")) {
            return AgentTaskLogService.ERROR_TYPE_UNKNOWN_TOOL;
        }
        if (normalizedMessage.contains("not allowed")
                || normalizedMessage.contains("policy")
                || normalizedMessage.contains("rejected")
                || normalizedMessage.contains("unauthorized")
                || normalizedMessage.contains("forbidden")) {
            return AgentTaskLogService.ERROR_TYPE_POLICY_REJECTED;
        }
        if (normalizedMessage.contains("timeout") || normalizedMessage.contains("timed out")) {
            return AgentTaskLogService.ERROR_TYPE_TOOL_TIMEOUT;
        }
        if (looksLikeArgumentProblem(normalizedMessage)) {
            return AgentTaskLogService.ERROR_TYPE_ARGUMENT_PARSE_ERROR;
        }
        return AgentTaskLogService.ERROR_TYPE_TOOL_EXCEPTION;
    }

    private boolean isCorrectable(Throwable error, String normalizedMessage, String errorType) {
        if (!AgentTaskLogService.ERROR_TYPE_ARGUMENT_PARSE_ERROR.equals(errorType)) {
            return false;
        }
        if (looksLikePolicyOrSystemProblem(normalizedMessage)) {
            return false;
        }
        return error instanceof IllegalArgumentException
                || normalizedMessage.contains("json")
                || normalizedMessage.contains("parse")
                || normalizedMessage.contains("argument")
                || normalizedMessage.contains("parameter")
                || normalizedMessage.contains("required")
                || normalizedMessage.contains("missing")
                || normalizedMessage.contains("type")
                || normalizedMessage.contains("deserialize")
                || normalizedMessage.contains("convert")
                || normalizedMessage.contains("validation");
    }

    private boolean looksLikeArgumentProblem(String normalizedMessage) {
        return normalizedMessage.contains("json")
                || normalizedMessage.contains("parse")
                || normalizedMessage.contains("argument")
                || normalizedMessage.contains("parameter")
                || normalizedMessage.contains("required")
                || normalizedMessage.contains("missing")
                || normalizedMessage.contains("type")
                || normalizedMessage.contains("deserialize")
                || normalizedMessage.contains("convert")
                || normalizedMessage.contains("validation");
    }

    private boolean looksLikePolicyOrSystemProblem(String normalizedMessage) {
        return normalizedMessage.contains("unknown tool")
                || normalizedMessage.contains("not allowed")
                || normalizedMessage.contains("policy")
                || normalizedMessage.contains("rejected")
                || normalizedMessage.contains("unsafe")
                || normalizedMessage.contains("database query execution failed")
                || normalizedMessage.contains("connection")
                || normalizedMessage.contains("timeout")
                || normalizedMessage.contains("timed out")
                || normalizedMessage.contains("permission")
                || normalizedMessage.contains("unauthorized")
                || normalizedMessage.contains("forbidden");
    }

    private String safeMessage(Throwable error) {
        String message = error == null ? "Unknown tool execution error" : error.getMessage();
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
