package com.kama.jchatmind.mcp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "jchatmind.mcp.client")
public class McpClientProperties {
    private boolean enabled = false;
    private int maxResultLength = 6000;
    private boolean auditEnabled = true;
    private List<ExternalMcpServerProperties> servers = new ArrayList<>();
}
