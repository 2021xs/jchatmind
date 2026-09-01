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
    private int compressionTriggerTokens = 200000;
    private int workingContextHardLimitTokens = 256000;
    private int maxSingleToolResultTokens = 2000;
    private int charsPerToken = 3;
    private int maxSummaryChars = 1200;
    private Map<String, TokenThreshold> modelThresholds = new HashMap<>();

    public TokenThreshold thresholdFor(String model) {
        if (model != null) {
            TokenThreshold threshold = modelThresholds.get(model);
            if (threshold != null) {
                return threshold.withDefaults(compressionTriggerTokens,
                        workingContextHardLimitTokens, maxSingleToolResultTokens);
            }
        }
        TokenThreshold defaultThreshold = modelThresholds.get("default");
        if (defaultThreshold != null) {
            return defaultThreshold.withDefaults(compressionTriggerTokens,
                    workingContextHardLimitTokens, maxSingleToolResultTokens);
        }
        return validatedThreshold(compressionTriggerTokens,
                workingContextHardLimitTokens, maxSingleToolResultTokens);
    }

    @Data
    public static class TokenThreshold {
        private Integer compressionTriggerTokens;
        private Integer workingContextHardLimitTokens;
        private Integer maxSingleToolResultTokens;

        public TokenThreshold() {
        }

        public TokenThreshold(Integer compressionTriggerTokens,
                              Integer workingContextHardLimitTokens,
                              Integer maxSingleToolResultTokens) {
            this.compressionTriggerTokens = compressionTriggerTokens;
            this.workingContextHardLimitTokens = workingContextHardLimitTokens;
            this.maxSingleToolResultTokens = maxSingleToolResultTokens;
        }

        private TokenThreshold withDefaults(int defaultCompressionTriggerTokens,
                                            int defaultWorkingContextHardLimitTokens,
                                            int defaultMaxSingleToolResultTokens) {
            return validatedThreshold(
                    compressionTriggerTokens == null
                            ? defaultCompressionTriggerTokens : compressionTriggerTokens,
                    workingContextHardLimitTokens == null
                            ? defaultWorkingContextHardLimitTokens : workingContextHardLimitTokens,
                    maxSingleToolResultTokens == null
                            ? defaultMaxSingleToolResultTokens : maxSingleToolResultTokens);
        }
    }

    private static TokenThreshold validatedThreshold(int compressionTriggerTokens,
                                                     int workingContextHardLimitTokens,
                                                     int maxSingleToolResultTokens) {
        if (compressionTriggerTokens <= 0) {
            throw new IllegalStateException("compressionTriggerTokens must be positive");
        }
        if (workingContextHardLimitTokens < compressionTriggerTokens) {
            throw new IllegalStateException("workingContextHardLimitTokens must be greater than or equal to "
                    + "compressionTriggerTokens");
        }
        if (maxSingleToolResultTokens <= 0) {
            throw new IllegalStateException("maxSingleToolResultTokens must be positive");
        }
        return new TokenThreshold(compressionTriggerTokens,
                workingContextHardLimitTokens, maxSingleToolResultTokens);
    }
}
