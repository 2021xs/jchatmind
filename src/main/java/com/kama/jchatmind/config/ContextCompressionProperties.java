package com.kama.jchatmind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "jchatmind.context-compression")
public class ContextCompressionProperties {
    private boolean enabled = true;
    private int keepRecentRounds = 6;
    private int maxHistoryMessages = 12;
    private int triggerMessageCount = 16;
    private int maxContextChars = 16000;
    private int maxSingleToolResultChars = 4000;
    private int maxSummaryChars = 1200;
}
