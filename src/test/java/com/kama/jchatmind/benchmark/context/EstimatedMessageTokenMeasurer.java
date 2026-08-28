package com.kama.jchatmind.benchmark.context;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;

/** Stable content-and-protocol estimator shared by future LEGACY/TASK_AWARE runs. */
final class EstimatedMessageTokenMeasurer {
    static final String SOURCE = "ESTIMATED_MESSAGE_CHARS_V1";

    private final int charsPerToken;

    EstimatedMessageTokenMeasurer(int charsPerToken) {
        if (charsPerToken <= 0) {
            throw new IllegalArgumentException("charsPerToken must be positive");
        }
        this.charsPerToken = charsPerToken;
    }

    Measurement measure(List<Message> messages, String additionalSystemPrompt) {
        long chars = textLength(additionalSystemPrompt);
        if (messages != null) {
            for (Message message : messages) {
                chars += messageChars(message);
            }
        }
        int tokens = (int) Math.min(Integer.MAX_VALUE, (chars + charsPerToken - 1L) / charsPerToken);
        return new Measurement(tokens, chars, SOURCE);
    }

    int measureText(String text) {
        long chars = textLength(text);
        return (int) Math.min(Integer.MAX_VALUE, (chars + charsPerToken - 1L) / charsPerToken);
    }

    private long messageChars(Message message) {
        if (message == null) {
            return 0;
        }
        long chars = textLength(message.getText());
        if (message instanceof AssistantMessage assistant && assistant.getToolCalls() != null) {
            for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                if (call != null) {
                    chars += textLength(call.id()) + textLength(call.type())
                            + textLength(call.name()) + textLength(call.arguments());
                }
            }
        }
        if (message instanceof ToolResponseMessage responseMessage) {
            for (ToolResponseMessage.ToolResponse response : responseMessage.getResponses()) {
                if (response != null) {
                    chars += textLength(response.id()) + textLength(response.name())
                            + textLength(response.responseData());
                }
            }
        }
        return chars;
    }

    private long textLength(String value) {
        return value == null ? 0 : value.length();
    }

    record Measurement(int tokens, long measuredChars, String source) {
    }
}
