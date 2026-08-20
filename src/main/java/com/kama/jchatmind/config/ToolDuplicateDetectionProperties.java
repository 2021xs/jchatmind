package com.kama.jchatmind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "jchatmind.agent.duplicate-detection")
public class ToolDuplicateDetectionProperties {
    private boolean enabled = true;
    private int maxConsecutiveSameCalls = 2;

    public int validatedMaxConsecutiveSameCalls() {
        if (maxConsecutiveSameCalls < 1) {
            throw new IllegalStateException("maxConsecutiveSameCalls must be at least 1");
        }
        return maxConsecutiveSameCalls;
    }
}
