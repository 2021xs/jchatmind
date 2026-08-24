package com.kama.jchatmind.mcp.registry;

import com.kama.jchatmind.mcp.config.ExternalMcpServerType;
import com.kama.jchatmind.mcp.safety.McpToolRiskLevel;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ExternalMcpPromptRegistration {
    private String serverName;
    private ExternalMcpServerType serverType;
    private String name;
    private String description;
    private List<String> requiredArguments;
    private McpToolRiskLevel riskLevel;
    private boolean autoAttachAllowed;
}
