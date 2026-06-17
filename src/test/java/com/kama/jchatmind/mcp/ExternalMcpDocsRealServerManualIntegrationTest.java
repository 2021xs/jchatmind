package com.kama.jchatmind.mcp;

import com.kama.jchatmind.mcp.config.ExternalMcpServerType;
import com.kama.jchatmind.mcp.safety.McpToolRiskLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;

class ExternalMcpDocsRealServerManualIntegrationTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "JCHATMIND_MCP_DOCS_REAL_ENABLED", matches = "true")
    void context7DocsReadOnlyToolRunsThroughJChatMindGatewayChain() {
        ExternalMcpRealServerTestSupport.run(new ExternalMcpRealServerTestSupport.RealMcpCase(
                "JCHATMIND_MCP_DOCS",
                ExternalMcpServerType.DOCS,
                "context7-docs",
                "cmd",
                List.of("/c", "npx", "-y", "@upstash/context7-mcp@3.2.1"),
                List.of("CONTEXT7_API_KEY"),
                "resolve-library-id",
                "query-docs",
                McpToolRiskLevel.NETWORK_READ,
                "{\"query\":\"Spring AI MCP Client\",\"libraryName\":\"spring ai\"}",
                6000,
                Map.of()
        ));
    }
}
