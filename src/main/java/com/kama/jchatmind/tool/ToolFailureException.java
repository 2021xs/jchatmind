package com.kama.jchatmind.tool;

public abstract class ToolFailureException extends RuntimeException {
    private final String errorType;
    private final boolean correctable;
    private final String safeMessage;
    private final String correctionHint;

    protected ToolFailureException(String message,
                                   Throwable cause,
                                   String errorType,
                                   boolean correctable,
                                   String correctionHint) {
        super(message, cause);
        this.errorType = errorType;
        this.correctable = correctable;
        this.safeMessage = message;
        this.correctionHint = correctionHint;
    }

    public String getErrorType() {
        return errorType;
    }

    public boolean isCorrectable() {
        return correctable;
    }

    public String getSafeMessage() {
        return safeMessage;
    }

    public String getCorrectionHint() {
        return correctionHint;
    }
}
