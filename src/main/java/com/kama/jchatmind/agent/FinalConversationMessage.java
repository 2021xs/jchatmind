package com.kama.jchatmind.agent;

import org.springframework.util.Assert;

/** Provider-independent conversation context retained for final synthesis. */
public record FinalConversationMessage(Role role, String content) {

    public FinalConversationMessage {
        Assert.notNull(role, "Final conversation role cannot be null");
        Assert.hasText(content, "Final conversation content cannot be empty");
    }

    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT
    }
}
