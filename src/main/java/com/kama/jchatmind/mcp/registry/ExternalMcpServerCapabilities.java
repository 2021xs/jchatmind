package com.kama.jchatmind.mcp.registry;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ExternalMcpServerCapabilities {
    private String serverName;
    private List<ExternalMcpDiscoveredTool> tools;
    private List<ExternalMcpDiscoveredResource> resources;
    private List<ExternalMcpDiscoveredPrompt> prompts;
}
