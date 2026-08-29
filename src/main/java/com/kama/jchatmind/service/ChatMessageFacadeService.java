package com.kama.jchatmind.service;

import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.request.CreateChatMessageRequest;
import com.kama.jchatmind.model.request.UpdateChatMessageRequest;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.model.response.GetChatMessagesResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;

public interface ChatMessageFacadeService {
    GetChatMessagesResponse getChatMessagesBySessionId(String sessionId);

    List<ChatMessageDTO> getChatMessageDTOsBySessionId(String sessionId);

    List<ChatMessageDTO> getChatMessagesBySessionIdRecently(String sessionId, int limit);

    CreateChatMessageResponse createChatMessage(CreateChatMessageRequest request);

    CreateChatMessageResponse createChatMessage(ChatMessageDTO chatMessageDTO);

    /**
     * Persists one complete Assistant tool-call batch and all matching terminal responses atomically.
     */
    void createToolProtocolBatch(String sessionId,
                                 String taskId,
                                 AssistantMessage assistantMessage,
                                 ToolResponseMessage toolResponseMessage);

    CreateChatMessageResponse agentCreateChatMessage(CreateChatMessageRequest request);

    CreateChatMessageResponse appendChatMessage(String chatMessageId, String appendContent);

    void deleteChatMessage(String chatMessageId);

    void updateChatMessage(String chatMessageId, UpdateChatMessageRequest request);
}
