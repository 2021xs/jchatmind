package com.kama.jchatmind.mcp;

import com.kama.jchatmind.mcp.config.ExternalMcpServerType;
import com.kama.jchatmind.mcp.safety.McpToolRiskLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;

class ExternalMcpGithubRealServerManualIntegrationTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "JCHATMIND_MCP_GITHUB_REAL_ENABLED", matches = "true")
    void githubPublicReadOnlyToolRunsThroughJChatMindGatewayChain() {
        ExternalMcpRealServerTestSupport.run(new ExternalMcpRealServerTestSupport.RealMcpCase(
                "JCHATMIND_MCP_GITHUB",
                ExternalMcpServerType.GITHUB,
                "github-readonly",
                "cmd",
                List.of("/c", "npx", "-y", "@modelcontextprotocol/server-github@2025.4.8"),
                List.of("GITHUB_PERSONAL_ACCESS_TOKEN"),
                "search_repositories",
                "create_issue",
                McpToolRiskLevel.NETWORK_READ,
                "{\"query\":\"spring-ai org:spring-projects\",\"perPage\":1}",
                6000,
                Map.of()
        ));
    }
}
