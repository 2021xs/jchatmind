package com.kama.jchatmind.mcp.config;

import com.kama.jchatmind.mcp.safety.McpToolRiskLevel;
import lombok.Data;

@Data
public class ExternalMcpPromptProperties {
    private String name;
    private McpToolRiskLevel riskLevel;
    private boolean autoAttachAllowed = false;
}
