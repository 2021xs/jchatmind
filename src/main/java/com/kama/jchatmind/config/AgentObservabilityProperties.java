package com.kama.jchatmind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "jchatmind.agent.observability")
public class AgentObservabilityProperties {
    private int staleRunningThresholdMinutes = 10;
    private boolean recoveryEnabled = true;
}
