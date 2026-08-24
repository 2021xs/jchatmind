package com.kama.jchatmind.mcp;

import com.kama.jchatmind.mcp.adapter.McpToolCallbackAdapter;
import com.kama.jchatmind.mcp.adapter.SpringAiExternalMcpClientAdapter;
import com.kama.jchatmind.mcp.config.McpClientIntegrationConfig;
import com.kama.jchatmind.mcp.config.McpClientProperties;
import com.kama.jchatmind.mcp.service.ExternalMcpPromptAccessService;
import com.kama.jchatmind.mcp.service.ExternalMcpResourceAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .run(context -> {
                    assertThat(context).hasSingleBean(McpToolCallbackAdapter.class);
                    assertThat(context).hasSingleBean(ExternalMcpResourceAccessService.class);
                    assertThat(context).hasSingleBean(ExternalMcpPromptAccessService.class);
                });
    }

    @Test
    void springAiAdapterLoadsWhenSpringMcpProvidesClientListBean() {
        new ApplicationContextRunner()
                .withUserConfiguration(McpClientIntegrationConfig.class)
                .withPropertyValues("jchatmind.mcp.client.enabled=true")
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean("mcpSyncClients", List.class, () -> List.of(mock(McpSyncClient.class)))
                .run(context -> {
                    assertThat(context).hasSingleBean(SpringAiExternalMcpClientAdapter.class);
                    assertThat(context).hasSingleBean(McpToolCallbackAdapter.class);
                });
    }
}
