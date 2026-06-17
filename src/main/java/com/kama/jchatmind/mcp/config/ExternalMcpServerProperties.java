package com.kama.jchatmind.mcp.config;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ExternalMcpServerProperties {
    private String name;
    private ExternalMcpServerType type;
    private String transport;
    private String command;
    private String url;
    private boolean enabled = false;
    private List<ExternalMcpToolProperties> allowedTools = new ArrayList<>();
}
