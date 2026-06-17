package com.kama.jchatmind.mcp.registry;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ExternalMcpDiscoveredResource {
    private String uri;
    private String name;
    private String description;
    private String mimeType;
}
