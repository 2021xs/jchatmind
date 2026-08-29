package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kama.jchatmind.agent.AgentToolProtocolInspector;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.event.ChatEvent;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.ChatMessageMapper;
import com.kama.jchatmind.mapper.ChatSessionMapper;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.ChatMessage;
import com.kama.jchatmind.model.entity.ChatSession;
import com.kama.jchatmind.model.request.CreateChatMessageRequest;
import com.kama.jchatmind.model.request.UpdateChatMessageRequest;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.model.response.GetChatMessagesResponse;
import com.kama.jchatmind.model.vo.ChatMessageVO;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class ChatMessageFacadeServiceImpl implements ChatMessageFacadeService {

    private final ChatMessageMapper chatMessageMapper;
    private final ChatMessageConverter chatMessageConverter;
    private final ApplicationEventPublisher publisher;
    private final ChatSessionMapper chatSessionMapper;
    private final ObjectMapper objectMapper;

    @Autowired
    public ChatMessageFacadeServiceImpl(ChatMessageMapper chatMessageMapper,
                                        ChatMessageConverter chatMessageConverter,
                                        ApplicationEventPublisher publisher,
                                        ChatSessionMapper chatSessionMapper,
                                        ObjectMapper objectMapper) {
        this.chatMessageMapper = chatMessageMapper;
        this.chatMessageConverter = chatMessageConverter;
        this.publisher = publisher;
        this.chatSessionMapper = chatSessionMapper;
        this.objectMapper = objectMapper;
    }

    /** Compatibility constructor retained for focused persistence tests. */
    public ChatMessageFacadeServiceImpl(ChatMessageMapper chatMessageMapper,
                                        ChatMessageConverter chatMessageConverter,
                                        ApplicationEventPublisher publisher) {
        this(chatMessageMapper, chatMessageConverter, publisher, null, null);
    }

    @Override
    public GetChatMessagesResponse getChatMessagesBySessionId(String sessionId) {
        List<ChatMessage> chatMessages = chatMessageMapper.selectBySessionId(sessionId);
        List<ChatMessageVO> result = new ArrayList<>();

        for (ChatMessage chatMessage : chatMessages) {
            try {
                ChatMessageVO vo = chatMessageConverter.toVO(chatMessage);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }

        return GetChatMessagesResponse.builder()
                .chatMessages(result.toArray(new ChatMessageVO[0]))
                .build();
    }

    @Override
    public List<ChatMessageDTO> getChatMessageDTOsBySessionId(String sessionId) {
        List<ChatMessage> chatMessages = chatMessageMapper.selectBySessionId(sessionId);
        List<ChatMessageDTO> result = new ArrayList<>();
        for (ChatMessage chatMessage : chatMessages) {
            try {
                result.add(chatMessageConverter.toDTO(chatMessage));
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return result;
    }

    @Override
    public List<ChatMessageDTO> getChatMessagesBySessionIdRecently(String sessionId, int limit) {
        List<ChatMessage> chatMessages = chatMessageMapper.selectBySessionIdRecently(sessionId, limit);
        List<ChatMessageDTO> result = new ArrayList<>();
        for (ChatMessage chatMessage : chatMessages) {
            try {
                ChatMessageDTO dto = chatMessageConverter.toDTO(chatMessage);
                result.add(dto);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return result;
    }

    @Override
    @Transactional
    public CreateChatMessageResponse createChatMessage(CreateChatMessageRequest request) {
        ChatMessage chatMessage = doCreateChatMessage(request);
        // 发布聊天通知事件
        publisher.publishEvent(new ChatEvent(
                        request.getAgentId(),
                        chatMessage.getSessionId(),
                        chatMessage.getId(),
                        chatMessage.getContent()
                )
        );
        // 返回生成的 chatMessageId
        return CreateChatMessageResponse.builder()
                .chatMessageId(chatMessage.getId())
                .build();
    }

    @Override
    public CreateChatMessageResponse createChatMessage(ChatMessageDTO chatMessageDTO) {
        ChatMessage chatMessage = doCreateChatMessage(chatMessageDTO);
        return CreateChatMessageResponse.builder()
                .chatMessageId(chatMessage.getId())
                .build();
    }

    @Override
    @Transactional
    public void createToolProtocolBatch(String sessionId,
                                        String taskId,
                                        AssistantMessage assistantMessage,
                                        ToolResponseMessage toolResponseMessage) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(taskId)) {
            throw new IllegalArgumentException("sessionId and taskId are required for tool protocol persistence");
        }
        if (assistantMessage == null || assistantMessage.getToolCalls() == null
                || assistantMessage.getToolCalls().isEmpty()) {
            throw new IllegalArgumentException("Assistant tool-call message is required");
        }
        if (toolResponseMessage == null || toolResponseMessage.getResponses() == null
                || toolResponseMessage.getResponses().isEmpty()) {
            throw new IllegalArgumentException("Terminal tool response batch is required");
        }

        AgentToolProtocolInspector.Inspection inspection = AgentToolProtocolInspector.inspect(
                List.<Message>of(assistantMessage, toolResponseMessage));
        if (!inspection.valid()) {
            throw new IllegalArgumentException("Invalid terminal tool protocol batch: " + inspection.diagnostic());
        }

        LocalDateTime batchCreatedAt = LocalDateTime.now();
        doCreateChatMessage(ChatMessageDTO.builder()
                .role(ChatMessageDTO.RoleType.ASSISTANT)
                .content(assistantMessage.getText())
                .sessionId(sessionId)
                .metadata(ChatMessageDTO.MetaData.builder()
                        .taskId(taskId)
                        .toolCalls(assistantMessage.getToolCalls())
                        .build())
                .build(), batchCreatedAt);
        for (int i = 0; i < toolResponseMessage.getResponses().size(); i++) {
            ToolResponseMessage.ToolResponse response = toolResponseMessage.getResponses().get(i);
            doCreateChatMessage(ChatMessageDTO.builder()
                    .role(ChatMessageDTO.RoleType.TOOL)
                    .content(response.responseData())
                    .sessionId(sessionId)
                    .metadata(ChatMessageDTO.MetaData.builder()
                            .taskId(taskId)
                            .toolResponse(response)
                            .build())
                    .build(), batchCreatedAt.plusNanos((i + 1L) * 1_000L));
        }
    }

    @Override
    public CreateChatMessageResponse agentCreateChatMessage(CreateChatMessageRequest request) {
        ChatMessage chatMessage = doCreateChatMessage(request);
        // 和 createChatMessage 的区别在于，Agent 创建的 chatMessage 不需要发布事件
        return CreateChatMessageResponse.builder()
                .chatMessageId(chatMessage.getId())
                .build();
    }

    private ChatMessage doCreateChatMessage(CreateChatMessageRequest request) {
        // 将 CreateChatMessageRequest 转换为 ChatMessageDTO
        ChatMessageDTO chatMessageDTO = chatMessageConverter.toDTO(request);
        // 将 ChatMessageDTO 转换为 ChatMessage 实体
        return doCreateChatMessage(chatMessageDTO);
    }

    private ChatMessage doCreateChatMessage(ChatMessageDTO chatMessageDTO) {
        return doCreateChatMessage(chatMessageDTO, LocalDateTime.now());
    }

    private ChatMessage doCreateChatMessage(ChatMessageDTO chatMessageDTO, LocalDateTime createdAt) {
        try {
            // 将 ChatMessageDTO 转换为 ChatMessage 实体
            ChatMessage chatMessage = chatMessageConverter.toEntity(chatMessageDTO);

            // 设置创建时间和更新时间
            chatMessage.setCreatedAt(createdAt);
            chatMessage.setUpdatedAt(createdAt);
            // 插入数据库，ID 由数据库自动生成
            int result = chatMessageMapper.insert(chatMessage);
            if (result <= 0) {
                throw new BizException("创建聊天消息失败");
            }
            return chatMessage;
        } catch (JsonProcessingException e) {
            throw new BizException("创建聊天消息时发生序列化错误: " + e.getMessage());
        }
    }

    @Override
    public CreateChatMessageResponse appendChatMessage(String chatMessageId, String appendContent) {
        // 查询现有的聊天消息
        ChatMessage existingChatMessage = chatMessageMapper.selectById(chatMessageId);
        if (existingChatMessage == null) {
            throw new BizException("聊天消息不存在: " + chatMessageId);
        }

        // 将追加内容添加到现有内容后面
        String currentContent = existingChatMessage.getContent() != null
                ? existingChatMessage.getContent()
                : "";
        String updatedContent = currentContent + appendContent;

        // 创建更新后的消息对象
        ChatMessage updatedChatMessage = ChatMessage.builder()
                .id(existingChatMessage.getId())
                .sessionId(existingChatMessage.getSessionId())
                .role(existingChatMessage.getRole())
                .content(updatedContent)
                .metadata(existingChatMessage.getMetadata())
                .createdAt(existingChatMessage.getCreatedAt())
                .updatedAt(LocalDateTime.now())
                .build();

        // 更新数据库
        int result = chatMessageMapper.updateById(updatedChatMessage);
        if (result <= 0) {
            throw new BizException("追加聊天消息内容失败");
        }

        // 返回聊天消息ID
        return CreateChatMessageResponse.builder()
                .chatMessageId(chatMessageId)
                .build();
    }

    @Override
    public void deleteChatMessage(String chatMessageId) {
        ChatMessage chatMessage = chatMessageMapper.selectById(chatMessageId);
        if (chatMessage == null) {
            throw new BizException("聊天消息不存在: " + chatMessageId);
        }

        int result = chatMessageMapper.deleteById(chatMessageId);
        if (result <= 0) {
            throw new BizException("删除聊天消息失败");
        }
    }

    @Override
    @Transactional
    public int discardTaskToolMessages(String sessionId, String taskId) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(taskId)) {
            throw new IllegalArgumentException("sessionId and taskId are required for cancelled Task cleanup");
        }
        if (chatSessionMapper == null || objectMapper == null) {
            throw new IllegalStateException("Cancelled Task memory cleanup dependencies are not configured");
        }

        ChatSession session = chatSessionMapper.selectByIdForUpdate(sessionId);
        if (session == null) {
            throw new BizException("Chat session does not exist: " + sessionId);
        }

        int deleted = chatMessageMapper.deleteTaskToolMessages(sessionId, taskId);
        if (deleted > 0) {
            invalidateContextSummary(session);
            log.info("Discarded cancelled Task tool messages and invalidated context summary: "
                    + "sessionId={}, taskId={}, deletedMessages={}", sessionId, taskId, deleted);
        }
        return deleted;
    }

    private void invalidateContextSummary(ChatSession session) {
        try {
            ObjectNode metadata = StringUtils.hasText(session.getMetadata())
                    ? (ObjectNode) objectMapper.readTree(session.getMetadata())
                    : objectMapper.createObjectNode();
            metadata.remove("contextSummary");
            metadata.remove("contextSummaryLastMessageId");
            metadata.remove("contextSummaryUpdatedAt");
            int updated = chatSessionMapper.updateById(ChatSession.builder()
                    .id(session.getId())
                    .metadata(objectMapper.writeValueAsString(metadata))
                    .build());
            if (updated != 1) {
                throw new IllegalStateException("Failed to invalidate context summary for session: "
                        + session.getId());
            }
        } catch (JsonProcessingException | ClassCastException e) {
            throw new IllegalStateException("Failed to invalidate context summary after Task cancellation", e);
        }
    }

    @Override
    public void updateChatMessage(String chatMessageId, UpdateChatMessageRequest request) {
        try {
            // 查询现有的聊天消息
            ChatMessage existingChatMessage = chatMessageMapper.selectById(chatMessageId);
            if (existingChatMessage == null) {
                throw new BizException("聊天消息不存在: " + chatMessageId);
            }

            // 将现有 ChatMessage 转换为 ChatMessageDTO
            ChatMessageDTO chatMessageDTO = chatMessageConverter.toDTO(existingChatMessage);

            // 使用 UpdateChatMessageRequest 更新 ChatMessageDTO
            chatMessageConverter.updateDTOFromRequest(chatMessageDTO, request);

            // 将更新后的 ChatMessageDTO 转换回 ChatMessage 实体
            ChatMessage updatedChatMessage = chatMessageConverter.toEntity(chatMessageDTO);

            // 保留原有的 ID、sessionId、role 和创建时间
            updatedChatMessage.setId(existingChatMessage.getId());
            updatedChatMessage.setSessionId(existingChatMessage.getSessionId());
            updatedChatMessage.setRole(existingChatMessage.getRole());
            updatedChatMessage.setCreatedAt(existingChatMessage.getCreatedAt());
            updatedChatMessage.setUpdatedAt(LocalDateTime.now());

            // 更新数据库
            int result = chatMessageMapper.updateById(updatedChatMessage);
            if (result <= 0) {
                throw new BizException("更新聊天消息失败");
            }
        } catch (JsonProcessingException e) {
            throw new BizException("更新聊天消息时发生序列化错误: " + e.getMessage());
        }
    }
}
