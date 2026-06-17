package com.kama.jchatmind.mcp;

import com.kama.jchatmind.mcp.adapter.McpToolCallbackAdapter;
import com.kama.jchatmind.mcp.config.McpClientIntegrationConfig;
import com.kama.jchatmind.mcp.config.McpClientProperties;
import com.kama.jchatmind.mcp.service.ExternalMcpPromptAccessService;
import com.kama.jchatmind.mcp.service.ExternalMcpResourceAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class McpClientIntegrationConfigTest {

    @Test
    void mcpClientIsDisabledByDefault() {
        new ApplicationContextRunner()
                .withUserConfiguration(McpClientIntegrationConfig.class)
                .withBean(McpClientProperties.class, McpClientProperties::new)
                .run(context -> assertThat(context).doesNotHaveBean(McpToolCallbackAdapter.class));
    }

    @Test
    void mcpClientBeansLoadWhenEnabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(McpClientIntegrationConfig.class)
                .withPropertyValues("jchatmind.mcp.client.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(McpToolCallbackAdapter.class);
                    assertThat(context).hasSingleBean(ExternalMcpResourceAccessService.class);
                    assertThat(context).hasSingleBean(ExternalMcpPromptAccessService.class);
                });
    }
}
