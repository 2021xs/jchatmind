package com.kama.jchatmind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "jchatmind.agent.final-synthesis")
public class FinalSynthesisProperties {
    /** Maximum estimated tokens allowed in a compiled Final provider request. */
    private int maxInputTokens = 64_000;

    /** Conservative fallback used when an exact provider tokenizer is unavailable. */
    private int charsPerToken = 3;
}
