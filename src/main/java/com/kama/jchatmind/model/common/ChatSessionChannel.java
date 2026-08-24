package com.kama.jchatmind.model.common;

import org.springframework.util.StringUtils;

import java.util.Locale;

public enum ChatSessionChannel {
    WEB_CONSOLE,
    FEISHU,
    LEGACY;

    public static ChatSessionChannel parse(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("chat session channel is required");
        }
        return ChatSessionChannel.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    public static String normalize(String value) {
        return parse(value).name();
    }
}
