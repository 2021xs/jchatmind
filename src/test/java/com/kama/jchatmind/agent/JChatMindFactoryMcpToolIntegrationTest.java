package com.kama.jchatmind.agent;

import com.kama.jchatmind.agent.tools.TerminateTool;
import com.kama.jchatmind.agent.tools.Tool;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.config.ToolCorrectionProperties;
import com.kama.jchatmind.converter.AgentConverter;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.converter.KnowledgeBaseConverter;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.mcp.adapter.McpToolCallbackAdapter;
import com.kama.jchatmind.mcp.audit.McpToolAuditLogger;
import com.kama.jchatmind.mcp.config.ExternalMcpServerProperties;
import com.kama.jchatmind.mcp.config.ExternalMcpServerType;
import com.kama.jchatmind.mcp.config.ExternalMcpToolProperties;
import com.kama.jchatmind.mcp.config.McpClientProperties;
import com.kama.jchatmind.mcp.registry.ExternalMcpDiscoveredTool;
import com.kama.jchatmind.mcp.registry.ExternalMcpServerRegistry;
import com.kama.jchatmind.mcp.registry.ExternalMcpToolRegistration;
import com.kama.jchatmind.mcp.registry.ExternalMcpToolRegistry;
import com.kama.jchatmind.mcp.safety.McpExternalToolPolicy;
import com.kama.jchatmind.mcp.safety.McpToolRiskLevel;
import com.kama.jchatmind.model.dto.AgentDTO;
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.service.AgentTaskLogService;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.ConversationContextCompressor;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.ToolExecutionService;
import com.kama.jchatmind.service.ToolFacadeService;
import com.kama.jchatmind.tool.ToolDefinition;
import com.kama.jchatmind.tool.ToolFailureClassifier;
import com.kama.jchatmind.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JChatMindFactoryMcpToolIntegrationTest {

    @Test
    void factoryBuildsAgentWithLocalToolsAndExplicitMcpCallbacksInRuntimeWhitelist() {
        TerminateTool terminateTool = new TerminateTool();
        ToolRegistry localRegistry = localToolRegistry();
        McpToolCallbackAdapter mcpAdapter = mcpAdapter();

        Agent agentEntity = Agent.builder()
                .id("agent-1")
                .name("test-agent")
                .description("test")
                .systemPrompt("system")
                .model("deepseek-chat")
                .allowedTools("[]")
                .allowedKbs("[]")
                .chatOptions("{}")
                .build();
        AgentDTO agentDto = AgentDTO.builder()
                .id("agent-1")
                .name("test-agent")
                .description("test")
                .systemPrompt("system")
                .model(AgentDTO.ModelType.DEEPSEEK_CHAT)
                .allowedTools(List.of())
                .allowedKbs(List.of())
                .chatOptions(AgentDTO.ChatOptions.defaultOptions())
                .build();
        AgentMapper agentMapper = mock(AgentMapper.class);
        when(agentMapper.selectById("agent-1")).thenReturn(agentEntity);
        AgentConverter agentConverter = mock(AgentConverter.class);
        try {
            when(agentConverter.toDTO(agentEntity)).thenReturn(agentDto);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        when(chatMessageFacadeService.getChatMessageDTOsBySessionId("session-1")).thenReturn(List.of());
        ConversationContextCompressor compressor = mock(ConversationContextCompressor.class);
        when(compressor.compressIfNeeded("session-1", "deepseek-chat", List.of()))
                .thenReturn(new ConversationContextCompressor.CompressedContext("", List.of(), false));

        JChatMindFactory factory = new JChatMindFactory(
                new ChatClientRegistry(Map.of("deepseek-chat", mock(ChatClient.class, RETURNS_DEEP_STUBS))),
                mock(SseService.class),
                agentMapper,
                agentConverter,
                mock(KnowledgeBaseMapper.class),
                mock(KnowledgeBaseConverter.class),
                toolFacadeService(terminateTool),
                chatMessageFacadeService,
                mock(ChatMessageConverter.class),
                mock(AgentTaskLogService.class),
                mock(AgentEventPublisher.class),
                mock(AgentRunFailureHandler.class),
                mock(ToolCallBatchExecutor.class),
                mock(ToolExecutionService.class),
                localRegistry,
                compressor,
                new ToolCorrectionProperties(),
                new ToolFailureClassifier(),
                provider(mcpAdapter)
        );

        JChatMind agent = factory.create("agent-1", "session-1", "message-1");

        @SuppressWarnings("unchecked")
        List<ToolCallback> callbacks = (List<ToolCallback>) ReflectionTestUtils.getField(agent, "availableTools");
        @SuppressWarnings("unchecked")
        List<String> runtimeToolNames = (List<String>) ReflectionTestUtils.getField(agent, "runtimeToolNames");

        assertEquals(2, callbacks.size());
        assertTrue(callbacks.stream().map(callback -> callback.getToolDefinition().name())
                .anyMatch("terminate"::equals));
        assertTrue(callbacks.stream().map(callback -> callback.getToolDefinition().name())
                .anyMatch("mcp_docs_search_docs"::equals));
        assertEquals(List.of("terminate", "mcp_docs_search_docs"), runtimeToolNames);
    }

    private ToolFacadeService toolFacadeService(Tool fixedTool) {
        return new ToolFacadeService() {
            @Override
            public List<Tool> getAllTools() {
                return List.of(fixedTool);
            }

            @Override
            public List<Tool> getOptionalTools() {
                return List.of();
            }

            @Override
            public List<Tool> getFixedTools() {
                return List.of(fixedTool);
            }
        };
    }

    private ToolRegistry localToolRegistry() {
        return new ToolRegistry() {
            @Override
            public java.util.Optional<ToolDefinition> find(String toolNameOrAlias) {
                if ("terminate".equals(toolNameOrAlias)) {
                    return java.util.Optional.of(ToolDefinition.builder()
                            .toolName("terminate")
                            .enabled(true)
                            .allowInAgent(true)
                            .maxResultLength(1000)
                            .build());
                }
                return java.util.Optional.empty();
            }

            @Override
            public String canonicalName(String toolNameOrAlias) {
                return find(toolNameOrAlias).map(ToolDefinition::getToolName).orElse(toolNameOrAlias);
            }

            @Override
            public boolean canExposeToAgent(String toolNameOrAlias) {
                return find(toolNameOrAlias).isPresent();
            }

            @Override
            public boolean isAllowedForRuntime(String toolNameOrAlias, Collection<String> runtimeToolNames) {
                return runtimeToolNames != null && runtimeToolNames.contains(canonicalName(toolNameOrAlias));
            }

            @Override
            public int maxResultLength(String toolNameOrAlias) {
                return 1000;
            }

            @Override
            public String truncateResult(String toolNameOrAlias, String result) {
                return result;
            }
        };
    }

    private McpToolCallbackAdapter mcpAdapter() {
        McpClientProperties properties = new McpClientProperties();
        properties.setServers(List.of(server()));
        ExternalMcpToolRegistry registry = new ExternalMcpToolRegistry(
                new ExternalMcpServerRegistry(properties),
                ignored -> List.of(ExternalMcpDiscoveredTool.builder()
                        .name("search_docs")
                        .description("Search docs")
                        .inputSchema("{\"type\":\"object\"}")
                        .build()),
                new McpExternalToolPolicy());
        return new McpToolCallbackAdapter(registry, (tool, argumentsJson) -> "ok", noopAudit(), properties);
    }

    private ExternalMcpServerProperties server() {
        ExternalMcpToolProperties tool = new ExternalMcpToolProperties();
        tool.setName("search_docs");
        tool.setRiskLevel(McpToolRiskLevel.READ_ONLY);
        tool.setAutoInvokeAllowed(true);
        ExternalMcpServerProperties server = new ExternalMcpServerProperties();
        server.setName("docs");
        server.setType(ExternalMcpServerType.DOCS);
        server.setTransport("stdio");
        server.setCommand("mock");
        server.setEnabled(true);
        server.setAllowedTools(List.of(tool));
        return server;
    }

    private McpToolAuditLogger noopAudit() {
        return new McpToolAuditLogger() {
            @Override
            public void start(String traceId, ExternalMcpToolRegistration tool, String argumentsJson) {
            }

            @Override
            public void success(String traceId, ExternalMcpToolRegistration tool, String resultSummary,
                                long latencyMs, boolean truncated) {
            }

            @Override
            public void failure(String traceId, ExternalMcpToolRegistration tool, String errorMessage,
                                long latencyMs, String errorCode) {
            }

            @Override
            public void denied(String traceId, ExternalMcpToolRegistration tool, String argumentsJson,
                               long latencyMs, String errorCode) {
            }
        };
    }

    private <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }
        };
    }
}
