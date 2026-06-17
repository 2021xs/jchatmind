package com.kama.jchatmind.mcp.safety;

import com.kama.jchatmind.mcp.config.ExternalMcpServerType;

public class McpExternalToolPolicy {
    public boolean isSupportedServerType(ExternalMcpServerType type) {
        return type == ExternalMcpServerType.DOCS
                || type == ExternalMcpServerType.GITHUB
                || type == ExternalMcpServerType.BROWSER;
    }

    public McpToolRiskLevel resolveRiskLevel(McpToolRiskLevel configuredRiskLevel) {
        return configuredRiskLevel == null ? McpToolRiskLevel.DANGEROUS : configuredRiskLevel;
    }

    public boolean canAutoInvoke(ExternalMcpServerType serverType,
                                 McpToolRiskLevel riskLevel,
                                 boolean configuredAutoInvokeAllowed) {
        if (!configuredAutoInvokeAllowed || !isSupportedServerType(serverType)) {
            return false;
        }
        return isToolAllowedByRisk(riskLevel);
    }

    public boolean isToolAllowedByRisk(McpToolRiskLevel riskLevel) {
        return riskLevel == McpToolRiskLevel.READ_ONLY || riskLevel == McpToolRiskLevel.NETWORK_READ;
    }
}
