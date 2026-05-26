package com.kama.jchatmind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "jchatmind.agent.tool-correction")
public class ToolCorrectionProperties {
    private boolean enabled = true;
    private int maxAttempts = 3;
}
