package com.kama.jchatmind.mcp.registry;

import com.kama.jchatmind.mcp.config.ExternalMcpServerProperties;
import com.kama.jchatmind.mcp.config.ExternalMcpServerType;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ExternalMcpServerRegistration {
    private String name;
    private ExternalMcpServerType type;
    private String transport;
    private String command;
    private String url;
    private ExternalMcpServerProperties properties;
}
