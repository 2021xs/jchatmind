package com.kama.jchatmind.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.kama.jchatmind.agent.tools.Tool;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.config.ToolCorrectionProperties;
import com.kama.jchatmind.converter.AgentConverter;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.converter.KnowledgeBaseConverter;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.mcp.adapter.McpToolCallbackAdapter;
import com.kama.jchatmind.model.dto.AgentDTO;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.dto.KnowledgeBaseDTO;
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.model.entity.KnowledgeBase;
import com.kama.jchatmind.service.AgentTaskLogService;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.ConversationContextCompressor;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.ToolExecutionService;
import com.kama.jchatmind.service.ToolFacadeService;
import com.kama.jchatmind.tool.ToolRegistry;
import com.kama.jchatmind.tool.ToolFailureClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
public class JChatMindFactory {

    private static final Logger log = LoggerFactory.getLogger(JChatMindFactory.class);

    private final ChatClientRegistry chatClientRegistry;
    private final SseService sseService;
    private final AgentMapper agentMapper;
    private final AgentConverter agentConverter;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseConverter knowledgeBaseConverter;
    private final ToolFacadeService toolFacadeService;
    private final ChatMessageFacadeService chatMessageFacadeService;
    private final ChatMessageConverter chatMessageConverter;
    private final AgentTaskLogService agentTaskLogService;
    private final AgentEventPublisher agentEventPublisher;
    private final AgentRunFailureHandler agentRunFailureHandler;
    private final ToolCallBatchExecutor toolCallBatchExecutor;
    private final ToolExecutionService toolExecutionService;
    private final ToolRegistry toolRegistry;
    private final ConversationContextCompressor conversationContextCompressor;
    private final ToolCorrectionProperties toolCorrectionProperties;
    private final ToolFailureClassifier toolFailureClassifier;
    private final ObjectProvider<McpToolCallbackAdapter> mcpToolCallbackAdapterProvider;

    public JChatMindFactory(
            ChatClientRegistry chatClientRegistry,
            SseService sseService,
            AgentMapper agentMapper,
            AgentConverter agentConverter,
            KnowledgeBaseMapper knowledgeBaseMapper,
            KnowledgeBaseConverter knowledgeBaseConverter,
            ToolFacadeService toolFacadeService,
            ChatMessageFacadeService chatMessageFacadeService,
            ChatMessageConverter chatMessageConverter,
            AgentTaskLogService agentTaskLogService,
            AgentEventPublisher agentEventPublisher,
            AgentRunFailureHandler agentRunFailureHandler,
            ToolCallBatchExecutor toolCallBatchExecutor,
            ToolExecutionService toolExecutionService,
            ToolRegistry toolRegistry,
            ConversationContextCompressor conversationContextCompressor,
            ToolCorrectionProperties toolCorrectionProperties,
            ToolFailureClassifier toolFailureClassifier,
            ObjectProvider<McpToolCallbackAdapter> mcpToolCallbackAdapterProvider
    ) {
        this.chatClientRegistry = chatClientRegistry;
        this.sseService = sseService;
        this.agentMapper = agentMapper;
        this.agentConverter = agentConverter;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.knowledgeBaseConverter = knowledgeBaseConverter;
        this.toolFacadeService = toolFacadeService;
        this.chatMessageFacadeService = chatMessageFacadeService;
        this.chatMessageConverter = chatMessageConverter;
        this.agentTaskLogService = agentTaskLogService;
        this.agentEventPublisher = agentEventPublisher;
        this.agentRunFailureHandler = agentRunFailureHandler;
        this.toolCallBatchExecutor = toolCallBatchExecutor;
        this.toolExecutionService = toolExecutionService;
        this.toolRegistry = toolRegistry;
        this.conversationContextCompressor = conversationContextCompressor;
        this.toolCorrectionProperties = toolCorrectionProperties;
        this.toolFailureClassifier = toolFailureClassifier;
        this.mcpToolCallbackAdapterProvider = mcpToolCallbackAdapterProvider;
    }

    private Agent loadAgent(String agentId) {
        return agentMapper.selectById(agentId);
    }

    private Agent withRuntimeModel(Agent agent, String runtimeModel) {
        if (!StringUtils.hasText(runtimeModel) || runtimeModel.equals(agent.getModel())) {
            return agent;
        }
        return Agent.builder()
                .id(agent.getId())
                .name(agent.getName())
                .description(agent.getDescription())
                .systemPrompt(agent.getSystemPrompt())
                .model(runtimeModel.trim())
                .allowedTools(agent.getAllowedTools())
                .allowedKbs(agent.getAllowedKbs())
                .chatOptions(agent.getChatOptions())
                .createdAt(agent.getCreatedAt())
                .updatedAt(agent.getUpdatedAt())
                .build();
    }

    private List<Message> loadMemory(String chatSessionId, String model) {
        List<ChatMessageDTO> allMessages = chatMessageFacadeService.getChatMessageDTOsBySessionId(chatSessionId);
        ConversationContextCompressor.CompressedContext compressedContext =
                conversationContextCompressor.compressIfNeeded(chatSessionId, model, allMessages);
        return AgentMemoryHistorySanitizer.toSafeReplayMessages(
                compressedContext.summary(), compressedContext.recentMessages());
    }

    private AgentDTO toAgentConfig(Agent agent) {
        try {
            return agentConverter.toDTO(agent);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse agent config", e);
        }
    }

    private AgentDTO withRuntimeAllowedTools(AgentDTO agentConfig, List<String> runtimeAllowedToolNames) {
        if (runtimeAllowedToolNames == null || runtimeAllowedToolNames.isEmpty()) {
            return agentConfig;
        }
        return AgentDTO.builder()
                .id(agentConfig.getId())
                .name(agentConfig.getName())
                .description(agentConfig.getDescription())
                .systemPrompt(agentConfig.getSystemPrompt())
                .model(agentConfig.getModel())
                .allowedTools(runtimeAllowedToolNames)
                .allowedKbs(agentConfig.getAllowedKbs())
                .chatOptions(agentConfig.getChatOptions())
                .createdAt(agentConfig.getCreatedAt())
                .updatedAt(agentConfig.getUpdatedAt())
                .build();
    }

    private List<KnowledgeBaseDTO> resolveRuntimeKnowledgeBases(AgentDTO agentConfig) {
        List<String> allowedKbIds = agentConfig.getAllowedKbs();
        if (allowedKbIds == null || allowedKbIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<KnowledgeBase> knowledgeBases = knowledgeBaseMapper.selectByIdBatch(allowedKbIds);
        if (knowledgeBases.isEmpty()) {
            return Collections.emptyList();
        }
        List<KnowledgeBaseDTO> kbDTOs = new ArrayList<>();
        try {
            for (KnowledgeBase knowledgeBase : knowledgeBases) {
                kbDTOs.add(knowledgeBaseConverter.toDTO(knowledgeBase));
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        return kbDTOs;
    }

    private List<Tool> resolveRuntimeTools(AgentDTO agentConfig) {
        List<Tool> runtimeTools = toolFacadeService.getFixedTools()
                .stream()
                .filter(tool -> toolRegistry.canExposeToAgent(tool.getName()))
                .collect(Collectors.toCollection(ArrayList::new));

        List<String> allowedToolNames = agentConfig.getAllowedTools();
        if (allowedToolNames == null || allowedToolNames.isEmpty()) {
            return runtimeTools;
        }

        for (String toolName : allowedToolNames) {
            String requestedCanonical = toolRegistry.canonicalName(toolName);
            toolFacadeService.getOptionalTools()
                    .stream()
                    .filter(tool -> toolRegistry.canExposeToAgent(tool.getName()))
                    .filter(tool -> toolRegistry.canonicalName(tool.getName()).equals(requestedCanonical))
                    .findFirst()
                    .ifPresent(runtimeTools::add);
        }
        return runtimeTools;
    }

    private List<ToolCallback> buildToolCallbacks(List<Tool> runtimeTools) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (Tool tool : runtimeTools) {
            Object target = resolveToolTarget(tool);
            ToolCallback[] toolCallbacks = MethodToolCallbackProvider.builder()
                    .toolObjects(target)
                    .build()
                    .getToolCallbacks();
            callbacks.addAll(Arrays.asList(toolCallbacks));
        }
        McpToolCallbackAdapter mcpToolCallbackAdapter = mcpToolCallbackAdapterProvider.getIfAvailable();
        if (mcpToolCallbackAdapter != null) {
            callbacks.addAll(mcpToolCallbackAdapter.toolCallbacks());
        }
        return callbacks;
    }

    private List<String> resolveRuntimeToolNames(List<Tool> runtimeTools) {
        List<String> runtimeToolNames = runtimeTools.stream()
                .map(Tool::getName)
                .map(toolRegistry::canonicalName)
                .collect(Collectors.toCollection(ArrayList::new));
        McpToolCallbackAdapter mcpToolCallbackAdapter = mcpToolCallbackAdapterProvider.getIfAvailable();
        if (mcpToolCallbackAdapter != null) {
            runtimeToolNames.addAll(mcpToolCallbackAdapter.exposedToolNames());
        }
        return runtimeToolNames.stream().distinct().toList();
    }

    private Object resolveToolTarget(Tool tool) {
        try {
            return AopUtils.isAopProxy(tool) ? AopUtils.getTargetClass(tool) : tool;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to resolve tool target: " + tool.getName(), e);
        }
    }

    private JChatMind buildAgentRuntime(
            Agent agent,
            AgentDTO agentConfig,
            List<Message> memory,
            List<KnowledgeBaseDTO> knowledgeBases,
            List<ToolCallback> toolCallbacks,
            List<String> runtimeToolNames,
            String chatSessionId,
            String userMessageId
    ) {
        ChatClient chatClient = chatClientRegistry.get(agent.getModel());
        if (Objects.isNull(chatClient)) {
            throw new IllegalStateException("ChatClient not found for model: " + agent.getModel());
        }
        return new JChatMind(
                agent.getId(),
                agent.getModel(),
                agent.getName(),
                agent.getDescription(),
                agent.getSystemPrompt(),
                chatClient,
                agentConfig.getChatOptions().getMessageLength(),
                memory,
                toolCallbacks,
                knowledgeBases,
                chatSessionId,
                sseService,
                agentEventPublisher,
                toolExecutionService,
                chatMessageFacadeService,
                chatMessageConverter,
                agentTaskLogService,
                conversationContextCompressor,
                userMessageId,
                runtimeToolNames,
                toolCorrectionProperties,
                toolFailureClassifier,
                agentRunFailureHandler,
                toolCallBatchExecutor
        );
    }

    public JChatMind create(String agentId, String chatSessionId) {
        return create(agentId, chatSessionId, null);
    }

    public JChatMind create(String agentId, String chatSessionId, String userMessageId) {
        return create(agentId, chatSessionId, userMessageId, null, null);
    }

    public JChatMind create(String agentId, String chatSessionId, String userMessageId,
                           String runtimeSystemContext, String traceId) {
        return create(agentId, chatSessionId, userMessageId, runtimeSystemContext, traceId, null);
    }

    public JChatMind create(String agentId, String chatSessionId, String userMessageId,
                           String runtimeSystemContext, String traceId, String runtimeModel) {
        return create(agentId, chatSessionId, userMessageId, runtimeSystemContext, traceId, runtimeModel, null);
    }

    public JChatMind create(String agentId, String chatSessionId, String userMessageId,
                           String runtimeSystemContext, String traceId, String runtimeModel,
                           List<String> runtimeAllowedToolNames) {
        Agent agent = loadAgent(agentId);
        agent = withRuntimeModel(agent, runtimeModel);
        AgentDTO agentConfig = withRuntimeAllowedTools(toAgentConfig(agent), runtimeAllowedToolNames);
        List<Message> memory = loadMemory(chatSessionId, agent.getModel());
        if (StringUtils.hasText(runtimeSystemContext)) {
            memory = new ArrayList<>(memory);
            memory.add(0, new SystemMessage(runtimeSystemContext));
        }

        List<KnowledgeBaseDTO> knowledgeBases = resolveRuntimeKnowledgeBases(agentConfig);
        List<Tool> runtimeTools = resolveRuntimeTools(agentConfig);
        List<ToolCallback> toolCallbacks = buildToolCallbacks(runtimeTools);
        List<String> runtimeToolNames = resolveRuntimeToolNames(runtimeTools);

        JChatMind runtime = buildAgentRuntime(
                agent,
                agentConfig,
                memory,
                knowledgeBases,
                toolCallbacks,
                runtimeToolNames,
                chatSessionId,
                userMessageId
        );
        if (StringUtils.hasText(traceId)) {
            runtime.setTraceId(traceId);
        }
        return runtime;
    }
}
