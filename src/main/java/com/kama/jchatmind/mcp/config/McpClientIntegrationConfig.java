package com.kama.jchatmind.mcp.config;

import com.kama.jchatmind.mcp.adapter.ExternalMcpToolInvoker;
import com.kama.jchatmind.mcp.adapter.McpToolCallbackAdapter;
import com.kama.jchatmind.mcp.adapter.SpringAiExternalMcpClientAdapter;
import com.kama.jchatmind.mcp.adapter.UnsupportedExternalMcpToolInvoker;
import com.kama.jchatmind.mcp.audit.McpToolAuditLogger;
import com.kama.jchatmind.mcp.audit.McpPromptAuditLogger;
import com.kama.jchatmind.mcp.audit.McpResourceAuditLogger;
import com.kama.jchatmind.mcp.audit.Slf4jMcpToolAuditLogger;
import com.kama.jchatmind.mcp.audit.Slf4jMcpPromptAuditLogger;
import com.kama.jchatmind.mcp.audit.Slf4jMcpResourceAuditLogger;
import com.kama.jchatmind.mcp.registry.ExternalMcpServerRegistry;
import com.kama.jchatmind.mcp.registry.ExternalMcpCapabilityDiscoveryClient;
import com.kama.jchatmind.mcp.registry.ExternalMcpCapabilityRegistry;
import com.kama.jchatmind.mcp.registry.ExternalMcpPromptClient;
import com.kama.jchatmind.mcp.registry.ExternalMcpPromptRegistry;
import com.kama.jchatmind.mcp.registry.ExternalMcpResourceReader;
import com.kama.jchatmind.mcp.registry.ExternalMcpResourceRegistry;
import com.kama.jchatmind.mcp.registry.ExternalMcpToolDiscoveryClient;
import com.kama.jchatmind.mcp.registry.ExternalMcpToolRegistry;
import com.kama.jchatmind.mcp.registry.UnsupportedExternalMcpPromptClient;
import com.kama.jchatmind.mcp.registry.UnsupportedExternalMcpResourceReader;
import com.kama.jchatmind.mcp.registry.NoopExternalMcpToolDiscoveryClient;
import com.kama.jchatmind.mcp.registry.NoopExternalMcpCapabilityDiscoveryClient;
import com.kama.jchatmind.mcp.safety.McpExternalToolPolicy;
import com.kama.jchatmind.mcp.service.ExternalMcpPromptAccessService;
import com.kama.jchatmind.mcp.service.ExternalMcpPromptAccessServiceImpl;
import com.kama.jchatmind.mcp.service.ExternalMcpResourceAccessService;
import com.kama.jchatmind.mcp.service.ExternalMcpResourceAccessServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@EnableConfigurationProperties(McpClientProperties.class)
@ConditionalOnProperty(prefix = "jchatmind.mcp.client", name = "enabled", havingValue = "true")
public class McpClientIntegrationConfig {

    @Bean
    public McpExternalToolPolicy mcpExternalToolPolicy() {
        return new McpExternalToolPolicy();
    }

    @Bean
    public ExternalMcpServerRegistry externalMcpServerRegistry(McpClientProperties properties) {
        return new ExternalMcpServerRegistry(properties);
    }

    @Bean
    @ConditionalOnMissingBean({ExternalMcpToolDiscoveryClient.class, ExternalMcpToolInvoker.class})
    public SpringAiExternalMcpClientAdapter springAiExternalMcpClientAdapter(ObjectProvider<List<McpSyncClient>> mcpClientsProvider,
                                                                             ObjectMapper objectMapper) {
        return new SpringAiExternalMcpClientAdapter(mcpClientsProvider, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(value = {SpringAiExternalMcpClientAdapter.class, ExternalMcpCapabilityDiscoveryClient.class})
    public ExternalMcpCapabilityDiscoveryClient externalMcpCapabilityDiscoveryClient() {
        return new NoopExternalMcpCapabilityDiscoveryClient();
    }

    @Bean
    @ConditionalOnMissingBean
    public ExternalMcpToolDiscoveryClient externalMcpToolDiscoveryClient(
            ExternalMcpCapabilityDiscoveryClient discoveryClient) {
        return discoveryClient;
    }

    @Bean
    public ExternalMcpToolRegistry externalMcpToolRegistry(ExternalMcpServerRegistry serverRegistry,
                                                           ExternalMcpToolDiscoveryClient discoveryClient,
                                                           McpExternalToolPolicy policy) {
        return new ExternalMcpToolRegistry(serverRegistry, discoveryClient, policy);
    }

    @Bean
    public ExternalMcpCapabilityRegistry externalMcpCapabilityRegistry(ExternalMcpServerRegistry serverRegistry,
                                                                       ExternalMcpCapabilityDiscoveryClient discoveryClient,
                                                                       McpExternalToolPolicy policy) {
        return new ExternalMcpCapabilityRegistry(serverRegistry, discoveryClient, policy);
    }

    @Bean
    public ExternalMcpResourceRegistry externalMcpResourceRegistry(ExternalMcpServerRegistry serverRegistry,
                                                                   ExternalMcpCapabilityDiscoveryClient discoveryClient,
                                                                   McpExternalToolPolicy policy) {
        return new ExternalMcpResourceRegistry(serverRegistry, discoveryClient, policy);
    }

    @Bean
    public ExternalMcpPromptRegistry externalMcpPromptRegistry(ExternalMcpServerRegistry serverRegistry,
                                                               ExternalMcpCapabilityDiscoveryClient discoveryClient,
                                                               McpExternalToolPolicy policy) {
        return new ExternalMcpPromptRegistry(serverRegistry, discoveryClient, policy);
    }

    @Bean
    @ConditionalOnMissingBean(value = {McpSyncClient.class, ExternalMcpToolInvoker.class})
    public ExternalMcpToolInvoker externalMcpToolInvoker() {
        return new UnsupportedExternalMcpToolInvoker();
    }

    @Bean
    @ConditionalOnMissingBean(value = {McpSyncClient.class, ExternalMcpResourceReader.class})
    public ExternalMcpResourceReader externalMcpResourceReader() {
        return new UnsupportedExternalMcpResourceReader();
    }

    @Bean
    @ConditionalOnMissingBean(value = {McpSyncClient.class, ExternalMcpPromptClient.class})
    public ExternalMcpPromptClient externalMcpPromptClient() {
        return new UnsupportedExternalMcpPromptClient();
    }

    @Bean
    public McpToolAuditLogger mcpToolAuditLogger(McpClientProperties properties) {
        return new Slf4jMcpToolAuditLogger(properties);
    }

    @Bean
    public McpResourceAuditLogger mcpResourceAuditLogger(McpClientProperties properties) {
        return new Slf4jMcpResourceAuditLogger(properties);
    }

    @Bean
    public McpPromptAuditLogger mcpPromptAuditLogger(McpClientProperties properties) {
        return new Slf4jMcpPromptAuditLogger(properties);
    }

    @Bean
    public McpToolCallbackAdapter mcpToolCallbackAdapter(ExternalMcpToolRegistry toolRegistry,
                                                         ExternalMcpToolInvoker toolInvoker,
                                                         McpToolAuditLogger auditLogger,
                                                         McpClientProperties properties) {
        return new McpToolCallbackAdapter(toolRegistry, toolInvoker, auditLogger, properties);
    }

    @Bean
    public ExternalMcpResourceAccessService externalMcpResourceAccessService(
            ExternalMcpResourceRegistry resourceRegistry,
            ExternalMcpResourceReader resourceReader,
            McpResourceAuditLogger auditLogger,
            McpClientProperties properties) {
        return new ExternalMcpResourceAccessServiceImpl(resourceRegistry, resourceReader, auditLogger, properties);
    }

    @Bean
    public ExternalMcpPromptAccessService externalMcpPromptAccessService(
            ExternalMcpPromptRegistry promptRegistry,
            ExternalMcpPromptClient promptClient,
            McpPromptAuditLogger auditLogger,
            McpClientProperties properties) {
        return new ExternalMcpPromptAccessServiceImpl(promptRegistry, promptClient, auditLogger, properties);
    }
}
