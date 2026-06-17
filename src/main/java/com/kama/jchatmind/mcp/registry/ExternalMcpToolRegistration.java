package com.kama.jchatmind.mcp.registry;

import com.kama.jchatmind.mcp.config.ExternalMcpServerType;
import com.kama.jchatmind.mcp.safety.McpToolRiskLevel;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ExternalMcpToolRegistration {
    private String serverName;
    private ExternalMcpServerType serverType;
    private String toolName;
    private String exposedName;
    private String description;
    private String inputSchema;
    private McpToolRiskLevel riskLevel;
    private boolean autoInvokeAllowed;
}
