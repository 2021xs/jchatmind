package com.kama.jchatmind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "jchatmind.context-compression")
public class ContextCompressionProperties {
    private boolean enabled = true;
    private int keepRecentRounds = 6;
    private int maxHistoryMessages = 12;
    private int maxContextTokens = 12000;
    private int maxSingleToolResultTokens = 2000;
    private int charsPerToken = 3;
    private int maxSummaryChars = 1200;
    private Map<String, TokenThreshold> modelThresholds = new HashMap<>();

    public TokenThreshold thresholdFor(String model) {
        if (model != null) {
            TokenThreshold threshold = modelThresholds.get(model);
            if (threshold != null) {
                return threshold.withDefaults(maxContextTokens, maxSingleToolResultTokens);
            }
        }
        TokenThreshold defaultThreshold = modelThresholds.get("default");
        if (defaultThreshold != null) {
            return defaultThreshold.withDefaults(maxContextTokens, maxSingleToolResultTokens);
        }
        return new TokenThreshold(maxContextTokens, maxSingleToolResultTokens);
    }

    @Data
    public static class TokenThreshold {
        private Integer maxContextTokens;
        private Integer maxSingleToolResultTokens;

        public TokenThreshold() {
        }

        public TokenThreshold(Integer maxContextTokens, Integer maxSingleToolResultTokens) {
            this.maxContextTokens = maxContextTokens;
            this.maxSingleToolResultTokens = maxSingleToolResultTokens;
        }

        private TokenThreshold withDefaults(int defaultMaxContextTokens, int defaultMaxSingleToolResultTokens) {
            return new TokenThreshold(
                    maxContextTokens == null ? defaultMaxContextTokens : maxContextTokens,
                    maxSingleToolResultTokens == null ? defaultMaxSingleToolResultTokens : maxSingleToolResultTokens
            );
        }
    }
}
