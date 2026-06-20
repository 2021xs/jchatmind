package com.kama.jchatmind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "jchatmind.token-counter")
public class TokenCounterProperties {
    private boolean enabled = false;
    private String baseUrl;
    private Duration timeout = Duration.ofSeconds(2);
    private boolean fallbackToEstimated = true;
}
