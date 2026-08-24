package com.kama.jchatmind.mcp.registry;

import com.kama.jchatmind.mcp.config.ExternalMcpServerType;
import com.kama.jchatmind.mcp.safety.McpToolRiskLevel;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ExternalMcpResourceRegistration {
    private String serverName;
    private ExternalMcpServerType serverType;
    private String uri;
    private String name;
    private String description;
    private String mimeType;
    private McpToolRiskLevel riskLevel;
    private boolean autoAttachAllowed;
}
