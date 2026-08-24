package com.kama.jchatmind.model.dto;

public class SelectorModelResponse {
    private final String content;
    private final Integer reasoningContentChars;
    private final Boolean reasoningContentPresent;
    private final Integer promptTokens;
    private final Integer completionTokens;
    private final Integer totalTokens;
    private final String finishReason;

    public SelectorModelResponse(String content,
                                 Integer reasoningContentChars,
                                 Boolean reasoningContentPresent,
                                 Integer promptTokens,
                                 Integer completionTokens,
                                 Integer totalTokens,
                                 String finishReason) {
        this.content = content;
        this.reasoningContentChars = reasoningContentChars;
        this.reasoningContentPresent = reasoningContentPresent;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.finishReason = finishReason;
    }

    public String getContent() {
        return content;
    }

    public Integer getReasoningContentChars() {
        return reasoningContentChars;
    }

    public Boolean getReasoningContentPresent() {
        return reasoningContentPresent;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public String getFinishReason() {
        return finishReason;
    }
}
