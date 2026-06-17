package com.kama.jchatmind.mcp.registry;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ExternalMcpDiscoveredPrompt {
    private String name;
    private String description;
    private java.util.List<String> requiredArguments;
}
