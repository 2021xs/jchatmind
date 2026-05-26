package com.kama.jchatmind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "jchatmind.agent.database-tool")
public class DatabaseToolProperties {
    private int maxRows = 50;
    private int queryTimeoutSeconds = 5;
    private int fetchSize = 50;
    private int maxCellChars = 500;
    private int maxResultLength = 4000;
}
