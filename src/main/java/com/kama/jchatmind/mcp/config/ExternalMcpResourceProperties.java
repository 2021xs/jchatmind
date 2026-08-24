package com.kama.jchatmind.mcp.config;

import com.kama.jchatmind.mcp.safety.McpToolRiskLevel;
import lombok.Data;

@Data
public class ExternalMcpResourceProperties {
    private String uri;
    private McpToolRiskLevel riskLevel;
    private boolean autoAttachAllowed = false;
}
