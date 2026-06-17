package com.kama.jchatmind.mcp;

import com.kama.jchatmind.mcp.config.ExternalMcpServerType;
import com.kama.jchatmind.mcp.safety.McpToolRiskLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;

class ExternalMcpBrowserRealServerManualIntegrationTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "JCHATMIND_MCP_BROWSER_REAL_ENABLED", matches = "true")
    void playwrightBrowserReadOnlyToolRunsThroughJChatMindGatewayChain() {
        ExternalMcpRealServerTestSupport.run(new ExternalMcpRealServerTestSupport.RealMcpCase(
                "JCHATMIND_MCP_BROWSER",
                ExternalMcpServerType.BROWSER,
                "playwright-browser",
                "cmd",
                List.of("/c", "npx", "-y", "@playwright/mcp@0.0.76", "--headless", "--isolated",
                        "--browser", "chromium", "--output-dir",
                        System.getProperty("java.io.tmpdir") + "/jchatmind-playwright-mcp"),
                List.of(),
                "browser_navigate",
                "browser_run_code_unsafe",
                McpToolRiskLevel.NETWORK_READ,
                "{\"url\":\"https://example.com\"}",
                6000,
                Map.of()
        ));
    }
}
