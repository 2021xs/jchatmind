package com.kama.jchatmind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "jchatmind.github-import")
public class GithubImportProperties {
    private String workspaceRoot;
    private Duration cloneTimeout = Duration.ofMinutes(10);
    private long maxTotalSourceBytes = 200L * 1024 * 1024;
    private long minFreeSpaceBytes = 0;
}
